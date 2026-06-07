"""
Mixes For You — cluster the library into sonically coherent groups via k-means,
then curate one mix per cluster (tracks ranked by proximity to centroid).

Each mix is given a human name derived from its tracks' sonic character (tempo +
energy, calibrated against the whole library so there are no hard-coded BPM/energy
magic numbers) and flavoured with a dominant artist when one clearly stands out.
Genre tags aren't stored, so naming is audio-feature + artist based.

Run via POST /sonic/library/build-mixes. Replaces all existing mixes on each run.
"""

from __future__ import annotations

import logging
import uuid
from collections import Counter
from datetime import datetime, timezone

import numpy as np
from sklearn.cluster import MiniBatchKMeans
from sqlalchemy import select, delete

logger = logging.getLogger(__name__)

_build_running = False

# Mood grid indexed by (tempo_level, energy_level), each 0=low / 1=mid / 2=high
# relative to the library's own distribution.
_MOOD = {
    (0, 0): "Chilled & Mellow",
    (0, 1): "Laid-Back Grooves",
    (0, 2): "Deep & Atmospheric",
    (1, 0): "Easy Listening",
    (1, 1): "Steady Flow",
    (1, 2): "Driving Mid-Tempo",
    (2, 0): "Bright & Breezy",
    (2, 1): "Upbeat",
    (2, 2): "High-Energy",
}

# A cluster gets an artist suffix when one artist is at least this fraction of it.
_ARTIST_DOMINANCE = 0.35


def build_state() -> dict:
    return {"running": _build_running}


def _mean(vals: list[float | None]) -> float | None:
    arr = np.array([v for v in vals if v is not None], dtype=float)
    arr = arr[~np.isnan(arr)]
    return float(arr.mean()) if arr.size else None


def _terciles(vals: list[float | None]) -> tuple[float | None, float | None]:
    arr = np.array([v for v in vals if v is not None], dtype=float)
    arr = arr[~np.isnan(arr)]
    if arr.size < 3:
        return (None, None)
    return (float(np.percentile(arr, 33)), float(np.percentile(arr, 67)))


def _level(x: float | None, terc: tuple[float | None, float | None]) -> int:
    lo, hi = terc
    if x is None or np.isnan(x) or lo is None:
        return 1  # mid / unknown
    return 0 if x < lo else (2 if x > hi else 1)


def _dominant_artist(artists: list[str | None]) -> tuple[str | None, float]:
    clean = [a for a in artists if a]
    if not clean:
        return (None, 0.0)
    artist, count = Counter(clean).most_common(1)[0]
    return (artist, count / len(clean))


def _mix_name(
    tempos: list[float | None],
    energies: list[float | None],
    artists: list[str | None],
    tempo_terc: tuple[float | None, float | None],
    energy_terc: tuple[float | None, float | None],
) -> str:
    """Build a human mix name from a cluster's tempo/energy character + artists."""
    mood = _MOOD[(_level(_mean(tempos), tempo_terc), _level(_mean(energies), energy_terc))]
    artist, frac = _dominant_artist(artists)
    if artist and frac >= _ARTIST_DOMINANCE:
        return f"{mood} · {artist}"
    return mood


def _dedupe(name: str, used: set[str]) -> str:
    if name not in used:
        used.add(name)
        return name
    n = 2
    while f"{name} ({n})" in used:
        n += 1
    final = f"{name} ({n})"
    used.add(final)
    return final


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
    from db.models import Embedding, Mix, MixTrack, Track

    def _utcnow() -> datetime:
        return datetime.now(timezone.utc).replace(tzinfo=None)

    # Load reduced vectors + the metadata we name mixes from.
    async with AsyncSessionLocal() as db:
        rows = (
            await db.execute(
                select(
                    Embedding.track_id,
                    Embedding.vector,
                    Embedding.tempo,
                    Embedding.energy,
                    Track.artist,
                ).join(Track, Track.id == Embedding.track_id)
            )
        ).all()

    if not rows:
        logger.warning("build_mixes: no embeddings found")
        return 0

    track_ids = [r.track_id for r in rows]
    vecs = np.array(
        [np.frombuffer(r.vector, dtype=np.float32) for r in rows],
        dtype=np.float32,
    )
    tempos = [r.tempo for r in rows]
    energies = [r.energy for r in rows]
    artists = [r.artist for r in rows]

    # Library-wide terciles so cluster naming is relative to this collection
    # (librosa's energy/tempo scales vary — no portable hard-coded thresholds).
    tempo_terc = _terciles(tempos)
    energy_terc = _terciles(energies)

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

        used_names: set[str] = set()
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

            # Name from the tracks that actually make the mix (the top ones).
            sel_tempos = [tempos[mask[i]] for i in top_indices]
            sel_energies = [energies[mask[i]] for i in top_indices]
            sel_artists = [artists[mask[i]] for i in top_indices]
            name = _dedupe(
                _mix_name(sel_tempos, sel_energies, sel_artists, tempo_terc, energy_terc),
                used_names,
            )

            mix_id = str(uuid.uuid4())
            db.add(Mix(
                id=mix_id,
                name=name,
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
