"""
Disk-persisted FAISS index for fast nearest-neighbour similarity search.

Uses IndexFlatIP (inner product) on L2-normalised vectors, which is equivalent
to cosine similarity. Scores returned are in [0, 1] (higher = more similar).

Upgrade path: swap IndexFlatIP for IndexIVFFlat when library exceeds ~50k tracks.
"""

from __future__ import annotations

import os

import numpy as np
import faiss

from config import settings


class SonicIndex:
    def __init__(self) -> None:
        self._index: faiss.IndexFlatIP | None = None
        self._track_ids: list[str] = []
        # track_id -> position in _track_ids, kept in sync alongside it. Lets
        # add() check for an existing vector in O(1) instead of a linear scan
        # — rebuild() calls add() once per track, so an O(n) check there would
        # make a full rebuild O(n^2) (this project has seen 50k-track
        # libraries; that's the difference between a rebuild and a multi
        # -minute coordinator startup stall).
        self._position_by_id: dict[str, int] = {}

    def load_or_create(self) -> None:
        if settings.faiss_index_path.exists() and settings.faiss_ids_path.exists():
            self._index = faiss.read_index(str(settings.faiss_index_path))
            self._track_ids = settings.faiss_ids_path.read_text(encoding="utf-8").splitlines()
            self._position_by_id = {tid: i for i, tid in enumerate(self._track_ids)}
        else:
            self._index = faiss.IndexFlatIP(settings.embedding_dim)
            self._position_by_id = {}

    def _ensure_loaded(self) -> None:
        if self._index is None:
            self.load_or_create()

    def add(self, track_id: str, vector: np.ndarray) -> None:
        self._ensure_loaded()
        vec = vector.astype(np.float32).reshape(1, -1)
        if vec.shape[1] != settings.embedding_dim:
            raise ValueError(
                f"embedding has {vec.shape[1]} dims, expected {settings.embedding_dim}"
            )
        if not np.all(np.isfinite(vec)):
            raise ValueError("embedding contains NaN/Inf")
        faiss.normalize_L2(vec)

        existing_pos = self._position_by_id.get(track_id)
        if existing_pos is not None:
            selector = faiss.IDSelectorBatch(np.array([existing_pos], dtype=np.int64))
            removed = self._index.remove_ids(selector)
            if removed != 1:
                raise RuntimeError(
                    f"removed {removed} stale FAISS vector(s) for {track_id}, expected 1"
                )
            del self._track_ids[existing_pos]
            # Removal shifts every later position down by one; rebuilding the
            # map is O(n), but only on this (rare) re-analysis path, not on
            # every add() call.
            self._position_by_id = {tid: i for i, tid in enumerate(self._track_ids)}

        self._index.add(vec)
        self._track_ids.append(track_id)
        self._position_by_id[track_id] = len(self._track_ids) - 1

    def rebuild(self, items: list[tuple[str, np.ndarray]]) -> None:
        """
        Reset the index and repopulate from (track_id, 128-dim vector) pairs.
        Used on startup to rebuild FAISS from the SQLite DB (the source of truth)
        — so a crash that loses the on-disk index loses no analysed work.
        """
        self._index = faiss.IndexFlatIP(settings.embedding_dim)
        self._track_ids = []
        self._position_by_id = {}
        for tid, vec in items:
            self.add(tid, vec)
        self.save()

    def search(self, vector: np.ndarray, k: int, exclude_id: str | None = None) -> list[tuple[str, float]]:
        """Return up to k (track_id, cosine_score) pairs, excluding exclude_id if given."""
        self._ensure_loaded()
        if len(self._track_ids) == 0:
            return []

        vec = vector.astype(np.float32).reshape(1, -1)
        faiss.normalize_L2(vec)

        # Fetch extra candidates to account for exclusion
        fetch_k = min(k + (1 if exclude_id else 0), len(self._track_ids))
        distances, indices = self._index.search(vec, fetch_k)

        results = []
        for j, idx in enumerate(indices[0]):
            if idx < 0 or idx >= len(self._track_ids):
                continue
            tid = self._track_ids[idx]
            if tid == exclude_id:
                continue
            results.append((tid, float(distances[0][j])))
            if len(results) == k:
                break
        return results

    def get_vector(self, track_id: str) -> np.ndarray | None:
        """Retrieve the stored vector for a track by ID."""
        self._ensure_loaded()
        idx = self._position_by_id.get(track_id)
        if idx is None:
            return None
        vec = np.zeros((1, settings.embedding_dim), dtype=np.float32)
        self._index.reconstruct(idx, vec[0])
        return vec[0]

    def save(self) -> None:
        self._ensure_loaded()
        index_path = settings.faiss_index_path
        ids_path = settings.faiss_ids_path
        index_path.parent.mkdir(parents=True, exist_ok=True)
        ids_path.parent.mkdir(parents=True, exist_ok=True)
        index_tmp = index_path.with_name(f".{index_path.name}.tmp")
        ids_tmp = ids_path.with_name(f".{ids_path.name}.tmp")

        try:
            faiss.write_index(self._index, str(index_tmp))
            ids_tmp.write_text("\n".join(self._track_ids), encoding="utf-8")
            os.replace(ids_tmp, ids_path)
            os.replace(index_tmp, index_path)
        finally:
            index_tmp.unlink(missing_ok=True)
            ids_tmp.unlink(missing_ok=True)

    def __len__(self) -> int:
        return len(self._track_ids)


# Module-level singleton
sonic_index = SonicIndex()
