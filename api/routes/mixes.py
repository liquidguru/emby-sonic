from datetime import datetime, timezone

import numpy as np
from fastapi import APIRouter, HTTPException
from sqlalchemy import select, delete
from api.deps import DB, AuthToken
from api.schemas import MixOut, MixDetail, RegenerateMixRequest, TrackOut
from analysis.mixes import is_mix_excluded
from config import settings
from db.models import Embedding, Mix, MixTrack, Track

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


@router.post("/mixes/{mix_id}/regenerate", response_model=MixDetail)
async def regenerate_mix(
    mix_id: str,
    body: RegenerateMixRequest,
    db: DB,
    _token: AuthToken,
) -> MixDetail:
    """Refresh one mix's track selection using its stored centroid.

    Excludes the mix's current tracks, then *samples* `tracks_per_mix` new tracks
    from a pool of the closest remaining matches (weighted toward the closest).
    So a refresh fully turns the mix over while staying on-theme; tracks from
    earlier selections can return in later refreshes. Also picks up new library
    additions without a full k-means rebuild.
    """
    mix = await db.get(Mix, mix_id)
    if mix is None:
        raise HTTPException(404, "Mix not found")
    if mix.centroid is None:
        raise HTTPException(409, "Mix has no stored centroid — run Build Mixes first")

    centroid = np.frombuffer(mix.centroid, dtype=np.float32)
    centroid_norm = centroid / (np.linalg.norm(centroid) + 1e-8)

    # The mix's current tracks, excluded below so a refresh fully turns the mix
    # over. Only the *current* set is excluded (not all history), so tracks are
    # free to return in a later refresh.
    current_ids = set(
        (
            await db.execute(
                select(MixTrack.track_id).where(MixTrack.mix_id == mix_id)
            )
        ).scalars().all()
    )

    rows = (
        await db.execute(
            select(Embedding.track_id, Embedding.vector, Track.file_path)
            .join(Track, Track.id == Embedding.track_id)
        )
    ).all()
    rows = [
        r for r in rows
        if not is_mix_excluded(r.file_path) and r.track_id not in current_ids
    ]
    if not rows:
        raise HTTPException(409, "No embeddings in library")

    track_ids = [r.track_id for r in rows]
    vecs = np.array(
        [np.frombuffer(r.vector, dtype=np.float32) for r in rows],
        dtype=np.float32,
    )
    scores = vecs @ centroid_norm
    n = min(body.tracks_per_mix, len(scores))

    # Candidate pool: the closest matches, then weighted-sample N from them so
    # repeated refreshes vary while staying on-theme. Weights are a softmax of
    # the pool's similarity scores; temperature tunes how far we roam.
    pool_size = min(
        len(scores),
        max(n * settings.refresh_pool_multiplier, settings.refresh_pool_min),
    )
    pool_idx = np.argsort(-scores)[:pool_size]
    pool_scores = scores[pool_idx]
    # Scale temperature by the pool's score spread so behaviour is independent of
    # the raw similarity magnitude (embeddings aren't unit-normalised, so scores
    # are unbounded dot products, not [0,1] cosine values).
    spread = float(pool_scores.std()) or 1.0
    scale = max(settings.refresh_temperature, 1e-6) * spread
    weights = np.exp((pool_scores - pool_scores.max()) / scale)
    weights /= weights.sum()
    chosen = np.random.choice(len(pool_idx), size=n, replace=False, p=weights)
    # Shuffle the order too: the closest tracks recur most often, so sorting by
    # score would pin the top of the list and make refreshes look unchanged even
    # though ~half the set rotates. A random order makes the freshness visible.
    chosen_idx = pool_idx[chosen]
    np.random.shuffle(chosen_idx)
    selected_ids = [track_ids[i] for i in chosen_idx]

    await db.execute(delete(MixTrack).where(MixTrack.mix_id == mix_id))
    for position, tid in enumerate(selected_ids):
        db.add(MixTrack(mix_id=mix_id, position=position, track_id=tid))
    mix.created_at = datetime.now(timezone.utc).replace(tzinfo=None)
    await db.commit()
    await db.refresh(mix)

    tracks = []
    for tid in selected_ids:
        track = await db.get(Track, tid)
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
