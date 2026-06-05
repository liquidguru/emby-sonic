from fastapi import APIRouter, HTTPException
from api.deps import DB, AuthToken
from api.schemas import AdventureRequest, AdventurePlaylist
from db.models import Track
from analysis.similarity import build_adventure

router = APIRouter(tags=["adventure"])


@router.post("/adventure", response_model=AdventurePlaylist)
async def sonic_adventure(body: AdventureRequest, db: DB, _token: AuthToken) -> AdventurePlaylist:
    for track_id in (body.from_id, body.to_id):
        if await db.get(Track, track_id) is None:
            raise HTTPException(404, f"Track not found: {track_id}")
    tracks = await build_adventure(body.from_id, body.to_id, body.length, db)
    return AdventurePlaylist(from_id=body.from_id, to_id=body.to_id, tracks=tracks)
