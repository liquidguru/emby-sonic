import tempfile
import unittest
from unittest.mock import patch

import numpy as np

import worker


def _fake_windows(path):
    return [np.random.randn(1000).astype(np.float32)]


class AnalyseFeatureFallbackTests(unittest.TestCase):
    """
    A beta tester's forum report (2026-07-06) showed several tracks failing
    with errors like "float division by zero" and "negative dimensions are
    not allowed" — from librosa's beat/chroma internals on short or unusual
    audio, raised *before* embed_raw() is ever reached. The #37 fix (short-
    window padding) only guarded the embedding step, not this earlier
    feature-extraction step, so these tracks were still losing their
    embedding entirely over what's supposed to be auxiliary metadata.
    """

    def setUp(self) -> None:
        self.tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".mp3")
        self.tmp.close()

    def test_feature_extraction_failure_still_preserves_the_embedding(self) -> None:
        def raising_extract_features(path, waveform, sr):
            raise ZeroDivisionError("float division by zero")

        with patch.object(worker.emby, "download_track", return_value=self.tmp.name), \
             patch.object(worker, "load_windows", side_effect=_fake_windows), \
             patch.object(worker, "extract_features", side_effect=raising_extract_features), \
             patch.object(worker, "measure_loudness", return_value=None), \
             patch.object(worker.embedder, "embed_raw", side_effect=lambda w: np.random.randn(2048).astype(np.float32)):
            result = worker._analyse({"id": "hinder-track", "path": "/music/hinder.mp3"})

        self.assertEqual(result["track_id"], "hinder-track")
        self.assertTrue(result["raw_vector"], "embedding must survive a feature-extraction failure")
        for field in ("tempo", "energy", "valence", "arousal", "instrumentalness", "vocals_present"):
            self.assertIsNone(result[field])

    def test_negative_dimensions_error_also_falls_back_cleanly(self) -> None:
        def raising_extract_features(path, waveform, sr):
            raise ValueError("negative dimensions are not allowed")

        with patch.object(worker.emby, "download_track", return_value=self.tmp.name), \
             patch.object(worker, "load_windows", side_effect=_fake_windows), \
             patch.object(worker, "extract_features", side_effect=raising_extract_features), \
             patch.object(worker, "measure_loudness", return_value=None), \
             patch.object(worker.embedder, "embed_raw", side_effect=lambda w: np.random.randn(2048).astype(np.float32)):
            result = worker._analyse({"id": "radio-track", "path": "/music/radio.mp3"})

        self.assertTrue(result["raw_vector"])
        self.assertIsNone(result["tempo"])

    def test_successful_extraction_is_unaffected(self) -> None:
        def good_extract_features(path, waveform, sr):
            return {
                "tempo": 120.0, "energy": 0.5, "valence": 0.6,
                "arousal": 0.4, "instrumentalness": None, "vocals_present": None,
            }

        with patch.object(worker.emby, "download_track", return_value=self.tmp.name), \
             patch.object(worker, "load_windows", side_effect=_fake_windows), \
             patch.object(worker, "extract_features", side_effect=good_extract_features), \
             patch.object(worker, "measure_loudness", return_value=-14.0), \
             patch.object(worker.embedder, "embed_raw", side_effect=lambda w: np.random.randn(2048).astype(np.float32)):
            result = worker._analyse({"id": "good-track", "path": "/music/good.mp3"})

        self.assertEqual(result["tempo"], 120.0)
        self.assertEqual(result["energy"], 0.5)
        self.assertEqual(result["lufs"], -14.0)


if __name__ == "__main__":
    unittest.main()
