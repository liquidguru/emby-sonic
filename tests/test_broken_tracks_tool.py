import csv
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from tools.broken_tracks import export_errors, requeue_errors, purge_errors


class BrokenTracksToolTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "sonic.db"
        with closing(sqlite3.connect(self.db_path)) as conn:
            conn.execute(
                """
                CREATE TABLE tracks (
                    id TEXT PRIMARY KEY,
                    title TEXT,
                    artist TEXT,
                    album TEXT,
                    file_path TEXT,
                    analysis_status TEXT,
                    claimed_at TEXT,
                    error TEXT
                )
                """
            )
            conn.executemany(
                """
                INSERT INTO tracks
                    (id, title, artist, album, file_path, analysis_status, claimed_at, error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                [
                    ("ok", "Good", "Artist", "Album", "/music/good.flac", "done", None, None),
                    ("bad-1", "Broken", "Artist", "Album", "/music/missing.flac", "error", "2026", "HTTP 500"),
                    ("bad-2", "Cracked", "Other", "Other Album", "/music/cracked.flac", "error", None, "decode"),
                ],
            )
            conn.commit()

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_export_errors_writes_expected_csv(self) -> None:
        output = Path(self.tmp.name) / "broken.csv"

        count = export_errors(self.db_path, output)

        self.assertEqual(count, 2)
        with output.open(newline="", encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
        self.assertEqual([row["id"] for row in rows], ["bad-1", "bad-2"])
        self.assertEqual(rows[0]["error"], "HTTP 500")

    def test_requeue_selected_errors(self) -> None:
        count = requeue_errors(self.db_path, ["bad-2", "ok"])

        self.assertEqual(count, 1)
        with closing(sqlite3.connect(self.db_path)) as conn:
            rows = {
                row[0]: row[1:]
                for row in conn.execute("SELECT id, analysis_status, claimed_at, error FROM tracks")
            }
        self.assertEqual(rows["bad-1"], ("error", "2026", "HTTP 500"))
        self.assertEqual(rows["bad-2"], ("pending", None, None))
        self.assertEqual(rows["ok"], ("done", None, None))

    def test_requeue_all_errors(self) -> None:
        count = requeue_errors(self.db_path)

        self.assertEqual(count, 2)
        with closing(sqlite3.connect(self.db_path)) as conn:
            statuses = dict(conn.execute("SELECT id, analysis_status FROM tracks"))
        self.assertEqual(statuses["bad-1"], "pending")
        self.assertEqual(statuses["bad-2"], "pending")
        self.assertEqual(statuses["ok"], "done")

    def test_purge_selected_errors_deletes_only_those_rows(self) -> None:
        # A tester (ginja, 2026-07-06) asked for a way to clear stale error
        # records (corrupt files, orphaned Emby entries) instead of retrying
        # them forever.
        count = purge_errors(self.db_path, ["bad-2"])

        self.assertEqual(count, 1)
        with closing(sqlite3.connect(self.db_path)) as conn:
            remaining_ids = {row[0] for row in conn.execute("SELECT id FROM tracks")}
        self.assertEqual(remaining_ids, {"ok", "bad-1"})

    def test_purge_never_touches_non_error_tracks(self) -> None:
        count = purge_errors(self.db_path, ["ok"])  # status='done', not 'error'

        self.assertEqual(count, 0)
        with closing(sqlite3.connect(self.db_path)) as conn:
            remaining_ids = {row[0] for row in conn.execute("SELECT id FROM tracks")}
        self.assertEqual(remaining_ids, {"ok", "bad-1", "bad-2"})

    def test_purge_all_errors(self) -> None:
        count = purge_errors(self.db_path)

        self.assertEqual(count, 2)
        with closing(sqlite3.connect(self.db_path)) as conn:
            remaining_ids = {row[0] for row in conn.execute("SELECT id FROM tracks")}
        self.assertEqual(remaining_ids, {"ok"})


if __name__ == "__main__":
    unittest.main()
