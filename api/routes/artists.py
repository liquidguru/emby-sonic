from fastapi import APIRouter, Query
from api.deps import DB, AuthToken
from api.schemas import SimilarArtist
from analysis.similarity import get_similar_artists

router = APIRouter(tags=["artists"])


@router.get("/artists/{artist_id}/similar", response_model=list[SimilarArtist])
async def similar_artists(
    artist_id: str,
    db: DB,
    _token: AuthToken,
    n: int = Query(default=10, ge=1, le=50),
) -> list[SimilarArtist]:
    return await get_similar_artists(artist_id, n, db)
