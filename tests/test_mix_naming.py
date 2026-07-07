import unittest

from analysis.mixes import _dedupe, _dominant_artist, _dominant_genre, _cluster_and_name


class DominantArtistTests(unittest.TestCase):
    def test_clear_majority_wins(self) -> None:
        artist, frac = _dominant_artist(["A", "A", "A", "B"])
        self.assertEqual(artist, "A")
        self.assertAlmostEqual(frac, 0.75)

    def test_placeholder_artists_never_win(self) -> None:
        artist, frac = _dominant_artist(["Various Artists", "Various Artists", "Real Artist"])
        self.assertEqual(artist, "Real Artist")

    def test_all_placeholder_returns_none(self) -> None:
        artist, frac = _dominant_artist(["Various Artists", "Unknown", None])
        self.assertIsNone(artist)
        self.assertEqual(frac, 0.0)

    def test_empty_input_returns_none(self) -> None:
        self.assertEqual(_dominant_artist([]), (None, 0.0))


class DominantGenreTests(unittest.TestCase):
    def test_clear_majority_wins(self) -> None:
        genre, frac = _dominant_genre(["Rock", "Rock", "Jazz"])
        self.assertEqual(genre, "Rock")
        self.assertAlmostEqual(frac, 2 / 3)

    def test_none_values_ignored(self) -> None:
        genre, frac = _dominant_genre([None, None, "Jazz", "Jazz"])
        self.assertEqual(genre, "Jazz")
        self.assertEqual(frac, 1.0)

    def test_empty_input_returns_none(self) -> None:
        self.assertEqual(_dominant_genre([]), (None, 0.0))

    def test_placeholder_genres_never_win(self) -> None:
        genre, frac = _dominant_genre(["Unknown", "Unknown", "Real Genre"])
        self.assertEqual(genre, "Real Genre")

    def test_all_placeholder_returns_none(self) -> None:
        genre, frac = _dominant_genre(["Unknown", "Other", None])
        self.assertIsNone(genre)
        self.assertEqual(frac, 0.0)


class DedupeTests(unittest.TestCase):
    def test_first_use_unchanged(self) -> None:
        used: set[str] = set()
        self.assertEqual(_dedupe("Upbeat", used), "Upbeat")

    def test_collision_gets_numbered_suffix(self) -> None:
        used = {"Upbeat"}
        self.assertEqual(_dedupe("Upbeat", used), "Upbeat (2)")

    def test_repeated_collisions_increment(self) -> None:
        used = {"Upbeat", "Upbeat (2)"}
        self.assertEqual(_dedupe("Upbeat", used), "Upbeat (3)")


class ClusterAndNameTests(unittest.TestCase):
    """
    Fixes #34 — mix names used to collapse to one of only 9 tempo/energy mood
    strings whenever a cluster had no dominant artist, colliding constantly on
    a 30-cluster default and getting a numeric suffix from _dedupe. Naming now
    falls back to a dominant genre before giving up to a bare mood name.
    """

    def _one_cluster(self, tempos, energies, artists, genres):
        # A single, trivial cluster: all points identical so k-means puts
        # everything in one bucket regardless of the input vectors.
        import numpy as np
        n = len(tempos)
        vecs = np.ones((n, 4), dtype=np.float32)
        track_ids = [f"t{i}" for i in range(n)]
        clusters = _cluster_and_name(track_ids, vecs, tempos, energies, artists, genres, n_clusters=1, tracks_per_mix=n)
        self.assertEqual(len(clusters), 1)
        return clusters[0]["name"]

    def test_dominant_artist_wins_over_genre(self) -> None:
        name = self._one_cluster(
            tempos=[120.0] * 4,
            energies=[0.5] * 4,
            artists=["Radiohead", "Radiohead", "Radiohead", "Other"],
            genres=["Rock", "Jazz", "Pop", "Pop"],
        )
        self.assertIn("Radiohead", name)

    def test_falls_back_to_genre_when_no_dominant_artist(self) -> None:
        name = self._one_cluster(
            tempos=[120.0] * 4,
            energies=[0.5] * 4,
            artists=["A", "B", "C", "D"],  # no repeats -> no dominant artist
            genres=["Electronic", "Electronic", "Electronic", "Jazz"],
        )
        self.assertIn("Electronic", name)

    def test_bare_mood_when_neither_dominant(self) -> None:
        name = self._one_cluster(
            tempos=[120.0] * 4,
            energies=[0.5] * 4,
            artists=["A", "B", "C", "D"],
            genres=["Rock", "Jazz", "Pop", "Classical"],
        )
        # No " · " suffix at all -- just the bare mood name.
        self.assertNotIn("·", name)


if __name__ == "__main__":
    unittest.main()
