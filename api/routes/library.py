from fastapi import APIRouter, BackgroundTasks, HTTPException
from api.deps import DB, AuthToken
from api.schemas import (
    ScanRequest,
    ScanStarted,
    BuildMixesRequest,
    BuildMixesStarted,
    BuildStateOut,
)
from analysis.scanner import scan_state, run_scan
from analysis.mixes import build_mixes, build_state

router = APIRouter(tags=["library"])


@router.post("/library/scan", response_model=ScanStarted)
async def trigger_scan(
    background_tasks: BackgroundTasks,
    db: DB,
    _token: AuthToken,
    body: ScanRequest = ScanRequest(),
) -> ScanStarted:
    if scan_state["running"]:
        raise HTTPException(409, "Scan already in progress")
    background_tasks.add_task(run_scan, full=body.full)
    return ScanStarted(message="Scan started", full=body.full)


@router.post("/library/build-mixes", response_model=BuildMixesStarted)
async def trigger_build_mixes(
    background_tasks: BackgroundTasks,
    _token: AuthToken,
    body: BuildMixesRequest = BuildMixesRequest(),
) -> BuildMixesStarted:
    if build_state()["running"]:
        raise HTTPException(409, "Mix build already in progress")
    background_tasks.add_task(build_mixes, body.n_clusters, body.tracks_per_mix)
    return BuildMixesStarted(
        message="Mix generation started",
        n_clusters=body.n_clusters,
        tracks_per_mix=body.tracks_per_mix,
    )


@router.get("/library/build-state", response_model=BuildStateOut)
async def get_build_state(_token: AuthToken) -> BuildStateOut:
    """Whether a mix build is currently running. Clients poll this after
    triggering build-mixes instead of waiting a fixed interval."""
    return BuildStateOut(running=build_state()["running"])
