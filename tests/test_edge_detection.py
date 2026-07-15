"""Crossfade edge trimming (#38): detector behaviour + endpoint plumbing."""

import unittest
from unittest.mock import patch

import numpy as np
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from analysis.audio import EDGE_SR, detect_edges
from api.routes.tracks import tracks_loudness
from api.schemas import LoudnessRequest
from db.database import Base
from db.models import Embedding, Track


def _tone(seconds: float, amplitude: float = 0.5) -> np.ndarray:
    t = np.linspace(0.0, seconds, int(EDGE_SR * seconds), endpoint=False)
    return (amplitude * np.sin(2 * np.pi * 440.0 * t)).astype(np.float32)


def _silence(seconds: float) -> np.ndarray:
    return np.zeros(int(EDGE_SR * seconds), dtype=np.float32)


class DetectEdgesTests(unittest.TestCase):
    """detect_edges decodes the file itself, so patch librosa.load to feed it
    synthetic audio with known edges."""

    def _detect(self, waveform: np.ndarray, **kwargs):
        with patch("librosa.load", return_value=(waveform, EDGE_SR)):
            return detect_edges("ignored.flac", **kwargs)

    def test_finds_music_between_leading_and_trailing_silence(self) -> None:
        audio = np.concatenate([_silence(2.0), _tone(10.0), _silence(3.0)])
        start_ms, end_ms = self._detect(audio)
        self.assertAlmostEqual(start_ms, 2000, delta=100)
        self.assertAlmostEqual(end_ms, 12000, delta=100)

    def test_cold_ending_track_is_left_alone(self) -> None:
        # Music right to the last sample: nothing to trim at either end.
        audio = _tone(10.0)
        start_ms, end_ms = self._detect(audio)
        self.assertAlmostEqual(start_ms, 0, delta=100)
        self.assertAlmostEqual(end_ms, 10000, delta=100)

    def test_threshold_controls_how_far_into_a_fade_out_we_trim(self) -> None:
        # 10s of full-level music, then a 6s linear fade to silence.
        fade = _tone(6.0) * np.linspace(1.0, 0.0, int(EDGE_SR * 6.0)).astype(np.float32)
        audio = np.concatenate([_tone(10.0), fade])
        _, lenient_end = self._detect(audio, threshold_db=-40.0)
        _, aggressive_end = self._detect(audio, threshold_db=-20.0)
        # A stricter (more negative) threshold keeps more of the fade; an
        # aggressive one cuts in earlier. This is the taste knob.
        self.assertGreater(lenient_end, aggressive_end)
        self.assertGreater(lenient_end, 10_000)   # never chops the music itself

    def test_silent_track_yields_no_data(self) -> None:
        self.assertEqual(self._detect(_silence(5.0)), (None, None))

    def test_empty_audio_yields_no_data(self) -> None:
        self.assertEqual(self._detect(np.zeros(0, dtype=np.float32)), (None, None))

    def test_undecodable_file_yields_no_data(self) -> None:
        with patch("librosa.load", side_effect=RuntimeError("corrupt")):
            self.assertEqual(detect_edges("bad.mp3"), (None, None))


class LoudnessEndpointEdgeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.engine = create_async_engine("sqlite+aiosqlite:///:memory:")
        async with self.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)

    async def asyncTearDown(self) -> None:
        await self.engine.dispose()

    async def test_serves_edges_and_loudness_independently(self) -> None:
        async with self.sessions() as db:
            db.add_all([
                Track(id="both"), Track(id="lufs-only"),
                Track(id="edges-only"), Track(id="neither"),
                Embedding(track_id="both", lufs=-9.0, effective_start_ms=500, effective_end_ms=180_000),
                Embedding(track_id="lufs-only", lufs=-12.0),
                Embedding(track_id="edges-only", effective_start_ms=0, effective_end_ms=90_000),
                Embedding(track_id="neither"),
            ])
            await db.commit()

        async with self.sessions() as db:
            result = await tracks_loudness(
                LoudnessRequest(ids=["both", "lufs-only", "edges-only", "neither", "unknown"]),
                db,
                "token",
            )

        # Each map is sparse and independent — a track can have one without the other.
        self.assertEqual(set(result.loudness), {"both", "lufs-only"})
        self.assertEqual(set(result.edges), {"both", "edges-only"})
        self.assertEqual(result.edges["both"].start_ms, 500)
        self.assertEqual(result.edges["both"].end_ms, 180_000)

    async def test_partial_edges_are_not_served(self) -> None:
        # A row with only one edge measured is unusable for trimming — omit it
        # rather than let a client infer the other end.
        async with self.sessions() as db:
            db.add_all([
                Track(id="half"),
                Embedding(track_id="half", effective_start_ms=1000, effective_end_ms=None),
            ])
            await db.commit()

        async with self.sessions() as db:
            result = await tracks_loudness(LoudnessRequest(ids=["half"]), db, "token")
        self.assertEqual(result.edges, {})


if __name__ == "__main__":
    unittest.main()
