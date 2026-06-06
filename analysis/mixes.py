"""
Mixes For You — cluster the library into sonically coherent groups via k-means,
then curate one mix per cluster (tracks ranked by proximity to centroid).

Run via POST /sonic/library/build-mixes. Replaces all existing mixes on each run.
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone

import numpy as np
from sklearn.cluster import MiniBatchKMeans
from sqlalchemy import select, delete

logger = logging.getLogger(__name__)

_build_running = False


def build_state() -> dict:
    return {"running": _build_running}


async def build_mixes(n_clusters: int = 30, tracks_per_mix: int = 50) -> int:
    global _build_running
    if _build_running:
        return 0
    _build_running = True
    try:
        return await _run(n_clusters, tracks_per_mix)
    finally:
        _build_running = False


async def _run(n_clusters: int, tracks_per_mix: int) -> int:
    from db.database import AsyncSessionLocal
    from db.models import Embedding, Mix, MixTrack

    def _utcnow() -> datetime:
        return datetime.now(timezone.utc).replace(tzinfo=None)

    # Load all reduced vectors from DB
    async with AsyncSessionLocal() as db:
        rows = (await db.execute(select(Embedding.track_id, Embedding.vector))).all()

    if not rows:
        logger.warning("build_mixes: no embeddings found")
        return 0

    track_ids = [r.track_id for r in rows]
    vecs = np.array(
        [np.frombuffer(r.vector, dtype=np.float32) for r in rows],
        dtype=np.float32,
    )
    logger.info("build_mixes: clustering %d tracks into %d mixes", len(track_ids), n_clusters)

    n_clusters = min(n_clusters, len(vecs))
    km = MiniBatchKMeans(n_clusters=n_clusters, random_state=42, n_init=10)
    labels = km.fit_predict(vecs)
    centroids = km.cluster_centers_.astype(np.float32)

    async with AsyncSessionLocal() as db:
        # Replace all mixes on each build
        await db.execute(delete(MixTrack))
        await db.execute(delete(Mix))
        await db.commit()

        built = 0
        for cluster_id in range(n_clusters):
            mask = np.where(labels == cluster_id)[0]
            if len(mask) == 0:
                continue

            cluster_track_ids = [track_ids[i] for i in mask]
            cluster_vecs = vecs[mask]

            centroid = centroids[cluster_id]
            centroid_norm = centroid / (np.linalg.norm(centroid) + 1e-8)
            scores = cluster_vecs @ centroid_norm
            top_indices = np.argsort(-scores)[:tracks_per_mix]

            mix_id = str(uuid.uuid4())
            db.add(Mix(
                id=mix_id,
                name=f"Mix {cluster_id + 1}",
                created_at=_utcnow(),
                cluster_id=cluster_id,
            ))
            for position, idx in enumerate(top_indices):
                db.add(MixTrack(
                    mix_id=mix_id,
                    position=position,
                    track_id=cluster_track_ids[idx],
                ))
            built += 1

        await db.commit()

    logger.info("build_mixes: done — %d mixes created", built)
    return built
