import httpx
from fastapi import HTTPException, Security
from fastapi.security import APIKeyHeader

from config import settings

_header = APIKeyHeader(name="X-Emby-Token", auto_error=True)
_worker_header = APIKeyHeader(name="X-Worker-Token", auto_error=True)


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
    async with httpx.AsyncClient(base_url=settings.emby_url, timeout=5.0) as client:
        resp = await client.get(
            "/System/Info",
            headers={"X-Emby-Token": token},
        )
    if resp.status_code != 200:
        raise HTTPException(status_code=401, detail="Invalid or expired Emby token")
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
