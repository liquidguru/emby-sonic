from fastapi import APIRouter, HTTPException, Query
from sqlalchemy import select
from api.deps import DB, AuthToken
from api.schemas import (
    SimilarTrack, RadioPlaylist, TrackOut, LoudnessRequest, LoudnessResponse, TrackEdges,
)
from db.models import Track, Embedding
from analysis.similarity import get_similar_tracks, build_radio

router = APIRouter(tags=["tracks"])


@router.post("/tracks/loudness", response_model=LoudnessResponse)
async def tracks_loudness(body: LoudnessRequest, db: DB, _token: AuthToken) -> LoudnessResponse:
    """
    Batch per-track playback data for a set of track ids, so the Android player
    can set up a whole queue in one round-trip:

    - `loudness`: integrated loudness (LUFS) for volume normalisation.
    - `edges`: where audible music starts/ends, for crossfade edge trimming (#38).

    Both are independent and sparse — a track appears in each only if that value
    was measured. Unknown/unmeasured ids are simply omitted, and clients treat a
    missing id as "no data" (unity gain / blend against the full duration).

    Kept on the existing /tracks/loudness route rather than a new endpoint so the
    client still makes ONE call per queue; `edges` is additive, so older clients
    ignore it.
    """
    ids = [i for i in dict.fromkeys(body.ids) if i]  # de-dupe, drop blanks, keep order
    if not ids:
        return LoudnessResponse(loudness={})
    rows = (
        await db.execute(
            select(
                Embedding.track_id,
                Embedding.lufs,
                Embedding.effective_start_ms,
                Embedding.effective_end_ms,
            ).where(Embedding.track_id.in_(ids))
        )
    ).all()
    loudness = {r.track_id: r.lufs for r in rows if r.lufs is not None}
    edges = {
        r.track_id: TrackEdges(start_ms=r.effective_start_ms, end_ms=r.effective_end_ms)
        for r in rows
        if r.effective_start_ms is not None and r.effective_end_ms is not None
    }
    return LoudnessResponse(loudness=loudness, edges=edges)


@router.get("/tracks/{track_id}/similar", response_model=list[SimilarTrack])
async def similar_tracks(
    track_id: str,
    db: DB,
    _token: AuthToken,
    n: int = Query(default=10, ge=1, le=100),
) -> list[SimilarTrack]:
    track = await db.get(Track, track_id)
    if track is None:
        raise HTTPException(404, "Track not found")
    return await get_similar_tracks(track_id, n, db)


@router.get("/tracks/{track_id}/radio", response_model=RadioPlaylist)
async def track_radio(
    track_id: str,
    db: DB,
    _token: AuthToken,
    length: int = Query(default=25, ge=5, le=100),
) -> RadioPlaylist:
    track = await db.get(Track, track_id)
    if track is None:
        raise HTTPException(404, "Track not found")
    tracks = await build_radio(track_id, length, db)
    return RadioPlaylist(seed_id=track_id, tracks=tracks)
