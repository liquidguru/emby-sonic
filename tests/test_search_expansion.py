import unittest
from unittest.mock import AsyncMock, patch

import httpx
from fastapi import HTTPException

from api.routes import webapp as webapp_module


def _item(id_: str, name: str, artist: str | None = None) -> dict:
    return {"Id": id_, "Name": name, "AlbumArtist": artist, "Artists": [artist] if artist else []}


class SearchTracksExpansionTests(unittest.IsolatedAsyncioTestCase):
    """
    Fixes #33 — searching an artist's name (e.g. "Madonna") only found a track
    literally titled that, missing the rest of their catalogue, because the
    old search was title-only. Mirrors the Android fix for the same gap
    (#28 / 73624e0): search direct title matches and artist names in
    parallel, then merge in every track by any matched artist, direct
    matches first, deduped by track id.
    """

    async def test_expands_to_full_artist_catalogue(self) -> None:
        async def fake_get(self, path, params=None, headers=None):
            class R:
                status_code = 200

                def json(self):
                    include = params.get("IncludeItemTypes")
                    if include == "MusicArtist":
                        return {"Items": [_item("artist-madonna", "Madonna")]}
                    if params.get("AlbumArtistIds") == "artist-madonna":
                        return {"Items": [
                            _item("song-1", "Like a Prayer", "Madonna"),
                            _item("song-2", "Vogue", "Madonna"),
                        ]}
                    # Direct title search: only a literal song titled "Madonna".
                    return {"Items": [_item("song-title-match", "Madonna", "Some Other Artist")]}

            return R()

        with patch.object(httpx.AsyncClient, "get", fake_get), \
             patch.object(webapp_module, "_music_parent_ids", AsyncMock(return_value=[])):
            results = await webapp_module.search_tracks(
                _token="dummy", q="Madonna", limit=60, user_id="user1", emby_token="tok",
            )

        ids = [t.id for t in results]
        self.assertIn("song-title-match", ids)
        self.assertIn("song-1", ids)
        self.assertIn("song-2", ids)
        self.assertEqual(ids[0], "song-title-match", "direct title match should be ordered first")

    async def test_dedupes_track_matched_by_both_paths(self) -> None:
        async def fake_get(self, path, params=None, headers=None):
            class R:
                status_code = 200

                def json(self):
                    include = params.get("IncludeItemTypes")
                    if include == "MusicArtist":
                        return {"Items": [_item("artist-x", "311")]}
                    if params.get("AlbumArtistIds") == "artist-x":
                        return {"Items": [_item("song-311", "311", "311"), _item("song-other", "Down", "311")]}
                    return {"Items": [_item("song-311", "311", "311")]}  # also a direct title match

            return R()

        with patch.object(httpx.AsyncClient, "get", fake_get), \
             patch.object(webapp_module, "_music_parent_ids", AsyncMock(return_value=[])):
            results = await webapp_module.search_tracks(
                _token="dummy", q="311", limit=60, user_id="user1", emby_token="tok",
            )

        ids = [t.id for t in results]
        self.assertEqual(ids.count("song-311"), 1, f"expected no duplicate, got {ids}")
        self.assertIn("song-other", ids)

    async def test_no_artist_match_behaves_like_title_only_search(self) -> None:
        async def fake_get(self, path, params=None, headers=None):
            class R:
                status_code = 200

                def json(self):
                    if params.get("IncludeItemTypes") == "MusicArtist":
                        return {"Items": []}  # nothing matches as an artist
                    return {"Items": [_item("song-x", "Some Random Title")]}

            return R()

        with patch.object(httpx.AsyncClient, "get", fake_get), \
             patch.object(webapp_module, "_music_parent_ids", AsyncMock(return_value=[])):
            results = await webapp_module.search_tracks(
                _token="dummy", q="random", limit=60, user_id="user1", emby_token="tok",
            )

        self.assertEqual([t.id for t in results], ["song-x"])

    async def test_emby_auth_error_propagates(self) -> None:
        async def fake_get(self, path, params=None, headers=None):
            class R:
                status_code = 401

                def json(self):
                    return {}

            return R()

        with patch.object(httpx.AsyncClient, "get", fake_get), \
             patch.object(webapp_module, "_music_parent_ids", AsyncMock(return_value=[])):
            with self.assertRaises(HTTPException) as ctx:
                await webapp_module.search_tracks(
                    _token="dummy", q="x", limit=60, user_id="user1", emby_token="bad-token",
                )
        self.assertEqual(ctx.exception.status_code, 401)


if __name__ == "__main__":
    unittest.main()
