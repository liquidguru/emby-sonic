from __future__ import annotations

import asyncio

import httpx
from fastapi import APIRouter, Header, HTTPException, Query

from analysis.emby import _music_parent_ids
from api.deps import AuthToken
from api.schemas import WebLoginRequest, WebLoginResponse, WebTrackOut
from config import settings

router = APIRouter(tags=["webapp"])

_ITEM_FIELDS = "UserData,PrimaryImageAspectRatio"
_WEB_CLIENT_AUTH = (
    'MediaBrowser Client="liquidWave", '
    'Device="Browser", '
    'DeviceId="emby-sonic-web", '
    'Version="web-mvp"'
)


def _emby_headers(token: str | None = None) -> dict[str, str]:
    headers = {
        "X-Emby-Authorization": _WEB_CLIENT_AUTH,
        "Accept": "application/json",
    }
    if token:
        headers["X-Emby-Token"] = token
    return headers


def _duration_ms(item: dict) -> int | None:
    ticks = item.get("RunTimeTicks")
    if not isinstance(ticks, int) or ticks <= 0:
        return None
    return ticks // 10_000


def _artist(item: dict) -> str | None:
    artists = item.get("Artists")
    if isinstance(artists, list) and artists:
        return ", ".join(str(artist) for artist in artists if artist)
    album_artist = item.get("AlbumArtist")
    return str(album_artist) if album_artist else None


def _track(item: dict) -> WebTrackOut | None:
    item_id = item.get("Id")
    if not item_id:
        return None
    return WebTrackOut(
        id=str(item_id),
        title=item.get("Name"),
        artist=_artist(item),
        album=item.get("Album"),
        duration_ms=_duration_ms(item),
    )


@router.post("/auth/login", response_model=WebLoginResponse)
async def web_login(body: WebLoginRequest) -> WebLoginResponse:
    """
    Browser login bootstrap. Authenticates against the coordinator's *own*
    configured Emby server (settings.emby_url) — never a client-supplied URL —
    so it stays same-origin and can't be abused as an open proxy. The browser
    streams audio directly from Emby using the returned server_url.

    Also returns server_url_external (settings.emby_url_external), Emby's
    optional publicly-reachable address, when configured. The two servers are
    the same Emby instance — this just gives the browser both addresses so it
    can pick whichever it can actually reach: a LAN client uses server_url, a
    WAN client (loaded the webapp over a public domain) uses
    server_url_external, since the LAN address isn't routable from outside
    and would otherwise silently fail (issue #30).
    """
    server_url = settings.emby_url.rstrip("/")
    payload = {"Username": body.username, "Pw": body.password}
    async with httpx.AsyncClient(base_url=server_url, timeout=15.0) as client:
        try:
            resp = await client.post(
                "/Users/AuthenticateByName",
                json=payload,
                headers=_emby_headers(),
            )
        except httpx.HTTPError as exc:
            raise HTTPException(status_code=502, detail=f"Emby login request failed: {exc}") from exc

    if resp.status_code in {401, 403}:
        raise HTTPException(status_code=401, detail="Invalid Emby username or password")
    if resp.status_code >= 400:
        raise HTTPException(status_code=502, detail=f"Emby login failed ({resp.status_code})")

    data = resp.json()
    user = data.get("User") or {}
    token = data.get("AccessToken")
    user_id = user.get("Id")
    if not token or not user_id:
        raise HTTPException(status_code=502, detail="Emby login response was missing a token or user id")

    return WebLoginResponse(
        access_token=token,
        user_id=user_id,
        user_name=user.get("Name"),
        server_url=server_url,
        server_url_external=settings.emby_url_external.rstrip("/") or None,
    )


async def _search_scoped(
    client: httpx.AsyncClient,
    emby_token: str,
    parent_ids: list[str],
    extra_params: dict,
    stop_at: int | None = None,
) -> list[dict]:
    """GET /Items with extra_params, once per music parent_id (or unscoped if
    none exist — matches fetch_audio_items' fallback). Stops early once
    stop_at items are collected, if given."""
    items: list[dict] = []
    queries = (
        [{**extra_params, "ParentId": pid} for pid in parent_ids]
        if parent_ids
        else [extra_params]
    )
    for params in queries:
        if stop_at is not None and len(items) >= stop_at:
            break
        resp = await client.get("/Items", params=params, headers=_emby_headers(emby_token))
        if resp.status_code == 401:
            raise HTTPException(status_code=401, detail="Invalid or expired Emby token")
        if resp.status_code >= 400:
            raise HTTPException(status_code=502, detail=f"Emby search failed ({resp.status_code})")
        items.extend(resp.json().get("Items", []))
    return items


@router.get("/search/tracks", response_model=list[WebTrackOut])
async def search_tracks(
    _token: AuthToken,
    q: str = Query(default="", max_length=120),
    limit: int = Query(default=60, ge=1, le=100),
    user_id: str | None = Header(default=None, alias="X-Emby-User-Id"),
    emby_token: str | None = Header(default=None, alias="X-Emby-Token"),
) -> list[WebTrackOut]:
    """
    Thin Emby track-search proxy for the static browser app, scoped to **music**
    libraries — it reuses the analysis scan's music-library detection
    (`_music_parent_ids`), so audiobooks don't show up in the sonic search.

    Expands results to a matched artist's full catalogue, not just tracks
    whose title contains the term — otherwise searching "Madonna" only finds
    a track literally titled "Madonna", missing her whole discography. Mirrors
    the Android app's searchTracksExpanded (#28 / 73624e0): direct title
    matches and a parallel artist-name search both run, then every track by
    any matched artist is merged in, direct matches first, deduped by id.

    TODO(webapp): add an audiobooks search mode (scoped to the audiobook
    collection type) when the web app grows audiobook playback, mirroring the
    Android app's music / audiobook / all search scopes.
    """
    term = q.strip()
    if not term:
        return []
    if not user_id:
        raise HTTPException(status_code=400, detail="X-Emby-User-Id is required")
    if not emby_token:
        raise HTTPException(status_code=401, detail="X-Emby-Token is required")

    direct_params = {
        "UserId": user_id,
        "IncludeItemTypes": "Audio",
        "Recursive": "true",
        "SortBy": "SortName",
        "SortOrder": "Ascending",
        "Fields": _ITEM_FIELDS,
        "StartIndex": 0,
        "Limit": limit,
        "SearchTerm": term,
    }
    artist_params = {
        "UserId": user_id,
        "IncludeItemTypes": "MusicArtist",
        "Recursive": "true",
        "Limit": 20,
        "SearchTerm": term,
    }

    async with httpx.AsyncClient(base_url=settings.emby_url, timeout=20.0) as client:
        try:
            parent_ids = await _music_parent_ids(client, emby_token)
            direct_items, artist_items = await asyncio.gather(
                _search_scoped(client, emby_token, parent_ids, direct_params, stop_at=limit),
                _search_scoped(client, emby_token, parent_ids, artist_params),
            )
            artist_ids = [str(a["Id"]) for a in artist_items if a.get("Id")]
            by_artist_items = (
                await _search_scoped(
                    client, emby_token, parent_ids,
                    {
                        "UserId": user_id,
                        "IncludeItemTypes": "Audio",
                        "Recursive": "true",
                        "SortBy": "SortName",
                        "SortOrder": "Ascending",
                        "Fields": _ITEM_FIELDS,
                        "AlbumArtistIds": ",".join(artist_ids),
                        "Limit": 200,
                    },
                )
                if artist_ids
                else []
            )
        except httpx.HTTPError as exc:
            raise HTTPException(status_code=502, detail=f"Emby search request failed: {exc}") from exc

    seen: set[str] = set()
    tracks: list[WebTrackOut] = []
    for item in direct_items + by_artist_items:
        track = _track(item)
        if track is None or track.id in seen:
            continue
        seen.add(track.id)
        tracks.append(track)
        if len(tracks) >= limit:
            break
    return tracks
