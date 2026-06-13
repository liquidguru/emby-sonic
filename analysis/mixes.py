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

import asyncio
import logging
import uuid
from collections import Counter
from datetime import datetime, timezone

import numpy as np
from sklearn.cluster import MiniBatchKMeans
from sqlalchemy import select, delete

from config import settings

logger = logging.getLogger(__name__)

_build_running = False


def is_mix_excluded(file_path: str | None) -> bool:
    """True if a track should be kept out of sonic mixes (e.g. audiobooks).

    Matches its Emby file path against settings.mix_exclude_path_markers
    (substring) and settings.mix_exclude_extensions (suffix), both
    case-insensitively. Shared by build_mixes and the regenerate endpoint.
    """
    if not file_path:
        return False
    lowered = file_path.lower()
    if any(marker.lower() in lowered for marker in settings.mix_exclude_path_markers):
        return True
    return any(lowered.endswith(ext.lower()) for ext in settings.mix_exclude_extensions)

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

# Compilation / placeholder "artists" that shouldn't be used to name a mix.
_IGNORED_ARTISTS = {
    "various artists", "various", "va", "unknown artist", "unknown",
    "[unknown]", "[unknown artist]", "soundtrack", "various artist",
}


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
    # Fraction is over all named tracks, but compilation placeholders never win.
    named = [a for a in artists if a]
    if not named:
        return (None, 0.0)
    real = [a for a in named if a.strip().lower() not in _IGNORED_ARTISTS]
    if not real:
        return (None, 0.0)
    artist, count = Counter(real).most_common(1)[0]
    return (artist, count / len(named))


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


def _cluster_and_name(
    track_ids: list[str],
    vecs: "np.ndarray",
    tempos: list[float | None],
    energies: list[float | None],
    artists: list[str | None],
    n_clusters: int,
    tracks_per_mix: int,
) -> list[dict]:
    """CPU-bound k-means + per-cluster track selection + naming. Pure/blocking,
    so it runs in a worker thread (see `_run`)."""
    n_clusters = min(n_clusters, len(vecs))
    km = MiniBatchKMeans(n_clusters=n_clusters, random_state=42, n_init=10)
    labels = km.fit_predict(vecs)
    centroids = km.cluster_centers_.astype(np.float32)

    # Pass 1: pick each cluster's top tracks and gather their naming features.
    clusters: list[dict] = []
    for cluster_id in range(n_clusters):
        mask = np.where(labels == cluster_id)[0]
        if len(mask) == 0:
            continue
        cluster_vecs = vecs[mask]
        centroid = centroids[cluster_id]
        centroid_norm = centroid / (np.linalg.norm(centroid) + 1e-8)
        scores = cluster_vecs @ centroid_norm
        top_indices = np.argsort(-scores)[:tracks_per_mix]

        sel_tempos = [tempos[mask[i]] for i in top_indices]
        sel_energies = [energies[mask[i]] for i in top_indices]
        clusters.append({
            "cluster_id": cluster_id,
            "track_ids": [track_ids[mask[i]] for i in top_indices],
            "mean_tempo": _mean(sel_tempos),
            "mean_energy": _mean(sel_energies),
            "artists": [artists[mask[i]] for i in top_indices],
            "centroid": centroids[cluster_id].tobytes(),
        })

    # Grade clusters RELATIVE TO EACH OTHER: terciles over the per-cluster means,
    # not over individual tracks (averaging 50 tracks pulls toward the global mean,
    # so track-level terciles would label almost every cluster "mid").
    tempo_terc = _terciles([c["mean_tempo"] for c in clusters])
    energy_terc = _terciles([c["mean_energy"] for c in clusters])

    used_names: set[str] = set()
    for c in clusters:
        mood = _MOOD[(_level(c["mean_tempo"], tempo_terc), _level(c["mean_energy"], energy_terc))]
        artist, frac = _dominant_artist(c["artists"])
        base = f"{mood} · {artist}" if (artist and frac >= _ARTIST_DOMINANCE) else mood
        c["name"] = _dedupe(base, used_names)

    return clusters


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
                    Track.file_path,
                ).join(Track, Track.id == Embedding.track_id)
            )
        ).all()

    if not rows:
        logger.warning("build_mixes: no embeddings found")
        return 0

    # Keep audiobooks (and any other excluded paths) out of the clustering.
    excluded = sum(1 for r in rows if is_mix_excluded(r.file_path))
    rows = [r for r in rows if not is_mix_excluded(r.file_path)]
    if excluded:
        logger.info("build_mixes: excluded %d audiobook/other tracks", excluded)
    if not rows:
        logger.warning("build_mixes: no eligible tracks after exclusions")
        return 0

    track_ids = [r.track_id for r in rows]
    vecs = np.array(
        [np.frombuffer(r.vector, dtype=np.float32) for r in rows],
        dtype=np.float32,
    )
    tempos = [r.tempo for r in rows]
    energies = [r.energy for r in rows]
    artists = [r.artist for r in rows]

    logger.info(
        "build_mixes: clustering %d tracks into %d mixes",
        len(track_ids), min(n_clusters, len(vecs)),
    )

    # Offload the blocking k-means + selection + naming to a worker thread so the
    # event loop stays responsive during the ~15-20s build (notably so the
    # /library/build-state poll can be answered while a build runs).
    clusters = await asyncio.to_thread(
        _cluster_and_name, track_ids, vecs, tempos, energies, artists, n_clusters, tracks_per_mix
    )

    # Pass 2: replace all mixes and write the new ones.
    async with AsyncSessionLocal() as db:
        await db.execute(delete(MixTrack))
        await db.execute(delete(Mix))
        await db.commit()

        for c in clusters:
            mix_id = str(uuid.uuid4())
            db.add(Mix(
                id=mix_id,
                name=c["name"],
                created_at=_utcnow(),
                cluster_id=c["cluster_id"],
                centroid=c.get("centroid"),
            ))
            for position, tid in enumerate(c["track_ids"]):
                db.add(MixTrack(mix_id=mix_id, position=position, track_id=tid))

        await db.commit()

    logger.info("build_mixes: done — %d mixes created", len(clusters))
    return len(clusters)
