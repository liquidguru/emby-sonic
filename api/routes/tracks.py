from fastapi import APIRouter, HTTPException, Query
from sqlalchemy import select
from api.deps import DB, AuthToken
from api.schemas import SimilarTrack, RadioPlaylist, TrackOut, LoudnessRequest, LoudnessResponse
from db.models import Track, Embedding
from analysis.similarity import get_similar_tracks, build_radio

router = APIRouter(tags=["tracks"])


@router.post("/tracks/loudness", response_model=LoudnessResponse)
async def tracks_loudness(body: LoudnessRequest, db: DB, _token: AuthToken) -> LoudnessResponse:
    """
    Batch lookup of integrated loudness (LUFS) for a set of track ids, so the
    Android player can apply per-track volume normalisation across a queue in one
    round-trip. Unknown / unmeasured ids are simply omitted from the response.
    """
    ids = [i for i in dict.fromkeys(body.ids) if i]  # de-dupe, drop blanks, keep order
    if not ids:
        return LoudnessResponse(loudness={})
    rows = (
        await db.execute(
            select(Embedding.track_id, Embedding.lufs).where(
                Embedding.track_id.in_(ids), Embedding.lufs.is_not(None)
            )
        )
    ).all()
    return LoudnessResponse(loudness={track_id: lufs for track_id, lufs in rows})


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
