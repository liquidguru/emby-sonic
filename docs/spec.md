# Emby Sonic — Project Specification
**Version:** 0.2
**Author:** Kaj Maney
**Status:** Phase 1 & 2 COMPLETE — Phase 3 (Android app "liquidWave") active;
playback, mixes, crossfade, equalizer, search, Track Radio & Sonic Adventure
shipping and verified on a Pixel 8 Pro (real device). See milestone list below.

---

## Overview

Emby Sonic is a fully self-contained Emby plugin + companion Android (and eventually iOS) app that replicates and extends Plex's Sonic Analysis feature set. Instead of relying on genre tags or online metadata, it uses neural audio analysis to map a music library into a multi-dimensional "sonic space," enabling discovery features that feel genuinely intelligent.

**Target user experience:**
1. Install the plugin into Emby
2. Plugin analyses library (one-time scan, then incremental on new imports)
3. Open the Android app, enter server address
4. All features work — no other configuration required

---

## Goals

- Full feature parity with Plexamp Sonic Analysis
- Self-hosted, privacy-first (no data leaves the user's server)
- Open source and community-distributable
- Single install artifact (plugin bundles everything)
- Runs on any machine that runs Emby

---

## Features (Full Scope)

| Feature | Description |
|---|---|
| Sonically similar artists/albums | Related music based on audio characteristics, not genre tags |
| Track radio | Continuous queue of sonically matched tracks from a seed |
| Sonic adventure | Mood-transitioning playlist from track A to track B |
| Mixes for you | Auto-curated, sonically consistent cluster mixes |
| Guest DJs | AI-driven queue injection of sonically similar tracks |
| Library browse | Full artists / albums / tracks browser |
| Now playing | Waveform display, queue management, metadata |

---

## Architecture

### Deployment topology

```
liquidBee (192.168.1.9) — or any Emby host
├── Emby Server (existing)
├── Emby Plugin (C# — config UI + scan trigger, Phase 2 ✅)
└── Emby Sonic Coordinator (FastAPI, :8765 — Phase 1 ✅)
    ├── SQLite — track metadata, analysis state, playlist definitions
    └── FAISS — 128-dim cosine similarity index

Any LAN machine (e.g. liquidHulk w/ RTX 4070)
└── Analysis Worker(s) — claim tracks, stream audio from Emby, embed, report back
```

The coordinator and workers are decoupled: workers can run on the same host or on
any networked machine (including a GPU box). This lets a small Emby server offload
heavy analysis to a more powerful machine on the LAN — without file shares or
special network config, since workers stream audio directly from Emby's HTTP API.

### Layer 1 — Emby (existing)

- Provides: music library, file paths, user authentication, REST API, stream URLs
- No changes required to Emby itself
- Plugin registers as a standard Emby plugin

### Layer 2 — Emby Plugin (C#)

- Minimal C# code — does NOT do audio analysis
- Responsibilities:
  - Register custom API routes inside Emby's HTTP server
  - On install: provision/launch the Python service (see provisioning options below)
  - Pass Emby auth tokens through to Python service
  - Trigger library scan when Emby library updates
- Plugin is the single user-facing install artifact

**Design principle — keep the plugin thin and platform-agnostic.** An Emby
plugin is a managed .NET assembly (IL), so a single `.dll` runs unchanged on
every platform Emby supports — *as long as the plugin contains no native code*.
The plugin must stay a pure proxy; it must NOT bundle the Python service's native
binaries (torch/faiss/numpy are per-OS/per-arch wheels). See provisioning options
in Resolved Decisions.

### Layer 3 — Python Analysis Service (FastAPI)

**Audio analysis pipeline (per track):**
1. Stream audio from Emby via `/Items/{id}/Download` (workers do this; no file share needed)
2. Decode 3×30s windows at 32 kHz mono (librosa; full track never loaded into memory)
3. Extract features (tempo, energy, spectral) on the windowed audio only
4. Embed via **PANNs CNN14** (AudioSet-pretrained CNN, PyTorch) → 2048-dim vector
5. PCA → 128-dim vector (PCA fitted on first full-library batch, saved to `data/pca.pkl`)
6. Store raw (2048-dim) + reduced (128-dim) vectors in SQLite; add to FAISS

**Storage:**
- `SQLite` — track metadata, analysis state (`pending`/`done`/`error`), worker leases, playlist definitions
- `FAISS` — `IndexFlatIP` (cosine) — in-memory, rebuilt from SQLite on startup (DB is source of truth)
- Raw 2048-dim vectors kept in SQLite so PCA can be refitted without re-analysing audio

**Coordinator/Worker split:**
- **Coordinator** (runs on the Emby host): owns SQLite + FAISS, serves all API routes, hands out
  tracks to workers on time-limited leases (default 600s). If a worker crashes, its tracks become
  reclaimable by another worker after the lease expires.
- **Workers** (`worker.py`): stateless; claim a batch → stream + embed → POST results back.
  Workers auto-detect CUDA and use it if available. Run as many as VRAM allows; 5 workers
  fit comfortably in 12 GB (RTX 4070).

**REST API (FastAPI, all under `/sonic`):**

| Endpoint | Method | Auth | Description |
|---|---|---|---|
| `/sonic/status` | GET | Emby user token | Analysis progress + library stats |
| `/sonic/tracks/{id}/similar` | GET | Emby user token | N most sonically similar tracks |
| `/sonic/tracks/{id}/radio` | GET | Emby user token | Track radio playlist (greedy walk) |
| `/sonic/adventure` | POST | Emby user token | Body: `{from_id, to_id, length}` → playlist |
| `/sonic/mixes` | GET | Emby user token | All curated mixes |
| `/sonic/mixes/{id}` | GET | Emby user token | Mix detail + track list |
| `/sonic/queue/inject` | POST | Emby user token | Guest DJ: inject similar tracks |
| `/sonic/artists/{id}/similar` | GET | Emby user token | Sonically similar artists |
| `/sonic/albums/{id}/similar` | GET | Emby user token | Sonically similar albums |
| `/sonic/library/scan` | POST | Emby user token | Trigger full/incremental sync |
| `/sonic/library/build-mixes` | POST | Emby user token | Trigger k-means mix generation |
| `/sonic/worker/claim` | POST | Worker token | Claim a batch of pending tracks |
| `/sonic/worker/results` | POST | Worker token | Submit embeddings for a batch |

**Auth:** User-facing routes validate `X-Emby-Token` against Emby's `/System/Info`
(returns 200 for any valid token, 401 otherwise). **Note:** `/Users/Me` is NOT
usable for this — Emby 4.10 returns 500 for a bare `X-Emby-Token` there (it needs
the full `X-Emby-Authorization` client context). The server `EMBY_API_KEY` is also
accepted directly (short-circuits the call) for admin use and integration testing.
Mobile clients use real Emby user session tokens. CORS is enabled (`allow_origins=["*"]`)
so the Emby dashboard config page can fetch the coordinator cross-origin.
Worker routes validate `X-Worker-Token` against the shared `EMBY_API_KEY`.

### Layer 4 — Android App (Kotlin / Jetpack Compose)

**Screens:**

| Screen | Content |
|---|---|
| Home | Mixes for you, recently played, featured artists |
| Library | Artists / Albums / Tracks tabs |
| Artist detail | Albums, sonic similar artists sidebar |
| Album detail | Track list, sonic similar albums |
| Now playing | Waveform, transport controls, queue, Guest DJ toggle |
| Track radio | Seed track + live-growing queue |
| Sonic adventure | From/to selector + generated playlist |
| Mixes | Auto-curated mix list + mix player |
| Settings | Server address, auth, analysis status |

**Playback:**
- Stream directly from Emby API
- ExoPlayer for playback
- MediaSession for system/notification controls

### Layer 5 — iOS App (Swift / SwiftUI — Phase 4)

- Feature parity with Android
- Consumes identical API
- AVPlayer for playback

---

## Database Schema (SQLite)

```sql
CREATE TABLE tracks (
  id TEXT PRIMARY KEY,          -- Emby item ID
  title TEXT,
  artist TEXT,
  album TEXT,
  duration_ms INTEGER,
  file_path TEXT,
  analysed_at TIMESTAMP,        -- naive UTC
  analysis_version INTEGER,

  -- Distributed worker state
  analysis_status TEXT DEFAULT 'pending',  -- 'pending' | 'done' | 'error'
  claimed_at TIMESTAMP,         -- naive UTC; NULL = unclaimed
  error TEXT                    -- set if status='error'
);

CREATE TABLE embeddings (
  track_id TEXT PRIMARY KEY REFERENCES tracks(id),
  vector BLOB,                  -- 128-dim float32, PCA-reduced (FAISS-indexed)
  raw_vector BLOB,              -- 2048-dim float32 (CNN14 native; kept for PCA refit)
  tempo REAL,
  energy REAL,
  valence REAL,                 -- mood: sad → happy (librosa proxy; Essentia if available)
  arousal REAL,                 -- mood: calm → energetic
  instrumentalness REAL,
  vocals_present INTEGER        -- boolean
);

CREATE TABLE mixes (
  id TEXT PRIMARY KEY,
  name TEXT,
  created_at TIMESTAMP,
  cluster_id INTEGER
);

CREATE TABLE mix_tracks (
  mix_id TEXT REFERENCES mixes(id),
  track_id TEXT REFERENCES tracks(id),
  position INTEGER,
  PRIMARY KEY (mix_id, position)
);
```

---

## Build Phases

### Phase 1 — Python Analysis Service ✅ COMPLETE

**Delivered:**
- FastAPI coordinator with full route set (similarity, radio, adventure, mixes, queue, library, workers)
- PANNs CNN14 embedding pipeline with 3×30s windowed audio decode
- Pipeline-windowing optimisation: librosa features on windows only (6.8s vs 19.9s for long tracks)
- SQLite schema with crash-safe worker lease mechanism
- FAISS `IndexFlatIP` rebuilt from DB on every startup (crash-safe)
- Distributed worker subsystem (`worker.py`) — GPU auto-detect, streams audio from Emby, no file share
- PCA fitted on first full-library batch (`data/pca.pkl`), 2048→128 dim

**Real-world results (liquidBee N100 + liquidHulk RTX 4070, library of 28,316 tracks):**
- 5 GPU workers on liquidHulk, coordinator on liquidBee
- **27,692 tracks embedded** (97.8%) — 608 errors (broken files in Emby library)
- End-to-end validated: claim → stream from Emby → GPU embed → store → FAISS → similarity query
- Similarity quality confirmed: Banco de Gaia → Aes Dana (0.752), Tiësto (0.733) — correct ambient/downtempo neighbourhood from audio alone, no genre tags
- Mixes confirmed: 30 clusters generated, sonically coherent — Mix 1 (jazz/standards: Lee Morgan, Diana Krall), Mix 10 (drum & bass: Nicky Blackmarket), Mix 20 (R&B/pop: Craig David, Leona Lewis), Mix 30 (audiobooks: Robert Jordan, John Gwynne) — genre separation correct from audio alone

**Benchmarks (liquidBee N100, warm model):**

| Stage | Time |
|---|---|
| Audio decode (3×30s windows) | ~6.5s |
| librosa features (on windows) | ~6.8s |
| CNN14 embed (3 windows, GPU) | ~1–2s |
| CNN14 embed (3 windows, CPU) | ~10.7s |
| **Total per track (GPU worker)** | **~10–15s** |

**All Phase 1 items complete.** Mixes are auto-named from each cluster's sonic
character (tempo + energy, graded by terciles over the per-cluster means so names
spread across the mood range) plus a dominant-artist suffix when one artist is
≥35% of the mix; compilation placeholders ("Various Artists", "unknown",
"soundtrack") are ignored. E.g. *Deep & Atmospheric*, *Bright & Breezy · Sarah J.
Maas*. Remaining for future consideration:
- Incremental scan is wired (plugin fires a scan on `ItemAdded`); a webhook/poll
  fallback for non-plugin setups is still optional
- `build_mixes` runs the blocking k-means in the event loop (~15–20s) — could be
  offloaded via `asyncio.to_thread`

### Phase 2 — Emby Plugin (C# wrapper) ✅ COMPLETE

.NET 8 plugin (`plugin/EmbysonicPlugin`) — installs into Emby, provides a
dashboard config page, and triggers scans on library changes.

- [x] Minimal plugin project (`BasePlugin<PluginConfiguration>`, .NET 8)
- [x] Dashboard config page: set coordinator URL, live service status, Save /
      Rebuild Mixes / Trigger Library Scan buttons
- [x] `ServerEntryPoint` subscribes to `ILibraryManager.ItemAdded` → triggers an
      incremental scan on the coordinator
- [x] Coordinator runs as a Windows scheduled task (`EmbySonicCoordinator`,
      at-boot, SYSTEM, auto-restart) — survives reboots
- [x] Packaging: `plugin/build.ps1` builds Release + a versioned release zip;
      `install.ps1` / `install.sh` drop the DLL into Emby's plugins dir and
      restart. (Emby has no custom-catalog/zip-upload API — `/Repositories`
      returns 404; only Emby's own curated `/Packages` catalog exists. So
      dashboard-catalog install would require submitting to Emby itself.)
- [x] Coordinator-only Docker image for NAS users (cross-brand deploy story):
      `Dockerfile.coordinator` + `docker-compose.yml` + `requirements-coordinator.txt`
      (no torch/librosa/panns — proven the coordinator imports and runs the
      `reduce()` path with the ML stack absent; validated in a clean venv with
      only the minimal deps). `python:3.12-slim` + `libgomp1`; data volume for
      SQLite/FAISS. Workers still run the full `requirements.txt` on a CPU/GPU box.

**Emby plugin gotchas (hard-won, 2026-06-07):**
- Emby injects config-page HTML via `innerHTML`, so **inline `<script>` never runs**.
  Config pages must split into two registered `PluginPageInfo`s: an HTML page whose
  root is `<div is="emby-scroller" class="view" data-controller="__plugin/<jsname>">`
  and a separate `*js` **AMD module** (`define([...], function(){ ... return View; })`
  where `View` extends `baseView` and implements `onResume`). See `plugin/Configuration/`.
- Config load/save uses `ApiClient.get/updatePluginConfiguration`; status & action
  calls use `fetch()` to the coordinator with `ApiClient.accessToken()`.
- Plugin DLL goes in `…/Emby-Server/programdata/plugins/` as a **flat file**.
- csproj needs `<FrameworkReference Include="Microsoft.AspNetCore.App" />`; the three
  `MediaBrowser.*.dll` SDK refs live in `plugin/lib/` (gitignored — not redistributable).
- An ASP.NET MVC proxy controller (`SonicController.cs`) was scaffolded but Emby does
  not auto-route plugin MVC controllers; the config page talks to the coordinator
  directly instead. Kept for a possible future client-app proxy.

**Tools:** Claude Code, C# / .NET 8 SDK, Emby Plugin SDK

### Phase 3 — Android App
*Pure UI consuming stable API. In progress (started 2026-06-08). M3 playback complete 2026-06-09; M3.5 playback controls/queue polish complete 2026-06-09; M3.6 Home landing polish complete 2026-06-09; M3.7 mini player complete 2026-06-09; M3.8 audiobook resume complete 2026-06-10; M3.9 Home customization complete 2026-06-10; M3.10 playback control polish complete 2026-06-10; M4.1 sonic mixes list/player complete 2026-06-10; M4.2 mix saving/options/Home complete 2026-06-10; M4.3 per-mix refresh + playlist delete + audiobook exclusion complete 2026-06-10; M4.4 crossfade implementation and six-second on-device listening verification complete 2026-06-11; M4.16 Google Cast Phase 1 active-player switching complete 2026-06-19; M4.17 Cast volume polish complete 2026-06-19; M4.19 Cast UI polish complete 2026-06-20; M4.20 Cast reporting verification complete 2026-06-20; M4.21 sonic-similar detail rails complete 2026-06-20; M4.22 album-art polish complete 2026-06-20.*

Kotlin / Jetpack Compose. The Android app is branded **liquidWave**. Browse/stream/auth go to the **Emby API directly**; all
sonic features go to the **coordinator**. Lives in `android/` inside this repo.
Stack: Compose + Hilt (DI) + Retrofit/OkHttp (two clients: Emby + coordinator) +
Media3 ExoPlayer + DataStore (token/server URL). minSdk 26.

**Milestones:**
- **M1 — Foundation:** scaffold, Hilt, Retrofit clients, DataStore, Settings
  screen, auth flow (Emby `AuthenticateByName` → session token → coordinator
  `/sonic/status` check).
- **M2 — Browse:** Library tabs (Artists/Albums/Tracks via Emby API), Artist
  detail, Album detail + track list.
- **M3 — Playback:** ✅ Now Playing, ExoPlayer (Media3), MediaSession, queue.
  Album/playlist track rows start an authenticated Emby universal audio stream
  (`/Audio/{id}/universal`) and open the Now Playing screen. Universal streaming
  is used instead of direct `/Items/{id}/Download` so Emby can transcode files
  ExoPlayer cannot parse directly, such as WMA/ASF. Transport uses a 96dp
  tap-to-seek progress module behind the `TrackProgress` interface (the future
  waveform slot; see waveform decision).
- **M3.5 — Playback controls:** ✅ Album/playlist leaf screens expose top-bar
  play and shuffle actions; track rows have explicit play buttons. Now Playing
  exposes shuffle and repeat off/all/one, keeps those states in the shared
  `PlaybackController`, and lets queue rows jump directly to a track. Follow-up:
  shuffle is queue-order shuffle, not "start a random song"; leaf track-list
  shuffle visibly reorders the list in place, moves the first visible row too,
  preserves play/pause state, and does not open Now Playing or start playback
  while paused. Collection cards/list rows expose explicit play affordances for
  artists/albums/books/playlists. Library A-Z indexes now derive from the same
  normalized sorted collection list they scroll, so jumps land on the expected
  section. Each displayed letter owns a stable 32dp-wide touch band; tap and drag
  use the same geometry, and the active letter gains a cyan circular highlight
  while dragging. Tapping a bottom library tab from a drill-down detail pops back to the
  existing library root when present, preserving the user's scroll position.
- **M3.6 — Home landing polish:** ✅ Home is now the liquidWave user landing
  screen instead of an analysis/admin status page. It shows scrollable playlist,
  recently added album, and artist sections backed by existing Emby browse data;
  playlist/album/artist tiles drill into the existing detail screens, and tile
  play buttons start playback only after a playable queue is successfully loaded.
  Coordinator analysis progress moved to Settings with a refreshable status card.
- **M3.7 — Mini player:** ✅ The main shell now shows a persistent mini player
  above the bottom navigation whenever a track is loaded and the full Now Playing
  screen is collapsed. The mini player shows artwork, title, artist, progress,
  play/pause, and stop/close; tapping the bar opens Now Playing, while tapping
  the progress strip seeks within the track. Stop clears the ExoPlayer queue,
  removes the mini player, and stops the playback service. The bottom Home tab
  now returns from drill-down detail screens to the existing Home root instead
  of restoring/staying on a stale detail screen.
- **M3.8 — Audiobook resume:** ✅ Playback progress now syncs durable resume
  position back to Emby user data (`Users/{userId}/Items/{itemId}/UserData`) in
  addition to normal session check-ins, because the session endpoints returned
  success on Emby 4.10 but did not persist `PlaybackPositionTicks` for tested
  audiobook audio items. Home now shows a `Resume audiobooks` row sourced from
  audiobook chapters with meaningful `UserData.PlaybackPositionTicks`, grouped
  back to book cards; tapping a tile opens the book, and the play affordance
  resumes from the saved chapter/offset. Home also refreshes the resume row on
  `ON_RESUME`, so returning Home after listening should no longer require the
  manual refresh button. Long-form resumed audio (20+ minutes, including
  single-file MP3/M4B audiobooks) uses Emby's `/Audio/{id}/stream` endpoint with
  `StartTimeTicks` and a 5-second pre-roll, while normal playback still uses
  `/Audio/{id}/universal`. This matters because `/universal` ignored
  `StartTimeTicks` on tested long files and could make the app counter appear
  resumed while audio started from the beginning. `/stream` was verified to emit
  non-intro audio for both a resumed 18h MP3 (`The Bladed Faith`) and a resumed
  26h M4B (`The Eye of the Bedlam Bride`), and user-confirmed the installed app
  resumed correctly.
- **M3.9 — Home customization:** ✅ Home now has a customize sheet opened from
  the top-bar tune icon. Users can switch between regular and small Home cards,
  choose which Home sections are visible, and reorder sections with up/down
  controls. Preferences persist in DataStore (`home_compact_cards`,
  `home_section_order`, `home_hidden_sections`) and apply to the existing
  sections: Resume audiobooks, Playlists, Recently added albums, and Artists.
- **M3.10 — Playback control polish:** ✅ Now Playing's top-bar queue action is
  wired to focus the queue tab/list, and the top-bar close action stops playback
  and clears the queue. Queue row taps jump to non-current tracks without
  restarting the current row. Repeat mode now persists through DataStore
  (`playback_repeat_mode`) and is restored into Media3 on controller startup.
  The mini player now exposes previous and next controls alongside play/pause
  and stop, so collapsed playback can be controlled without opening Now Playing.
- **M4.1 — Sonic mixes list/player:** ✅ The Mixes tab now has a real `Mixes`
  sub-tab backed by the coordinator's `/sonic/mixes` and `/sonic/mixes/{id}`
  endpoints. Mix summaries show generated names and track counts, rows open an
  in-place detail view, and play buttons queue the coordinator's Emby track ids
  through the existing `PlaybackController`. Track artwork is currently absent
  because the coordinator response only returns track metadata; placeholders are
  expected until a richer Emby hydration step or coordinator artwork field is
  added.
- **M4.2 — Mix saving/options/Home:** ✅ Mix names are displayed with cleaned
  title text on the first line and stable metadata on the second line
  (`Mix N • track count`) so duplicate generated names are distinguishable
  without relying on truncated suffixes. Starting a new playback queue resets
  repeat mode to off so repeat-one/all does not leak from the previous session.
  The Mixes options sheet exposes a `tracks_per_mix` selector (25/50/75/100) and
  a global regenerate action backed by `/sonic/library/build-mixes`; this
  coordinator endpoint still replaces all mixes. Mix detail screens can save the
  current generated mix as a named Emby playlist before regeneration. After save,
  the Playlists tab refreshes and the new playlist appears server-side. Home now
  has a `Sonic mixes` row backed by coordinator mixes, with play buttons and Home
  customization visibility/reorder controls alongside the existing Playlists row.
- **M4.3 — Per-mix refresh + playlist delete + audiobook exclusion:** ✅
  (verified 2026-06-10). `Mix.centroid` (128-dim float32 BLOB) is stored per mix
  during `build_mixes`, enabling `POST /sonic/mixes/{id}/regenerate` to refresh a
  single mix without touching the others. Refresh semantics are a *full
  turnover*: it excludes the mix's current tracks, then weighted-samples
  `tracks_per_mix` new tracks from a pool of the closest remaining matches
  (softmax temperature scaled by the pool's score spread — the embeddings are
  not unit-normalised, so raw scores are unbounded dot products), and shuffles
  the result order. Consecutive refreshes share 0% of tracks; earlier tracks can
  return in later refreshes. Tunable via `refresh_temperature` /
  `refresh_pool_min` / `refresh_pool_multiplier`. Spoken-word content is excluded
  from all mixes (build and refresh) via `is_mix_excluded()` —
  `mix_exclude_path_markers` (`\Videos\Audio\`) plus `mix_exclude_extensions`
  (`.m4b`, which catches audiobooks/radio dramas misfiled under `\music\BBC\`).
  Android: mix detail gains a Refresh action with a track-count dialog (state in
  the ViewModel so it survives reopen); Playlists tab gains per-item delete
  (Emby playlists only, with confirmation) via `DELETE /Items/{itemId}`.
- **M4.4 — Crossfade (music only):** ✅ implementation complete; six-second
  overlap verified on-device across consecutive transitions (2026-06-11).
  Settings toggle
  + overlap duration (3/6/9/12s, default 6s), persisted via DataStore
  (`crossfade_enabled` / `crossfade_duration_ms`, surfaced on `AppSettings`).
  Engine in `PlaybackController`: the existing single `player` stays the sole
  queue + MediaSession player (when crossfade is off the path is byte-for-byte
  unchanged). A secondary `fadePlayer` plays the outgoing track's tail while the
  primary advances early, with equal-power-style volume ramps. Two-phase:
  *arm* opens the normal Emby source in an independent playback session, seeks
  to the outgoing tail, and
  buffers it paused 12s ahead; *fire* occurs only once that helper is ready and
  the configured blend point is reached. The incoming ramp waits for the primary
  decoder to become ready, and late helper preparation falls back to the normal
  transition instead of muting playback. The helper is explicitly paused,
  stopped, cleared, and reset before every preload because ExoPlayer `stop()`
  retains `playWhenReady`. A 50ms trigger poll keeps tail replay below the
  previously audible quarter-second range. Never applies to audiobooks/long-form
  (either side), repeat-one, the last track, or resumed/offset streams; cancelled
  cleanly on pause/skip/seek/shuffle/stop/new-queue. The incoming curve uses an
  18% starting floor and a 0.5 exponent so it remains audible beneath louder
  outgoing material, including longer fades. Also: stopping playback now clears
  a *music* track's resume position both locally and in the Emby stopped report
  (starts fresh next time) while audiobooks keep their resume point. Pausing is
  distinct and preserves resume. Open items: verify 3/9-second overlaps on the emulator, test pause/skip/seek/stop
  during an overlap, tune the preload window for slow networks, and optionally
  hold the Now Playing label until the blend completes (it currently flips to
  the next track approximately one overlap-duration early).
- **M4.5 — Playback correctness hardening (in progress, started 2026-06-13):**
  Fixes from the 2026-06-13 comprehensive review. Phase 1 (HIGH, implemented,
  pending device verification):
  - *No durable resume for music* (product decision): progress reports no
    longer write `UserData` positions for non-long-form tracks, and stored
    positions are ignored when starting music playback. Previously every
    3-second progress sync wrote a position, and a crossfade handoff (which
    fires up to the fade duration before the end — outside the 5s end padding
    for 6/9/12s fades) left a near-end resume on every transition, so tracks
    could restart mid-song or in their final seconds. Skips are skips; pause
    and Android Auto/cast continuity live in the session, not the server.
  - *Completion marks Played*: a track that reaches its end (or crossfades
    out — fade duration + 1s slack counts as completion) gets `UserData`
    `{position: 0, Played: true}`. The old code stamped `Played: false` every
    3 seconds, wiping played status server-wide and making finished audiobook
    chapters indistinguishable from unstarted ones — stopping near a chapter
    boundary resumed the book at chapter 1. `resumeStartItem()` now falls back
    to the first unplayed chapter after the last played one (LibraryItem
    carries `played`).
  - *Audio focus, becoming-noisy, wake mode*: the primary player now requests
    audio focus (`USAGE_MEDIA`, handleAudioFocus=true), pauses on headphone
    unplug/BT drop, and holds `WAKE_MODE_NETWORK` (+ `WAKE_LOCK` permission)
    so screen-off streaming doesn't stall. The crossfade helper uses the same
    attributes with handleAudioFocus=**false** — both players must sound at
    once during a blend.
  - *Listener-based crossfade cancellation*: pause/seek from the media
    notification, Bluetooth controls, or focus loss call `player.pause()`/
    seek directly, bypassing the in-app wrappers — the crossfade was not
    cancelled. A `Player.Listener` now cancels on `playWhenReady=false` and on
    any external SEEK discontinuity (the fire's own `seekToNextMediaItem` is
    recognised via `crossfadeTargetIndex` and ignored).
  - *Seek on transcoded tracks restarted at 0:00 / played wrong audio*
    (user-reported 2026-06-13, reproduced on emulator with R.E.M.
    "Electrolite", WMA). Two stacked causes, both fixed:
    (1) Emby serves transcodes as chunked streams of unknown length, ExoPlayer
    marks the window unseekable, and an in-player seek on unseekable media
    restarts at zero → `seekTo` now routes unseekable-READY tracks through the
    server-side `/stream?StartTimeTicks=` path (`seekViaStreamOffset`, the
    generalised long-form seek).
    (2) **Emby keys transcode jobs by `PlaySessionId`** — re-requesting
    `/stream` with a new `StartTimeTicks` but the same session id returns the
    already-running job, so audio continues from the old position while the
    counter shows the seek target. Server-side seeks now mint a fresh
    `PlaySessionId` per seek (verified against Emby 4.10 with fresh-session
    curl + ffprobe for wma/mp3/m4b sources; same-session requests provably
    returned the old job). This latent bug also affected in-chapter audiobook
    seeking; audiobook *resume* always worked because a new queue mints a new
    session id. Beware when testing with curl: requests without a
    `PlaySessionId` can also reuse jobs and poison A/B comparisons.
  - *Media notification never appeared in the shade* (user-reported
    2026-06-13; pre-existing — the in-app mini-player masked it). Root cause:
    the app injects one `ExoPlayer` singleton into both the UI and the
    `MediaSessionService` and the UI drives that player **directly**, so no
    `MediaController` ever connected to the service — and Media3 only starts
    the foreground media notification (and the foreground service that keeps
    background playback alive) once a controller connects. Verified on
    emulator: `startForegroundCount` stayed 0 through play/pause/media-key
    transitions. Fix: `PlaybackController` now lazily connects its own
    `MediaController` to `SonicPlaybackService` on play (`connectNotification
    Controller`) and releases it on stop. The controller issues no commands —
    its presence activates the notification lifecycle. After the fix:
    `isForeground=true`, a MediaStyle notification (id 1001, transport
    category) shows with art/transport/seek, tapping it opens the app, and
    Stop tears the service + notification down cleanly. POST_NOTIFICATIONS
    runtime request and `setSessionActivity` (notification → app) were
    necessary too but not sufficient on their own.
  - Also fixed while verifying: library tab selection now survives popping
    back from a detail screen (`rememberSaveable`), and each library tab owns
    its scroll state via `SaveableStateProvider` (Artists/Albums no longer
    share one scroll offset).
  Phase 2 (MEDIUM, done 2026-06-13, verified on emulator):
  - *Home degrades per-section instead of all-or-nothing.* A coordinator
    outage (`/sonic/mixes`) used to blank the whole Home screen with "Failed
    to connect to …:8765" because `refresh()` wrapped all six fetches in one
    `runCatching`. Now each section loads independently: the Emby rows fetch
    in parallel (`async`) and render first; the coordinator-backed Sonic mixes
    row loads *afterwards* so a slow/dead coordinator never gates or blanks the
    rest of Home. A full-screen error shows only when nothing at all is
    reachable (Emby down). Verified: with the coordinator stopped, Home still
    shows playlists/albums/artists/resume rows and appears fast.
  - *Home fetch weight*: `artists()`/`playlists()` take an optional server-side
    `limit`; Home passes `HOME_SECTION_LIMIT` (12) instead of pulling up to
    10 000 rows to show 12.
  - *Mixes play gating + errors*: `PlaylistsViewModel` now exposes
    `openNowPlaying`/`messages` channels; playing a mix or playlist opens Now
    Playing only once a playable queue loads, and failures show a snackbar
    instead of dropping the user on an empty player.
  - *Mixes back + list cache*: a `BackHandler` closes an open mix detail back
    to the list (instead of leaving the Mixes tab), and the last-loaded mix
    list is cached so backing out restores it instantly rather than refetching.
  - *Refresh dialog copy*: now "Replace this mix with a fresh set of similar
    tracks" — matches the implemented full-turnover semantics (product
    decision confirmed: full turnover is intended).
  - *Now Playing queue toggle*: the top-bar queue button now toggles — tap to
    scroll down to the track list, tap again to scroll back up to the player
    hero. Previously, once scrolled to the queue the only top-bar buttons were
    Stop (X) and Collapse (down), so the player view felt unreachable.
  - *Crossfade source compatibility* (updated 2026-06-15): direct-play MP3 and
    Emby-transcoded WMA/ASF both blend on the Pixel 8 Pro when each player owns
    an independent Emby `PlaySessionId`. If a helper cannot seek/buffer its
    tail in time, the existing readiness guard still preserves the normal
    transition instead of advancing early.
  - *Mix artwork hydration*: coordinator track lists carry no images, so sonic
    mixes showed grey placeholders everywhere (mix detail, Now Playing big art,
    mini player, queue). Mix tracks now resolve their Emby Primary cover in one
    batched `/Items?Ids=` query (`LibraryRepository.artworkByIds`, applied in
    the mix detail/play paths). Verified on emulator.
  - *Mix tile/row covers + build polling* (coordinator + Android, deployed
    2026-06-13). Coordinator: `MixOut` gains `cover_track_id` (the position-0
    track), set in list/detail/regenerate; `list_mixes` now does counts +
    covers in two grouped queries (removed the N+1). New
    `GET /sonic/library/build-state {running}`. `build_mixes` offloads the
    blocking k-means/selection/naming to `asyncio.to_thread` so the event loop
    stays responsive (without it, the build-state poll can't be answered while
    a build runs). Android: mix list rows and the Home Sonic-mixes row hydrate
    a cover from `cover_track_id` (falls back to the GraphicEq icon when the
    track has no resolvable art); `generateSonicMixes` now polls build-state
    (grace window for start, capped at 3 min) instead of a fixed `delay(25s)`.
    Verified on emulator: list + Home covers render; build-state endpoint
    returns `{running:false}`. Poll *cycle* not live-tested (won't trigger a
    destructive full rebuild that replaces all the user's mixes).
  - *Recent plays Home row* (2026-06-13): new `RECENT_PLAYS` Home section —
    recently played music grouped back to albums
    (`LibraryRepository.recentlyPlayedAlbums`: Emby `SortBy=DatePlayed` +
    `Filters=IsPlayed` over a 100-track window, deduped to albums, album cover
    resolved). Tapping opens the album; play plays it. Customizable/reorderable
    like the other sections. Verified on emulator. Note: `Filters=IsPlayed`
    only catches fully-completed tracks, so the row reflects finished listens
    (matches the H3 Played-on-completion behavior). New sections append to the
    end of an existing saved section order, so it lands last for users who
    already customized Home (default position is 2nd for fresh installs).
  - *Stations row on Home (pass 1: metadata radios, 2026-06-13)*. A "Stations"
    row at the top of Home with three tap-to-play radios:
    **Library Radio** (`SortBy=Random` over the whole library),
    **Random Album Radio** (a handful of random albums played start-to-finish),
    and **Decade Radio** (a square decade-tile picker → `Years=` filter +
    Random). Builders in `LibraryRepository` (libraryRadio/randomAlbumRadio/
    decadeRadio); `HomeViewModel.playStation` builds the queue and opens Now
    Playing; failures snackbar. Verified on emulator: each produces a fresh
    queue. **Deep Cuts dropped** — `SortBy=PlayCount` and `Filters=IsUnplayed`
    both throw a SQLite 500 on this Emby 4.10, so least-played isn't queryable.
  - *Track Radio (Stations pass 2, 2026-06-13)*. The Now Playing "Radio" tab
    now generates a live sonic radio from the current track via the coordinator
    `/sonic/tracks/{id}/radio` (`NowPlayingViewModel` RadioState; auto-loads on
    tab open and when the seed track changes; "Play radio" / per-track play /
    "New radio"; artwork hydrated). The coordinator track→LibraryItem mapping
    (previously duplicated in Home + Playlists VMs) was consolidated into
    `data/coordinator/CoordinatorMappers.kt`. Verified: radio loads and plays.
  - *Crossfade helper lifecycle fix*. The `fadePlayer` (second ExoPlayer) was
    created once and kept alive forever, permanently holding a second decoder;
    on the resource-starved emulator this contributed to codec exhaustion
    ("required system resources: 6" / dead MediaCodec thread → silent-but-
    advancing playback). It's now created per-crossfade and fully `release()`d
    when the blend ends/cancels, so normal single-track playback holds one
    decoder. Also: `setQueue` resets `player.volume = 1f` to self-heal any
    stuck-silent state. Crossfade cycle re-verified on emulator (arm → ready →
    fire → ramp). NOTE: the silent-playback incident itself looked like
    emulator software-codec failure (audio focus was held; ExoPlayer raised no
    error) — retest on real hardware.
  - *Artwork cross-dissolve during crossfade (2026-06-13)*. Now Playing and the
    mini player cross-dissolve the album art (and Now Playing's title/artist)
    from the outgoing to the incoming track instead of hard-cutting. The current
    `crossfadeOutgoingAlpha` implementation snapshots the incoming position when
    the composable enters a blend, then finishes with a wall-clock tween; it does
    not continuously follow playback or pause while the incoming decoder buffers.
    PlaybackController publishes `crossfadeFromTrack` + `crossfadeBlendMs`. Only
    active only when a real blend fires (music with a helper-ready source).
    Now Playing verified on-device-good by Kaj; **mini-player dissolve pending
    real-device confirmation** (emulator silence during blends confounds it, and
    the mini player is only visible when not on Now Playing, so it must be
    watched while browsing during a natural transition). Supersedes the old
    "hold the Now Playing label" item.
  - *Seek-into-tail no longer fires a crossfade (2026-06-13)*. Manually seeking
    into a track's blend window has no runway for the helper to preload and was
    force-firing a glitchy blend (the trigger for a reproducible silent-playback
    on the emulator). `seekTo` now suppresses crossfade for a track seeked into
    its tail (`suppressCrossfadeIndex`), re-enabled on seeking back out / new
    queue. Correct behaviour regardless of platform.
  - **Emulator audio ceiling reached (2026-06-13).** Repeated silent-but-
    advancing playback during/after crossfades traced to the emulator's software
    codecs failing under crossfade's two simultaneous decoders ("required system
    resources" / dead MediaCodec thread; audio focus was held, ExoPlayer raised
    no error). Not fixable in app code. Crossfade-dependent behaviour (blend
    quality, artwork dissolves, no-dropout) must be validated on real hardware.
    Non-crossfade features can still be emulator-tested with crossfade OFF.
  - *Real-device verification (2026-06-13).* liquidWave installed on Kaj's
    **Pixel 8 Pro** (wireless adb). Quick pass: audio quality and crossfade
    confirmed excellent on real hardware — vindicates the "emulator codec
    ceiling" diagnosis. Ongoing real-device checks: mini-player dissolve during
    a natural transition, focus handling, media notification.
  - *Crossfade regression root cause and fix (2026-06-15, verified Pixel 8
    Pro).* The earlier diagnosis that Android allowed only one of the two
    ExoPlayers to reach the speaker was disproven by a debug-only floor test:
    two bare players were simultaneously audible with separate sessions,
    primary-only focus, and a shared Android audio session. The production
    engine also blended MP3→MP3 correctly with EQ/session handling intact,
    including screen-off playback. The reproducible failure was Emby session
    ownership: every primary queue item and the helper reused one
    `PlaySessionId`, while Emby keys transcode jobs by that id. WMA/ASF→MP3
    could therefore hand the incoming player bytes from the wrong server job,
    producing `UnrecognizedInputFormatException` after the primary advanced
    early. `PlaybackController` now mints a `PlaySessionId` per primary queue
    item (and uses it for that item's playback reports), refreshes it when an
    item is replaced/server-seeked, and gives each helper request its own id.
    WMA→MP3 and MP3→MP3 six-second blends both reached incoming READY in 20ms,
    completed without source errors, and were user-confirmed audible. Keep the
    two-player architecture: it is proven on target hardware and keeps the
    existing shared Android audio session/EQ path. A custom mixing
    `AudioProcessor`/`AudioSink` would require a multi-decoder playback-engine
    rewrite; Media3 composition mixing is not a low-risk replacement for the
    interactive MediaSession queue. Full evidence and the repeatable debug
    harness are in `docs/crossfade-investigation.md`.
  Phase 2 remaining (queued):
  - Guest DJ toggle (Now Playing) is currently a disabled placeholder — wire it
    to `/sonic/queue/inject` (inject similar tracks into the live queue) or hide
    it until implemented. Distinct from Track Radio (augments the queue rather
    than replacing it).
  - *Equalizer (built 2026-06-14, verified on Pixel 8 Pro).* In-app graphic EQ
    via `android.media.audiofx.Equalizer`. Both ExoPlayers share one audio
    session (`generateAudioSessionId` set on primary + fade helper) so a single
    Equalizer covers normal playback and crossfade blends. `AudioEffects
    Controller` (singleton) owns the effect, exposes `EqualizerState` (bands,
    level range, presets, enabled), persists enabled + per-band millibel levels
    in DataStore, and is robust to devices without an effects impl (UI shows
    "not available"). UI: Settings → Equalizer (enable toggle, system presets,
    per-band sliders with dB readout, Flat reset). Also broadcasts
    `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` so external EQ apps (Wavelet,
    system EQ) can attach. Follow-ups if wanted: BassBoost / LoudnessEnhancer,
    a vertical-slider layout.
  - *Review hardening batch 1 (2026-06-14).* Every primary playback-start path
    now starts/connects the MediaSession foreground service before `player.play()`,
    including prepared shuffle queues and queue-row starts. Crossfade's widened
    Played threshold is granted only to the specific outgoing item when a blend
    actually fires; otherwise the normal 5-second completion padding applies.
    Search caches library discovery only after a successful response, so transient
    failures remain retryable instead of making later searches unscoped. Equalizer
    sliders still update the live effect continuously, but persist band levels to
    DataStore only when the drag finishes.
  - Accepted product ideas: audiobook playback speed, sleep timer, Android Auto
    browse tree, Chromecast/Google Cast output, home-screen Now Playing widget,
    drag scrubbing, queue reorder, swipe-to-dismiss mini player, small offline
    cache.
  - "On This Day" — needs a play-history log (Emby only stores last-played per
    item), i.e. a new coordinator subsystem. Deferred.
  - Accepted product ideas: audiobook playback speed, sleep timer, Android Auto
    browse tree, Chromecast/Google Cast output, home-screen Now Playing widget,
    drag scrubbing, queue reorder, swipe-to-dismiss mini player, small offline
    cache.
  - (Done earlier in M4.5 phase 1: POST_NOTIFICATIONS runtime request,
    `setSessionActivity`, per-tab lazy-list state.)
- **M4.6 — Equalizer (2026-06-14, verified Pixel 8 Pro):** in-app graphic EQ
  via `android.media.audiofx.Equalizer`. Both ExoPlayers share one audio session
  (the Android effect session, distinct from Emby's per-stream
  `PlaySessionId`) so the EQ covers playback and crossfade blends;
  `AudioEffectsController`
  (singleton) owns the effect, persists enabled + per-band levels, and broadcasts
  the audio session so external EQ apps (Wavelet) can attach. UI at
  Settings → Equalizer (toggle, system presets, per-band sliders, Flat reset).
- **M4.7 — Search + Track Radio + Sonic Adventure (2026-06-14):**
  - *Track Radio:* Now Playing "Radio" tab generates a live sonic radio from the
    current track (`/sonic/tracks/{id}/radio`).
  - *Search:* reusable debounced Emby search. Music tab → Tracks/Albums/Artists;
    Audiobooks tab → Books/Authors; Home search icon → all five. Library-scoped
    per kind; the long-dead Search button is now wired.
  - *Sonic Adventure:* A→B journey screen from the Home Stations strip
    (`/sonic/adventure`). Endpoints via search sheet (Start defaults to
    now-playing); bookended with the chosen tracks, de-duped by title+artist,
    and length-compensated (over-request + even sample) so it lands on the chosen
    length and ends on B. **Coordinator fix:** `build_adventure` now includes the
    start/end tracks (was excluding both).
  - *Stations:* Home strip (Library / Random Album / Decade radios + Sonic
    Adventure), horizontally scrollable.
  - *Recent plays:* Home row of recently played albums.
  - *Nav fix:* bottom-nav tabs always land on their section root; Search/Adventure
    overlays are not restored by a tab.
- **M4.8 — Recent plays = local session history (2026-06-14, verified Pixel 8
  Pro):** the Home "Recent plays" row changed from Emby `DatePlayed`-derived
  albums to a **local play-history of full queues**, because Emby only records
  last-played *per track* and can't attribute a track to the playlist/mix/
  adventure it was played as part of. New `RecentPlaysRepository` (DataStore JSON,
  capped 20, de-duped by source key, most-recent-first) stores each session as
  `{key, title, subtitle, coverUrl, trackIds, timestampMs}`. `PlaybackController`
  records on every queue start via a new optional `PlaybackSource` arg, **skipping
  audiobooks** by content kind (so the exclusion is centralized, not per-call-site).
  Sources are tagged at every start path — album/artist/playlist (via shared
  `playbackSourceFor(DetailKind, …)`), sonic mix, Sonic Adventure, Track Radio,
  Stations, and single-track search. The row is **live** (a new
  `observeRecentPlays` collector updates it without a manual refresh). Tapping a
  tile (or its play button) replays the **exact stored queue** —
  `LibraryRepository.itemsByIds` re-hydrates the track ids in order — which
  preserves generated radio/adventure queues instead of regenerating different
  ones. Note: the row starts empty on first install (history begins now); the old
  `recentlyPlayedAlbums` helper is no longer used by Home. This is the first piece
  of the play-history subsystem the spec deferred for "On This Day".
- **M4.9 — Genre playlists + offline prefetch buffer (2026-06-18,
  implementation built and installed on Pixel 8 Pro):**
  - *Bluetooth/AVRCP duration fix:* `PlaybackController` now publishes positive
    Emby `RunTimeTicks` durations into the Media3 `MediaMetadata` via
    `setDurationMs()`. If a track arrives without a non-zero duration, the app
    logs a warning and omits the metadata duration rather than exporting a wrong
    zero. Phone Now Playing already had correct duration; this targets external
    Bluetooth displays/head units that read duration from the `MediaSession`.
    A diagnostic Pixel Bluetooth dump confirmed Android was advertising non-zero
    durations for liquidWave (`duration=196728` for the active item, with queue
    durations populated). The Triumph Tiger display still showed `0:00`, and the
    same symptom then reproduced with other media apps, so the remaining field
    issue appears to be in the Tiger/display/Bluetooth route rather than
    liquidWave's `MediaSession` export.
  - *Genre mixes/playlists:* live Emby probing confirmed music genres come from
    `/Genres?UserId=...&ParentId=<musicLibraryId>&Recursive=true&IncludeItemTypes=Audio`,
    and playable tracks come from `/Items?...&ParentId=<musicLibraryId>&GenreIds=<genreId>`.
    Android adds a Music library `Genres` tab plus a Home `Genres` station card
    that opens a genre picker. Selecting a genre opens a generated genre-mix
    detail screen rather than dumping the whole tag: `/Items` is queried with
    `SortBy=Random` and the same persisted track-count choice as Sonic mixes
    (25/50/75/100, default/current 25), surfaced in Settings under "Generated
    mixes". The generated list plays through
    `PlaybackController.playQueue()`,
    excludes audiobooks by scoping to the music library, supports the same
    leaf-screen shuffle path, can be refreshed at a chosen count, and can be saved
    as an Emby playlist. Recent plays records the exact generated list under
    `PlaybackSource("genre:<genreName>", ...)`, so replay uses `itemsByIds` and
    does not re-randomize.
  - *Offline prefetch buffer:* while a music queue is active, `PlaybackController`
    downloads the next 3 non-long-form tracks to a private cache under
    `context.cacheDir/liquidwave-prefetch`. Cache keys include Emby item id plus
    the current universal-stream quality key; cap is 5 files or 200 MB, with LRU
    eviction and played-past entries deleted as the queue advances. Prefetched
    files are swapped into future ExoPlayer media items as local `file://` URIs;
    playback now uses Media3 `DefaultDataSource.Factory` so both authenticated
    HTTP streams and local cache files work. Audiobooks/long-form chapters are
    never prefetched.
  - Verification so far: `./gradlew :app:assembleDebug` passes on liquidHulk,
    and the cleaned debug build (without the temporary legacy AVRCP diagnostic
    session) was installed on Kaj's Pixel 8 Pro over wireless ADB. Earlier Pixel
    interaction showed the Home Stations row with the new `Genres` card visible
    and the generated genre list layout with Save/Refresh at the top. Kaj
    verified the offline prefetch behavior on-device: after a queue had time to
    pre-buffer, playback continued cleanly through a network interruption. Kaj
    also checked the remaining genre flow on-device: generated-count behavior,
    save-as-playlist, and Recent plays replay of a stored genre queue all looked
    good.
- **M4.10 — Playlist item removal (2026-06-18, built + endpoint verified):**
  Emby playlist track rows now expose a per-track overflow action, "Remove from
  playlist", with confirmation. This removes only the stored playlist entry, not
  the underlying library song. Implementation preserves `PlaylistItemId` from
  `GET /Playlists/{Id}/Items` on playlist-track `LibraryItem`s and calls
  `DELETE /Playlists/{Id}/Items?EntryIds=<PlaylistItemId>`. The row is removed
  from the visible list after a successful server response. Endpoint behavior was
  verified against Emby 4.10 using a temporary playlist: count changed from 3 to
  2 and the selected `PlaylistItemId` disappeared; the temp playlist was deleted.
  `./gradlew :app:assembleDebug` passes and the debug build was installed on the
  Pixel 8 Pro.
- **M4.11 — Sleep timer, audiobook speed, Android Auto browse tree
  (2026-06-18, implementation built and installed on Pixel 8 Pro):**
  - *Sleep timer:* Now Playing has a timer action with 5/10/15/30/45/60 minute
    options for all playback and an extra "End of chapter" option for
    audiobooks. Active timers show a compact countdown/status chip below the
    progress control; tapping the chip cancels the timer. Timers are in-memory
    only and are cleared by manual pause, skip, seek, stop, or loading a new
    queue. When the timer fires, `PlaybackController` fades ExoPlayer volume to
    zero over about 3 seconds, pauses playback, restores volume for the next
    session, and clears the chip. The timed 5-minute path was verified on Kaj's
    Pixel 8 Pro: the chip counted down, music faded out, and playback ended
    paused/stopped without closing Now Playing.
  - *Audiobook speed:* audiobook queues now use a persisted DataStore playback
    speed (`0.75x`, `1x`, `1.25x`, `1.5x`, `1.75x`, `2x`) applied through
    Media3 `PlaybackParameters(speed, 1f)` so pitch is preserved. The speed chip
    appears only for `ContentKind.AUDIOBOOK`; music playback always resets to
    `1x`. Kaj verified the audiobook speed UI and playback behavior on the
    Pixel 8 Pro after installing the debug build.
  - *Android Auto:* `SonicPlaybackService` is now a `MediaLibraryService` while
    retaining the normal phone `MediaSession` behavior. The Auto browse root
    exposes Recent plays, Sonic Mixes, Albums, Artists, and Audiobooks. The
    Audiobooks branch contains Resume audiobooks, Books, and Authors, and book
    starts use the same resume-aware chapter selection as the phone UI. Recent
    plays replay the stored exact queue via `itemsByIds`; mixes load coordinator
    mix detail; albums/artists/books/authors hydrate their queues and start
    playback through the same `PlaybackController.playQueue()` path, preserving
    equalizer and audiobook behavior. The app also declares the automotive media
    descriptor at `@xml/automotive_app_desc`. Verification so far: the debug
    build passes, the APK is installed on the Pixel, and Android's installed
    package dump resolves `SonicPlaybackService` for
    `androidx.media3.session.MediaSessionService`,
    `androidx.media3.session.MediaLibraryService`, and the platform
    `android.media.browse.MediaBrowserService` action required by Android Auto
    media-app discovery. This was added after a car test showed audio routing
    worked but the app/player surface was not visible in Android Auto. A follow-up
    car test confirmed the app appears; a later build added the Audiobooks branch
    after the car only showed music choices. Final car verification confirmed
    audiobook resume works from Android Auto and the car progress bar reports the
    absolute book position after the `AvrcpDurationPlayer` session wrapper began
    exporting stream-offset-corrected position and buffered-position values.
- **Cast roadmap note (accepted 2026-06-18; Phase 0/1 implemented
  2026-06-19):** Chromecast/Google Cast output is separate from
  Bluetooth/AVRCP and Android Auto. Phase 1 now uses the Media3 Cast extension /
  `CastPlayer` and switches playback between local ExoPlayer and remote Cast
  playback. Because Cast devices fetch and decode the stream themselves, cast
  URLs use LAN-reachable Emby endpoints with query-param auth. Local-only
  features such as in-app Equalizer, crossfade, and offline prefetch are treated
  as unavailable while casting unless explicitly reworked.
- **Widget roadmap note (accepted 2026-06-18):** an Android home-screen widget is
  accepted as a future liquidWave feature. The first version should be a Now
  Playing widget with artwork, title/artist, play/pause, previous/next, and a tap
  target that opens Now Playing. A compact one-row mini-player variant is the
  preferred initial shape. Widget commands should route through the same
  MediaSession/PlaybackController path as the notification and app UI so normal
  playback behavior stays consistent. Later extensions can add Recent
  plays/mix/genre shortcuts, sleep timer status, or audiobook-focused controls.
- **M4.12 — Guest DJ queue injection (2026-06-19, built + Pixel verified):**
  The Now Playing Guest DJ row is now a real switch instead of a disabled
  placeholder. It is a music queue extender, not a replacement radio mode: when
  enabled, `PlaybackController` watches the current queue and, once fewer than
  three upcoming tracks remain, calls `/sonic/queue/inject` with the current
  track id and appends up to five deduped, artwork-hydrated similar tracks.
  Existing injected tracks stay in the queue when the switch is turned off, but
  no further injections run. Guest DJ is disabled for audiobooks/long-form
  playback and is also disabled while repeat-all or repeat-one is active; turning
  repeat on while Guest DJ is enabled immediately turns Guest DJ off. Verification
  on the Pixel 8 Pro: the switch enabled on a music queue, similar tracks were
  appended near the end, and enabling repeat disabled the switch with the
  explanatory "Turn repeat off to use Guest DJ" row text.
- **M4.13 — Stations grid on Home (2026-06-19, built + Pixel verified):** the
  Home "Stations" section no longer hides tiles behind a horizontal scroll. The
  scrolling `Row` is now a `FlowRow` capped at three tiles per row, so all five
  stations (Library Radio, Random Album, Decade Radio, Genres, Sonic Adventure)
  are visible at once in a 3 + 2 grid. Station cards shrank 116dp → 108dp so
  three fit cleanly within the Pixel 8 Pro's usable width, and the grid is padded
  to 20dp to align with the section title. Verified on the Pixel 8 Pro: all five
  tiles show without swiping and each still launches its station/picker.
- **M4.14 — Now Playing home-screen widget (2026-06-19, built + Pixel verified):**
  a compact one-row mini-player widget (`RemoteViews`/`AppWidgetProvider`, not
  Glance). It shows artwork, title, and artist with previous / play-pause / next
  controls; tapping the body opens the app. Display is driven from
  `PlaybackController.state` via a collector that only re-renders when the
  widget-relevant fields change (not on every position tick) and caches the
  Coil-decoded artwork bitmap so a play/pause toggle doesn't reload it. Button
  taps broadcast to `NowPlayingWidgetProvider`, which reaches the singleton
  `PlaybackController` through a Hilt `EntryPoint` and calls the same
  `togglePlayPause()` / `skipNext()` / `skipPrevious()` the in-app UI and media
  notification use. When nothing is loaded the widget shows "liquidWave / Tap to
  open" with the transport controls hidden. Idle/cold-start playback is out of
  scope for v1 (controls act on an existing session). Verified on the Pixel 8 Pro.
  - *Prefetch backward-skip fix (surfaced by the widget, also fixed in-app):*
    the offline prefetch cache swapped each track's `MediaItem` to a local cache
    file once downloaded, but deleting those files (behind-anchor cleanup in
    `schedulePrefetch` and size-cap `evictIfNeeded`) left the `MediaItem`s
    pointing at missing files. Skipping **back** opened a deleted file →
    `ENOENT` → `Source error` → the player jammed in `STATE_ERROR` where play()
    was a no-op and only skipping forward escaped (confirmed via on-device
    logcat). Fix: `schedulePrefetch` now reverts behind-anchor tracks'
    `MediaItem`s to streaming URLs after their cache files are deleted, and a new
    `onPlayerError` handler re-streams the current item on
    `ERROR_CODE_IO_FILE_NOT_FOUND` as a safety net against cache-eviction races.
    Verified on the Pixel 8 Pro: skip-back now keeps playing with no source error.
- **M4.15 — Theming / Material You (2026-06-19, built + Pixel verified):** the
  app's single hardcoded dark palette is now a user choice. `EmbySonicTheme`
  takes a `ThemeChoice` and resolves the Compose color scheme: `DYNAMIC` uses
  `dynamicDarkColorScheme(context)` (Material You, Android 12+, falls back to
  liquidWave below), plus fixed dark palettes liquidWave (default), Ember,
  Violet, Forest, and Rose. The choice persists in DataStore
  (`SettingsRepository.themeChoice`) and is collected reactively in
  `MainActivity`, so a new "Appearance" card in Settings recolours the whole app
  live (no restart). Still dark-first; no light theme. The Now Playing widget
  also follows the selected theme: `PlaybackController` watches `themeChoice`
  alongside playback state and repaints the widget, which recolours icons/text
  via `setColorFilter` (all API levels) and tints its rounded backgrounds on
  API 31+; `DYNAMIC` maps to the `system_accent1`/`system_neutral1` resources so
  the widget tracks the wallpaper too. Verified on the Pixel 8 Pro across all
  six themes, in-app and on the home-screen widget.
- **M4.16 — Google Cast Phase 1 active-player switching (2026-06-19, built +
  Pixel 8 Pro / SHIELD verified):** Cast now uses Media3 `CastPlayer`, backed by
  the shared `CastContext`, as the playback controller's active player during a
  music Cast session. `PlaybackController` routes transport controls,
  active-state publishing, queue jumps, repeat, and Guest DJ appends through the
  active player; `SonicPlaybackService` swaps `MediaLibrarySession.player` to the
  active player so the in-app player, media notification, lock screen, Android
  Auto, and widget control the cast as one session. The Phase 0 one-track
  `RemoteMediaClient.load(...)` shortcut was replaced with full-queue handoff
  using cast-safe LAN Emby mp3 URLs (`api_key` query auth, artwork rebased to the
  configured Cast server URL). Queue handoff preserves current index and position
  local->cast and cast->local; a Stop Casting regression that resumed locally at
  0:00 was fixed by snapshotting the last live CastPlayer index/position and
  preferring it during disconnect. Final verification on Pixel 8 Pro + NVIDIA
  SHIELD logged `Bamboleo` handing off local->remote around 9.9s, Stop Casting
  handing back at 63.7s, and Now Playing resuming locally around 1:09. While
  casting, local-only EQ is suppressed, crossfade polling is stopped, and offline
  prefetch is cancelled; Guest DJ/mixes still operate on the queue. Audiobook
  casting remains deliberately out of scope.
- **M4.17 — Cast volume polish (2026-06-19, built + Pixel 8 Pro / SHIELD
  verified):** Now Playing shows an in-app Cast volume slider while a Cast
  session is active. `CastManager` listens to `CastSession` device-volume changes
  and sends app-side updates through `CastSession.setVolume(...)`; playback state
  exposes a `CastVolumeState` with device label, normalized volume, availability,
  and pending status. Slider movement updates optimistically, debounces receiver
  writes, and briefly ignores stale Cast volume echoes so the UI does not snap
  back while the SHIELD reports old volume. User verification compared the Now
  Playing slider, phone volume buttons, and system Cast card; the second build
  "works much better". The system Cast card/phone overlay can still be subject to
  Cast framework latency, but the in-app slider is now responsive.
- **M4.18 — Cast transient-drop hardening (2026-06-20, built + Pixel 8 Pro /
  SHIELD verified):** a Wi-Fi blip while casting used to hand playback back to the
  phone and resume, so the phone played on top of the receiver (which keeps
  playing autonomously). Now `CastManager` distinguishes a transient suspend from
  a genuine end: `onSessionSuspended` (and the `CastPlayer`'s
  `onCastSessionUnavailable`, which also fires on suspend) no longer trigger a
  remote->local handoff — the authoritative end signals are `onSessionEnded` /
  `onSessionResumeFailed`. The phone auto-resumes only when Cast reports a clean
  stop (`error == 0`) or when the session first fires `onSessionEnding`, which
  covers user-initiated Stop Casting paths that can still end with a non-zero
  framework code such as `2154`; abnormal ends/resume-failures hand back paused.
  Also fixed a regression where the media handoff fired on
  `onSessionStarted` before the `CastPlayer` was ready ("no media selected"); it
  now loads on `onCastSessionAvailable`. Verified on the Pixel 8 Pro + SHIELD: a
  Wi-Fi toggle leaves the SHIELD playing solo with the phone silent, and a clean
  Stop Casting still resumes locally where it left off.
- **M4.19 — Cast UI polish + long-queue regression (2026-06-20, built + Pixel
  8 Pro / SHIELD verified):** The mini-player now includes the same Cast route
  button as Now Playing. `PlaybackUiState` exposes explicit `isCasting` state so
  Settings and Equalizer can visibly disable local-only features while casting:
  crossfade controls grey out, the Equalizer entry/screen disables controls, and
  Settings shows an offline-prefetch card explaining that Cast receivers fetch
  directly from Emby. Now Playing shows a "Casting to <device>" indicator plus a
  short hint that equalizer, crossfade, and offline prefetch are unavailable
  while casting; the volume slider remains focused on Cast volume. User
  verification on the Pixel 8 Pro + SHIELD confirmed the mini-player Cast button,
  Now Playing indicator, Settings disabled states, and Equalizer gating. The same
  run regression-tested a 30-track Cast queue after Guest DJ appended five tracks:
  next/previous, seek, repeat modes, and Guest DJ append worked. Stop Casting
  initially failed to resume locally because the SHIELD route ended with
  `error=2154`; `CastManager` now tracks `onSessionEnding` as user intent and
  resumes on that path. ADB logs from the fixed build show local->remote at queue
  index 24 around 10.1s and remote->local at the same index around 52.3s with
  `userEnding=true resumePlayback=true`.
- **M4.20 — Cast Emby reporting verification (2026-06-20, Pixel 8 Pro /
  SHIELD verified):** Emby server-side Now Playing / progress reporting was
  checked while casting. User verification confirmed Emby Now Playing followed
  the phone/casting state during remote playback. This closes the remaining
  Phase 2 Cast verification item for music v1; audiobook casting and audiobook
  resume semantics remain deliberately deferred.
- **M4.21 — Sonic-similar detail rails (2026-06-20, built + Pixel 8 Pro
  verified):** Artist detail pages now show a compact "Sonically similar artists"
  rail above the album grid, and album track-list pages show "Sonically similar
  albums" above the tracks. The live coordinator endpoints return names rather
  than Emby collection ids (`{artist, score}` and `{album, artist, score}`), and
  the current coordinator implementation expects a representative track id even
  on `/sonic/artists/{id}/similar` and `/sonic/albums/{id}/similar`; the Android
  view model therefore seeds artist pages with the artist's first playable track,
  seeds album pages with the first album track, then resolves returned names back
  to Emby artist/album items through existing search APIs. Rails drill into the
  existing detail routes and preserve normal playback controls below. User
  verification confirmed artist similar cards open artists' albums, album similar
  cards open album tracks, similar albums appear on album track lists, and album
  track playback still starts normally.
- **M4.22 — Album-art and sonic-card polish (2026-06-20, built + Pixel 8 Pro
  verified):** Sonic-similar collection cards are now whole-card tap targets
  instead of title-only buttons. Music album grids now recover missing album art
  from representative child tracks, matching the audiobook cover fallback pattern;
  this applies to the main Music Albums tab, Artist -> Albums detail, Recently
  Added albums, and similar-album resolution. User verification confirmed album
  art appears in the main album tab and artist album lists.
- **M4 — Remaining sonic features:** none currently open.
- **M5 — Waveform + polish:** Real waveform (Option A) considered here, dropped
  in behind the `TrackProgress` interface; remaining UI polish as identified.

- **Deliverable:** APK sideloadable; later: Play Store or F-Droid

**M3 verification (liquidHulk / Pixel_3a_API_36, 2026-06-09):**
- Built with `./gradlew :app:assembleDebug`.
- Installed `android/app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`.
- Verified on emulator by tapping Music → Albums → album track. Now Playing opened,
  ExoPlayer advanced the timer, the play button showed pause state, and
  `adb shell cmd media_session list-sessions` reported
  `package=guru.liquid.embysonic`.
- Screenshots captured in `android/verify-m3-nowplaying-final.png` and
  `android/verify-m3-media-session.png`.

**M3.5 verification (liquidHulk / Pixel_3a_API_36, 2026-06-09):**
- Built with `./gradlew :app:assembleDebug`.
- Installed `android/app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`.
- Verified on emulator by opening Music → artist → album. The album track-list
  top bar showed Play + Shuffle actions, and the track row showed its explicit
  play button.
- Started playback from the row play button. Now Playing opened, playback
  advanced, shuffle/repeat controls rendered below transport, and
  `adb shell cmd media_session list-sessions` reported
  `package=guru.liquid.embysonic`.
- Regression-checked A-Z picker and shuffle behavior: A-Z now shows
  `# A B C ... Z` and tapping `T` jumps to T artists; detail shuffle visibly
  reorders the leaf track list, changes the first row, prepares the matching
  shuffled queue, and does not navigate away or start playback when paused.
- Regression-checked playback on direct-play and transcode-needed tracks. A
  Radiohead track direct-played, while R.E.M. "Electrolite" was identified as
  WMA/ASF; switching to Emby universal audio streaming made the WMA track play
  successfully (user-confirmed on emulator).
- Regression-checked bottom Music tab from artist/album drill-down: tapping the
  tab pops back to the existing Music root and preserves the prior scroll section
  (user-confirmed on emulator after jumping to `M`).
- Screenshots captured in `android/verify-m35-tracklist.png` and
  `android/verify-m35-nowplaying-controls.png`.
- Regression screenshots captured in `android/verify-az-picker-fixed.png`,
  `android/verify-az-picker-jump-fixed.png`, and
  `android/verify-m35-tab-return-preserves-scroll.png`.

**M3.6 verification (liquidHulk / Pixel_3a_API_36, 2026-06-09):**
- Built with `./gradlew :app:assembleDebug`.
- Installed `android/app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`.
- Verified Home renders as the liquidWave landing screen with playlists and
  recently added album sections instead of coordinator analysis status.
- Verified Settings contains the refreshable analysis status card.
- Verified tapping a Home album tile drills into the existing album track-list
  detail screen with top-bar play/shuffle and per-row play actions intact.
- Screenshots captured in `android/verify-home-discovery.png`,
  `android/verify-settings-analysis-status.png`, and
  `android/verify-home-album-drilldown.png`.

**M3.7 verification (liquidHulk / Pixel_3a_API_36, 2026-06-09):**
- Built with `./gradlew :app:assembleDebug`.
- Installed `android/app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`.
- User-confirmed the bottom Home tab now returns correctly from detail screens.
- User-confirmed the mini player appears after starting playback and returning to
  the shell, its bar opens Now Playing, play/pause works, and stop removes the
  bar/stops playback.
- User-confirmed the mini-player progress strip seeks within the current track,
  matching the main Now Playing seek behavior.
- Screenshot captured in `android/verify-mini-player.png`.

**M3.8 verification (liquidHulk / Pixel_3a_API_36, 2026-06-09/10):**
- Built with `./gradlew :app:assembleDebug`.
- Installed `android/app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`.
- Verified Emby direct user-data resume persistence by posting an audiobook
  `PlaybackPositionTicks` update and reading the same value back from
  `/Users/{userId}/Items/{itemId}?Fields=UserData`.
- Verified Home renders a `Resume audiobooks` row with book artwork and
  `Resume at ...` subtitles.
- User confirmed saved audiobook position appears on other Emby devices, so
  server-side sync via `UserData` is working.
- User found the Home resume row initially required pressing refresh after
  returning from playback; a Home lifecycle `ON_RESUME` refresh was added and
  installed, but needs user confirmation.
- Diagnosed the long-form resume failure with ffmpeg against Emby:
  `/Audio/{id}/universal` produced identical first-six-second audio with and
  without `StartTimeTicks`, proving it ignored the resume offset for tested long
  files. `/Audio/{id}/stream` with `StartTimeTicks` produced different audio
  from the intro for both `The Bladed Faith` (18h MP3, resume ~4:45:30) and
  `The Eye of the Bedlam Bride` (26h M4B, resume ~6:05:27).
- Installed the `/stream` long-form resume build and user-confirmed resume now
  works.
- Screenshots captured in `android/verify-resume-audiobooks-home.png` and
  `android/verify-resume-audiobook-playing.png`.

**M3.9 verification (liquidHulk / Pixel_3a_API_36, 2026-06-10):**
- Built with `./gradlew :app:assembleDebug`.
- Installed `android/app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`.
- User-confirmed Home customization works.
- Verified screenshot shows the customize sheet with small cards enabled,
  section visibility toggles, and reordered sections.
- Screenshot captured in `android/verify-home-customize-sheet.png`.

**M3.10 verification (liquidHulk / Pixel_3a_API_36, 2026-06-10):**
- Built with `./gradlew :app:assembleDebug`.
- Installed `android/app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`.
- User-confirmed repeat controls and track changes work on Now Playing.
- User-confirmed mini-player previous/next controls work.
- User-confirmed the queue focus/jump behavior, stop/clear behavior, and repeat
  persistence checks look good.
- Screenshot captured in `android/verify-playback-queue-controls.png`.

**M4.1 verification (liquidHulk / Pixel_3a_API_36, 2026-06-10):**
- Curled the live coordinator before writing Android DTOs. `/sonic/mixes`
  returned generated mix summaries (`id`, `name`, `created_at`, `cluster_id`,
  `track_count`), and `/sonic/mixes/{id}` returned `mix` plus ordered `tracks`
  with Emby item ids.
- Built with `./gradlew :app:assembleDebug`.
- Installed `android/app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`.
- Verified the Mixes sub-tab renders coordinator mixes, the first mix opens an
  in-place detail list, and tapping the mix play button opens Now Playing with
  playback advancing.
- Screenshots captured in `android/verify-sonic-mixes-list.png`,
  `android/verify-sonic-mix-detail.png`, and
  `android/verify-sonic-mix-playing.png`.

**M4.2 verification (liquidHulk / Pixel_3a_API_36, 2026-06-10):**
- Built with `./gradlew :app:assembleDebug`.
- Installed `android/app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`.
- User-confirmed repeat resets for new playback sessions, the Mix options sheet
  shows track-count choices, mix list labels are clearer, and Save as playlist
  works with a naming dialog.
- Verified screenshot shows Home with `Sonic mixes` cards using `Mix N • track
  count` metadata and the saved mix visible in the Playlists row.
- Screenshot captured in `android/verify-mix-save-home-options.png`.

**M4.3/M4.4 current verification state (liquidHulk, 2026-06-11):**
- M4.3 is complete: selected mixes can be refreshed independently, refreshes
  fully replace the selected mix's tracks, saved Emby playlists can be deleted
  with confirmation, and spoken-word/audiobook paths are excluded from mix build
  and refresh.
- M4.4 builds successfully with `./gradlew :app:assembleDebug`. The default
  six-second crossfade was user-verified on the Pixel 3a API 36 emulator across
  two consecutive transitions: no gap, smooth outgoing tail, audible incoming
  track, and correct helper reset between transitions. Device logs show each
  helper armed with `playWhenReady=false`, ready before the blend point, and the
  ramps firing at about 5.96 seconds remaining. Screenshot captured in
  `android/crossfade-verified-half.png`.
- The 12-second setting was tested across several consecutive transitions on
  2026-06-13. Trigger logs were accurate to 11.96-11.99 seconds; the incoming
  curve was then strengthened and user-confirmed as working substantially better.
  Music stop/reset was also user-verified: closing Now Playing with `X` causes
  the next play to start at 0:00, while pause remains the resume-preserving action.
- A-Z tap/drag was regression-tested after stopping playback. Direct letter taps
  and dragging move to the expected artist sections, with a visible cyan active
  letter during drag. Screenshot: `android/verify-az-picker-drag-feedback.png`.
- Remaining M4.4 verification: 3/9-second durations and cancellation during
  pause, skip, seek, stop, shuffle, and new-queue actions.

### Phase 4 — iOS App
*Feature parity, separate timeline.*

- Swift / SwiftUI
- AVPlayer for audio
- **Deliverable:** TestFlight → App Store

---

## Technical Decisions (Locked)

| Decision | Choice | Rationale |
|---|---|---|
| Analysis runs on | Any LAN machine (coordinator on Emby host) | Coordinator is lightweight; GPU workers can run elsewhere |
| Worker architecture | Coordinator + distributed workers via HTTP | Crash-safe leases; GPU offload; no file-share dependency |
| Library size target | 10k–50k tracks | FAISS flat index sufficient; upgrade path to IVFFlat exists |
| Audio analysis library | librosa (core) + Essentia (optional) | Essentia has no Windows/ARM wheel; librosa proxies mood features |
| Embedding model | PANNs CNN14 (2048-dim, PyTorch) | Lightweight CNN, fast at scale; MERT transformer was ~4× slower on CPU |
| Audio windowing | 3×30s windows, decoded on-demand | Bounds RAM regardless of track length; windowed features = ~3× faster |
| Embedding dim | 128 (PCA from 2048) | FAISS-efficient; PCA fitted on full library, saved for refit without re-analysis |
| Vector store | FAISS IndexFlatIP (cosine) | Fast, in-process; DB is source of truth; FAISS rebuilt on startup |
| Metadata DB | SQLite (aiosqlite, WAL mode) | No server overhead; crash-safe with WAL + busy_timeout |
| Plugin language | C# (.NET) | Required by Emby plugin SDK |
| Android language | Kotlin / Jetpack Compose | Modern Android standard |
| API auth | Emby token passthrough | No second auth system; worker routes use shared API key |
| MVP scope | All discovery features together | No point shipping without the features that make it valuable |

---

## Open Questions

- **Incremental scan:** webhook/poll fallback for setups without the plugin (the plugin already fires a scan on `ItemAdded`)?
- **Worker token:** currently the shared `EMBY_API_KEY` — split into a dedicated `WORKER_SECRET` env var?

---

## Resolved Decisions

### Embedding model: MERT → PANNs CNN14 (benchmark-driven)

The spec originally locked MERT-v1-95M. Benchmarking on liquidBee (N100) showed
MERT — a 95M-param **transformer** — is too slow for CPU-at-scale:

- Full-track: ~10 GB RAM, impractically slow.
- Chunked (3×30s): **~57s/track** → 25k tracks = **~19 days**.

Plex is fast on the same hardware because it uses a compact CNN, not a research
transformer. Switched to **PANNs CNN14** (AudioSet-pretrained, PyTorch, 2048-dim):

- **~14s/track CPU, ~10s GPU** — 4–8× faster than MERT.
- CNN14 checkpoint (`Cnn14_mAP=0.431.pth`, 327 MB) auto-downloaded on first use
  by `analysis.embeddings.ensure_checkpoint()` via stdlib `urllib` (atomic,
  resumable-safe) to `settings.panns_checkpoint_path` (default `~/panns_data/`,
  env-overridable). We pass an explicit `checkpoint_path` to `AudioTagging` so
  `panns_inference` never falls back to its `wget` download (absent on
  Windows/most NAS). A pre-placed file is reused as-is.

### Pipeline windowing

librosa's full-track feature extraction was the CPU bottleneck (~20s on long files).
Fixed by decoding only the N sampled windows and running all feature extraction on
the concatenated windows. librosa features dropped from ~20s to ~7s per track.
Config knobs: `NUM_WINDOWS` (default 3), `WINDOW_SECONDS` (default 30).

### Distributed workers (crash-safe)

Initial design ran analysis inside the coordinator. Rejected because:
- A 25k-track scan ties up the coordinator for days.
- No GPU offload path.
- No crash-resume at the track level.

Replaced with coordinator + workers via HTTP:
- SQLite is the source of truth (not FAISS).
- Workers claim tracks on a lease; expired leases are reclaimed automatically.
- FAISS is rebuilt from SQLite on every coordinator startup — a crash that loses the
  on-disk FAISS index loses no analysed work.
- Workers stream audio from Emby's `/Items/{id}/Download` — no file share needed.

### Waveform: MVP placeholder (B), designed for on-demand caching (A)

Decided 2026-06-08. The Android Now Playing screen ships with a plain progress/seek
bar for the MVP — **no real waveform** (Option B). This keeps waveform off the
critical path and out of the otherwise-lightweight coordinator (the slim
`Dockerfile.coordinator` image deliberately excludes librosa/decoders).

The app is **designed so real waveforms drop in later (Option A)** without rework:

- Now Playing renders a `TrackProgress` component behind an interface. The MVP
  implementation is a progress bar; the future implementation fetches a real
  amplitude array and draws bars. Swapping one for the other touches no other code.
- Future server side (when we do A): add `waveform BLOB` to the `embeddings` table
  and a `GET /sonic/tracks/{id}/waveform` route. On first request the coordinator
  streams the track from Emby (`/Items/{id}/Download`, same path workers use),
  decodes at a low sample rate, downsamples to ~300 peak-amplitude floats, caches
  them in the new column, and returns them. Cache hits are instant and shared
  across all clients/users. First-ever request for a track costs ~2–5s decode on
  the N100 (show a shimmer placeholder). No library re-scan required — waveforms
  accrue lazily as tracks are played.
- Option C (compute during analysis) stays rejected: it would force a 27k re-scan.
- Slim-image caveat for A: the coordinator-only Docker image would need a minimal
  decoder (`soundfile`/`audioread`) added before the waveform route works there.

### Android brand: liquidWave

Decided 2026-06-09. The Android app is branded **liquidWave** while the broader
repo/service remains Emby Sonic for now. Brand assets use a premium dark Material
3 style:

- Static launcher/logo mark: liquid cyan W over subtle amplitude bars, based on
  `C:\Users\liqui\liquidwave-icon-static.svg`.
- Launcher icons use density-specific PNG foreground assets rendered from the
  original static SVG and inset for Android adaptive-icon safe area. Avoid
  hand-tuned VectorDrawable replacements for the launcher unless the source SVG
  changes and the rendered PNGs are regenerated.
- Compose loading/splash mark uses the same W and highlight geometry; the W stays
  static and only the background amplitude bars gently undulate.
- The Android 12 platform splash uses only the dark background so its adaptive-icon
  mask does not distort the mark before the controlled Compose splash appears.
- No music notes, headphones, speaker icons, or droplets are part of the liquidWave
  brand mark.

### Cross-platform portability

Essentia has no wheel on Windows or ARM. Made optional: librosa proxies for
valence/arousal, upgraded automatically if Essentia is detected at runtime.
Native `pip install` is the primary path; Docker is optional for NAS/Linux.

### Plugin agnosticism vs. Python provisioning (Phase 2)

Plugin stays a thin agnostic proxy (managed IL, no native code). Python service
provisioned separately. Options for Phase 2:

| Option | Mechanism | Trade-off |
|---|---|---|
| **A. Bootstrap** | Plugin creates a venv + `pip install` on first run | Best UX; needs Python on host |
| **B. Sidecar** | Python service runs separately; plugin points at its URL | Zero binaries in plugin (current setup) |
| **C. Bundle all** | Ship every platform's native wheels in the zip | **Rejected** — destroys agnosticism |

Leaning A (community) + B (power users). C rejected.

### M3 playback — Now Playing + ExoPlayer + MediaSession (Phase 3)

Implemented via Media3 ExoPlayer with MediaSession for Android auto support.
**PlaybackController** (singleton) owns the player, manages the queue (with
shuffle/repeat), and publishes UI state. Tracks stream through Emby's
authenticated universal audio endpoint (`/Audio/{id}/universal`) with proper
User-Agent headers so Emby can transcode unsupported codecs such as WMA/ASF.

**M3 Polish & Known Refinements:**
- Home now has its user-facing landing shell with Resume audiobooks, Recent
  Plays, Playlists, Sonic mixes, recently added albums, artist shortcuts, and
  the Stations/Adventure discovery strip. Analysis status lives in Settings.
- Mini player exists in the shell for active playback and now exposes previous,
  play/pause, next, and stop. Follow-up polish could add swipe-to-dismiss or
  queue context if desired.
- Audiobook resume sync uses direct Emby user-data writes as the durable source
  of truth; normal session check-ins remain for active playback/session metadata.
  Long-form resume must use `/Audio/{id}/stream` with `StartTimeTicks`; do not
  use `/Audio/{id}/universal` for server-offset resume because Emby 4.10 accepted
  `StartTimeTicks` there while still serving audio from the beginning.
- Play buttons on grids (CardGrid, DetailScreen) are live; all playlists/albums/
  artists now have play actions wired to the playback queue.
- Shuffle and repeat modes are visible in Now Playing (not just toggles).
- App rebranded to **liquidWave** (custom logo with animated bars).

---

## Conversation & Tool Map

| Phase | Where |
|---|---|
| Spec & architecture | Claude.ai chat |
| Phase 1 backend | Claude Code (desktop app) |
| Phase 2 plugin | Claude Code + Claude.ai for C# learning |
| Phase 3 Android | Claude.ai for UI design, Claude Code for implementation |
| Phase 4 iOS | Same as Phase 3 |

---

*This document is the source of truth. Update it as decisions change.*
