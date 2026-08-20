import logging
from contextlib import asynccontextmanager
from pathlib import Path
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import RedirectResponse
from fastapi.staticfiles import StaticFiles
from api.routes import status, tracks, adventure, mixes, queue, library, artists, albums, worker, webapp
from db.database import init_db
from config import settings

log = logging.getLogger(__name__)


class NoCacheStaticFiles(StaticFiles):
    """StaticFiles that tells browsers to revalidate the webapp on every load.

    The default StaticFiles sends ETag/Last-Modified but no Cache-Control, so
    browsers apply heuristic freshness and can serve a stale app.js on a normal
    revisit — meaning a webapp fix (e.g. a crash fix) may not reach a tester
    until they hard-refresh. `no-cache` keeps the cached copy but forces an
    ETag revalidation each load (cheap 304s when unchanged), so updates land
    promptly while unchanged assets still aren't re-downloaded.
    """

    async def get_response(self, path, scope):
        response = await super().get_response(path, scope)
        response.headers["Cache-Control"] = "no-cache"
        return response


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    settings.model_dir.mkdir(parents=True, exist_ok=True)
    await init_db()
    from analysis.faiss_index import sonic_index
    from analysis.index_sync import rebuild_index_from_db
    sonic_index.load_or_create()
    # Rebuild FAISS from the DB (source of truth) so a crash that lost the
    # on-disk index — or stale index after analysed tracks were committed —
    # is reconciled on every startup.
    await rebuild_index_from_db()
    await _warn_if_emby_urls_unreachable()
    yield


async def _warn_if_emby_urls_unreachable() -> None:
    """
    Probe the Emby addresses at startup and warn loudly about any that fail.

    EMBY_URL_EXTERNAL is handed to BROWSERS, never used by the coordinator
    itself, so a wrong value has no symptom here — it surfaces as a web app
    with an apparently empty library, which reads as a data problem rather
    than a networking one. Saying so at startup turns a long debugging session
    into one line in the log.

    This only ever WARNS. A coordinator that can't reach the external address
    may be perfectly healthy: plenty of networks don't hairpin, so the public
    hostname is unreachable from inside while working fine for real clients.
    Refusing to start on that would be wrong.
    """
    import httpx

    targets = [("EMBY_URL", settings.emby_url)]
    if settings.emby_url_external:
        targets.append(("EMBY_URL_EXTERNAL", settings.emby_url_external))

    for name, url in targets:
        base = url.rstrip("/")
        if not base:
            continue
        try:
            async with httpx.AsyncClient(timeout=8.0) as client:
                resp = await client.get(f"{base}/System/Info/Public")
            if resp.status_code >= 400:
                log.warning("%s=%s answered HTTP %s — check the address.", name, base, resp.status_code)
            else:
                log.info("%s=%s reachable.", name, base)
        except Exception as exc:
            if name == "EMBY_URL_EXTERNAL":
                log.warning(
                    "%s=%s is NOT reachable from the coordinator (%s). This can be normal if "
                    "your network doesn't hairpin — but this address is what BROWSERS are told "
                    "to stream from, so if the web app shows an empty library over a domain "
                    "name, this is why. Check the host AND the port.",
                    name, base, exc,
                )
            else:
                log.error(
                    "%s=%s is NOT reachable (%s). The coordinator needs this to scan and "
                    "analyse; nothing will work until it resolves.",
                    name, base, exc,
                )


app = FastAPI(title="Emby Sonic", version="0.1.0", lifespan=lifespan)


@app.middleware("http")
async def add_security_headers(request, call_next):
    response = await call_next(request)
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; "
        "script-src 'self'; "
        "object-src 'none'; "
        "base-uri 'self'; "
        "connect-src *; "
        "img-src * data:; "
        "media-src * blob:"
    )
    return response


app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(status.router, prefix="/sonic")
app.include_router(tracks.router, prefix="/sonic")
app.include_router(adventure.router, prefix="/sonic")
app.include_router(mixes.router, prefix="/sonic")
app.include_router(queue.router, prefix="/sonic")
app.include_router(library.router, prefix="/sonic")
app.include_router(artists.router, prefix="/sonic")
app.include_router(albums.router, prefix="/sonic")
app.include_router(worker.router, prefix="/sonic")
app.include_router(webapp.router, prefix="/sonic")

_webapp_dir = Path(__file__).resolve().parent / "webapp"
app.mount("/app", NoCacheStaticFiles(directory=_webapp_dir, html=True), name="webapp")


@app.get("/", include_in_schema=False)
async def root_to_webapp() -> RedirectResponse:
    """
    Send the bare host to the web app.

    Without this, anyone who types just host:port — the natural thing to do,
    and what a browser offers from history — gets `{"detail":"Not Found"}`.
    Raw JSON reads as the application being broken rather than as a missing
    path, and it has already sent one person hunting a non-existent outage.

    307 rather than 301: a permanent redirect is cached by browsers more or
    less forever, which would be painful to undo if the web app ever moves.
    """
    return RedirectResponse(url="/app/", status_code=307)


if __name__ == "__main__":
    import sys
    import uvicorn

    try:
        uvicorn.run("main:app", host=settings.host, port=settings.port, reload=False)
    except OSError as exc:
        if exc.errno in (48, 98, 10048):  # EADDRINUSE (macOS/BSD, Linux, Windows)
            print(
                f"\n*** Port {settings.port} is already in use — another emby-sonic "
                "coordinator (or something else) is bound to it. The supervisor will "
                "keep restarting this process until the port is freed. ***\n",
                file=sys.stderr, flush=True,
            )
        raise
