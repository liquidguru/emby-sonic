from datetime import datetime
from pydantic import BaseModel


class TrackOut(BaseModel):
    id: str
    title: str | None
    artist: str | None
    album: str | None
    duration_ms: int | None
    analysed_at: datetime | None

    model_config = {"from_attributes": True}


class SimilarTrack(BaseModel):
    track: TrackOut
    score: float  # cosine similarity (0–1, higher = more similar)


class StatusOut(BaseModel):
    total_tracks: int
    analysed_tracks: int
    pending_tracks: int
    scan_running: bool
    scan_progress: float | None  # 0.0–1.0, None if not running


class RadioPlaylist(BaseModel):
    seed_id: str
    tracks: list[TrackOut]


class AdventureRequest(BaseModel):
    from_id: str
    to_id: str
    length: int = 20


class AdventurePlaylist(BaseModel):
    from_id: str
    to_id: str
    tracks: list[TrackOut]


class MixOut(BaseModel):
    id: str
    name: str | None
    created_at: datetime | None
    cluster_id: int | None
    track_count: int

    model_config = {"from_attributes": True}


class MixDetail(BaseModel):
    mix: MixOut
    tracks: list[TrackOut]


class QueueInjectRequest(BaseModel):
    current_track_id: str
    queue_length: int = 5


class QueueInjectOut(BaseModel):
    injected: list[TrackOut]


class ScanRequest(BaseModel):
    full: bool = False  # True = re-analyse everything; False = only unanalysed tracks


class ScanStarted(BaseModel):
    message: str
    full: bool


class SimilarArtist(BaseModel):
    artist: str
    score: float


class SimilarAlbum(BaseModel):
    album: str
    artist: str | None
    score: float
