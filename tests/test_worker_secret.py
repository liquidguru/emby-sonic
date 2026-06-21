import unittest

from fastapi import HTTPException

from api.auth import verify_worker
from config import settings


class WorkerSecretAuthTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self._emby_api_key = settings.emby_api_key
        self._worker_secret = settings.worker_secret

    def tearDown(self) -> None:
        settings.emby_api_key = self._emby_api_key
        settings.worker_secret = self._worker_secret

    async def test_worker_secret_overrides_emby_api_key(self) -> None:
        settings.emby_api_key = "emby-key"
        settings.worker_secret = "worker-secret"

        self.assertEqual(await verify_worker("worker-secret"), "worker-secret")
        with self.assertRaises(HTTPException):
            await verify_worker("emby-key")

    async def test_emby_api_key_remains_fallback(self) -> None:
        settings.emby_api_key = "emby-key"
        settings.worker_secret = ""

        self.assertEqual(await verify_worker("emby-key"), "emby-key")
        with self.assertRaises(HTTPException):
            await verify_worker("worker-secret")


if __name__ == "__main__":
    unittest.main()
