"""
Background library scanner.

Flow:
  1. Fetch all tracks from Emby (/Items?IncludeItemTypes=Audio)
  2. Upsert track rows into SQLite
  3. For each unanalysed track (or all, if full=True):
     a. Load audio with librosa
     b. Extract librosa features (tempo, energy)
     c. Extract Essentia features (valence, arousal, instrumentalness, vocals)
     d. Embed with MERT → 128-dim vector
     e. Write embedding row to SQLite
     f. Add vector to FAISS index
  4. After all tracks: if enough raw embeddings collected, fit/refit PCA
  5. Save FAISS index to disk

scan_state is a plain dict — safe for single-process access. The scanner
runs in a FastAPI BackgroundTask (thread), not a subprocess, so the in-memory
index and singleton embedder are shared with the API process.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, UTC

import httpx
import numpy as np

from config import settings

logger = logging.getLogger(__name__)

scan_state: dict = {
    "running": False,
    "total": 0,
    "done": 0,
    "errors": 0,
}

# Minimum tracks needed before fitting PCA (too few → PCA is unstable)
_PCA_MIN_TRACKS = 200


async def _fetch_emby_tracks(token: str | None = None) -> list[dict]:
    """Fetch all audio items from Emby using the server API key."""
    headers = {"X-Emby-Token": token or settings.emby_api_key}
    async with httpx.AsyncClient(base_url=settings.emby_url, timeout=30.0) as client:
        resp = await client.get(
            "/Items",
            params={
                "IncludeItemTypes": "Audio",
                "Recursive": "true",
                "Fields": "Id,Name,AlbumArtist,Album,RunTimeTicks,Path",
                "Limit": 50000,
            },
            headers=headers,
        )
        resp.raise_for_status()
        return resp.json().get("Items", [])


async def run_scan(full: bool = False) -> None:
    """
    Entry point called by FastAPI BackgroundTasks.
    Wraps the async scan in its own event loop if needed.
    """
    if scan_state["running"]:
        return
    scan_state["running"] = True
    scan_state["done"] = 0
    scan_state["errors"] = 0

    try:
        await _do_scan(full=full)
    except Exception:
        logger.exception("Scan failed")
    finally:
        scan_state["running"] = False


async def _do_scan(full: bool) -> None:
    from db.database import AsyncSessionLocal
    from db.models import Track, Embedding
    from analysis.audio import load_audio, extract_librosa_features, extract_essentia_features
    from analysis.embeddings import embedder
    from analysis.faiss_index import sonic_index
    from sqlalchemy import select

    sonic_index.load_or_create()

    emby_items = await _fetch_emby_tracks()
    scan_state["total"] = len(emby_items)
    logger.info("Scan: %d tracks from Emby", len(emby_items))

    raw_embeddings: list[np.ndarray] = []

    async with AsyncSessionLocal() as db:
        for item in emby_items:
            track_id = item.get("Id", "")
            file_path = item.get("Path", "")
            if not track_id or not file_path:
                scan_state["done"] += 1
                continue

            # Upsert track metadata
            track = await db.get(Track, track_id)
            if track is None:
                track = Track(id=track_id)
                db.add(track)
            track.title = item.get("Name")
            track.artist = item.get("AlbumArtist")
            track.album = item.get("Album")
            ticks = item.get("RunTimeTicks")
            track.duration_ms = int(ticks / 10000) if ticks else None
            track.file_path = file_path

            # Skip if already analysed (unless full re-scan)
            existing = await db.get(Embedding, track_id)
            if existing is not None and not full:
                scan_state["done"] += 1
                continue

            try:
                waveform, sr = load_audio(file_path)
                librosa_feats = extract_librosa_features(waveform, sr)
                essentia_feats = extract_essentia_features(file_path)

                raw_vec = embedder.embed_raw(waveform)
                raw_embeddings.append(raw_vec)

                final_vec = embedder.embed(waveform)

                if existing is None:
                    emb = Embedding(track_id=track_id)
                    db.add(emb)
                else:
                    emb = existing

                emb.vector = final_vec.tobytes()
                emb.tempo = librosa_feats.get("tempo")
                emb.energy = librosa_feats.get("energy")
                emb.valence = essentia_feats.get("valence")
                emb.arousal = essentia_feats.get("arousal")
                emb.instrumentalness = essentia_feats.get("instrumentalness")
                emb.vocals_present = essentia_feats.get("vocals_present")

                track.analysed_at = datetime.now(UTC)
                track.analysis_version = 1

                sonic_index.add(track_id, final_vec)

            except Exception:
                logger.exception("Failed to analyse track %s (%s)", track_id, file_path)
                scan_state["errors"] += 1

            scan_state["done"] += 1

            # Commit in batches to avoid holding a long transaction
            if scan_state["done"] % 50 == 0:
                await db.commit()

        await db.commit()

    # Fit PCA if we have enough new raw embeddings
    if len(raw_embeddings) >= _PCA_MIN_TRACKS:
        logger.info("Fitting PCA on %d embeddings", len(raw_embeddings))
        embedder.fit_pca(np.stack(raw_embeddings))

    sonic_index.save()
    logger.info(
        "Scan complete: %d done, %d errors", scan_state["done"], scan_state["errors"]
    )
