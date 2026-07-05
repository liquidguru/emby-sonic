import unittest
from unittest.mock import patch

import numpy as np

from analysis.audio import load_windows
from config import settings

_SR = settings.sample_rate
_WIN = settings.window_seconds
_REAL_CONTENT_SECONDS = 60  # the file's *actual* audio is only this long


def _tone(seconds: int) -> np.ndarray:
    return np.full(seconds * _SR, 0.2, dtype=np.float32)  # non-silent, non-zero


class LoadWindowsBadDurationTests(unittest.TestCase):
    """
    A beta tester's forum report (2026-07-06, ginja) included a legitimate,
    playable MP3 that still failed to embed — librosa.get_duration() reported
    74 minutes for a file whose real audio was only ~15 minutes (a corrupt/
    wrong VBR header, seen in the wild). Window offsets computed from that
    bogus duration landed past the real end of the file (decode to nothing),
    and the one offset that did land within real content happened to hit a
    quiet passage — leaving embed_raw() with mostly-empty or all-silent input.
    """

    def _fake_load(self, file_path, sr=None, mono=True, offset=None, duration=None):
        offset = offset or 0.0
        dur = duration if duration is not None else (_REAL_CONTENT_SECONDS - offset)
        if offset >= _REAL_CONTENT_SECONDS:
            return np.array([], dtype=np.float32), sr
        clipped = min(dur, _REAL_CONTENT_SECONDS - offset)
        return _tone(int(clipped)), sr

    def test_falls_back_to_sequential_windows_when_duration_is_wildly_wrong(self) -> None:
        bogus_duration = 4453.8  # what get_duration() actually reported for ginja's file

        with patch("librosa.get_duration", return_value=bogus_duration), \
             patch("librosa.load", side_effect=self._fake_load):
            windows = load_windows("/music/bad-duration.mp3")

        self.assertGreaterEqual(len(windows), 2, "should recover real content instead of ~1 window")
        for w in windows:
            self.assertGreater(w.size, 0)
            self.assertGreater(np.sqrt(np.mean(w.astype(np.float64) ** 2)), 0, "window should not be silent")

    def test_accurate_duration_is_unaffected(self) -> None:
        # A normal file with a correct, larger duration should use the usual
        # spread-across-the-track offsets, not trigger the fallback at all.
        real_duration = 240.0  # 4 minutes, correctly reported

        def fake_load(file_path, sr=None, mono=True, offset=None, duration=None):
            offset = offset or 0.0
            dur = duration if duration is not None else real_duration
            return _tone(int(min(dur, real_duration - offset))), sr

        with patch("librosa.get_duration", return_value=real_duration), \
             patch("librosa.load", side_effect=fake_load):
            windows = load_windows("/music/normal.mp3")

        self.assertEqual(len(windows), settings.num_windows)


if __name__ == "__main__":
    unittest.main()
