"""
Coordinator-side API for distributed analysis workers.

Workers (local or on any networked machine — e.g. a GPU box) repeatedly:
  1. POST /worker/claim  -> get a batch of pending tracks (leased)
  2. stream each track's audio from Emby, embed it
  3. POST /worker/results -> return embeddings; coordinator stores + indexes them

The lease (claimed_at) makes this crash-safe: if a worker dies mid-batch, its
tracks become reclaimable after lease_seconds and another worker picks them up.
"""

from __future__ import annotations

import asyncio
import base64
import logging
from datetime import datetime, timedelta, UTC

import numpy as np
from fastapi import APIRouter
from sqlalchemy import select, or_

from api.deps import DB, WorkerAuth
from api.schemas import ClaimRequest, ClaimResponse, ClaimedTrack, ResultsRequest, ResultsResponse
from db.models import Track, Embedding
from analysis.faiss_index import sonic_index

logger = logging.getLogger(__name__)

# Serialise result-storage so many concurrent workers don't collide on the
# single SQLite writer / the shared in-memory FAISS index. The embedding work
# (the slow part) still parallelises across workers; only the quick store is
# serialised here.
_store_lock = asyncio.Lock()

router = APIRouter(tags=["worker"])


@router.post("/worker/claim", response_model=ClaimResponse)
async def claim(body: ClaimRequest, db: DB, _auth: WorkerAuth) -> ClaimResponse:
    cutoff = datetime.now(UTC) - timedelta(seconds=body.lease_seconds)
    stmt = (
        select(Track)
        .where(
            Track.analysis_status == "pending",
            or_(Track.claimed_at.is_(None), Track.claimed_at < cutoff),
        )
        .limit(body.batch_size)
    )
    tracks = (await db.execute(stmt)).scalars().all()

    now = datetime.now(UTC)
    for t in tracks:
        t.claimed_at = now
    await db.commit()

    return ClaimResponse(tracks=[ClaimedTrack(id=t.id, path=t.file_path) for t in tracks])


@router.post("/worker/results", response_model=ResultsResponse)
async def results(body: ResultsRequest, db: DB, _auth: WorkerAuth) -> ResultsResponse:
    from analysis.embeddings import embedder  # reduce() only — no model load needed

    stored = failed = 0
    async with _store_lock:
        for r in body.results:
            track = await db.get(Track, r.track_id)
            if track is None:
                continue
            try:
                if r.error or not r.raw_vector:
                    raise ValueError(r.error or "no embedding returned")

                raw = np.frombuffer(base64.b64decode(r.raw_vector), dtype=np.float32)
                if raw.size == 0:
                    raise ValueError("empty embedding")
                final = embedder.reduce(raw)

                emb = await db.get(Embedding, r.track_id)
                if emb is None:
                    emb = Embedding(track_id=r.track_id)
                    db.add(emb)
                emb.raw_vector = raw.tobytes()
                emb.vector = final.tobytes()
                emb.tempo = r.tempo
                emb.energy = r.energy
                emb.valence = r.valence
                emb.arousal = r.arousal
                emb.instrumentalness = r.instrumentalness
                emb.vocals_present = r.vocals_present

                track.analysis_status = "done"
                track.analysed_at = datetime.now(UTC)
                track.analysis_version = 1
                track.error = None
                track.claimed_at = None

                sonic_index.add(r.track_id, final)
                stored += 1
            except Exception as exc:
                # One bad result must not 500 the whole batch.
                logger.warning("results: track %s failed to store: %s", r.track_id, exc)
                track.analysis_status = "error"
                track.error = str(exc)[:500]
                track.claimed_at = None
                failed += 1

        await db.commit()
    # NB: we intentionally do NOT persist the FAISS index here — writing the
    # whole index on every batch blocks the event loop and worsens as it grows.
    # The in-memory index stays current via sonic_index.add(); on restart it is
    # rebuilt from the DB (the source of truth) by rebuild_index_from_db().
    return ResultsResponse(stored=stored, failed=failed)
