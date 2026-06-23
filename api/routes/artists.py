from fastapi import APIRouter, Query
from api.deps import DB, AuthToken
from api.schemas import SimilarArtist, ArtistMixRequest, ArtistMixPlaylist
from analysis.similarity import get_similar_artists, build_artist_mix

router = APIRouter(tags=["artists"])


@router.get("/artists/{artist_id}/similar", response_model=list[SimilarArtist])
async def similar_artists(
    artist_id: str,
    db: DB,
    _token: AuthToken,
    n: int = Query(default=10, ge=1, le=50),
) -> list[SimilarArtist]:
    return await get_similar_artists(artist_id, n, db)


@router.post("/artists/mix", response_model=ArtistMixPlaylist)
async def artist_mix(
    body: ArtistMixRequest,
    db: DB,
    _token: AuthToken,
) -> ArtistMixPlaylist:
    tracks = await build_artist_mix(
        artist_names=body.artists,
        per_artist=body.per_artist,
        db=db,
        length=body.length,
    )
    return ArtistMixPlaylist(artists=body.artists, tracks=tracks)
