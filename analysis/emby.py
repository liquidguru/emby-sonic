"""
Emby API client.

Two roles:
- Coordinator: list the audio library (to populate the analysis queue).
- Worker: download a track's original audio over HTTP so it can be analysed on
  any machine on the network — no filesystem/SMB access to the music required.
"""

from __future__ import annotations

import os
import tempfile

import httpx

from config import settings

_ITEM_FIELDS = "Id,Name,AlbumArtist,Album,RunTimeTicks,Path,Container"


def _auth_headers(token: str | None = None) -> dict:
    return {"X-Emby-Token": token or settings.emby_api_key}


async def fetch_audio_items(token: str | None = None) -> list[dict]:
    """List every audio item in the Emby library (coordinator side, async)."""
    async with httpx.AsyncClient(base_url=settings.emby_url, timeout=120.0) as client:
        resp = await client.get(
            "/Items",
            params={
                "IncludeItemTypes": "Audio",
                "Recursive": "true",
                "Fields": _ITEM_FIELDS,
                "Limit": 200000,
            },
            headers=_auth_headers(token),
        )
        resp.raise_for_status()
        return resp.json().get("Items", [])


def download_track(item_id: str, suffix: str = ".tmp", token: str | None = None) -> str:
    """
    Download an Emby item's original audio to a temp file; return the path
    (worker side, sync). Caller is responsible for deleting the file.
    """
    fd, path = tempfile.mkstemp(suffix=suffix or ".tmp")
    try:
        with httpx.stream(
            "GET",
            f"{settings.emby_url}/Items/{item_id}/Download",
            headers=_auth_headers(token),
            timeout=300.0,
            follow_redirects=True,
        ) as resp:
            resp.raise_for_status()
            with os.fdopen(fd, "wb") as f:  # closes fd on exit
                for chunk in resp.iter_bytes(65536):
                    f.write(chunk)
    except Exception:
        try:
            os.close(fd)  # no-op/OSError if fdopen already closed it
        except OSError:
            pass
        if os.path.exists(path):
            os.remove(path)
        raise
    return path
