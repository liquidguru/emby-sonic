from fastapi import APIRouter, HTTPException
from sqlalchemy import select
from api.deps import DB, AuthToken
from api.schemas import MixOut, MixDetail, TrackOut
from db.models import Mix, MixTrack, Track

router = APIRouter(tags=["mixes"])


@router.get("/mixes", response_model=list[MixOut])
async def list_mixes(db: DB, _token: AuthToken) -> list[MixOut]:
    result = await db.execute(select(Mix))
    mixes = result.scalars().all()
    out = []
    for mix in mixes:
        count_result = await db.execute(
            select(MixTrack).where(MixTrack.mix_id == mix.id)
        )
        count = len(count_result.scalars().all())
        out.append(MixOut(
            id=mix.id,
            name=mix.name,
            created_at=mix.created_at,
            cluster_id=mix.cluster_id,
            track_count=count,
        ))
    return out


@router.get("/mixes/{mix_id}", response_model=MixDetail)
async def get_mix(mix_id: str, db: DB, _token: AuthToken) -> MixDetail:
    mix = await db.get(Mix, mix_id)
    if mix is None:
        raise HTTPException(404, "Mix not found")

    mt_result = await db.execute(
        select(MixTrack).where(MixTrack.mix_id == mix_id).order_by(MixTrack.position)
    )
    mix_tracks = mt_result.scalars().all()

    tracks = []
    for mt in mix_tracks:
        track = await db.get(Track, mt.track_id)
        if track:
            tracks.append(TrackOut.model_validate(track))

    mix_out = MixOut(
        id=mix.id,
        name=mix.name,
        created_at=mix.created_at,
        cluster_id=mix.cluster_id,
        track_count=len(tracks),
    )
    return MixDetail(mix=mix_out, tracks=tracks)
