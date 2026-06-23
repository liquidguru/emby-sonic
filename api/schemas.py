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


class ArtistMixRequest(BaseModel):
    artists: list[str]          # artist names (as stored on tracks)
    per_artist: int = 5         # representative tracks to draw from each artist
    length: int | None = None   # optional cap on the final ordered queue


class ArtistMixPlaylist(BaseModel):
    artists: list[str]
    tracks: list[TrackOut]


class MixOut(BaseModel):
    id: str
    name: str | None
    created_at: datetime | None
    cluster_id: int | None
    track_count: int
    cover_track_id: str | None = None  # a representative track id, for client artwork

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


class RegenerateMixRequest(BaseModel):
    tracks_per_mix: int = 50   # how many tracks to include in the refreshed mix


class BuildMixesRequest(BaseModel):
    n_clusters: int = 30       # number of mixes to generate
    tracks_per_mix: int = 50   # tracks per mix (closest to cluster centroid)


class BuildMixesStarted(BaseModel):
    message: str
    n_clusters: int
    tracks_per_mix: int


class BuildStateOut(BaseModel):
    running: bool  # True while a mix build is in progress (poll after build-mixes)


class SimilarArtist(BaseModel):
    artist: str
    score: float


class SimilarAlbum(BaseModel):
    album: str
    artist: str | None
    score: float


# ---- Distributed analysis workers ----

class ClaimRequest(BaseModel):
    worker_id: str
    batch_size: int = 16
    lease_seconds: int = 600  # reclaim a track if a worker hasn't reported back in time


class ClaimedTrack(BaseModel):
    id: str            # Emby item id — worker streams audio via /Items/{id}/Download
    path: str | None   # original path; used only for the temp-file extension


class ClaimResponse(BaseModel):
    tracks: list[ClaimedTrack]


class WorkerResult(BaseModel):
    track_id: str
    raw_vector: str | None = None  # base64 of the 2048-dim float32 CNN embedding
    tempo: float | None = None
    energy: float | None = None
    valence: float | None = None
    arousal: float | None = None
    instrumentalness: float | None = None
    vocals_present: int | None = None
    error: str | None = None       # set if this track failed on the worker


class ResultsRequest(BaseModel):
    results: list[WorkerResult]


class ResultsResponse(BaseModel):
    stored: int
    failed: int
