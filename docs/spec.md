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
coordinator-host (192.168.1.50) — or any Emby host
├── Emby Server (existing)
├── Emby Plugin (C# — config UI + scan trigger, Phase 2 ✅)
└── Emby Sonic Coordinator (FastAPI, :8765 — Phase 1 ✅)
    ├── SQLite — track metadata, analysis state, playlist definitions
    └── FAISS — 128-dim cosine similarity index

Any LAN machine (e.g. dev-pc w/ RTX 4070)
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

**Real-world results (coordinator-host N100 + dev-pc RTX 4070, library of 28,316 tracks):**
- 5 GPU workers on dev-pc, coordinator on coordinator-host
- **27,692 tracks embedded** (97.8%) — 608 errors (broken files in Emby library)
- End-to-end validated: claim → stream from Emby → GPU embed → store → FAISS → similarity query
- Similarity quality confirmed: Banco de Gaia → Aes Dana (0.752), Tiësto (0.733) — correct ambient/downtempo neighbourhood from audio alone, no genre tags
- Mixes confirmed: 30 clusters generated, sonically coherent — Mix 1 (jazz/standards: Lee Morgan, Diana Krall), Mix 10 (drum & bass: Nicky Blackmarket), Mix 20 (R&B/pop: Craig David, Leona Lewis), Mix 30 (audiobooks: Robert Jordan, John Gwynne) — genre separation correct from audio alone

**Benchmarks (coordinator-host N100, warm model):**

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
*Pure UI consuming stable API. In progress (started 2026-06-08). M3 playback complete 2026-06-09.*

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
  Album/playlist track rows start an authenticated Emby `/Items/{id}/Download`
  stream and open the Now Playing screen. Transport uses a 96dp tap-to-seek
  progress module behind the `TrackProgress` interface (the future waveform slot;
  see waveform decision).
- **M4 — Sonic features:** Mixes list + player, Track radio, Sonic adventure,
  sonic-similar sidebars on Artist/Album detail, Guest DJ toggle.
- **M5 — Waveform + polish:** Home (recents + mixes), icon/theming. Real waveform
  (Option A) considered here, dropped in behind the `TrackProgress` interface.

- **Deliverable:** APK sideloadable; later: Play Store or F-Droid

**M3 verification (dev-pc / Pixel_3a_API_36, 2026-06-09):**
- Built with `./gradlew :app:assembleDebug`.
- Installed `android/app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`.
- Verified on emulator by tapping Music → Albums → album track. Now Playing opened,
  ExoPlayer advanced the timer, the play button showed pause state, and
  `adb shell cmd media_session list-sessions` reported
  `package=guru.liquid.embysonic`.
- Screenshots captured in `android/verify-m3-nowplaying-final.png` and
  `android/verify-m3-media-session.png`.

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

The spec originally locked MERT-v1-95M. Benchmarking on coordinator-host (N100) showed
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
