from sqlalchemy import event
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
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


async def init_db(target_engine: AsyncEngine | None = None) -> None:
    from db import models  # noqa: F401 — side-effect: registers all ORM classes
    from db.migrations import run_migrations

    active_engine = target_engine or engine
    async with active_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    await run_migrations(active_engine)


async def get_db() -> AsyncSession:
    async with AsyncSessionLocal() as session:
        yield session
