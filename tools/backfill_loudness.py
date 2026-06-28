#!/usr/bin/env python3
"""
Backfill integrated loudness (LUFS) for already-analysed tracks.

Volume normalisation (added after the first library scan) reads a per-track LUFS
value the worker now measures during analysis. Tracks embedded *before* that have
no LUFS yet — this script fills them in without re-embedding: it streams each
track's audio from Emby, measures EBU R128 loudness over the same sampled windows
the analyser uses, and writes it straight into the embeddings table.

It is CPU-only and never loads the neural model, so it's far cheaper than a
re-analysis and safe to run on the always-on coordinator box (e.g. coordinator-host).
It's resumable: only rows with lufs IS NULL are touched, so re-running picks up
where it left off (and retries any that errored to None).

Usage:
    python tools/backfill_loudness.py                 # whole library, in batches
    python tools/backfill_loudness.py --limit 50      # just 50 (a test batch)
    python tools/backfill_loudness.py --db data/sonic.db

Reads EMBY_URL / EMBY_API_KEY from the repo .env (same as the worker).
"""

from __future__ import annotations

import argparse
import os
import sqlite3
import sys
from contextlib import closing
from pathlib import Path

# Import the shared analysis helpers (these pull in librosa + pyloudnorm lazily,
# never torch). Run from the repo root so `analysis`/`config` are importable.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from config import settings  # noqa: E402
from analysis import emby  # noqa: E402
from analysis.audio import load_windows, measure_loudness  # noqa: E402

import numpy as np  # noqa: E402


def _pending(conn: sqlite3.Connection, limit: int | None) -> list[tuple[str, str | None]]:
    """Analysed tracks (have an embedding row) that still lack a LUFS value."""
    sql = (
        "SELECT e.track_id, t.file_path "
        "FROM embeddings e JOIN tracks t ON t.id = e.track_id "
        "WHERE e.lufs IS NULL "
        "ORDER BY e.track_id"
    )
    if limit:
        sql += f" LIMIT {int(limit)}"
    return list(conn.execute(sql))


def _measure_one(track_id: str, file_path: str | None) -> float | None:
    suffix = os.path.splitext(file_path or "")[1]
    path = emby.download_track(track_id, suffix=suffix)
    try:
        windows = load_windows(path)
        if not windows:
            return None
        return measure_loudness(np.concatenate(windows), settings.sample_rate)
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=Path("data/sonic.db"), help="SQLite DB path")
    parser.add_argument("--limit", type=int, default=None, help="Only process this many tracks")
    args = parser.parse_args()

    if not args.db.exists():
        raise SystemExit(f"DB not found: {args.db}")

    if measure_loudness(np.zeros(settings.sample_rate, dtype=np.float32) + 0.1, settings.sample_rate) is None:
        # A constant tone should yield a finite value; None here means pyloudnorm
        # is missing. Fail loud rather than silently writing nothing.
        print("pyloudnorm not installed — run: pip install pyloudnorm", file=sys.stderr)
        return 2

    with closing(sqlite3.connect(args.db)) as conn:
        pending = _pending(conn, args.limit)
        total = len(pending)
        print(f"backfill: {total} track(s) need loudness")
        done = failed = 0
        for i, (track_id, file_path) in enumerate(pending, start=1):
            try:
                lufs = _measure_one(track_id, file_path)
            except Exception as exc:
                lufs = None
                print(f"  [{i}/{total}] {track_id} ERROR {str(exc)[:120]}")
            if lufs is None:
                failed += 1
            else:
                conn.execute(
                    "UPDATE embeddings SET lufs = ? WHERE track_id = ?", (lufs, track_id)
                )
                conn.commit()
                done += 1
                if i % 25 == 0 or i == total:
                    print(f"  [{i}/{total}] measured={done} failed={failed}")
        print(f"backfill done: measured={done} failed={failed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
