from __future__ import annotations

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
    streams audio from the returned server_url.
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
    )


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

    base_params = {
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
    items: list[dict] = []
    async with httpx.AsyncClient(base_url=settings.emby_url, timeout=20.0) as client:
        try:
            parent_ids = await _music_parent_ids(client, emby_token)
            # Search each music library; fall back to an unscoped search only if
            # no music-typed library exists (matches fetch_audio_items).
            queries = (
                [{**base_params, "ParentId": pid} for pid in parent_ids]
                if parent_ids
                else [base_params]
            )
            for params in queries:
                if len(items) >= limit:
                    break
                resp = await client.get(
                    "/Items",
                    params=params,
                    headers=_emby_headers(emby_token),
                )
                if resp.status_code == 401:
                    raise HTTPException(status_code=401, detail="Invalid or expired Emby token")
                if resp.status_code >= 400:
                    raise HTTPException(status_code=502, detail=f"Emby search failed ({resp.status_code})")
                items.extend(resp.json().get("Items", []))
        except httpx.HTTPError as exc:
            raise HTTPException(status_code=502, detail=f"Emby search request failed: {exc}") from exc

    tracks: list[WebTrackOut] = []
    for item in items[:limit]:
        track = _track(item)
        if track is not None:
            tracks.append(track)
    return tracks
