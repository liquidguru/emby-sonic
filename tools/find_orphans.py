"""
Report coordinator rows whose Emby item no longer exists.

DRY RUN BY DEFAULT — this script never writes to the database. It prints a
summary and dumps the full candidate list to CSV for review. Deleting is a
separate, deliberate step.

Why this exists: replacing files in Emby (e.g. the 2026-08 WMA→MP3 conversion)
creates NEW item ids and deletes the old ones. Emby's library ends up clean, but
the coordinator keeps its rows — fully analysed, still in the FAISS index, still
eligible to be picked for a mix. The result is a mix serving two ids for the
same recording, or a 404 at playback.

Rather than checking 27k ids one at a time, this pulls Emby's full Audio id list
in a few paged calls and does a set difference.

Usage (on the coordinator host, in its venv):
    python tools/find_orphans.py                 # dry run, writes orphans.csv
    python tools/find_orphans.py --out /tmp/x.csv

Credentials come from the coordinator's own .env (EMBY_URL, EMBY_API_KEY); they
are never printed.
"""

from __future__ import annotations

import argparse
import csv
import json
import sqlite3
import sys
import urllib.parse
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DB = REPO_ROOT / "data" / "sonic.db"
PAGE_SIZE = 5000


def load_env(env_path: Path) -> tuple[str, str]:
    """Read EMBY_URL / EMBY_API_KEY from .env without echoing the key."""
    if not env_path.is_file():
        sys.exit(f"No .env at {env_path}")
    values: dict[str, str] = {}
    for raw in env_path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        values[key.strip()] = val.strip().strip('"').strip("'")
    url = values.get("EMBY_URL", "").rstrip("/")
    key = values.get("EMBY_API_KEY", "")
    if not url or not key:
        sys.exit("EMBY_URL and EMBY_API_KEY must both be set in .env")
    return url, key


def fetch_emby_audio_ids(base_url: str, api_key: str) -> set[str]:
    """Every Audio item id Emby currently knows about, paged."""
    ids: set[str] = set()
    start = 0
    total = None
    while True:
        query = urllib.parse.urlencode(
            {
                "Recursive": "true",
                "IncludeItemTypes": "Audio",
                "EnableTotalRecordCount": "true",
                "Limit": PAGE_SIZE,
                "StartIndex": start,
                "api_key": api_key,
            }
        )
        req = urllib.request.Request(f"{base_url}/Items?{query}")
        with urllib.request.urlopen(req, timeout=120) as resp:
            payload = json.load(resp)
        items = payload.get("Items", [])
        if total is None:
            total = payload.get("TotalRecordCount", len(items))
            print(f"Emby reports {total} audio items")
        ids.update(str(item["Id"]) for item in items if item.get("Id") is not None)
        start += len(items)
        print(f"  fetched {len(ids)}/{total}", end="\r", flush=True)
        if not items or start >= (total or 0):
            break
    print()
    return ids


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--env", type=Path, default=REPO_ROOT / ".env")
    parser.add_argument("--out", type=Path, default=REPO_ROOT / "orphans.csv")
    args = parser.parse_args()

    base_url, api_key = load_env(args.env)
    emby_ids = fetch_emby_audio_ids(base_url, api_key)

    # A failed/empty fetch would mark the entire library as orphaned. Refuse.
    if len(emby_ids) < 100:
        sys.exit(f"Only {len(emby_ids)} ids came back from Emby — refusing to "
                 "report orphans against a suspect list.")

    if not args.db.is_file():
        sys.exit(f"No database at {args.db}")
    conn = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
    rows = conn.execute(
        "select t.id, t.artist, t.title, t.file_path, "
        "       (e.track_id is not null) as has_embedding "
        "from tracks t left join embeddings e on e.track_id = t.id"
    ).fetchall()

    orphans = [r for r in rows if str(r[0]) not in emby_ids]
    with_emb = sum(1 for r in orphans if r[4])

    exts: dict[str, int] = {}
    for _, _, _, path, _ in orphans:
        ext = (Path(path).suffix.lower() if path else "(no path)") or "(none)"
        exts[ext] = exts.get(ext, 0) + 1

    print()
    print(f"coordinator rows : {len(rows)}")
    print(f"live in Emby     : {len(rows) - len(orphans)}")
    print(f"ORPHANED         : {len(orphans)}  ({with_emb} carry embeddings)")
    print()
    print("by file extension:")
    for ext, count in sorted(exts.items(), key=lambda kv: -kv[1]):
        print(f"  {ext:<12} {count}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(["id", "artist", "title", "file_path", "has_embedding"])
        writer.writerows(orphans)
    print(f"\nFull list written to {args.out}")
    print("DRY RUN — nothing was deleted.")


if __name__ == "__main__":
    main()
