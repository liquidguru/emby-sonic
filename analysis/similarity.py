"""
Similarity search and playlist generation algorithms.

All functions are async (SQLAlchemy async sessions), but the FAISS calls are
synchronous in-memory operations — fast enough not to need offloading.

Algorithms:
  Track Radio    — seed → k-NN in FAISS
  Sonic Adventure — interpolate embedding space from A to B, pick nearest real tracks
  Mixes for You  — k-means cluster → one mix per cluster (run during scan)
  Guest DJ       — alias for Track Radio with smaller k (used by queue/inject)
"""

from __future__ import annotations

import re
import unicodedata

import numpy as np
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from api.schemas import SimilarTrack, TrackOut, SimilarArtist, SimilarAlbum
from analysis.faiss_index import sonic_index
from db.models import Track, Embedding

ADVENTURE_SEARCH_K = 50
ADVENTURE_OVERSTEP_FACTOR = 3
ADVENTURE_MIN_EXTRA_STEPS = 10


async def _load_track_out(track_id: str, db: AsyncSession) -> TrackOut | None:
    track = await db.get(Track, track_id)
    if track is None:
        return None
    return TrackOut.model_validate(track)


def _normalise_identity_part(value: str | None) -> str:
    text = unicodedata.normalize("NFKD", value or "")
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    return re.sub(r"[^a-z0-9]+", " ", text.casefold()).strip()


def _track_identity_key(track: TrackOut) -> str:
    title = _normalise_identity_part(track.title)
    artist = _normalise_identity_part(track.artist)
    if title or artist:
        return f"{artist}|{title}"
    return f"id:{track.id}"


def _even_sample(items: list[TrackOut], target: int) -> list[TrackOut]:
    if target <= 0:
        return []
    if len(items) <= target:
        return items
    if target == 1:
        return [items[len(items) // 2]]
    last = len(items) - 1
    return [items[round(i * last / (target - 1))] for i in range(target)]


async def get_similar_tracks(
    track_id: str, n: int, db: AsyncSession
) -> list[SimilarTrack]:
    vec = sonic_index.get_vector(track_id)
    if vec is None:
        return []
    results = sonic_index.search(vec, k=n, exclude_id=track_id)
    out = []
    for tid, score in results:
        track_out = await _load_track_out(tid, db)
        if track_out:
            out.append(SimilarTrack(track=track_out, score=score))
    return out


async def build_radio(seed_id: str, length: int, db: AsyncSession) -> list[TrackOut]:
    """
    Track Radio: grow a queue by iteratively picking the nearest unvisited neighbour.
    Starting from seed, each step finds the nearest track to the *current* tail track
    to keep the queue sonically coherent rather than just clustering around the seed.
    """
    seed_vec = sonic_index.get_vector(seed_id)
    if seed_vec is None:
        return []

    visited = {seed_id}
    queue: list[TrackOut] = []
    current_vec = seed_vec

    while len(queue) < length:
        candidates = sonic_index.search(current_vec, k=20)
        next_track = next(
            ((tid, score) for tid, score in candidates if tid not in visited), None
        )
        if next_track is None:
            break
        tid, _ = next_track
        visited.add(tid)
        track_out = await _load_track_out(tid, db)
        if track_out:
            queue.append(track_out)
            next_vec = sonic_index.get_vector(tid)
            if next_vec is not None:
                current_vec = next_vec

    return queue


async def build_adventure(
    from_id: str, to_id: str, length: int, db: AsyncSession
) -> list[TrackOut]:
    """
    Sonic Adventure: interpolate linearly through embedding space from A to B,
    find the nearest real track at each interpolation step.
    """
    vec_a = sonic_index.get_vector(from_id)
    vec_b = sonic_index.get_vector(to_id)
    if vec_a is None or vec_b is None:
        return []

    start_out = await _load_track_out(from_id, db)
    end_out = await _load_track_out(to_id, db)
    if start_out is None or end_out is None:
        return []

    target_middle = max(0, length)
    if target_middle == 0:
        return [start_out] if end_out.id == start_out.id else [start_out, end_out]

    visited_ids = {from_id, to_id}
    visited_keys = {_track_identity_key(start_out), _track_identity_key(end_out)}
    candidates: list[TrackOut] = []
    walk_steps = max(
        target_middle * ADVENTURE_OVERSTEP_FACTOR,
        target_middle + ADVENTURE_MIN_EXTRA_STEPS,
    )

    for step in range(1, walk_steps + 1):
        t = step / (walk_steps + 1)  # 0 < t < 1, never hits endpoints
        interp = (1.0 - t) * vec_a + t * vec_b
        interp = interp / (np.linalg.norm(interp) + 1e-8)

        for tid, _ in sonic_index.search(interp, k=ADVENTURE_SEARCH_K):
            if tid in visited_ids:
                continue
            track_out = await _load_track_out(tid, db)
            if track_out is None:
                visited_ids.add(tid)
                continue
            key = _track_identity_key(track_out)
            if key in visited_keys:
                visited_ids.add(tid)
                continue
            visited_ids.add(tid)
            visited_keys.add(key)
            candidates.append(track_out)
            break

    # A→B journey: bookend the interpolated walk with the actual endpoints so
    # the adventure literally starts at A and ends at B.
    tracks: list[TrackOut] = [start_out]
    middle = _even_sample(candidates, target_middle)
    tracks.extend(middle)
    if end_out.id != start_out.id:
        tracks.append(end_out)

    return tracks


async def get_similar_artists(
    artist_id: str, n: int, db: AsyncSession
) -> list[SimilarArtist]:
    """
    Artist similarity: average the embeddings of all tracks by the artist,
    then find tracks from other artists whose centroids are nearest.

    artist_id here is the Emby ArtistId (string). We resolve artist name → tracks
    via the Track table's artist column (Emby doesn't give us a separate artist table).
    """
    result = await db.execute(select(Track).where(Track.id == artist_id))
    seed_track = result.scalar_one_or_none()
    if seed_track is None or seed_track.artist is None:
        return []

    artist_name = seed_track.artist
    artist_tracks = (
        await db.execute(select(Track).where(Track.artist == artist_name))
    ).scalars().all()

    vecs = [sonic_index.get_vector(t.id) for t in artist_tracks]
    vecs = [v for v in vecs if v is not None]
    if not vecs:
        return []

    centroid = np.mean(vecs, axis=0).astype(np.float32)
    candidates = sonic_index.search(centroid, k=n * 20)

    seen_artists: set[str] = {artist_name}
    out: list[SimilarArtist] = []
    for tid, score in candidates:
        track = await db.get(Track, tid)
        if track and track.artist and track.artist not in seen_artists:
            seen_artists.add(track.artist)
            out.append(SimilarArtist(artist=track.artist, score=score))
        if len(out) >= n:
            break
    return out


async def get_similar_albums(
    album_id: str, n: int, db: AsyncSession
) -> list[SimilarAlbum]:
    """
    Album similarity: average track embeddings for the seed album, then find
    other albums with nearest centroids.
    """
    seed_track = await db.get(Track, album_id)
    if seed_track is None or seed_track.album is None:
        return []

    album_name = seed_track.album
    album_tracks = (
        await db.execute(select(Track).where(Track.album == album_name))
    ).scalars().all()

    vecs = [sonic_index.get_vector(t.id) for t in album_tracks]
    vecs = [v for v in vecs if v is not None]
    if not vecs:
        return []

    centroid = np.mean(vecs, axis=0).astype(np.float32)
    candidates = sonic_index.search(centroid, k=n * 20)

    seen_albums: set[str] = {album_name}
    out: list[SimilarAlbum] = []
    for tid, score in candidates:
        track = await db.get(Track, tid)
        if track and track.album and track.album not in seen_albums:
            seen_albums.add(track.album)
            out.append(SimilarAlbum(
                album=track.album,
                artist=track.artist,
                score=score,
            ))
        if len(out) >= n:
            break
    return out
