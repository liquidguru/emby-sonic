from fastapi import APIRouter, HTTPException
from api.deps import DB, AuthToken
from api.schemas import QueueInjectRequest, QueueInjectOut
from db.models import Track
from analysis.similarity import get_similar_tracks

router = APIRouter(tags=["queue"])


@router.post("/queue/inject", response_model=QueueInjectOut)
async def guest_dj_inject(body: QueueInjectRequest, db: DB, _token: AuthToken) -> QueueInjectOut:
    if await db.get(Track, body.current_track_id) is None:
        raise HTTPException(404, "Track not found")
    similar = await get_similar_tracks(body.current_track_id, body.queue_length, db)
    return QueueInjectOut(injected=[s.track for s in similar])
