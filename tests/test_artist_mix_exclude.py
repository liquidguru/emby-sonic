import unittest
from unittest.mock import patch

import numpy as np
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from analysis import similarity
from db.database import Base
from db.models import Track


class _FakeIndex:
    """Stands in for the FAISS index: a fixed vector per track id."""

    def __init__(self, vectors: dict[str, list[float]]) -> None:
        self._vectors = {k: np.array(v, dtype=np.float32) for k, v in vectors.items()}

    def get_vector(self, track_id: str):
        return self._vectors.get(track_id)


class ArtistMixExcludeTests(unittest.IsolatedAsyncioTestCase):
    """
    Guest DJ keeps an Artist Mix Creator queue going by asking for more songs from
    the same artists. build_artist_mix must then return fresh songs rather than the
    same most-representative ones, and must treat another library file of a queued
    song as already queued.
    """

    async def asyncSetUp(self) -> None:
        self.engine = create_async_engine("sqlite+aiosqlite:///:memory:")
        async with self.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        self.session_factory = async_sessionmaker(self.engine, expire_on_commit=False)
        async with self.session_factory() as db:
            db.add_all([
                Track(id="a1", title="Song One", artist="A"),
                Track(id="a1-dup", title="Song One", artist="A"),  # same song, other file
                Track(id="a2", title="Song Two", artist="A"),
                Track(id="a3", title="Song Three", artist="A"),
                Track(id="b1", title="Other One", artist="B"),
                Track(id="b2", title="Other Two", artist="B"),
            ])
            await db.commit()
        self.index = _FakeIndex({
            "a1": [1.0, 0.0], "a1-dup": [1.0, 0.0], "a2": [0.9, 0.1], "a3": [0.5, 0.5],
            "b1": [0.0, 1.0], "b2": [0.1, 0.9],
        })

    async def asyncTearDown(self) -> None:
        await self.engine.dispose()

    async def _mix(self, exclude: list[str] | None = None) -> list[str]:
        with patch.object(similarity, "sonic_index", self.index):
            async with self.session_factory() as db:
                tracks = await similarity.build_artist_mix(
                    ["A", "B"], per_artist=100, db=db, exclude_ids=exclude,
                )
        return [t.id for t in tracks]

    async def test_without_exclusions_every_song_appears_once(self) -> None:
        ids = await self._mix()
        # The duplicate file of "Song One" is collapsed to a single entry.
        self.assertEqual(len(ids), 5)
        self.assertEqual(sum(1 for i in ids if i in ("a1", "a1-dup")), 1)

    async def test_excluded_tracks_and_their_duplicate_files_are_left_out(self) -> None:
        ids = await self._mix(exclude=["a1", "b1"])
        self.assertNotIn("a1", ids)
        self.assertNotIn("a1-dup", ids)  # same song as the excluded a1
        self.assertNotIn("b1", ids)
        self.assertCountEqual(ids, ["a2", "a3", "b2"])

    async def test_everything_excluded_returns_nothing(self) -> None:
        ids = await self._mix(exclude=["a1", "a2", "a3", "b1", "b2"])
        self.assertEqual(ids, [])


if __name__ == "__main__":
    unittest.main()
