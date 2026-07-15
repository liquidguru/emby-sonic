#!/usr/bin/env python3
"""
Backfill effective start/end (crossfade edge trimming) for already-analysed tracks.

Crossfade edge trimming (issue #38) reads per-track `effective_start_ms` /
`effective_end_ms` — where the audible music actually starts and ends — so a blend
lands on real music instead of a silent tail or a quiet intro. The worker now
measures these during analysis; tracks embedded *before* that have NULLs. This
script fills them in without re-embedding: it streams each track's audio from
Emby, detects the edges, and writes them straight into the embeddings table.

It is CPU-only and never loads the neural model, so it's far cheaper than a
re-analysis and safe to run on the always-on coordinator box (e.g. coordinator-host).
It's resumable: only rows still missing an edge are touched, so re-running picks
up where it left off.

Note it decodes the WHOLE file (at a low sample rate) rather than sampled
windows — the edges are precisely the parts sampled windows skip. That makes it
slower per track than the loudness backfill, so expect it to take a while over a
large library; it's safe to stop and re-run.

Usage:
    python tools/backfill_edges.py                  # whole library
    python tools/backfill_edges.py --limit 50       # just 50 (a test batch)
    python tools/backfill_edges.py --threshold-db -25   # override the trim threshold
    python tools/backfill_edges.py --db data/sonic.db

Reads EMBY_URL / EMBY_API_KEY from the repo .env (same as the worker).
"""

from __future__ import annotations

import argparse
import os
import sqlite3
import sys
from contextlib import closing
from pathlib import Path

# Import the shared analysis helpers (these pull in librosa lazily, never torch).
# Run from the repo root so `analysis`/`config` are importable.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from config import settings  # noqa: E402
from analysis import emby  # noqa: E402
from analysis.audio import detect_edges  # noqa: E402


def _pending(conn: sqlite3.Connection, limit: int | None) -> list[tuple[str, str | None]]:
    """Analysed tracks (have an embedding row) that still lack edge data.

    Audiobooks are skipped: they are never crossfaded, and decoding hours of
    spoken word for a blend that will not happen is pure waste.
    """
    markers = list(settings.mix_exclude_path_markers)
    extensions = list(settings.mix_exclude_extensions)
    clauses = ["(e.effective_start_ms IS NULL OR e.effective_end_ms IS NULL)"]
    params: list[str] = []
    for marker in markers:
        clauses.append("COALESCE(t.file_path, '') NOT LIKE ?")
        params.append(f"%{marker}%")
    for ext in extensions:
        clauses.append("COALESCE(t.file_path, '') NOT LIKE ?")
        params.append(f"%{ext}")
    sql = (
        "SELECT e.track_id, t.file_path "
        "FROM embeddings e JOIN tracks t ON t.id = e.track_id "
        f"WHERE {' AND '.join(clauses)} "
        "ORDER BY e.track_id"
    )
    if limit:
        sql += f" LIMIT {int(limit)}"
    return list(conn.execute(sql, params))


def _detect_one(track_id: str, file_path: str | None, threshold_db: float | None):
    suffix = os.path.splitext(file_path or "")[1]
    path = emby.download_track(track_id, suffix=suffix)
    try:
        return detect_edges(path, threshold_db=threshold_db)
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=Path("data/sonic.db"), help="SQLite DB path")
    parser.add_argument("--limit", type=int, default=None, help="Only process this many tracks")
    parser.add_argument(
        "--threshold-db",
        type=float,
        default=None,
        help=f"dB below the track's loud passages to treat as an edge (default: {settings.edge_threshold_db})",
    )
    args = parser.parse_args()

    if not args.db.exists():
        raise SystemExit(f"DB not found: {args.db}")

    with closing(sqlite3.connect(args.db)) as conn:
        columns = {row[1] for row in conn.execute("PRAGMA table_info(embeddings)")}
        if not {"effective_start_ms", "effective_end_ms"} <= columns:
            print(
                "embeddings has no effective_start_ms/effective_end_ms columns — "
                "start the coordinator once to run migrations, then re-run.",
                file=sys.stderr,
            )
            return 2

        pending = _pending(conn, args.limit)
        total = len(pending)
        threshold = args.threshold_db if args.threshold_db is not None else settings.edge_threshold_db
        print(f"backfill: {total} track(s) need edge data (threshold {threshold} dB)")
        done = failed = 0
        for i, (track_id, file_path) in enumerate(pending, start=1):
            try:
                start_ms, end_ms = _detect_one(track_id, file_path, args.threshold_db)
            except Exception as exc:
                start_ms = end_ms = None
                print(f"  [{i}/{total}] {track_id} ERROR {str(exc)[:120]}")
            if start_ms is None or end_ms is None:
                # Undetectable (silent/corrupt/odd) — leave NULL so the client
                # falls back to the full duration. Counted, not retried forever.
                failed += 1
            else:
                conn.execute(
                    "UPDATE embeddings SET effective_start_ms = ?, effective_end_ms = ? "
                    "WHERE track_id = ?",
                    (start_ms, end_ms, track_id),
                )
                conn.commit()
                done += 1
            if i % 25 == 0 or i == total:
                print(f"  [{i}/{total}] detected={done} skipped={failed}")
        print(f"backfill done: detected={done} skipped={failed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
