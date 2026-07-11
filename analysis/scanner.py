"""
Library sync — keep the analysis queue in step with the Emby library.

Fetches every audio item from Emby and upserts it into `tracks` with
status='pending'. Analysis itself is performed by workers
(api/routes/worker.py + worker.py): they claim pending tracks, stream the audio
from Emby, embed it, and report results back. So this module only populates and
refreshes the queue — it does no audio work itself.
"""

from __future__ import annotations

import logging

from sqlalchemy import select

from analysis.emby import fetch_audio_items

logger = logging.getLogger(__name__)

# Lightweight progress for the sync operation itself (analysis progress is read
# from the DB in /sonic/status, since workers drive it).
scan_state: dict = {"running": False, "total": 0, "added": 0}
SCAN_BATCH_SIZE = 500


async def run_scan(full: bool = False) -> None:
    """Entry point for the /library/scan BackgroundTask."""
    if scan_state["running"]:
        return
    scan_state["running"] = True
    scan_state["added"] = 0
    try:
        await _sync_library(full=full)
    except Exception:
        logger.exception("Library sync failed")
    finally:
        scan_state["running"] = False


async def _sync_library(full: bool) -> None:
    from db.database import AsyncSessionLocal
    from db.models import Track

    items = await fetch_audio_items()
    scan_state["total"] = len(items)
    logger.info("Library sync: %d audio items from Emby", len(items))

    valid_items = [item for item in items if item.get("Id")]
    async with AsyncSessionLocal() as db:
        for start in range(0, len(valid_items), SCAN_BATCH_SIZE):
            batch = valid_items[start:start + SCAN_BATCH_SIZE]
            # Preserve order while deduplicating IDs for the bounded IN query.
            batch_ids = list(dict.fromkeys(item["Id"] for item in batch))
            existing = (
                await db.execute(select(Track).where(Track.id.in_(batch_ids)))
            ).scalars().all()
            tracks_by_id = {track.id: track for track in existing}

            for item in batch:
                track_id = item["Id"]
                track = tracks_by_id.get(track_id)
                if track is None:
                    track = Track(id=track_id, analysis_status="pending")
                    db.add(track)
                    tracks_by_id[track_id] = track
                    scan_state["added"] += 1

                track.title = item.get("Name")
                track.artist = item.get("AlbumArtist")
                track.album = item.get("Album")
                genres = item.get("Genres") or []
                track.genre = genres[0] if genres else None
                ticks = item.get("RunTimeTicks")
                track.duration_ms = int(ticks / 10000) if ticks else None
                track.file_path = item.get("Path")

                # Full re-scan requeues every track for re-analysis.
                if full:
                    track.analysis_status = "pending"
                    track.claimed_at = None
                    track.error = None

            await db.commit()

    logger.info("Library sync done: %d new tracks queued", scan_state["added"])
