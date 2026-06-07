from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from api.routes import status, tracks, adventure, mixes, queue, library, artists, albums, worker
from db.database import init_db
from config import settings


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


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=False)
