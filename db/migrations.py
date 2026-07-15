"""Small, ordered SQLite migration runner for coordinator-owned schema changes."""

from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncConnection, AsyncEngine


MigrationFunction = Callable[[AsyncConnection], Awaitable[None]]


@dataclass(frozen=True)
class Migration:
    version: int
    name: str
    apply: MigrationFunction


async def _column_names(conn: AsyncConnection, table: str) -> set[str]:
    # Table names come only from the static migrations below, never user input.
    rows = (await conn.execute(text(f"PRAGMA table_info({table})"))).all()
    return {row[1] for row in rows}


async def _add_mix_centroid(conn: AsyncConnection) -> None:
    if "centroid" not in await _column_names(conn, "mixes"):
        await conn.execute(text("ALTER TABLE mixes ADD COLUMN centroid BLOB"))


async def _add_embedding_lufs(conn: AsyncConnection) -> None:
    if "lufs" not in await _column_names(conn, "embeddings"):
        await conn.execute(text("ALTER TABLE embeddings ADD COLUMN lufs REAL"))


async def _add_track_genre(conn: AsyncConnection) -> None:
    if "genre" not in await _column_names(conn, "tracks"):
        await conn.execute(text("ALTER TABLE tracks ADD COLUMN genre TEXT"))


async def _add_embedding_effective_edges(conn: AsyncConnection) -> None:
    columns = await _column_names(conn, "embeddings")
    if "effective_start_ms" not in columns:
        await conn.execute(text("ALTER TABLE embeddings ADD COLUMN effective_start_ms INTEGER"))
    if "effective_end_ms" not in columns:
        await conn.execute(text("ALTER TABLE embeddings ADD COLUMN effective_end_ms INTEGER"))


MIGRATIONS: tuple[Migration, ...] = (
    Migration(1, "add mixes.centroid", _add_mix_centroid),
    Migration(2, "add embeddings.lufs", _add_embedding_lufs),
    Migration(3, "add tracks.genre", _add_track_genre),
    Migration(4, "add embeddings effective edges", _add_embedding_effective_edges),
)

_current_schema_version = 0


def current_schema_version() -> int:
    return _current_schema_version


async def _ensure_ledger(engine: AsyncEngine) -> None:
    async with engine.begin() as conn:
        await conn.execute(text("""
            CREATE TABLE IF NOT EXISTS schema_migrations (
                version INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                applied_at TEXT NOT NULL
            )
        """))


async def run_migrations(
    engine: AsyncEngine,
    *,
    migrations: Sequence[Migration] = MIGRATIONS,
) -> int:
    """Apply each missing migration once, in version order.

    Semantics are AT-LEAST-ONCE, not atomic: SQLAlchemy's pysqlite driver
    executes DDL in autocommit mode (no BEGIN is emitted before DDL), so a
    migration that fails partway can leave earlier DDL applied. What is
    guaranteed is that the ledger insert runs only after apply() succeeds, so
    a failed migration is never recorded and re-runs on the next startup.
    Every migration function must therefore be IDEMPOTENT — check state before
    altering it, as the column-add migrations here do. That same idempotency
    is what lets databases upgraded by the old ad hoc startup checks adopt the
    ledger without repeating an ALTER TABLE.
    """
    global _current_schema_version
    ordered = sorted(migrations, key=lambda migration: migration.version)
    versions = [migration.version for migration in ordered]
    if len(versions) != len(set(versions)):
        raise ValueError("Migration versions must be unique")

    await _ensure_ledger(engine)
    for migration in ordered:
        async with engine.begin() as conn:
            already_applied = (
                await conn.execute(
                    text("SELECT 1 FROM schema_migrations WHERE version = :version"),
                    {"version": migration.version},
                )
            ).scalar_one_or_none()
            if already_applied:
                continue

            await migration.apply(conn)
            await conn.execute(
                text("""
                    INSERT INTO schema_migrations (version, name, applied_at)
                    VALUES (:version, :name, :applied_at)
                """),
                {
                    "version": migration.version,
                    "name": migration.name,
                    "applied_at": datetime.now(timezone.utc).isoformat(),
                },
            )

    async with engine.connect() as conn:
        version = (
            await conn.execute(text("SELECT COALESCE(MAX(version), 0) FROM schema_migrations"))
        ).scalar_one()
    _current_schema_version = int(version)
    return _current_schema_version
