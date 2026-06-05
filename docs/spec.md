# Emby Sonic — Project Specification
**Version:** 0.1 (draft)
**Author:** Kaj Maney
**Status:** Architecture locked, implementation not started

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

## Features (Full Scope — Option C)

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
liquidBee (192.168.1.9)
├── Emby Server (existing)
├── Emby Plugin (C# — thin wrapper, Phase 2)
└── Python Analysis Service (FastAPI — Phase 1)
    ├── Audio analysis engine
    ├── Embeddings model
    ├── FAISS vector store
    └── SQLite metadata DB
```

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
every platform Emby supports (Windows, Linux x86/ARM, macOS) — *as long as the
plugin contains no native code*. The plugin must therefore stay a pure proxy:
it registers routes and forwards to the Python service, nothing more. It must
NOT bundle the Python service's native binaries (torch/faiss/numpy/librosa are
per-OS/per-arch wheels) inside the plugin zip — doing so would destroy the
plugin's natural agnosticism and force a per-platform build. How the Python
service is provisioned is a *separate* concern from the plugin artifact — see
the resolved note in Open Questions.

### Layer 3 — Python Analysis Service (FastAPI)

**Audio analysis pipeline:**
- Input: file path (from Emby library)
- Libraries: `librosa` (tempo, energy, spectral features), `Essentia` (mood, instruments, vocals)
- Model: pre-trained audio embedding model (HuggingFace — MusicCaps or similar)
- Output: multi-dimensional vector (128-dim embedding) per track

**Storage:**
- `SQLite` — track metadata, analysis state, playlist definitions
- `FAISS` — vector index for fast nearest-neighbour similarity search

**Similarity engine:**
- Cosine similarity between track embeddings
- FAISS `IndexFlatL2` for MVP, upgrade to `IndexIVFFlat` at scale (>50k tracks)

**Playlist generation algorithms:**
- Track Radio: seed track → k-nearest neighbours → queue
- Sonic Adventure: seed A + target B → find intermediate chain through embedding space
- Mixes For You: cluster library into N groups (k-means) → curate one mix per cluster
- Guest DJ: real-time queue injection of nearest neighbours to current track

**REST API (FastAPI):**

| Endpoint | Method | Description |
|---|---|---|
| `/sonic/status` | GET | Analysis progress, library stats |
| `/sonic/tracks/{id}/similar` | GET | N most sonically similar tracks |
| `/sonic/tracks/{id}/radio` | GET | Generate track radio playlist |
| `/sonic/adventure` | POST | Body: `{from_id, to_id, length}` → playlist |
| `/sonic/mixes` | GET | All curated mixes for current user |
| `/sonic/mixes/{id}` | GET | Mix detail + track list |
| `/sonic/queue/inject` | POST | Guest DJ: inject similar tracks into queue |
| `/sonic/library/scan` | POST | Trigger full or incremental re-analysis |
| `/sonic/artists/{id}/similar` | GET | Sonically similar artists |
| `/sonic/albums/{id}/similar` | GET | Sonically similar albums |

Auth: Emby API token passed in `X-Emby-Token` header — Python service validates against Emby's `/Users/Me` endpoint.

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
- Stream directly from Emby API using Emby stream URL
- ExoPlayer for playback
- MediaSession for system/notification controls
- Offline queue caching (optional, Phase 3+)

**UI aesthetic reference:** Plexamp / YouTube Music — dark theme, large album art, smooth transitions, waveform visualisation on Now Playing

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
  analysed_at TIMESTAMP,
  analysis_version INTEGER
);

CREATE TABLE embeddings (
  track_id TEXT PRIMARY KEY REFERENCES tracks(id),
  vector BLOB,                  -- serialised 128-dim float32 array
  tempo REAL,
  energy REAL,
  valence REAL,                 -- mood: sad → happy
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

### Phase 1 — Python Analysis Service
*Your comfort zone. Start here.*

- Set up FastAPI project structure
- Integrate librosa + Essentia audio pipeline
- Download and integrate HuggingFace audio embedding model
- Build SQLite schema + FAISS index
- Implement similarity search endpoints
- Implement playlist generation algorithms
- Test against local music library on liquidBee
- **Deliverable:** Working API accessible at `http://liquidBee:8765`

**Tools:** Claude Code, Python, FastAPI, librosa, Essentia, FAISS, SQLite

### Phase 2 — Emby Plugin (C# wrapper)
*New learning, but small scope.*

- Learn Emby plugin SDK basics
- Create minimal plugin project (C# / .NET)
- Register API route passthrough to Python service
- Bundle Python service launcher
- Test plugin install from Emby dashboard
- **Deliverable:** Plugin zip installable from Emby → Python service auto-starts

**Tools:** Claude Code, C# / .NET SDK, Emby Plugin SDK

### Phase 3 — Android App
*Pure UI consuming stable API.*

- Set up Kotlin / Jetpack Compose project
- Implement server discovery + auth flow
- Build library browser screens
- Build Now Playing screen (ExoPlayer + waveform)
- Integrate all discovery features
- Polish UI (dark theme, animations, transitions)
- **Deliverable:** APK sideloadable; later: Play Store or F-Droid

**Tools:** Android Studio, Kotlin, Jetpack Compose, ExoPlayer

### Phase 4 — iOS App
*Feature parity, separate timeline.*

- Swift / SwiftUI project
- Same API, same feature set
- AVPlayer for audio
- **Deliverable:** TestFlight build → App Store

---

## Technical Decisions (Locked)

| Decision | Choice | Rationale |
|---|---|---|
| Analysis runs on | liquidBee | Co-located with Emby; portable model for other users |
| Library size target | 10k–50k tracks | FAISS flat index sufficient; upgrade path exists |
| Audio analysis library | librosa + Essentia | Well-maintained, Python-native, proven on music |
| Embedding model | HuggingFace (TBD — MusicCaps or similar) | No API key needed, runs locally |
| Vector store | FAISS | Fast, runs in-process, no extra service |
| Metadata DB | SQLite | Consistent with idGuru pattern; no server overhead |
| Plugin language | C# (.NET) | Required by Emby plugin SDK |
| Android language | Kotlin / Jetpack Compose | Modern Android standard |
| API auth | Emby token passthrough | No second auth system to maintain |
| MVP scope | All discovery features together | No point building without the features that make it valuable |

---

## Open Questions (to resolve in Phase 1)

- Which HuggingFace embedding model gives best results for music? *(Locked for Phase 1: `m-a-p/MERT-v1-95M`, 768→128-dim via PCA. Revisit after benchmarking.)*
- Should FAISS index live in-memory or on disk? *(Resolved: on disk — `IndexFlatIP` persisted to `data/faiss.index`, loaded on startup.)*
- Analysis speed on N100 CPU — benchmark needed. GPU acceleration possible? *(Benchmark harness ready: `benchmark.py` reports per-stage timing + real-time factor. Run before full scan.)*
- Waveform data: generate during analysis or on-demand in app?
- Incremental scan strategy — watch Emby webhook for library updates?

## Resolved Decisions (Phase 1 build)

### Cross-platform portability — analysis service

The Python service must run anywhere Emby Server runs, **including native
Windows hosts with no Docker and no NAS** (a large share of Emby users, and
the developer's own setup). Decisions made to guarantee this:

- **Essentia is NOT a core dependency.** It has no reliable wheel on Windows or
  ARM. mood/vocal features fall back to librosa-derived proxies and are
  upgraded automatically if Essentia is detected at runtime
  (`requirements-optional.txt`). Native `pip install` is the primary path on
  every platform; Docker is an optional convenience for NAS/Linux users.
- The mobile apps (Android/iOS) are **clients only** — they consume the HTTP
  API and never run analysis, so "runs on iOS" is not a service concern.

### Plugin agnosticism vs. Python provisioning (Phase 2)

An Emby plugin (managed .NET IL) is inherently platform/arch-agnostic — one
`.dll` for all platforms — *unless* it embeds native code. The Python analysis
service is the opposite: native, per-OS/per-arch wheels. These two facts must
not be conflated. **The plugin stays a thin agnostic proxy; the Python service
is provisioned separately.** Provisioning options (decide in Phase 2):

| Option | Mechanism | Trade-off |
|---|---|---|
| **A. Bootstrap** | Plugin creates a venv + `pip install` against the host's Python on first run | Best "single install" UX; keeps plugin agnostic; needs Python present on host |
| **B. Sidecar / Docker** | Python service runs separately; plugin points at its URL | Zero binaries in plugin (current liquidBee setup); user provisions the service |
| **C. Bundle all platforms** | Ship every platform's binaries in the zip | **Avoid** — destroys plugin agnosticism, balloons artifact, N binary sets to maintain |

Leaning A (community distribution) + B (power users / current setup). C rejected.

---

## Conversation & Tool Map

| Phase | Where |
|---|---|
| Spec & architecture | Claude.ai chat (here) |
| Phase 1 backend | Claude Code (desktop app) |
| Phase 2 plugin | Claude Code + Claude.ai chat for C# learning |
| Phase 3 Android | Claude.ai chat for UI design, Claude Code for implementation |
| Phase 4 iOS | Same as Phase 3 |

---

*This document is the source of truth. Update it as decisions change.*
