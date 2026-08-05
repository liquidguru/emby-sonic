from pathlib import Path
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    # Emby's address. This is NOT private to the coordinator: the webapp hands it
    # straight to the browser for direct browser->Emby browsing and streaming, so
    # it must be reachable from OTHER machines on your network too — not just from
    # the coordinator host.
    #
    # In particular, do NOT use localhost/127.0.0.1 even when Emby runs on the same
    # machine as the coordinator: the coordinator's own calls would work, but every
    # browser would try to reach Emby on ITS OWN loopback and find nothing. The
    # symptom is confusing — login succeeds (that hop is server-side) while the
    # library comes up empty. Use the LAN address, e.g. http://192.168.1.10:8096.
    emby_url: str = "http://localhost:8096"
    # Optional: Emby's publicly-reachable address (reverse-proxied FQDN, etc.).
    # The webapp hands a server URL straight to the browser for direct
    # browser->Emby streaming, so a LAN-only address fails for anyone loading
    # the page over WAN. When set, the webapp picks between emby_url and this
    # one based on how the browser itself reached the coordinator (see
    # api/routes/webapp.py's web_login). Leave blank if Emby has no separate
    # external address — everything then behaves as it always has.
    emby_url_external: str = ""
    emby_api_key: str = ""
    worker_secret: str = ""
    # Successful Emby user-token validations are cached briefly by SHA-256
    # digest. This avoids an Emby /System/Info round trip on every coordinator
    # request while bounding both revocation delay and process memory.
    auth_cache_ttl_seconds: int = Field(default=30, ge=0, le=3600)
    auth_cache_max_entries: int = Field(default=1024, ge=0, le=10_000)

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

    # Tracks excluded from sonic mixes. Spoken-word content (audiobooks, radio
    # dramas) is typed as "Audio" by Emby and otherwise leaks in. Two signals:
    # a path substring for the audiobook library (mixed .mp3/.m4b), and the
    # .m4b container extension, which catches audiobooks misfiled under music\
    # (e.g. the BBC radio dramas in \music\BBC\). Both case-insensitive.
    mix_exclude_path_markers: list[str] = ["\\Videos\\Audio\\"]
    mix_exclude_extensions: list[str] = [".m4b"]

    # Crossfade edge trimming (issue #38): how far BELOW a track's own loud
    # passages (95th-percentile frame RMS) audio counts as an inaudible edge.
    # Near 0 trims aggressively into the music; very negative only trims true
    # silence and leaves a long mastered fade-out intact. ~-30 dB marks where a
    # fade has become negligible without amputating endings people want to hear.
    # This is the taste knob — tune by ear, not by theory.
    edge_threshold_db: float = Field(default=-30.0, ge=-80.0, le=-6.0)

    # How strongly tempo + energy weigh against the (unit-normalized) timbre
    # embedding when clustering mixes. The embedding barely encodes tempo, so 0
    # gives timbre-only mixes that never vary in tempo; ~1 balances the three so
    # mixes span slow→fast while staying timbrally coherent. See mix_features().
    mix_feature_weight: float = Field(default=1.0, ge=0.0, le=8.0)

    # Refresh (per-mix regenerate) variety. Rather than always returning the
    # strict top-N closest to the centroid (which makes every refresh identical),
    # Refresh samples N tracks from a pool of the closest matches, weighted
    # toward the closest — so repeated refreshes give a fresh but on-theme mix.
    # Pool size = max(N * multiplier, min). Temperature is expressed as a
    # multiple of the pool's score spread (std), so it's robust to the raw
    # similarity scale: ~1.0 changes about half the list each refresh; lower
    # hugs the centroid (less variety), higher roams further (more variety).
    refresh_pool_multiplier: int = 5
    refresh_pool_min: int = 250
    refresh_temperature: float = 1.5

    @property
    def db_url(self) -> str:
        # as_posix() forces forward slashes — backslashes from a Windows Path
        # break the SQLAlchemy URL on the driver side.
        return f"sqlite+aiosqlite:///{(self.data_dir / 'sonic.db').as_posix()}"

    @property
    def effective_worker_secret(self) -> str:
        return self.worker_secret or self.emby_api_key

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
