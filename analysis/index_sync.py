"""
Reconcile the FAISS index with the SQLite DB (the source of truth).

FAISS is a derived structure: every vector it holds is also stored in the
`embeddings` table. Rebuilding from the DB on startup means a crash that loses
or staleness in the on-disk index costs no analysed work.
"""

from __future__ import annotations

import numpy as np
from sqlalchemy import select

from analysis.faiss_index import sonic_index
from db.database import AsyncSessionLocal
from db.models import Embedding


async def rebuild_index_from_db() -> None:
    """Load all stored 128-dim vectors from the DB and repopulate FAISS."""
    async with AsyncSessionLocal() as db:
        rows = (
            await db.execute(
                select(Embedding.track_id, Embedding.vector).where(
                    Embedding.vector.is_not(None)
                )
            )
        ).all()

    items: list[tuple[str, np.ndarray]] = [
        (track_id, np.frombuffer(blob, dtype=np.float32)) for track_id, blob in rows
    ]
    sonic_index.rebuild(items)
