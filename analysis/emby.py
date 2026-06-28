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

# Only these Emby library collection types are analysed. Audiobooks live in their
# own library (CollectionType "audiobooks") — spoken word must never enter the
# sonic index, or it pollutes Track Radio / Similar / Adventure / Guest DJ.
MUSIC_COLLECTION_TYPES = {"music"}


async def _music_parent_ids(client: httpx.AsyncClient, token: str | None) -> list[str]:
    """View ids of music-typed libraries, so the scan is scoped to music only."""
    resp = await client.get("/Library/VirtualFolders", headers=_auth_headers(token))
    resp.raise_for_status()
    ids: list[str] = []
    for lib in resp.json():
        if (lib.get("CollectionType") or "").lower() in MUSIC_COLLECTION_TYPES:
            lib_id = lib.get("ItemId")
            if lib_id:
                ids.append(lib_id)
    return ids


async def fetch_audio_items(token: str | None = None) -> list[dict]:
    """
    List audio items to analyse — scoped to **music** libraries only.

    Audiobooks (and any other non-music audio library) are excluded by scanning
    per music-library ParentId rather than the whole server. If no music-typed
    library exists (unusual/misconfigured), falls back to scanning all audio so
    the tool still works, just without the audiobook exclusion.
    """
    base_params = {
        "IncludeItemTypes": "Audio",
        "Recursive": "true",
        "Fields": _ITEM_FIELDS,
        "Limit": 200000,
    }
    async with httpx.AsyncClient(base_url=settings.emby_url, timeout=120.0) as client:
        parent_ids = await _music_parent_ids(client, token)
        if not parent_ids:
            resp = await client.get("/Items", params=base_params, headers=_auth_headers(token))
            resp.raise_for_status()
            return resp.json().get("Items", [])
        items: list[dict] = []
        for parent_id in parent_ids:
            resp = await client.get(
                "/Items",
                params={**base_params, "ParentId": parent_id},
                headers=_auth_headers(token),
            )
            resp.raise_for_status()
            items.extend(resp.json().get("Items", []))
        return items


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
