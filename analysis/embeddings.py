"""
Audio embedding via PANNs CNN14 (PyTorch).

Why CNN14 and not a transformer (MERT): MERT (95M-param transformer) costs ~19s
per 30s window on the N100 CPU, which makes a 25k-track library scan take weeks.
CNN14 is a convolutional AudioSet model that runs an order of magnitude faster on
CPU — the same class of lightweight model Plex uses for Sonic Analysis.

Pipeline:
  audio waveform (32kHz mono)
  → sample NUM_WINDOWS fixed-length windows (bounds memory/time vs full track)
  → CNN14 per window → 2048-dim embedding → average windows → 2048-dim
  → PCA reduction → 128-dim float32 embedding

PCA is fitted on the first full-library batch and persisted to data/pca.pkl.
Until PCA is available, naive truncation to 128-dim is used.

NOTE: the CNN14 checkpoint must already exist at ~/panns_data/Cnn14_mAP=0.431.pth.
panns_inference's auto-download shells out to `wget`, which is absent on Windows,
so the checkpoint is pre-placed there via curl during setup.
"""

from __future__ import annotations
import pickle
from pathlib import Path

import numpy as np

from config import settings

SAMPLE_RATE = 32000  # CNN14's expected input sample rate
RAW_DIM = 2048       # CNN14 embedding dimensionality (before PCA)

# Each track is sampled as a few fixed-length windows, embedded independently and
# averaged — bounds memory/time regardless of track length.
WINDOW_SECONDS = 30
WINDOW_SAMPLES = WINDOW_SECONDS * SAMPLE_RATE
NUM_WINDOWS = 3


class PANNsEmbedder:
    def __init__(self) -> None:
        self._model = None
        self._pca = None
        self._pca_fitted = False

        if settings.pca_path.exists():
            self._load_pca(settings.pca_path)

    def _ensure_loaded(self) -> None:
        if self._model is not None:
            return
        from panns_inference import AudioTagging

        # checkpoint_path=None → panns_inference resolves to
        # ~/panns_data/Cnn14_mAP=0.431.pth, which we pre-place via curl.
        self._model = AudioTagging(checkpoint_path=None, device="cpu")

    def embed_raw(self, waveform: np.ndarray) -> np.ndarray:
        """
        Return the native 2048-dim CNN14 embedding for a 32kHz mono waveform,
        averaged over up to NUM_WINDOWS sampled windows.
        """
        self._ensure_loaded()

        per_window = []
        for w in self._sample_windows(waveform):
            audio = np.ascontiguousarray(w[None, :], dtype=np.float32)  # (1, samples)
            _clipwise, embedding = self._model.inference(audio)
            per_window.append(np.asarray(embedding)[0])  # (2048,)

        return np.mean(per_window, axis=0).astype(np.float32)

    @staticmethod
    def _sample_windows(waveform: np.ndarray) -> list[np.ndarray]:
        """Up to NUM_WINDOWS windows of WINDOW_SAMPLES, spread across the track."""
        if len(waveform) <= WINDOW_SAMPLES:
            return [waveform]
        usable = len(waveform) - WINDOW_SAMPLES
        # Spread starts across the track, avoiding the exact edges
        starts = (np.linspace(0.1, 0.9, NUM_WINDOWS) * usable).astype(int)
        return [waveform[s : s + WINDOW_SAMPLES] for s in starts]

    def embed(self, waveform: np.ndarray) -> np.ndarray:
        """Return a 128-dim embedding, using PCA if fitted or truncation otherwise."""
        raw = self.embed_raw(waveform)
        if self._pca_fitted:
            return self._pca.transform(raw.reshape(1, -1))[0].astype(np.float32)
        # Naive fallback until PCA is fitted: first 128 dims
        return raw[: settings.embedding_dim].astype(np.float32)

    def fit_pca(self, raw_embeddings: np.ndarray) -> None:
        """Fit PCA on an (N, 2048) matrix of raw embeddings and persist to disk."""
        from sklearn.decomposition import PCA

        self._pca = PCA(n_components=settings.embedding_dim, random_state=42)
        self._pca.fit(raw_embeddings)
        self._pca_fitted = True
        self._save_pca(settings.pca_path)

    def _save_pca(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "wb") as f:
            pickle.dump(self._pca, f)

    def _load_pca(self, path: Path) -> None:
        with open(path, "rb") as f:
            self._pca = pickle.load(f)
        self._pca_fitted = True


# Module-level singleton — loaded lazily on first embed() call
embedder = PANNsEmbedder()
