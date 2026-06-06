"""
Single-track benchmark — run on coordinator-host before scanning the full library.

Usage:
    python benchmark.py <path-to-audio-file>

With the pipeline-windowing optimisation, decode + features + embedding all run
on only the sampled windows, so per-track cost is bounded regardless of length.
"""

import sys
import time
from pathlib import Path


def main():
    if len(sys.argv) < 2:
        print("Usage: python benchmark.py <audio_file>")
        sys.exit(1)

    path = sys.argv[1]
    if not Path(path).exists():
        print(f"File not found: {path}")
        sys.exit(1)

    import numpy as np
    from config import settings

    print(f"Benchmarking: {path}")
    print(f"Windowing: {settings.num_windows} x {settings.window_seconds}s "
          f"@ {settings.sample_rate}Hz\n")

    t0 = time.perf_counter()
    from analysis.audio import load_windows
    windows = load_windows(path)
    t_load = time.perf_counter() - t0
    win_secs = sum(len(w) for w in windows) / settings.sample_rate
    print(f"  load_windows        {t_load:.2f}s   ({len(windows)} windows, {win_secs:.0f}s decoded)")

    window_audio = np.concatenate(windows)

    t0 = time.perf_counter()
    from analysis.audio import extract_features, _HAS_ESSENTIA
    feats = extract_features(path, window_audio, settings.sample_rate)
    t_feats = time.perf_counter() - t0
    backend = "essentia + librosa" if _HAS_ESSENTIA else "librosa only (no Essentia)"
    print(f"  scalar features     {t_feats:.2f}s   [{backend}]")
    print(f"    tempo={feats['tempo']:.1f} bpm  energy={feats['energy']:.4f}  "
          f"valence={feats['valence']:.3f}  arousal={feats['arousal']:.3f}")

    print("\n  Loading embedding model (CNN14; first call loads checkpoint)...")
    t0 = time.perf_counter()
    from analysis.embeddings import embedder
    raw_vec = embedder.embed_raw(windows)
    t_embed = time.perf_counter() - t0
    print(f"  embed_raw (CNN14)   {t_embed:.2f}s   shape={raw_vec.shape}")

    total = t_load + t_feats + t_embed
    print(f"\n  Total (cold): {total:.2f}s/track  — bounded, independent of length")
    # Warm estimate: the model loads once per scan, so subtract a ~6s load estimate.
    warm = t_load + t_feats + max(t_embed - 6.0, t_embed * 0.4)
    print(f"  Warm est: ~{warm:.1f}s/track  →  ~25k tracks ≈ ~{warm * 25000 / 3600:.0f} hours")


if __name__ == "__main__":
    main()
