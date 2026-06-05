import httpx
from fastapi import HTTPException, Security
from fastapi.security import APIKeyHeader

from config import settings

_header = APIKeyHeader(name="X-Emby-Token", auto_error=True)


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
