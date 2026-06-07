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

The CNN14 checkpoint (~327 MB) is auto-provisioned cross-platform: if it's not
already at settings.panns_checkpoint_path we download it with stdlib urllib
before constructing AudioTagging. (panns_inference's own auto-download shells out
to `wget`, which is absent on Windows and most NAS — hence we handle it here and
pass an explicit checkpoint_path so panns never reaches for wget.)
"""

from __future__ import annotations
import pickle
from pathlib import Path

import numpy as np

from config import settings

RAW_DIM = 2048  # CNN14 embedding dimensionality (before PCA)
_MIN_CHECKPOINT_BYTES = 3e8  # panns considers <300 MB a failed/partial download


def _download(url: str, dest: Path) -> None:
    """Stream a URL to dest atomically (via a .tmp file), printing progress."""
    import urllib.request

    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".tmp")
    req = urllib.request.Request(url, headers={"User-Agent": "emby-sonic/1.0"})
    with urllib.request.urlopen(req) as resp, open(tmp, "wb") as f:
        total = int(resp.headers.get("Content-Length", 0))
        done = 0
        next_pct = 0
        while True:
            buf = resp.read(262144)  # 256 KB
            if not buf:
                break
            f.write(buf)
            done += len(buf)
            if total:
                pct = int(done * 100 / total)
                if pct >= next_pct:
                    print(
                        f"  CNN14 checkpoint: {pct}% "
                        f"({done // 1_000_000}/{total // 1_000_000} MB)",
                        flush=True,
                    )
                    next_pct = pct + 5
    tmp.replace(dest)


def ensure_checkpoint() -> Path:
    """Return the CNN14 checkpoint path, downloading it if absent/partial."""
    path = settings.panns_checkpoint_path
    if path.exists() and path.stat().st_size >= _MIN_CHECKPOINT_BYTES:
        return path
    print(
        f"CNN14 checkpoint not found at {path}; downloading (~327 MB)...",
        flush=True,
    )
    _download(settings.panns_checkpoint_url, path)
    size = path.stat().st_size if path.exists() else 0
    if size < _MIN_CHECKPOINT_BYTES:
        raise RuntimeError(
            f"CNN14 checkpoint download looks incomplete ({size} bytes) at {path}. "
            f"Check {settings.panns_checkpoint_url} or place the file manually."
        )
    print(f"CNN14 checkpoint ready: {path}", flush=True)
    return path

# NOTE: windowing now happens upstream in audio.load_windows() — the embedder
# receives a list of pre-decoded windows and averages their embeddings. Window
# count / length / sample rate live in config (settings.num_windows etc.).


class PANNsEmbedder:
    def __init__(self) -> None:
        self._model = None
        self._device = None
        self._pca = None
        self._pca_fitted = False

        if settings.pca_path.exists():
            self._load_pca(settings.pca_path)

    def _ensure_loaded(self) -> None:
        if self._model is not None:
            return
        import torch
        from panns_inference import AudioTagging

        # Auto-detect GPU: a worker on a CUDA box (e.g. liquidHulk's RTX 4070)
        # gets a 10-50x speedup for free; the N100 coordinator stays on CPU.
        self._device = "cuda" if torch.cuda.is_available() else "cpu"
        # Provision the checkpoint ourselves (cross-platform) and pass it
        # explicitly so panns_inference never falls back to its wget download.
        ckpt = ensure_checkpoint()
        self._model = AudioTagging(checkpoint_path=str(ckpt), device=self._device)

    def embed_raw(self, windows: list[np.ndarray]) -> np.ndarray:
        """
        Return the native 2048-dim CNN14 embedding for a track, given the list of
        pre-decoded 32kHz mono windows (from audio.load_windows). Each window is
        embedded independently and the results are averaged.
        """
        self._ensure_loaded()

        per_window = []
        for w in windows:
            audio = np.ascontiguousarray(w[None, :], dtype=np.float32)  # (1, samples)
            _clipwise, embedding = self._model.inference(audio)
            per_window.append(np.asarray(embedding)[0])  # (2048,)

        return np.mean(per_window, axis=0).astype(np.float32)

    def reduce(self, raw: np.ndarray) -> np.ndarray:
        """Reduce a raw 2048-dim vector to 128-dim (PCA if fitted, else truncate)."""
        if self._pca_fitted:
            return self._pca.transform(raw.reshape(1, -1))[0].astype(np.float32)
        # Naive fallback until PCA is fitted: first 128 dims
        return raw[: settings.embedding_dim].astype(np.float32)

    def embed(self, windows: list[np.ndarray]) -> np.ndarray:
        """Return a 128-dim embedding for a track's pre-decoded windows."""
        return self.reduce(self.embed_raw(windows))

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
