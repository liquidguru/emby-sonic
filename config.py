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
