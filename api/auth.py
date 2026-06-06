import httpx
from fastapi import HTTPException, Security
from fastapi.security import APIKeyHeader

from config import settings

_header = APIKeyHeader(name="X-Emby-Token", auto_error=True)
_worker_header = APIKeyHeader(name="X-Worker-Token", auto_error=True)


async def verify_emby_token(token: str = Security(_header)) -> str:
    """Validate the caller's Emby token against Emby's /Users/Me endpoint."""
    async with httpx.AsyncClient(base_url=settings.emby_url, timeout=5.0) as client:
        resp = await client.get(
            "/Users/Me",
            headers={"X-Emby-Token": token},
        )
    if resp.status_code != 200:
        raise HTTPException(status_code=401, detail="Invalid or expired Emby token")
    return token


async def verify_worker(token: str = Security(_worker_header)) -> str:
    """
    Authenticate an analysis worker. Workers present a shared secret (the Emby
    API key, which they already hold to stream audio) in X-Worker-Token. Simple
    and sufficient for a trusted LAN; can be split into a dedicated secret later.
    """
    if not settings.emby_api_key or token != settings.emby_api_key:
        raise HTTPException(status_code=401, detail="Invalid worker token")
    return token
