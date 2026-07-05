"""
Audio feature extraction — librosa always, Essentia optionally.

Design for cross-platform portability:
  - librosa is pure-Python + numpy/scipy/soundfile → installs cleanly on
    Windows, Linux, macOS and ARM. It is the ALWAYS-AVAILABLE baseline.
  - Essentia gives higher-quality mood/vocal models but has no reliable
    wheel on Windows or ARM. It is treated as an OPTIONAL upgrade: if it
    imports, we use it for valence/arousal/instrumentalness/vocals; if not,
    we fall back to librosa-derived proxies so the features still populate
    everywhere — just with lower fidelity.

These scalar features are auxiliary metadata for display/filtering. Core
sonic similarity comes from the CNN14 embedding vector, not these scalars,
so the librosa proxies being approximate is acceptable.
"""

from __future__ import annotations
import numpy as np

from config import settings

try:
    import essentia.standard as es
    _HAS_ESSENTIA = True
except ImportError:
    _HAS_ESSENTIA = False

try:
    import pyloudnorm as _pyln
    _HAS_PYLOUDNORM = True
except ImportError:
    _HAS_PYLOUDNORM = False

# Reuse one meter per sample rate — building the K-weighting filters is cheap but
# pointless to repeat for every track.
_loudness_meters: dict[int, "object"] = {}


def load_audio(file_path: str, target_sr: int = 32000) -> tuple[np.ndarray, int]:
    """Load the whole file, mono, resampled to target_sr. (Used as a fallback.)"""
    import librosa
    waveform, sr = librosa.load(file_path, sr=target_sr, mono=True)
    return waveform, sr


def load_windows(file_path: str) -> list[np.ndarray]:
    """
    Decode ONLY the sampled analysis windows, not the whole track.

    Reads the duration from the header, then decodes `settings.num_windows`
    windows of `settings.window_seconds` spread across the track (avoiding the
    exact edges). This bounds decode + downstream feature/embedding cost to a
    fixed amount of audio regardless of track length — the core of the
    pipeline-windowing optimisation. Short tracks return a single whole-file window.
    """
    import librosa

    sr = settings.sample_rate
    win = settings.window_seconds

    try:
        duration = float(librosa.get_duration(path=file_path))
    except Exception:
        duration = 0.0

    if duration <= win:
        y, _ = librosa.load(file_path, sr=sr, mono=True)
        return [y] if len(y) else []

    usable = duration - win
    starts = np.linspace(0.1, 0.9, settings.num_windows) * usable
    windows: list[np.ndarray] = []
    for s in starts:
        # A corrupt frame/header at one offset shouldn't lose windows decoded
        # fine at other offsets — isolate each decode so one bad seek doesn't
        # take the whole track down with it.
        try:
            y, _ = librosa.load(file_path, sr=sr, mono=True, offset=float(s), duration=win)
        except Exception:
            continue
        if len(y):
            windows.append(y)

    # get_duration() reads the file's own header (e.g. a Xing/VBR frame count),
    # which can be badly wrong — seen in practice as a track reporting 74
    # minutes when only ~15 minutes of real audio actually decodes. Offsets
    # computed from a bogus duration land past the real end of the file and
    # decode to nothing, so most of our "spread across the track" windows come
    # back empty even though the file is perfectly playable. Getting fewer
    # than half the requested windows is a strong signal of exactly that, so
    # fall back to sequential windows from the start — real content, and
    # doesn't depend on trusting the reported duration at all.
    if len(windows) < settings.num_windows / 2:
        windows = _sequential_windows_from_start(file_path, sr, win, settings.num_windows)

    if not windows:  # decode failed for every window — fall back to whole file
        try:
            y, _ = librosa.load(file_path, sr=sr, mono=True)
        except Exception:
            return []
        windows = [y] if len(y) else []
    return windows


def _sequential_windows_from_start(file_path: str, sr: int, win: int, num_windows: int) -> list[np.ndarray]:
    """Decode up to num_windows sequential windows from position 0, stopping at
    the real end of the file. Used when duration-based offset sampling
    produced too few usable windows to trust (see load_windows)."""
    import librosa

    windows: list[np.ndarray] = []
    for i in range(num_windows):
        try:
            y, _ = librosa.load(file_path, sr=sr, mono=True, offset=i * win, duration=win)
        except Exception:
            break
        if not len(y):
            break  # ran out of real content
        windows.append(y)
    return windows


def measure_loudness(waveform: np.ndarray, sample_rate: int) -> float | None:
    """
    Integrated loudness in LUFS (EBU R128 / ITU-R BS.1770), or None.

    Measured over the same sampled analysis windows the embedder uses (~90 s of
    representative audio), which is a close, *consistent* estimate of whole-track
    integrated loudness for music — every track is measured identically, which is
    what matters for relative volume levelling.

    Returns None when pyloudnorm isn't installed, the clip is too short for a
    gating block, or the signal is silent (loudness would be -inf). Callers treat
    None as "no normalisation data" and leave the track at unity gain.
    """
    if not _HAS_PYLOUDNORM:
        return None
    if waveform is None or waveform.size == 0:
        return None
    meter = _loudness_meters.get(sample_rate)
    if meter is None:
        meter = _pyln.Meter(sample_rate)  # BS.1770-4 K-weighting + gating
        _loudness_meters[sample_rate] = meter
    try:
        # pyloudnorm wants float64; librosa gives float32 mono (shape (n,)).
        lufs = float(meter.integrated_loudness(waveform.astype(np.float64)))
    except Exception:
        return None
    if not np.isfinite(lufs):  # silent clip → -inf
        return None
    return lufs


def extract_features(file_path: str, waveform: np.ndarray, sample_rate: int) -> dict:
    """
    Single entry point. Returns the full scalar feature dict.
    Librosa provides the baseline; Essentia (if installed) overrides the
    mood/vocal fields with higher-quality model outputs.
    """
    feats = _extract_librosa_features(waveform, sample_rate)
    if _HAS_ESSENTIA:
        feats.update(_extract_essentia_features(file_path))
    return feats


def _extract_librosa_features(waveform: np.ndarray, sample_rate: int) -> dict:
    """
    Tempo + energy (accurate), plus valence/arousal proxies.
    instrumentalness/vocals are left None here — librosa alone cannot detect
    vocals reliably, and a fabricated number would be worse than an honest null.
    Essentia fills these when available.
    """
    import librosa

    tempo, _ = librosa.beat.beat_track(y=waveform, sr=sample_rate)
    # librosa >=0.10 returns tempo as a 1-element ndarray, not a scalar
    tempo = float(np.atleast_1d(tempo)[0])

    rms = librosa.feature.rms(y=waveform)
    energy = float(np.mean(rms))

    # Arousal (calm → energetic): grounded in loudness + tempo, both of which
    # correlate strongly with perceived activation. Normalised to ~0–1.
    tempo_norm = np.clip((tempo - 60.0) / 120.0, 0.0, 1.0)   # 60–180 bpm → 0–1
    energy_norm = np.clip(energy / 0.2, 0.0, 1.0)            # rough RMS ceiling
    arousal = float(0.5 * tempo_norm + 0.5 * energy_norm)

    # Valence (sad → happy): major/minor mode is the most defensible cheap proxy.
    # Estimate mode from chroma correlation with major vs minor key profiles,
    # nudged by spectral brightness (brighter timbre ≈ more positive affect).
    valence = _estimate_valence(waveform, sample_rate)

    return {
        "tempo": tempo,
        "energy": energy,
        "valence": valence,
        "arousal": arousal,
        "instrumentalness": None,
        "vocals_present": None,
    }


# Krumhansl–Schmuckler key profiles (major / minor), used to estimate mode.
_MAJOR_PROFILE = np.array(
    [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88]
)
_MINOR_PROFILE = np.array(
    [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17]
)


def _estimate_valence(waveform: np.ndarray, sample_rate: int) -> float:
    """Major-vs-minor mode + brightness → rough valence in 0–1."""
    import librosa

    chroma = librosa.feature.chroma_cqt(y=waveform, sr=sample_rate)
    chroma_mean = np.mean(chroma, axis=1)

    # Best correlation across all 12 rotations for each profile
    best_major = max(
        np.corrcoef(np.roll(_MAJOR_PROFILE, i), chroma_mean)[0, 1] for i in range(12)
    )
    best_minor = max(
        np.corrcoef(np.roll(_MINOR_PROFILE, i), chroma_mean)[0, 1] for i in range(12)
    )
    # +1 if clearly major, toward 0 if clearly minor
    mode_score = np.clip((best_major - best_minor + 1.0) / 2.0, 0.0, 1.0)

    centroid = librosa.feature.spectral_centroid(y=waveform, sr=sample_rate)
    brightness = np.clip(float(np.mean(centroid)) / (sample_rate / 2), 0.0, 1.0)

    return float(0.7 * mode_score + 0.3 * brightness)


def _extract_essentia_features(file_path: str) -> dict:
    """High-quality mood/vocal features from Essentia's pre-trained models."""
    features, _ = es.MusicExtractor(
        lowlevelStats=["mean"],
        rhythmStats=["mean"],
        tonalStats=["mean"],
    )(file_path)

    valence = features.get("highlevel.mood_happy.all.happy", None)
    arousal = features.get("highlevel.danceability.all.danceable", None)
    instrumentalness = features.get("highlevel.voice_instrumental.all.instrumental", None)
    vocals_raw = features.get("highlevel.voice_instrumental.all.voice", None)
    vocals_present = int(vocals_raw > 0.5) if vocals_raw is not None else None

    out: dict = {}
    if valence is not None:
        out["valence"] = float(valence)
    if arousal is not None:
        out["arousal"] = float(arousal)
    if instrumentalness is not None:
        out["instrumentalness"] = float(instrumentalness)
    if vocals_present is not None:
        out["vocals_present"] = vocals_present
    return out
