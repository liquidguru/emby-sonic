# Codex task — next batch: Bluetooth duration bug, genre playlists, offline prefetch

emby-sonic (liquidWave Android) is in daily use and field-tested on a motorbike
ride. Read `AGENTS.md` and `docs/spec.md` first — the spec is the source of
truth for accepted features and architecture decisions. HEAD at handoff is
`ec381c9`, tree clean.

---

## 1. Bug fix: Bluetooth/AVRCP song duration shows 00:00 (high priority)

**Symptom.** When the Pixel 8 Pro is connected via Bluetooth to an external
display (motorbike screen, car head unit, etc.), the display shows song title,
album, artist, and a live position counter — but the total song duration is
`00:00`, so the progress bar can't render. The phone's own Now Playing screen
shows duration correctly; this is an AVRCP metadata export issue only.

**Root cause to investigate.** AVRCP reads track metadata from the Android
`MediaSession`. Our `MediaMetadata` is published by `PlaybackController` (look
for `updateNowPlaying()` or equivalent, and how it feeds the session). Check
whether `MediaMetadata.Builder.setDurationMs()` is called with the track's actual
duration from the Emby item, and whether that value survives the session publish.
Also verify the Emby duration field is non-null and non-zero for the tracks
tested — some Emby items (e.g. transcoded or non-indexed formats) can return
null for `RunTimeTicks`.

**Fix.** Ensure `setDurationMs(durationMs)` is set in the `MediaMetadata`
published to the session. If the Emby value can be null/zero, log a warning and
either skip setting it (prefer missing bar over wrong zero) or default to the
ExoPlayer-derived duration once the item is loaded. Field-test: connect the
phone to a Bluetooth device with a display and confirm the progress bar fills
correctly.

---

## 2. Feature: Genre playlists (M4.9)

Kaj wants to play a queue of all songs in a given genre — similar to "play all
by artist" but filtered by genre tag.

**Design questions to answer first:**
- Does the Emby library API support genre-filtered track queries? Check
  `LibraryRepository` and `EmbyApi` for any existing genre endpoint or
  `DetailKind` support. If not, what query params does Emby accept for
  genre-based `Items` lookups?
- Where does genre browsing live in the UI? Options: a new "Genres" section in
  the Music library tab, a genre chip on Album/Track detail screens, or a Home
  station. Pick the lowest-effort entry point that feels natural.

**Requirements:**
- Queue plays through `PlaybackController.playQueue()` with a `PlaybackSource`
  keyed as `"genre:{genreName}"` so Recent plays records it.
- Shuffle supported (same shuffle toggle as album/artist queues).
- Audiobooks excluded (genre query should be Music library only).
- Recent plays records the session; tapping the tile replays the exact stored
  queue via `itemsByIds` (same pattern as adventures/radio).

**Constraints:** genre names from Emby can be long — make sure the Recent plays
tile subtitle truncates gracefully.

---

## 3. Feature: Offline prefetch buffer (accepted roadmap item)

This is already listed as an accepted product idea in the spec ("small offline
cache"). Implement it now because it's the most practically useful roadmap item
for a mobile listener.

**Goal.** While a queue is playing, silently download the next N upcoming tracks
into a local cache. If the network drops (tunnel, dead zone, signal loss), the
pre-buffered tracks continue playing without interruption. When connectivity
returns, buffering resumes.

**Design guidelines:**
- Cache the next **2–3 tracks** ahead of the current position (enough for most
  short outages without burning storage).
- Use a simple file cache in the app's private storage (`context.cacheDir` or
  `context.filesDir`) keyed by Emby item id + quality settings. Delete entries
  once they've been played and the queue has moved past them.
- Wire into `PlaybackController`: when the queue advances or a queue is loaded,
  kick off a background prefetch coroutine for the next N items. Cancel and
  restart if the queue changes (shuffle, skip, new queue).
- ExoPlayer already buffers aggressively for the *current* track; this is about
  having the *next track's file on disk* before it's needed, so a network drop
  mid-track doesn't stall the transition.
- Do NOT prefetch audiobooks (they're long and the user is unlikely to lose
  signal mid-chapter in a way that matters).
- Cache size cap: 200 MB or 5 tracks, whichever is smaller. Evict oldest entries
  if the cap is hit.

**Update `docs/spec.md`** with an M4.9 entry covering both genre playlists and
the offline prefetch buffer once they're implemented and verified.

---

## Constraints (from AGENTS.md)

- Judge all audio/UI changes on the **real Pixel 8 Pro over wireless ADB**, not
  the emulator. Drive the phone via screenshots + taps, not `uiautomator`.
- Keep the equalizer working in all new playback paths.
- Follow the AGENTS.md git routine: commit each logical step with the agent
  trailer, push, and `git pull` the NAS checkout.
- Update `docs/spec.md` with findings and the final approach after each item.
- Don't claim anything is verified that you couldn't run on the real device.

---

## Context

- Crossfade fixed `ec381c9` — root cause was Emby `PlaySessionId` collision
  (primary and helper sharing one id; Emby keyed the transcode job by that id so
  one stream got replaced). Two-player architecture confirmed viable on Pixel 8
  Pro; two-player output floor test passed in `AudioOutputDiagnosticActivity`.
- Recent plays shipped `be51664` — local session history, all sources, exact
  queue replay via `itemsByIds`, audiobooks excluded.
- App is stable and in daily use. All batch 2 items shipped. Crossfade
  investigation fully documented in `docs/crossfade-investigation.md`.
