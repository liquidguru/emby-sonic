import unittest

from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine

from db.database import init_db
from db.migrations import Migration, current_schema_version, run_migrations


class DatabaseMigrationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.engine = create_async_engine("sqlite+aiosqlite:///:memory:")

    async def asyncTearDown(self) -> None:
        await self.engine.dispose()

    async def test_fresh_database_reaches_current_version_idempotently(self) -> None:
        await init_db(self.engine)
        await init_db(self.engine)

        async with self.engine.connect() as conn:
            versions = (
                await conn.execute(text("SELECT version FROM schema_migrations ORDER BY version"))
            ).scalars().all()
            track_columns = {
                row[1] for row in (await conn.execute(text("PRAGMA table_info(tracks)"))).all()
            }
            embedding_columns = {
                row[1] for row in (await conn.execute(text("PRAGMA table_info(embeddings)"))).all()
            }
            mix_columns = {
                row[1] for row in (await conn.execute(text("PRAGMA table_info(mixes)"))).all()
            }

        self.assertEqual(versions, [1, 2, 3, 4])
        self.assertEqual(current_schema_version(), 4)
        self.assertIn("genre", track_columns)
        self.assertIn("lufs", embedding_columns)
        self.assertIn("effective_start_ms", embedding_columns)
        self.assertIn("effective_end_ms", embedding_columns)
        self.assertIn("centroid", mix_columns)

    async def test_legacy_database_upgrades_without_losing_data(self) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                CREATE TABLE tracks (
                    id TEXT PRIMARY KEY, title TEXT, artist TEXT, album TEXT,
                    duration_ms INTEGER, file_path TEXT, analysed_at TIMESTAMP,
                    analysis_version INTEGER, analysis_status TEXT DEFAULT 'pending',
                    claimed_at TIMESTAMP, error TEXT
                )
            """))
            await conn.execute(text("""
                CREATE TABLE embeddings (
                    track_id TEXT PRIMARY KEY REFERENCES tracks(id), vector BLOB,
                    raw_vector BLOB, tempo REAL, energy REAL, valence REAL,
                    arousal REAL, instrumentalness REAL, vocals_present INTEGER
                )
            """))
            await conn.execute(text("""
                CREATE TABLE mixes (
                    id TEXT PRIMARY KEY, name TEXT, created_at TIMESTAMP,
                    cluster_id INTEGER
                )
            """))
            await conn.execute(text("""
                CREATE TABLE mix_tracks (
                    mix_id TEXT REFERENCES mixes(id), track_id TEXT REFERENCES tracks(id),
                    position INTEGER, PRIMARY KEY (mix_id, position)
                )
            """))
            await conn.execute(
                text("INSERT INTO tracks (id, title, analysis_status) VALUES ('track-1', 'Keep me', 'done')")
            )
            await conn.execute(
                text("INSERT INTO embeddings (track_id, vector) VALUES ('track-1', X'0102')")
            )
            await conn.execute(
                text("INSERT INTO mixes (id, name) VALUES ('mix-1', 'Keep mix')")
            )

        await init_db(self.engine)

        async with self.engine.connect() as conn:
            track = (
                await conn.execute(text("SELECT title, genre FROM tracks WHERE id='track-1'"))
            ).one()
            embedding = (
                await conn.execute(
                    text(
                        "SELECT vector, lufs, effective_start_ms, effective_end_ms "
                        "FROM embeddings WHERE track_id='track-1'"
                    )
                )
            ).one()
            mix = (
                await conn.execute(text("SELECT name, centroid FROM mixes WHERE id='mix-1'"))
            ).one()
            versions = (
                await conn.execute(text("SELECT version FROM schema_migrations ORDER BY version"))
            ).scalars().all()

        self.assertEqual(track, ("Keep me", None))
        # Legacy row survives, with the new columns added and left NULL.
        self.assertEqual(embedding, (b"\x01\x02", None, None, None))
        self.assertEqual(mix, ("Keep mix", None))
        self.assertEqual(versions, [1, 2, 3, 4])

    async def test_failed_migration_is_not_recorded(self) -> None:
        async def fail_after_ddl(conn) -> None:
            await conn.execute(text("CREATE TABLE partial_ddl (id INTEGER)"))
            raise RuntimeError("deliberate migration failure")

        failing = Migration(99, "deliberate failure", fail_after_ddl)
        with self.assertRaisesRegex(RuntimeError, "deliberate migration failure"):
            await run_migrations(self.engine, migrations=(failing,))

        async with self.engine.connect() as conn:
            recorded = (
                await conn.execute(
                    text("SELECT COUNT(*) FROM schema_migrations WHERE version = 99")
                )
            ).scalar_one()
            tables = (
                await conn.execute(
                    text("SELECT name FROM sqlite_master WHERE type = 'table'")
                )
            ).scalars().all()
        self.assertEqual(recorded, 0)
        # Pin the real semantics: pysqlite runs DDL in autocommit, so DDL from
        # a failed migration PERSISTS — the runner is at-least-once, not
        # atomic. That is why every migration function must be idempotent. If
        # this assertion ever flips (e.g. a driver/dialect change makes DDL
        # transactional), update run_migrations()'s docstring to match.
        self.assertIn("partial_ddl", tables)


if __name__ == "__main__":
    unittest.main()
