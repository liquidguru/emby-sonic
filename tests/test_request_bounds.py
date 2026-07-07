import unittest

from fastapi import FastAPI
from fastapi.testclient import TestClient

from api.schemas import (
    AdventureRequest,
    ArtistMixRequest,
    BuildMixesRequest,
    ClaimRequest,
    RegenerateMixRequest,
)


class RequestBoundsTests(unittest.TestCase):
    def _post_to_schema(self, schema_type, payload: dict):
        app = FastAPI()
        calls = {"count": 0}

        @app.post("/test")
        def endpoint(body: schema_type):  # type: ignore[valid-type]
            calls["count"] += 1
            return {"ok": True}

        response = TestClient(app).post("/test", json=payload)
        return response, calls["count"]

    def assert_rejected(self, schema_type, payload: dict) -> None:
        response, calls = self._post_to_schema(schema_type, payload)
        self.assertEqual(response.status_code, 422, response.text)
        self.assertEqual(calls, 0)

    def test_adventure_length_is_bounded(self) -> None:
        self.assert_rejected(AdventureRequest, {"from_id": "a", "to_id": "b", "length": 0})

    def test_artist_mix_per_artist_is_bounded(self) -> None:
        self.assert_rejected(ArtistMixRequest, {"artists": ["A"], "per_artist": 0})

    def test_artist_mix_length_is_bounded(self) -> None:
        self.assert_rejected(ArtistMixRequest, {"artists": ["A"], "length": 0})

    def test_regenerate_tracks_per_mix_is_bounded(self) -> None:
        self.assert_rejected(RegenerateMixRequest, {"tracks_per_mix": 101})

    def test_build_mixes_n_clusters_is_bounded(self) -> None:
        self.assert_rejected(BuildMixesRequest, {"n_clusters": 0, "tracks_per_mix": 25})

    def test_build_mixes_tracks_per_mix_is_bounded(self) -> None:
        self.assert_rejected(BuildMixesRequest, {"n_clusters": 30, "tracks_per_mix": 101})

    def test_claim_batch_size_is_bounded(self) -> None:
        self.assert_rejected(ClaimRequest, {"worker_id": "w1", "batch_size": 0, "lease_seconds": 600})

    def test_claim_lease_seconds_is_bounded(self) -> None:
        self.assert_rejected(ClaimRequest, {"worker_id": "w1", "batch_size": 16, "lease_seconds": 0})


if __name__ == "__main__":
    unittest.main()
