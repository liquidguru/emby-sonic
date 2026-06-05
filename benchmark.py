"""
Single-track benchmark — run this on liquidBee BEFORE scanning the full library.

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
    from analysis.audio import extract_librosa_features
    librosa_feats = extract_librosa_features(waveform, sr)
    t_librosa = time.perf_counter() - t0
    print(f"  librosa features    {t_librosa:.2f}s   tempo={librosa_feats['tempo']:.1f} bpm, energy={librosa_feats['energy']:.4f}")

    t0 = time.perf_counter()
    from analysis.audio import extract_essentia_features
    essentia_feats = extract_essentia_features(path)
    t_essentia = time.perf_counter() - t0
    print(f"  essentia features   {t_essentia:.2f}s   {essentia_feats}")

    print("\n  Loading MERT model (first call includes download/cache)...")
    t0 = time.perf_counter()
    from analysis.embeddings import embedder
    raw_vec = embedder.embed_raw(waveform)
    t_embed = time.perf_counter() - t0
    print(f"  MERT embed_raw      {t_embed:.2f}s   shape={raw_vec.shape}")

    total = t_load + t_librosa + t_essentia + t_embed
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
