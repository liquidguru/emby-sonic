from contextlib import asynccontextmanager
from pathlib import Path
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from api.routes import status, tracks, adventure, mixes, queue, library, artists, albums, worker, webapp
from db.database import init_db
from config import settings


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
    yield


app = FastAPI(title="Emby Sonic", version="0.1.0", lifespan=lifespan)


@app.middleware("http")
async def add_referrer_policy_header(request, call_next):
    response = await call_next(request)
    response.headers["Referrer-Policy"] = "no-referrer"
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
