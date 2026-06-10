# Emby Sonic — Project Specification
**Version:** 0.2
**Author:** Kaj Maney
**Status:** Phase 1 & 2 COMPLETE — Phase 3 (Android app) M3 complete

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
*Pure UI consuming stable API. In progress (started 2026-06-08). M3 playback complete 2026-06-09; M3.5 playback controls/queue polish complete 2026-06-09; M3.6 Home landing polish complete 2026-06-09; M3.7 mini player complete 2026-06-09; M3.8 audiobook resume complete 2026-06-10; M3.9 Home customization complete 2026-06-10; M3.10 playback control polish complete 2026-06-10; M4.1 sonic mixes list/player complete 2026-06-10; M4.2 mix saving/options/Home complete 2026-06-10.*

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
  section. Tapping a bottom library tab from a drill-down detail pops back to the
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
- **M4 — Remaining sonic features:** Track radio, Sonic adventure,
  sonic-similar sidebars on Artist/Album detail, Guest DJ toggle. TODO: add a
  selected-mix regenerate flow that refreshes one chosen mix without deleting the
  others, likely requiring a new coordinator endpoint; add playlist delete
  actions for Emby playlists only (not generated sonic mixes), with confirmation.
- **M5 — Waveform + polish:** Real recents/mixes on Home, icon/theming. Real waveform
  (Option A) considered here, dropped in behind the `TrackProgress` interface.

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
- **Selected mix regeneration:** current `/sonic/library/build-mixes` rebuilds
  all generated mixes and deletes/replaces the old set. Add a coordinator API
  for refreshing selected mix ids/clusters while preserving the rest, then expose
  multi-select or per-detail regenerate in Android.
- **Playlist deletion:** add Android UI for deleting saved Emby playlists with a
  confirmation dialog. This must target Emby playlist items only; generated
  sonic mixes are ephemeral coordinator rows and should not be deleted through
  the same action.

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
- Home now has its user-facing landing shell with playlists, recently added
  albums, and artist shortcuts. Analysis status lives in Settings. Follow-up:
  replace placeholder browse-backed rows with true recent listens, sonic mixes,
  radio/adventure entries, and other M4 discovery surfaces as those APIs/UI flows
  land.
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
