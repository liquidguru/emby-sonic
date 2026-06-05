"""
MERT-v1-95M audio embedding model.

Pipeline:
  audio waveform (24kHz mono)
  → MERT transformer (12 layers, 768-dim hidden states)
  → mean-pool last hidden state over time → 768-dim vector
  → PCA reduction → 128-dim float32 embedding

PCA is fitted on the first full-library batch and persisted to data/pca.pkl.
Until PCA is available, naive truncation to 128-dim is used (similarity will
be approximate but structurally correct).
"""

from __future__ import annotations
import pickle
from pathlib import Path

import numpy as np

from config import settings

MODEL_ID = "m-a-p/MERT-v1-95M"
SAMPLE_RATE = 24000  # MERT's expected input sample rate

# Full-track inference is ~10GB RAM / very slow on CPU (benchmarked on an N100),
# so each track is sampled as a few fixed-length windows that are embedded
# independently and averaged. This bounds memory/time per track regardless of
# its length.
WINDOW_SECONDS = 30
WINDOW_SAMPLES = WINDOW_SECONDS * SAMPLE_RATE
NUM_WINDOWS = 3


class MERTEmbedder:
    def __init__(self) -> None:
        self._model = None
        self._processor = None
        self._pca = None
        self._pca_fitted = False

        if settings.pca_path.exists():
            self._load_pca(settings.pca_path)

    def _ensure_loaded(self) -> None:
        if self._model is not None:
            return
        from transformers import AutoModel, AutoProcessor

        # Prefer a locally pre-downloaded model dir (models/MERT-v1-95M) for
        # offline / slow-network hosts like the N100; otherwise download by repo
        # id into the cache. The local dir avoids HF's xet CDN entirely.
        local_dir = settings.model_dir / "MERT-v1-95M"
        if (local_dir / "config.json").exists():
            source = str(local_dir)
            kwargs = {"trust_remote_code": True, "local_files_only": True}
        else:
            source = MODEL_ID
            kwargs = {"trust_remote_code": True, "cache_dir": str(settings.model_dir)}

        self._processor = AutoProcessor.from_pretrained(source, **kwargs)
        self._model = AutoModel.from_pretrained(source, **kwargs).eval()

    def embed_raw(self, waveform: np.ndarray) -> np.ndarray:
        """
        Return the native 768-dim MERT embedding for a 24kHz mono waveform.

        The track is sampled as up to NUM_WINDOWS windows of WINDOW_SAMPLES,
        each embedded independently (mean-pooled over time) and then averaged.
        This keeps memory/compute roughly constant regardless of track length.
        """
        import torch

        self._ensure_loaded()

        per_window = []
        for w in self._sample_windows(waveform):
            inputs = self._processor(
                raw_speech=w,
                sampling_rate=SAMPLE_RATE,
                return_tensors="pt",
            )
            with torch.no_grad():
                outputs = self._model(**inputs, output_hidden_states=True)
            # Mean-pool the last transformer layer over the time dimension
            last_hidden = outputs.hidden_states[-1]  # (1, T, 768)
            per_window.append(last_hidden.mean(dim=1).squeeze(0).numpy())

        embedding = np.mean(per_window, axis=0)  # (768,)
        return embedding.astype(np.float32)

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
        # Naive fallback: first 128 principal components by index
        return raw[: settings.embedding_dim].astype(np.float32)

    def fit_pca(self, raw_embeddings: np.ndarray) -> None:
        """Fit PCA on an (N, 768) matrix of raw embeddings and persist to disk."""
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
embedder = MERTEmbedder()
