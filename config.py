from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    emby_url: str = "http://192.168.1.9:8096"
    emby_api_key: str = ""

    host: str = "0.0.0.0"
    port: int = 8765

    data_dir: Path = Path("data")
    model_dir: Path = Path("models")

    embedding_model: str = "PANNs-CNN14"  # informational; embedder loads CNN14 directly

    # CNN14 checkpoint (~327 MB). Default location matches panns_inference's own
    # convention so a pre-placed file is reused. If missing, the embedder
    # downloads it from this URL with stdlib urllib (panns's own auto-download
    # shells out to `wget`, which is absent on Windows/most NAS). Override either
    # with env PANNS_CHECKPOINT_PATH / PANNS_CHECKPOINT_URL.
    panns_checkpoint_path: Path = Path.home() / "panns_data" / "Cnn14_mAP=0.431.pth"
    panns_checkpoint_url: str = (
        "https://zenodo.org/record/3987831/files/Cnn14_mAP%3D0.431.pth?download=1"
    )
    # Target dimensionality after PCA reduction from CNN14's native 2048-dim output.
    # PCA is fitted on the first full-library batch then saved to data/pca.pkl.
    embedding_dim: int = 128

    # Analysis windowing. Each track is sampled as `num_windows` windows of
    # `window_seconds`, decoded + feature-analysed + embedded — only these
    # windows, never the full track — so per-track cost is bounded regardless of
    # length. num_windows is the main speed/quality knob (1 = fastest scan).
    sample_rate: int = 32000  # CNN14's expected input rate
    window_seconds: int = 30
    num_windows: int = 3

    analysis_workers: int = 2

    # Tracks whose Emby file path contains any of these (case-insensitive)
    # substrings are excluded from sonic mixes. Audiobooks live under
    # \Videos\Audio\ but Emby types them as "Audio", so they otherwise leak in.
    mix_exclude_path_markers: list[str] = ["\\Videos\\Audio\\"]

    @property
    def db_url(self) -> str:
        # as_posix() forces forward slashes — backslashes from a Windows Path
        # break the SQLAlchemy URL on the driver side.
        return f"sqlite+aiosqlite:///{(self.data_dir / 'sonic.db').as_posix()}"

    @property
    def faiss_index_path(self) -> Path:
        return self.data_dir / "faiss.index"

    @property
    def faiss_ids_path(self) -> Path:
        return self.data_dir / "faiss.ids"

    @property
    def pca_path(self) -> Path:
        return self.data_dir / "pca.pkl"


settings = Settings()
