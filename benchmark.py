"""
Single-track benchmark — run this on coordinator-host BEFORE scanning the full library.

Usage:
    python benchmark.py <path-to-audio-file>

Reports time for each stage so you know where the N100 bottleneck is.
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

    print(f"Benchmarking: {path}\n")

    t0 = time.perf_counter()
    from analysis.audio import load_audio
    waveform, sr = load_audio(path)
    t_load = time.perf_counter() - t0
    print(f"  load_audio          {t_load:.2f}s   ({len(waveform)/sr:.0f}s of audio at {sr}Hz)")

    t0 = time.perf_counter()
    from analysis.audio import extract_features, _HAS_ESSENTIA
    feats = extract_features(path, waveform, sr)
    t_feats = time.perf_counter() - t0
    backend = "essentia + librosa" if _HAS_ESSENTIA else "librosa only (no Essentia)"
    print(f"  scalar features     {t_feats:.2f}s   [{backend}]")
    print(f"    tempo={feats['tempo']:.1f} bpm  energy={feats['energy']:.4f}  "
          f"valence={feats['valence']:.3f}  arousal={feats['arousal']:.3f}")

    print("\n  Loading embedding model (CNN14; first call loads checkpoint)...")
    t0 = time.perf_counter()
    from analysis.embeddings import embedder
    raw_vec = embedder.embed_raw(waveform)
    t_embed = time.perf_counter() - t0
    print(f"  embed_raw (CNN14)   {t_embed:.2f}s   shape={raw_vec.shape}")

    total = t_load + t_feats + t_embed
    audio_len = len(waveform) / sr
    rtf = total / audio_len  # real-time factor

    print(f"\n  Total: {total:.2f}s for {audio_len:.0f}s of audio (RTF {rtf:.2f}x)")
    if rtf < 0.5:
        print("  N100 looks fine — full library scan should be manageable.")
    elif rtf < 2.0:
        print("  Acceptable. Consider analysing overnight for large libraries.")
    else:
        print("  SLOW — consider a lighter model (music2vec) or 30s audio chunks.")


if __name__ == "__main__":
    main()
