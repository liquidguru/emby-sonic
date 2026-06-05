"""
Librosa + Essentia audio feature extraction.
Returns per-track scalar features stored in the embeddings table alongside the MERT vector.
"""

from __future__ import annotations
import numpy as np

try:
    import essentia.standard as es
    _HAS_ESSENTIA = True
except ImportError:
    _HAS_ESSENTIA = False


def extract_librosa_features(waveform: np.ndarray, sample_rate: int) -> dict:
    """Tempo, energy, and basic spectral features via librosa."""
    import librosa

    tempo, _ = librosa.beat.beat_track(y=waveform, sr=sample_rate)
    rms = librosa.feature.rms(y=waveform)
    energy = float(np.mean(rms))

    return {
        "tempo": float(tempo),
        "energy": energy,
    }


def extract_essentia_features(file_path: str) -> dict:
    """
    Valence, arousal, instrumentalness, and vocal detection via Essentia.
    Falls back to None values if Essentia is not installed.
    """
    if not _HAS_ESSENTIA:
        return {
            "valence": None,
            "arousal": None,
            "instrumentalness": None,
            "vocals_present": None,
        }

    # Essentia's MusicExtractor runs a full feature suite on the file path
    features, _ = es.MusicExtractor(
        lowlevelStats=["mean"],
        rhythmStats=["mean"],
        tonalStats=["mean"],
    )(file_path)

    # Valence/arousal from Essentia's pre-trained mood models
    valence = features.get("highlevel.mood_happy.all.happy", None)
    arousal = features.get("highlevel.danceability.all.danceable", None)
    instrumentalness = features.get("highlevel.voice_instrumental.all.instrumental", None)
    vocals_raw = features.get("highlevel.voice_instrumental.all.voice", None)
    vocals_present = int(vocals_raw > 0.5) if vocals_raw is not None else None

    return {
        "valence": float(valence) if valence is not None else None,
        "arousal": float(arousal) if arousal is not None else None,
        "instrumentalness": float(instrumentalness) if instrumentalness is not None else None,
        "vocals_present": vocals_present,
    }


def load_audio(file_path: str, target_sr: int = 24000) -> tuple[np.ndarray, int]:
    """Load audio file, convert to mono, resample to target_sr."""
    import librosa
    waveform, sr = librosa.load(file_path, sr=target_sr, mono=True)
    return waveform, sr
