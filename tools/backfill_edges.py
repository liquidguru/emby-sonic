#!/usr/bin/env python3
"""
Backfill effective start/end (crossfade edge trimming) for already-analysed tracks.

Crossfade edge trimming (issue #38) reads per-track `effective_start_ms` /
`effective_end_ms` — where the audible music actually starts and ends — so a blend
lands on real music instead of a silent tail or a quiet intro. The worker now
measures these during analysis; tracks embedded *before* that have NULLs. This
script fills them in without re-embedding: it streams each track's audio from
Emby, detects the edges, and writes them straight into the embeddings table.

It is CPU-only and never loads the neural model, so it is FAR cheaper than a
re-analysis. It's resumable: only rows still missing an edge are touched, so
re-running picks up where it left off, and it's safe to stop at any time.

WHERE TO RUN IT — it needs BOTH librosa AND the coordinator's database, and
must run ON THE HOST WHERE THE DATABASE LIVES. A Docker named volume is local
to its host, so it cannot be reached from a worker on a different machine (#40).

  Docker:      run a one-off WORKER container ON THE COORDINATOR'S HOST, with
               the data volume attached — NOT on a separate worker box. The
               coordinator image deliberately has no librosa (that is the point
               of the coordinator/worker split — a small, ARM-buildable image),
               and the worker image doesn't normally mount the database:

                 docker compose run --rm -v emby-sonic-data:/app/data \
                     worker python tools/backfill_edges.py

               (Split hosts: the worker service may live elsewhere, but this
               one-off container must run where the volume is.)

  Bare metal:  run it on the coordinator's host, wherever you installed
               requirements.txt (the full set, not requirements-coordinator.txt),
               pointing --db at the database.

It decodes the WHOLE file rather than sampled windows — the edges are precisely
the parts sampled windows skip — so it costs more per track than the loudness
backfill, but nothing like a re-analysis.

Measured throughput (Intel N100 / coordinator-host, streaming from Emby over LAN):

    ~42 tracks/minute  (~1.4 s/track)
    25,528 tracks -> ~10 hours       (0.01% undetectable)

    1,000 tracks  ~25 min
    5,000 tracks  ~2 hours
    20,000 tracks ~8 hours

Dominated by decode, so a faster CPU helps; a slow link to Emby will bound it
instead. Audiobooks are skipped (never crossfaded). Newly-analysed tracks get
their edges from the worker automatically — this is only for the back catalogue.

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

DB_TIMEOUT_SECONDS = 120.0


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

    # Generous timeout: the coordinator is normally live on this same DB, and
    # SQLite only allows one writer at a time. The default 5s can raise
    # "database is locked" mid-run against a busy coordinator.
    with closing(sqlite3.connect(args.db, timeout=DB_TIMEOUT_SECONDS)) as conn:
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
