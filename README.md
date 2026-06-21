# Emby Sonic

Self-hosted neural audio analysis for Emby — a privacy-first equivalent of
Plexamp's Sonic Analysis. Maps a music library into a multi-dimensional "sonic
space" using audio embeddings (not genre tags), enabling sonically intelligent
discovery: similar tracks/artists/albums, track radio, sonic adventures, auto-curated
mixes, and a Guest DJ.

> **Status:** Phase 1 (Python analysis service) and Phase 2 (C# Emby plugin) complete.
> Phase 3 (Android app, **liquidWave**) is well advanced and running on real
> hardware — browse, Media3 playback, sonic mixes (per-mix refresh), crossfade
> with artwork cross-dissolve, an in-app equalizer, Track Radio, Sonic Adventure,
> Stations, Recent Plays, and search across music + audiobooks. See
> [`docs/spec.md`](docs/spec.md) for the full architecture and milestone list,
> and [`AGENTS.md`](AGENTS.md) for the working agreement / dev environment.

## Architecture

```
Emby Server (existing)
└── Emby Plugin (C#, config UI + scan trigger — Phase 2 ✅)
    └── Emby Sonic Coordinator (FastAPI, :8765 — Phase 1 ✅)
        ├── SQLite — metadata, analysis state, playlists
        └── FAISS — 128-dim cosine similarity index

Any LAN machine (e.g. a GPU box)
└── Analysis Workers — claim tracks, stream audio from Emby, embed, report back
```

Workers can run on the Emby host or on any networked machine. They stream audio
directly from Emby's HTTP API — no file shares or special network config needed.

## Phase 1 — Python Analysis Service

### Requirements

- Python 3.11+ (tested on 3.12)
- Emby Server with API key

### Setup

```bash
# CPU-only PyTorch (recommended for Emby hosts; workers can use GPU separately)
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install -r requirements.txt

cp .env.example .env   # set EMBY_URL and EMBY_API_KEY
python main.py         # coordinator on http://0.0.0.0:8765
```

**PANNs CNN14 checkpoint** (~327 MB) — auto-downloaded by workers on first use
(stdlib `urllib`, cross-platform). No manual step needed. To pre-place or
relocate it, set `PANNS_CHECKPOINT_PATH` (default `~/panns_data/Cnn14_mAP=0.431.pth`);
an existing file is reused.

### Deploy on a NAS (Docker)

The coordinator is lightweight (no torch/librosa/panns — those live on workers),
so it runs in a small container on any NAS (Synology, QNAP, UGREEN, …), including
ARM. Audio analysis runs on separate **workers** on a machine with spare CPU/GPU.

```bash
EMBY_URL=http://<emby-host>:8096 EMBY_API_KEY=<key> docker compose up -d --build
```

This builds [`Dockerfile.coordinator`](Dockerfile.coordinator) and starts the
coordinator on port 8765 with a persistent `emby-sonic-data` volume (SQLite +
FAISS index). Then point the Emby plugin's **Python Service URL** at
`http://<nas-host>:8765` and run one or more workers (full `requirements.txt`)
on your GPU/CPU box — they stream audio from Emby, so no file shares are needed.

### Benchmark before a full scan

```bash
python benchmark.py /path/to/a/track.flac
```

Reports per-stage timing + real-time factor. Expect ~10–15s/track with GPU workers,
~20–30s/track CPU-only.

### Scanning your library

```bash
# 1. Sync library from Emby (populates the queue; no audio work yet)
curl -X POST http://localhost:8765/sonic/library/scan \
  -H "X-Emby-Token: <your-emby-token>"

# 2. Run a worker (on this machine or any other on the LAN)
COORDINATOR_URL=http://<coordinator-host>:8765 \
WORKER_ID=my-worker \
python worker.py
```

Workers auto-detect CUDA. Run multiple workers in parallel for faster scanning.

### Automatic analysis (worker as a scheduled task)

For hands-off operation, install the worker as a Windows scheduled task so newly
added tracks are analysed without running anything manually. The
[Emby plugin](plugin/) already triggers a library scan on every add; the worker
then drains the queue on its own.

```powershell
# On the coordinator / server box — always-on worker (run elevated):
./deploy/worker-install.ps1 -Mode service

# On a separate GPU desktop you also use — only runs while the machine is idle:
./deploy/worker-install.ps1 -Mode idle -CoordinatorUrl http://<coordinator-host>:8765
```

`service` mode runs as SYSTEM, starts at boot, and restarts if it dies. `idle`
mode runs only when the machine is idle and stops the instant you return, so it
never fights your foreground work. Both can run together on different machines.

The script auto-detects the repo + `.venv`, writes the run wrapper, and pins
`USERPROFILE` so `panns_inference` finds its model under SYSTEM. See
`./deploy/worker-install.ps1 -?` for all options.

### Docker (optional — for NAS / Linux hosts)

```bash
docker build -t emby-sonic .
docker run -d --name emby-sonic -p 8765:8765 \
  -e EMBY_URL=http://<emby-host>:8096 \
  -e EMBY_API_KEY=<key> \
  -v emby-sonic-data:/app/data \
  -v emby-sonic-models:/app/models \
  emby-sonic
```

## API

All user-facing routes are under `/sonic` and require an `X-Emby-Token` header
(validated against Emby's `/System/Info`). Worker routes use `X-Worker-Token`.

| Endpoint | Method | Description |
|---|---|---|
| `/sonic/status` | GET | Analysis progress + library stats |
| `/sonic/tracks/{id}/similar` | GET | Sonically similar tracks |
| `/sonic/tracks/{id}/radio` | GET | Track radio playlist |
| `/sonic/adventure` | POST | Mood-transitioning playlist A→B |
| `/sonic/mixes` | GET | Auto-curated mixes (named by sonic character + dominant artist) |
| `/sonic/mixes/{id}` | GET | One mix with its tracks |
| `/sonic/mixes/{id}/regenerate` | POST | Refresh one mix: full track turnover from its stored centroid, on-theme |
| `/sonic/queue/inject` | POST | Guest DJ queue injection |
| `/sonic/artists/{id}/similar` | GET | Similar artists |
| `/sonic/albums/{id}/similar` | GET | Similar albums |
| `/sonic/library/scan` | POST | Trigger library sync |
| `/sonic/library/build-mixes` | POST | Rebuild auto-curated mixes (k-means) |
| `/sonic/library/build-state` | GET | Report whether a mix rebuild is running |

Interactive docs at `http://<host>:8765/docs` once running.

## Configuration

Set via environment variables or a `.env` file:

| Variable | Default | Description |
|---|---|---|
| `EMBY_URL` | `http://192.168.1.50:8096` | Emby server URL |
| `EMBY_API_KEY` | *(required)* | Emby API key (also used as worker shared secret) |
| `HOST` | `0.0.0.0` | Bind address |
| `PORT` | `8765` | Bind port |
| `NUM_WINDOWS` | `3` | Windows sampled per track (speed/quality knob) |
| `WINDOW_SECONDS` | `30` | Duration of each analysis window |
| `EMBEDDING_DIM` | `128` | PCA target dimensionality |

## Phase 2 — Emby Plugin

A .NET 8 plugin (`plugin/`) that adds an Emby dashboard config page (set the
coordinator URL, view live analysis status, trigger scans / mix rebuilds) and
fires an incremental scan when tracks are added to the library.

**Build** (requires the .NET 8 SDK and Emby's SDK DLLs in `plugin/lib/` —
`MediaBrowser.Common.dll`, `MediaBrowser.Controller.dll`, `MediaBrowser.Model.dll`,
copied from your Emby install's `system/` folder; they are not redistributable):

```powershell
cd plugin
./build.ps1            # Release build + dist/EmbysonicPlugin_<version>.zip
```

**Install:** Emby (unlike Jellyfin) has no plugin-zip upload in its dashboard, so
the plugin is installed by placing its DLL in Emby's `programdata/plugins/` folder.
The release zip ships install scripts that do this for you — run on the Emby host:

```powershell
./install.ps1         # Windows: auto-detects Emby, copies DLL, restarts
```
```bash
./install.sh          # Linux: auto-detects Emby plugins dir, copies DLL
```

Or copy `EmbysonicPlugin.dll` into `…/Emby-Server/programdata/plugins/` manually
and restart Emby. The plugin then appears under **Dashboard → Plugins → Emby Sonic**.

The plugin talks to the coordinator over HTTP; run the coordinator wherever it's
convenient (same host or another LAN machine) and point the plugin's config page
at its URL.

## Phase 3 — Android app (liquidWave)

A Kotlin / Jetpack Compose app (`android/`, package `guru.liquid.embysonic`,
minSdk 26). Browse/stream/auth go to the **Emby API directly**; sonic features go
to the **coordinator**. Stack: Compose + Hilt + Retrofit/OkHttp (two clients) +
Media3 ExoPlayer + DataStore.

**Features:** library + audiobook browse with A–Z fast-scroll; Now Playing with
queue, shuffle/repeat, mini player, and a system media notification; durable
audiobook resume; music crossfade with a synced artwork cross-dissolve; an in-app
equalizer (presets + per-band, also broadcasts its session for external EQ apps);
auto-curated sonic mixes (per-mix refresh, save as playlist); Track Radio; Sonic
Adventure (a sonic journey from one track to another); Stations (Library / Random
Album / Decade radios); Recent Plays; and search across music (tracks/albums/
artists), audiobooks (books/authors), or everything from Home.

### Build

Requires the Android SDK (platform android-36) and JDK 17 (Android Studio's
bundled JBR works).

```bash
cd android
JAVA_HOME="<path-to-jdk17>" ./gradlew :app:assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

### Install on a phone (USB or wireless ADB)

The debug APK is fine for real use (debug vs release doesn't affect audio).

**USB:** enable *Developer options → USB debugging*, plug in, then:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

**Wireless ADB** (no cable; Android 11+): on the phone, *Developer options →
Wireless debugging*. Pair once, then connect and install — after pairing, only
the port changes between sessions:

```bash
# one-time pairing (use the IP:port + 6-digit code from "Pair device with pairing code")
adb pair <phone-ip>:<pair-port> <code>
# then each session (IP:port from the main Wireless debugging screen):
adb connect <phone-ip>:<connect-port>
adb -s <phone-ip>:<connect-port> install -r android/app/build/outputs/apk/debug/app-debug.apk
```

On first launch, enter your Emby server URL + credentials and the coordinator URL
in the login screen. The phone must be on the same LAN as Emby and the coordinator.

## License

TBD — intended to be open source / community-distributable.
