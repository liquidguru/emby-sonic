from datetime import datetime
import hashlib
import unittest
from unittest.mock import AsyncMock, patch

from fastapi import HTTPException
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from api import auth
from api.routes.status import get_status
from config import settings
from db.database import Base
from db.models import Embedding, Track


class EmbyTokenCacheTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.original_api_key = settings.emby_api_key
        self.original_ttl = settings.auth_cache_ttl_seconds
        self.original_max = settings.auth_cache_max_entries
        settings.emby_api_key = "server-api-key"
        settings.auth_cache_ttl_seconds = 60
        settings.auth_cache_max_entries = 2
        auth.clear_token_cache()

    def tearDown(self) -> None:
        auth.clear_token_cache()
        settings.emby_api_key = self.original_api_key
        settings.auth_cache_ttl_seconds = self.original_ttl
        settings.auth_cache_max_entries = self.original_max

    @staticmethod
    def _client(status_code: int):
        response = unittest.mock.Mock(status_code=status_code)
        client = AsyncMock()
        client.get.return_value = response
        context = AsyncMock()
        context.__aenter__.return_value = client
        context.__aexit__.return_value = False
        return context, client

    async def test_successful_token_is_cached_by_digest(self) -> None:
        context, client = self._client(200)
        with patch("api.auth.httpx.AsyncClient", return_value=context):
            self.assertEqual(await auth.verify_emby_token("user-token"), "user-token")
            self.assertEqual(await auth.verify_emby_token("user-token"), "user-token")

        self.assertEqual(client.get.await_count, 1)
        keys = list(auth._valid_token_cache)
        self.assertEqual(keys, [hashlib.sha256(b"user-token").hexdigest()])
        self.assertNotIn("user-token", keys)

    async def test_invalid_token_is_never_cached(self) -> None:
        context, client = self._client(401)
        with patch("api.auth.httpx.AsyncClient", return_value=context):
            for _ in range(2):
                with self.assertRaises(HTTPException):
                    await auth.verify_emby_token("bad-token")

        self.assertEqual(client.get.await_count, 2)
        self.assertEqual(len(auth._valid_token_cache), 0)

    async def test_expired_token_is_revalidated(self) -> None:
        context, client = self._client(200)
        with (
            patch("api.auth.httpx.AsyncClient", return_value=context),
            patch("api.auth.time.monotonic", side_effect=[100.0, 100.0, 161.0, 161.0]),
        ):
            await auth.verify_emby_token("expiring-token")
            await auth.verify_emby_token("expiring-token")

        self.assertEqual(client.get.await_count, 2)

    async def test_cache_evicts_oldest_entry_at_configured_bound(self) -> None:
        context, client = self._client(200)
        with patch("api.auth.httpx.AsyncClient", return_value=context):
            await auth.verify_emby_token("one")
            await auth.verify_emby_token("two")
            await auth.verify_emby_token("three")
            await auth.verify_emby_token("one")

        self.assertEqual(client.get.await_count, 4)
        self.assertEqual(len(auth._valid_token_cache), 2)

    async def test_server_api_key_still_short_circuits_emby_and_cache(self) -> None:
        with patch("api.auth.httpx.AsyncClient") as client_class:
            self.assertEqual(
                await auth.verify_emby_token("server-api-key"),
                "server-api-key",
            )

        client_class.assert_not_called()
        self.assertEqual(len(auth._valid_token_cache), 0)


class OperationalStatusTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.engine = create_async_engine("sqlite+aiosqlite:///:memory:")
        async with self.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)

    async def asyncTearDown(self) -> None:
        await self.engine.dispose()

    async def test_status_reports_index_and_claim_health(self) -> None:
        oldest_claim = datetime(2026, 7, 10, 8, 30, 0)
        async with self.sessions() as db:
            db.add_all(
                [
                    Track(id="done", analysis_status="done"),
                    Track(id="claimed", analysis_status="pending", claimed_at=oldest_claim),
                    Track(id="failed", analysis_status="error", error="decode"),
                    Embedding(track_id="done", vector=b"vector"),
                ]
            )
            await db.commit()

        fake_index = unittest.mock.MagicMock()
        fake_index.__len__.return_value = 1
        cache_stats = {"entries": 2, "hits": 7, "misses": 3}
        async with self.sessions() as db:
            with (
                patch("api.routes.status.sonic_index", fake_index),
                patch("api.routes.status.token_cache_stats", return_value=cache_stats),
                patch("api.routes.status.current_schema_version", return_value=3),
            ):
                result = await get_status(db, "token")

        self.assertEqual(result.total_tracks, 3)
        self.assertEqual(result.analysed_tracks, 1)
        self.assertEqual(result.failed_tracks, 1)
        self.assertEqual(result.pending_tracks, 1)
        self.assertEqual(result.claimed_tracks, 1)
        self.assertEqual(result.oldest_claimed_at, oldest_claim)
        self.assertEqual(result.indexed_tracks, 1)
        self.assertTrue(result.index_in_sync)
        self.assertEqual(result.auth_cache_entries, 2)
        self.assertEqual(result.auth_cache_hits, 7)
        self.assertEqual(result.auth_cache_misses, 3)
        self.assertEqual(result.database_schema_version, 3)


if __name__ == "__main__":
    unittest.main()
