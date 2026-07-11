from datetime import datetime
import unittest
from unittest.mock import patch

from sqlalchemy import event, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from analysis import scanner
from db.database import Base
from db.models import Track


def _emby_item(track_id: str, *, title: str | None = None) -> dict:
    return {
        "Id": track_id,
        "Name": title or f"Track {track_id}",
        "AlbumArtist": "Artist",
        "Album": "Album",
        "Genres": ["Electronic", "Ambient"],
        "RunTimeTicks": 123_450_000,
        "Path": f"/music/{track_id}.flac",
    }


class ScannerBatchingTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.engine = create_async_engine("sqlite+aiosqlite:///:memory:")
        async with self.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)
        scanner.scan_state.update(running=False, total=0, added=0)

    async def asyncTearDown(self) -> None:
        await self.engine.dispose()

    async def _scan(self, items: list[dict], *, full: bool) -> None:
        with (
            patch("analysis.scanner.fetch_audio_items", return_value=items),
            patch("db.database.AsyncSessionLocal", self.sessions),
        ):
            await scanner._sync_library(full=full)

    async def test_incremental_scan_updates_metadata_without_requeueing_done_track(self) -> None:
        claimed_at = datetime(2026, 7, 1, 12, 0, 0)
        async with self.sessions() as db:
            db.add(
                Track(
                    id="existing",
                    title="Old title",
                    analysis_status="done",
                    claimed_at=claimed_at,
                    error="preserved marker",
                )
            )
            await db.commit()

        await self._scan(
            [_emby_item("existing", title="Fresh title"), _emby_item("new")],
            full=False,
        )

        async with self.sessions() as db:
            tracks = {
                track.id: track
                for track in (await db.execute(select(Track).order_by(Track.id))).scalars()
            }
        self.assertEqual(set(tracks), {"existing", "new"})
        self.assertEqual(tracks["existing"].title, "Fresh title")
        self.assertEqual(tracks["existing"].analysis_status, "done")
        self.assertEqual(tracks["existing"].claimed_at, claimed_at)
        self.assertEqual(tracks["existing"].error, "preserved marker")
        self.assertEqual(tracks["new"].analysis_status, "pending")
        self.assertEqual(scanner.scan_state["added"], 1)

    async def test_full_scan_requeues_existing_track_and_clears_lease_error(self) -> None:
        async with self.sessions() as db:
            db.add(
                Track(
                    id="existing",
                    title="Old title",
                    analysis_status="error",
                    claimed_at=datetime(2026, 7, 1, 12, 0, 0),
                    error="decode failed",
                )
            )
            await db.commit()

        await self._scan([_emby_item("existing")], full=True)

        async with self.sessions() as db:
            track = await db.get(Track, "existing")
        self.assertEqual(track.analysis_status, "pending")
        self.assertIsNone(track.claimed_at)
        self.assertIsNone(track.error)
        self.assertEqual(scanner.scan_state["added"], 0)

    async def test_track_lookup_queries_scale_by_batch_not_item(self) -> None:
        items = [_emby_item(f"track-{i}") for i in range(1_201)]
        track_selects: list[str] = []

        def count_track_selects(_conn, _cursor, statement, _parameters, _context, _many):
            normalised = " ".join(statement.upper().split())
            if normalised.startswith("SELECT") and " FROM TRACKS " in normalised:
                track_selects.append(normalised)

        event.listen(self.engine.sync_engine, "before_cursor_execute", count_track_selects)
        try:
            await self._scan(items, full=False)
        finally:
            event.remove(self.engine.sync_engine, "before_cursor_execute", count_track_selects)

        # A 500-item batch needs three lookups. Leave one spare for harmless ORM
        # bookkeeping while still catching the old 1,201-query db.get() loop.
        self.assertLessEqual(len(track_selects), 4, len(track_selects))
        self.assertEqual(scanner.scan_state["added"], len(items))


if __name__ == "__main__":
    unittest.main()
