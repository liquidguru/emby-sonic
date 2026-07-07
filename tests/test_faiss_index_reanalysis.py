import unittest

import faiss
import numpy as np

from analysis.faiss_index import SonicIndex
from config import settings


def _unit(value: float) -> np.ndarray:
    vec = np.zeros(settings.embedding_dim, dtype=np.float32)
    vec[0] = value
    return vec


class SonicIndexReanalysisTests(unittest.TestCase):
    def test_readding_track_replaces_live_vector(self) -> None:
        index = SonicIndex()
        index._index = faiss.IndexFlatIP(settings.embedding_dim)
        index._track_ids = []

        old_vec = _unit(1.0)
        new_vec = np.zeros(settings.embedding_dim, dtype=np.float32)
        new_vec[1] = 1.0
        other_vec = np.zeros(settings.embedding_dim, dtype=np.float32)
        other_vec[2] = 1.0

        index.add("track-1", old_vec)
        index.add("track-2", other_vec)
        index.add("track-1", new_vec)

        results = index.search(new_vec, k=10)
        ids = [track_id for track_id, _ in results]

        self.assertEqual(ids.count("track-1"), 1, ids)
        self.assertTrue(np.allclose(index.get_vector("track-1"), new_vec))


if __name__ == "__main__":
    unittest.main()
