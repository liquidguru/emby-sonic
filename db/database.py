from sqlalchemy import event, text
from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession, async_sessionmaker
from sqlalchemy.orm import DeclarativeBase
from config import settings


# timeout (busy_timeout) lets a writer wait for a lock instead of failing
# immediately when many workers report results at once.
engine = create_async_engine(
    str(settings.db_url), echo=False, connect_args={"timeout": 30}
)
AsyncSessionLocal = async_sessionmaker(engine, expire_on_commit=False)


@event.listens_for(engine.sync_engine, "connect")
def _sqlite_pragmas(dbapi_conn, _record):
    # WAL allows concurrent readers (e.g. /status) alongside the writer.
    cur = dbapi_conn.cursor()
    cur.execute("PRAGMA journal_mode=WAL")
    cur.execute("PRAGMA synchronous=NORMAL")
    cur.close()


class Base(DeclarativeBase):
    pass


async def init_db() -> None:
    from db import models  # noqa: F401 — side-effect: registers all ORM classes
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
        # Add columns to existing DBs (SQLite has no ADD COLUMN IF NOT EXISTS).
        result = await conn.execute(text("PRAGMA table_info(mixes)"))
        mix_cols = {row[1] for row in result.fetchall()}
        if "centroid" not in mix_cols:
            await conn.execute(text("ALTER TABLE mixes ADD COLUMN centroid BLOB"))

        result = await conn.execute(text("PRAGMA table_info(embeddings)"))
        embedding_cols = {row[1] for row in result.fetchall()}
        if "lufs" not in embedding_cols:
            await conn.execute(text("ALTER TABLE embeddings ADD COLUMN lufs REAL"))

        result = await conn.execute(text("PRAGMA table_info(tracks)"))
        track_cols = {row[1] for row in result.fetchall()}
        if "genre" not in track_cols:
            await conn.execute(text("ALTER TABLE tracks ADD COLUMN genre TEXT"))


async def get_db() -> AsyncSession:
    async with AsyncSessionLocal() as session:
        yield session
