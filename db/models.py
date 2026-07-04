from datetime import datetime
from sqlalchemy import Text, Integer, Float, DateTime, ForeignKey, LargeBinary
from sqlalchemy.orm import Mapped, mapped_column, relationship
from db.database import Base


class Track(Base):
    __tablename__ = "tracks"

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    title: Mapped[str | None] = mapped_column(Text)
    artist: Mapped[str | None] = mapped_column(Text)
    album: Mapped[str | None] = mapped_column(Text)
    # Primary genre only (Emby's first Genres entry) — enough to find a mix
    # cluster's dominant genre for naming; not a full tag list.
    genre: Mapped[str | None] = mapped_column(Text)
    duration_ms: Mapped[int | None] = mapped_column(Integer)
    file_path: Mapped[str | None] = mapped_column(Text)
    analysed_at: Mapped[datetime | None] = mapped_column(DateTime)
    analysis_version: Mapped[int | None] = mapped_column(Integer)

    # Crash-safe / distributed-worker state. The coordinator hands tracks to
    # workers (local or remote) on a lease: a track is claimable when
    # status='pending' and claimed_at is NULL or older than the lease timeout.
    # On crash/restart, stale claims are reclaimed and 'done' tracks are skipped.
    analysis_status: Mapped[str] = mapped_column(Text, default="pending", index=True)
    claimed_at: Mapped[datetime | None] = mapped_column(DateTime)
    error: Mapped[str | None] = mapped_column(Text)

    embedding: Mapped["Embedding | None"] = relationship(back_populates="track", uselist=False)


class Embedding(Base):
    __tablename__ = "embeddings"

    track_id: Mapped[str] = mapped_column(Text, ForeignKey("tracks.id"), primary_key=True)
    # 128-dim float32 stored as raw bytes (numpy ndarray.tobytes() / frombuffer()).
    # This is the FAISS-indexed vector — FAISS is rebuilt from these on startup.
    vector: Mapped[bytes | None] = mapped_column(LargeBinary)
    # Native 2048-dim CNN14 vector, kept so PCA can be (re)fitted later and the
    # 128-dim `vector` + FAISS index regenerated without re-analysing audio.
    raw_vector: Mapped[bytes | None] = mapped_column(LargeBinary)
    tempo: Mapped[float | None] = mapped_column(Float)
    energy: Mapped[float | None] = mapped_column(Float)
    valence: Mapped[float | None] = mapped_column(Float)
    arousal: Mapped[float | None] = mapped_column(Float)
    instrumentalness: Mapped[float | None] = mapped_column(Float)
    vocals_present: Mapped[int | None] = mapped_column(Integer)
    # Integrated loudness in LUFS (EBU R128 / BS.1770), measured over the analysis
    # windows. Drives client-side volume normalisation. NULL until measured.
    lufs: Mapped[float | None] = mapped_column(Float)

    track: Mapped["Track"] = relationship(back_populates="embedding")


class Mix(Base):
    __tablename__ = "mixes"

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    name: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime | None] = mapped_column(DateTime)
    cluster_id: Mapped[int | None] = mapped_column(Integer)
    centroid: Mapped[bytes | None] = mapped_column(LargeBinary)  # 128-dim float32 k-means centroid

    mix_tracks: Mapped[list["MixTrack"]] = relationship(
        back_populates="mix", order_by="MixTrack.position"
    )


class MixTrack(Base):
    __tablename__ = "mix_tracks"

    # PK is (mix_id, position) per spec — a track can appear in multiple mixes
    mix_id: Mapped[str] = mapped_column(Text, ForeignKey("mixes.id"), primary_key=True)
    position: Mapped[int] = mapped_column(Integer, primary_key=True)
    track_id: Mapped[str] = mapped_column(Text, ForeignKey("tracks.id"))

    mix: Mapped["Mix"] = relationship(back_populates="mix_tracks")
