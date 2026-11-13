import unittest
from unittest.mock import AsyncMock, Mock, patch

from fastapi import HTTPException
from fastapi.routing import APIRoute

from api import auth
from api.routes import library, mixes
from config import settings


def _client(status_code: int):
    client = AsyncMock()
    client.get.return_value = Mock(status_code=status_code)
    context = AsyncMock()
    context.__aenter__.return_value = client
    context.__aexit__.return_value = False
    return context, client


class AdminAuthTests(unittest.IsolatedAsyncioTestCase):
    """
    Rescans and building/refreshing mixes change what every user sees, so only
    an Emby administrator (or the server API key, used by the Emby plugin) may
    trigger them. Admin status is asked of Emby via an admin-only endpoint.
    """

    def setUp(self) -> None:
        self._key = settings.emby_api_key
        self._ttl = settings.auth_cache_ttl_seconds
        settings.emby_api_key = "server-api-key"
        settings.auth_cache_ttl_seconds = 60
        auth.clear_token_cache()

    def tearDown(self) -> None:
        auth.clear_token_cache()
        settings.emby_api_key = self._key
        settings.auth_cache_ttl_seconds = self._ttl

    async def test_server_api_key_needs_no_emby_call(self) -> None:
        context, client = _client(500)
        with patch("api.auth.httpx.AsyncClient", return_value=context):
            self.assertEqual(await auth.verify_emby_admin("server-api-key"), "server-api-key")
        client.get.assert_not_awaited()

    async def test_admin_session_is_allowed_and_cached(self) -> None:
        context, client = _client(200)
        with patch("api.auth.httpx.AsyncClient", return_value=context):
            await auth.verify_emby_admin("admin-token")
            await auth.verify_emby_admin("admin-token")
        self.assertEqual(client.get.await_count, 1)
        self.assertEqual(client.get.await_args.args[0], "/ScheduledTasks")

    async def test_regular_user_is_refused_and_not_cached(self) -> None:
        for status in (401, 403):
            context, client = _client(status)
            with patch("api.auth.httpx.AsyncClient", return_value=context):
                for _ in range(2):
                    with self.assertRaises(HTTPException) as caught:
                        await auth.verify_emby_admin("user-token")
                    self.assertEqual(caught.exception.status_code, 403)
            self.assertEqual(client.get.await_count, 2)

    async def test_emby_error_is_not_mistaken_for_a_refusal(self) -> None:
        context, _ = _client(500)
        with patch("api.auth.httpx.AsyncClient", return_value=context):
            with self.assertRaises(HTTPException) as caught:
                await auth.verify_emby_admin("admin-token")
        self.assertEqual(caught.exception.status_code, 502)


class MaintenanceRoutesRequireAdminTests(unittest.TestCase):
    def _dependencies(self, router, path: str, method: str) -> set:
        for route in router.routes:
            if isinstance(route, APIRoute) and route.path.endswith(path) and method in route.methods:
                found = set()
                stack = list(route.dependant.dependencies)
                while stack:
                    dep = stack.pop()
                    found.add(dep.call)
                    stack.extend(dep.dependencies)
                return found
        self.fail(f"route {method} {path} not found")

    def test_maintenance_routes_require_admin(self) -> None:
        for router, path, method in [
            (library.router, "/library/scan", "POST"),
            (library.router, "/library/build-mixes", "POST"),
            (mixes.router, "/mixes/{mix_id}/regenerate", "POST"),
        ]:
            self.assertIn(auth.verify_emby_admin, self._dependencies(router, path, method), path)

    def test_reading_mixes_still_only_needs_a_valid_token(self) -> None:
        deps = self._dependencies(mixes.router, "/mixes", "GET")
        self.assertIn(auth.verify_emby_token, deps)
        self.assertNotIn(auth.verify_emby_admin, deps)


if __name__ == "__main__":
    unittest.main()
