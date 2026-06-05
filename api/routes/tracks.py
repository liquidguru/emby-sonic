from fastapi import APIRouter, HTTPException, Query
from sqlalchemy import select
from api.deps import DB, AuthToken
from api.schemas import SimilarTrack, RadioPlaylist, TrackOut
from db.models import Track
from analysis.similarity import get_similar_tracks, build_radio

router = APIRouter(tags=["tracks"])


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
