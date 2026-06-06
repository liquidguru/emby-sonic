from fastapi import APIRouter
from sqlalchemy import select, func
from api.deps import DB, AuthToken
from api.schemas import StatusOut
from db.models import Track, Embedding
from analysis.scanner import scan_state

router = APIRouter(tags=["status"])


@router.get("/status", response_model=StatusOut)
async def get_status(db: DB, _token: AuthToken) -> StatusOut:
    total = (await db.execute(select(func.count()).select_from(Track))).scalar_one()
    analysed = (await db.execute(select(func.count()).select_from(Embedding))).scalar_one()

    # Analysis progress is the share of the library embedded (workers drive it).
    progress = (analysed / total) if total else None

    return StatusOut(
        total_tracks=total,
        analysed_tracks=analysed,
        pending_tracks=total - analysed,
        scan_running=scan_state["running"],
        scan_progress=progress,
    )
