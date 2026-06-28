from fastapi import APIRouter
from sqlalchemy import select, func
from api.deps import DB, AuthToken
from api.schemas import StatusOut, ErroredTrack
from db.models import Track, Embedding
from analysis.scanner import scan_state

router = APIRouter(tags=["status"])


@router.get("/status", response_model=StatusOut)
async def get_status(db: DB, _token: AuthToken) -> StatusOut:
    total = (await db.execute(select(func.count()).select_from(Track))).scalar_one()
    analysed = (await db.execute(select(func.count()).select_from(Embedding))).scalar_one()
    failed = (
        await db.execute(
            select(func.count()).select_from(Track).where(Track.analysis_status == "error")
        )
    ).scalar_one()

    # Pending = genuinely queued work, excluding tracks that errored out and won't
    # retry (unreadable/missing files). Progress is measured over *analysable*
    # tracks so it reaches 100% once the only thing left is the broken set — i.e.
    # "complete" no longer sits forever at <100% because of un-fixable tracks.
    pending = max(total - analysed - failed, 0)
    analysable = total - failed
    progress = (analysed / analysable) if analysable > 0 else None

    return StatusOut(
        total_tracks=total,
        analysed_tracks=analysed,
        pending_tracks=pending,
        failed_tracks=failed,
        scan_running=scan_state["running"],
        scan_progress=progress,
    )


@router.get("/status/errors", response_model=list[ErroredTrack])
async def get_errored_tracks(db: DB, _token: AuthToken) -> list[ErroredTrack]:
    """List tracks that failed analysis and the reason, so the cause is visible
    (and exportable) rather than hiding behind a stuck 'pending' count."""
    rows = (
        await db.execute(
            select(Track)
            .where(Track.analysis_status == "error")
            .order_by(Track.artist, Track.album, Track.title)
        )
    ).scalars().all()
    return [
        ErroredTrack(id=t.id, title=t.title, artist=t.artist, album=t.album, error=t.error)
        for t in rows
    ]
