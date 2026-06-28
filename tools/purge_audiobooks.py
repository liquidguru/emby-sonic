#!/usr/bin/env python3
"""
Remove non-music audio (audiobooks etc.) that leaked into sonic analysis.

Early library scans pulled in EVERY Emby "Audio" item — including audiobooks from
a separate library — which then got embedded and polluted Track Radio / Similar /
Sonic Adventure / Guest DJ. The scanner now scopes to music-typed libraries
(see analysis/emby.py); this one-time cleanup removes the already-analysed
non-music tracks so the FAISS index (rebuilt on coordinator start) no longer
contains them.

Approach: ask Emby for every audio track in its **music** libraries (the
keep-set), then delete any DB track whose id isn't in it. Reuses the same
music-library rule as the scanner, so it tracks whatever a given install calls
its music vs audiobook libraries — no hard-coded paths.

Safety: aborts if the keep-set looks implausibly small, or if the purge would
remove more than 25% of the library (guards against a bad/partial Emby fetch).

Usage:
    python tools/purge_audiobooks.py            # dry run — counts only
    python tools/purge_audiobooks.py --apply    # actually delete
Reads EMBY_URL / EMBY_API_KEY from .env.
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from contextlib import closing
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from config import settings  # noqa: E402
from analysis.emby import MUSIC_COLLECTION_TYPES, _auth_headers  # noqa: E402


def music_track_ids() -> set[str]:
    """Every audio track id in Emby's music-typed libraries (the keep-set)."""
    with httpx.Client(base_url=settings.emby_url, timeout=180.0) as client:
        resp = client.get("/Library/VirtualFolders", headers=_auth_headers())
        resp.raise_for_status()
        parents = [
            lib["ItemId"]
            for lib in resp.json()
            if (lib.get("CollectionType") or "").lower() in MUSIC_COLLECTION_TYPES
            and lib.get("ItemId")
        ]
        if not parents:
            raise SystemExit("ABORT: no music-typed library found in Emby — refusing to delete.")
        ids: set[str] = set()
        for parent_id in parents:
            resp = client.get(
                "/Items",
                params={
                    "IncludeItemTypes": "Audio",
                    "Recursive": "true",
                    "ParentId": parent_id,
                    "Fields": "Id",
                    "Limit": 500000,
                },
                headers=_auth_headers(),
            )
            resp.raise_for_status()
            ids.update(it["Id"] for it in resp.json().get("Items", []) if it.get("Id"))
        return ids


def main() -> int:
    parser = argparse.ArgumentParser(description="Purge non-music (audiobook) tracks from sonic analysis.")
    parser.add_argument("--db", type=Path, default=Path("data/sonic.db"))
    parser.add_argument("--apply", action="store_true", help="actually delete (default: dry run)")
    args = parser.parse_args()
    if not args.db.exists():
        raise SystemExit(f"DB not found: {args.db}")

    keep = music_track_ids()
    print(f"music tracks in Emby (keep-set): {len(keep)}")
    if len(keep) < 100:
        raise SystemExit("ABORT: music keep-set implausibly small — refusing to delete.")

    with closing(sqlite3.connect(args.db)) as conn:
        all_ids = [row[0] for row in conn.execute("SELECT id FROM tracks")]
        purge = [i for i in all_ids if i not in keep]
        print(f"tracks in DB: {len(all_ids)}  |  to purge (not in a music library): {len(purge)}")
        if all_ids and len(purge) > len(all_ids) * 0.25:
            raise SystemExit(
                f"ABORT: purge set ({len(purge)}) exceeds 25% of the library ({len(all_ids)}) — refusing."
            )
        if not purge:
            print("Nothing to purge.")
            return 0
        if not args.apply:
            print("dry run — re-run with --apply to delete")
            return 0

        params = [(i,) for i in purge]
        cur = conn.cursor()
        cur.executemany("DELETE FROM embeddings WHERE track_id = ?", params)
        cur.executemany("DELETE FROM mix_tracks WHERE track_id = ?", params)
        cur.executemany("DELETE FROM tracks WHERE id = ?", params)
        conn.commit()
        print(f"deleted {len(purge)} non-music tracks (+ their embeddings / mix entries)")
    print("Restart the coordinator to rebuild the FAISS index without them.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
