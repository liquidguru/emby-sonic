"""
Recognising when two track ids are the same song.

Every selection path picks tracks by **Emby item id**, so two ids for one
recording look like two different tracks. That happens for two unrelated
reasons, and only one of them is a mistake:

  * the same file present more than once (an album sitting in several folders);
  * a song legitimately on both its studio album and a greatest-hits.

The second is not something a user can or should tidy away, so a tidy library
does not solve this — the selection side has to.

It matters most where similarity drives the choice: a duplicate is the nearest
possible neighbour to its twin, and mixes are built from the tracks closest to
a cluster centroid, where near-identical embeddings land together. These paths
actively concentrate duplicates rather than occasionally admitting one.

This logic began inside `similarity.py` for Sonic Adventure and is shared here
so mixes and radio agree with it, rather than growing a second notion of
"the same song".
"""

from __future__ import annotations

import re
import unicodedata


def normalise_identity_part(value: str | None) -> str:
    """
    Casefold and strip a title/artist down to comparable words.

    Decomposes to NFKD and drops combining marks so accented spellings match
    ("Bjork" == "Björk"), then reduces anything that isn't a letter or digit to
    a single space — which absorbs the punctuation differences that plague
    tagging ("Gimme! Gimme! Gimme!" vs "Gimme Gimme Gimme", "Rock & Roll" vs
    "Rock and Roll" is NOT covered, but "Rock & Roll" vs "Rock  &  Roll" is).
    """
    text = unicodedata.normalize("NFKD", value or "")
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    return re.sub(r"[^a-z0-9]+", " ", text.casefold()).strip()


def identity_key(artist: str | None, title: str | None, track_id: str) -> str:
    """
    A key that is equal for two ids holding the same recording.

    Falls back to the track id when there is no usable artist or title — without
    that, every untagged track would share one key and all but the first would
    be discarded as duplicates.

    Deliberately does NOT strip "(Live)", "(Remastered)", "(Radio Edit)" and
    friends. Those distinguish genuinely different recordings, and treating a
    live take as a duplicate of the studio one would silently remove music the
    user does want. The plain key already catches both observed causes; widen it
    only if real reports justify it.
    """
    norm_artist = normalise_identity_part(artist)
    norm_title = normalise_identity_part(title)
    if norm_title or norm_artist:
        return f"{norm_artist}|{norm_title}"
    return f"id:{track_id}"
