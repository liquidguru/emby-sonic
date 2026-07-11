from collections import OrderedDict
import hashlib
import time

import httpx
from fastapi import HTTPException, Security
from fastapi.security import APIKeyHeader

from config import settings

_header = APIKeyHeader(name="X-Emby-Token", auto_error=True)
_worker_header = APIKeyHeader(name="X-Worker-Token", auto_error=True)

# SHA-256 token digest -> monotonic expiry. Raw Emby tokens are never retained.
_valid_token_cache: OrderedDict[str, float] = OrderedDict()
_cache_hits = 0
_cache_misses = 0


def _token_digest(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _prune_token_cache(now: float) -> None:
    expired = [digest for digest, expiry in _valid_token_cache.items() if expiry <= now]
    for digest in expired:
        _valid_token_cache.pop(digest, None)


def _cached_token_is_valid(digest: str) -> bool:
    global _cache_hits, _cache_misses
    now = time.monotonic()
    expiry = _valid_token_cache.get(digest)
    if expiry is not None and expiry > now:
        _valid_token_cache.move_to_end(digest)
        _cache_hits += 1
        return True
    if expiry is not None:
        _valid_token_cache.pop(digest, None)
    _cache_misses += 1
    return False


def _cache_valid_token(digest: str) -> None:
    ttl = settings.auth_cache_ttl_seconds
    max_entries = settings.auth_cache_max_entries
    if ttl <= 0 or max_entries <= 0:
        return
    now = time.monotonic()
    _prune_token_cache(now)
    _valid_token_cache[digest] = now + ttl
    _valid_token_cache.move_to_end(digest)
    while len(_valid_token_cache) > max_entries:
        _valid_token_cache.popitem(last=False)


def clear_token_cache() -> None:
    """Clear cached validations and counters (startup/tests/admin diagnostics)."""
    global _cache_hits, _cache_misses
    _valid_token_cache.clear()
    _cache_hits = 0
    _cache_misses = 0


def token_cache_stats() -> dict[str, int]:
    _prune_token_cache(time.monotonic())
    return {
        "entries": len(_valid_token_cache),
        "hits": _cache_hits,
        "misses": _cache_misses,
    }


async def verify_emby_token(token: str = Security(_header)) -> str:
    """Validate the caller's Emby token against Emby's /System/Info endpoint.

    /System/Info requires authentication and returns 200 for any valid token
    (user session or API key) and 401 otherwise. We use it instead of
    /Users/Me, which returns 500 for a bare X-Emby-Token on Emby 4.10.

    The server API key is also accepted directly so admin tooling and
    integration tests don't need a user session token.
    """
    if settings.emby_api_key and token == settings.emby_api_key:
        return token
    digest = _token_digest(token)
    if _cached_token_is_valid(digest):
        return token
    async with httpx.AsyncClient(base_url=settings.emby_url, timeout=5.0) as client:
        resp = await client.get(
            "/System/Info",
            headers={"X-Emby-Token": token},
        )
    if resp.status_code != 200:
        raise HTTPException(status_code=401, detail="Invalid or expired Emby token")
    _cache_valid_token(digest)
    return token


async def verify_worker(token: str = Security(_worker_header)) -> str:
    """
    Authenticate an analysis worker. Workers present WORKER_SECRET in
    X-Worker-Token. For existing deployments that have not set WORKER_SECRET
    yet, the Emby API key remains the fallback worker secret.
    """
    worker_secret = settings.effective_worker_secret
    if not worker_secret or token != worker_secret:
        raise HTTPException(status_code=401, detail="Invalid worker token")
    return token
