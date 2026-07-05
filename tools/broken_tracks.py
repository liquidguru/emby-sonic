#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import sqlite3
from contextlib import closing
from pathlib import Path
from typing import Iterable

EXPORT_COLUMNS = ("id", "title", "artist", "album", "file_path", "error")


def export_errors(db_path: Path, output_path: Path) -> int:
    with closing(sqlite3.connect(db_path)) as conn, output_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(EXPORT_COLUMNS)
        rows = conn.execute(
            """
            SELECT id, title, artist, album, file_path, error
            FROM tracks
            WHERE analysis_status = 'error'
            ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, title COLLATE NOCASE, id
            """
        )
        count = 0
        for row in rows:
            writer.writerow(["" if value is None else value for value in row])
            count += 1
    return count


def requeue_errors(db_path: Path, track_ids: Iterable[str] | None = None) -> int:
    ids = [track_id.strip() for track_id in (track_ids or []) if track_id.strip()]
    with closing(sqlite3.connect(db_path)) as conn:
        if ids:
            placeholders = ",".join("?" for _ in ids)
            params = [*ids]
            result = conn.execute(
                f"""
                UPDATE tracks
                SET analysis_status = 'pending',
                    claimed_at = NULL,
                    error = NULL
                WHERE analysis_status = 'error'
                  AND id IN ({placeholders})
                """,
                params,
            )
        else:
            result = conn.execute(
                """
                UPDATE tracks
                SET analysis_status = 'pending',
                    claimed_at = NULL,
                    error = NULL
                WHERE analysis_status = 'error'
                """
            )
        conn.commit()
        return result.rowcount


def purge_errors(db_path: Path, track_ids: Iterable[str] | None = None) -> int:
    """Permanently delete tracks with analysis_status='error' (optionally
    scoped to specific ids), instead of requeuing them for another attempt.

    For tracks that will never succeed — a corrupt file, or a stale/orphaned
    Emby library entry with no real file behind it — repeatedly requeuing
    them just retries forever and clutters the skipped-tracks list. Deleting
    the row removes it from /status and /status/errors immediately; if the
    same Emby item id genuinely still exists, the next library sync just
    recreates a fresh 'pending' row for it, so nothing is lost that Emby
    itself still has.
    """
    ids = [track_id.strip() for track_id in (track_ids or []) if track_id.strip()]
    with closing(sqlite3.connect(db_path)) as conn:
        if ids:
            placeholders = ",".join("?" for _ in ids)
            result = conn.execute(
                f"DELETE FROM tracks WHERE analysis_status = 'error' AND id IN ({placeholders})",
                ids,
            )
        else:
            result = conn.execute("DELETE FROM tracks WHERE analysis_status = 'error'")
        conn.commit()
        return result.rowcount


def ids_from_file(path: Path) -> list[str]:
    return [
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def ids_from_csv(path: Path) -> list[str]:
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        if "id" not in (reader.fieldnames or []):
            raise SystemExit(f"{path} has no 'id' column")
        return [row["id"].strip() for row in reader if row.get("id", "").strip()]


def _add_selection_group(subparser: argparse.ArgumentParser, verb: str) -> None:
    group = subparser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all", action="store_true", help=f"{verb} all error tracks")
    group.add_argument("--id", action="append", dest="ids", help=f"Track ID to {verb.lower()}; repeatable")
    group.add_argument("--ids-file", type=Path, help="Text file with one track ID per line")
    group.add_argument("--csv", type=Path, help="CSV with an id column, such as the export output")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Export, requeue, or purge tracks with analysis_status='error'.")
    parser.add_argument("--db", type=Path, default=Path("data/sonic.db"), help="SQLite DB path")
    subparsers = parser.add_subparsers(dest="command", required=True)

    export_parser = subparsers.add_parser("export", help="Export broken tracks to CSV")
    export_parser.add_argument(
        "--output",
        "-o",
        type=Path,
        default=Path("broken_tracks.csv"),
        help="CSV output path",
    )

    requeue_parser = subparsers.add_parser("requeue", help="Requeue broken tracks for worker retry")
    _add_selection_group(requeue_parser, "Requeue")

    purge_parser = subparsers.add_parser(
        "purge", help="Permanently delete broken tracks instead of retrying them (corrupt files, stale Emby entries)"
    )
    _add_selection_group(purge_parser, "Delete")
    return parser


def _resolve_ids(args: argparse.Namespace) -> list[str] | None:
    if args.all:
        return None
    if args.ids:
        return args.ids
    if args.ids_file:
        return ids_from_file(args.ids_file)
    return ids_from_csv(args.csv)


def main() -> int:
    args = build_parser().parse_args()
    if not args.db.exists():
        raise SystemExit(f"DB not found: {args.db}")

    if args.command == "export":
        count = export_errors(args.db, args.output)
        print(f"exported={count} output={args.output}")
        return 0

    ids = _resolve_ids(args)
    if args.command == "purge":
        count = purge_errors(args.db, ids)
        print(f"purged={count}")
    else:
        count = requeue_errors(args.db, ids)
        print(f"requeued={count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
