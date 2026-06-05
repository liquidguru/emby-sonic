from fastapi import APIRouter, BackgroundTasks, HTTPException
from api.deps import DB, AuthToken
from api.schemas import ScanRequest, ScanStarted
from analysis.scanner import scan_state, run_scan

router = APIRouter(tags=["library"])


@router.post("/library/scan", response_model=ScanStarted)
async def trigger_scan(
    body: ScanRequest,
    background_tasks: BackgroundTasks,
    db: DB,
    _token: AuthToken,
) -> ScanStarted:
    if scan_state["running"]:
        raise HTTPException(409, "Scan already in progress")
    background_tasks.add_task(run_scan, full=body.full)
    return ScanStarted(message="Scan started", full=body.full)
