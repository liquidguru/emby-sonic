from fastapi import APIRouter, Query
from api.deps import DB, AuthToken
from api.schemas import SimilarAlbum
from analysis.similarity import get_similar_albums

router = APIRouter(tags=["albums"])


@router.get("/albums/{album_id}/similar", response_model=list[SimilarAlbum])
async def similar_albums(
    album_id: str,
    db: DB,
    _token: AuthToken,
    n: int = Query(default=10, ge=1, le=50),
) -> list[SimilarAlbum]:
    return await get_similar_albums(album_id, n, db)
