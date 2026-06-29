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
> the Artist Mix Creator, Stations, Recent Plays, offline playlist downloads,
> per-track volume normalisation, and search across music + audiobooks. See
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

New installs should start with the scenario guide:
[`docs/quickstart.md`](docs/quickstart.md).

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
so it runs in a small container on any NAS (Synology, QNAP, UGREEN, ...), including
ARM. Audio analysis runs on separate **workers** on a machine with spare CPU/GPU.

```bash
EMBY_URL=http://<emby-host>:8096 EMBY_API_KEY=<key> docker compose up -d --build
```

This builds [`Dockerfile.coordinator`](Dockerfile.coordinator) and starts the
coordinator on port 8765 with a persistent `emby-sonic-data` volume (SQLite +
FAISS index). Then point the Emby plugin's **Python Service URL** at
`http://<nas-host>:8765` and run one or more workers on your GPU/CPU box — they
stream audio from Emby, so no file shares are needed.

> **Prebuilt images:** at public launch the coordinator/worker images will be
> pulled from GHCR (set `COORDINATOR_IMAGE` / `WORKER_IMAGE` and drop `--build`).
> During the private beta the images are not yet public, so build from source.

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
WORKER_SECRET=<worker-secret> \
WORKER_ID=my-worker \
python worker.py
```

Workers authenticate to the coordinator with `WORKER_SECRET` when it is set, or
fall back to `EMBY_API_KEY` for older deployments. They auto-detect CUDA. Run
multiple workers in parallel for faster scanning.

> **Music libraries only.** The scan is scoped to Emby libraries whose collection
> type is **`music`** — audiobooks (a separate `audiobooks` library) and other
> audio are never analysed, so spoken-word content can't pollute Track Radio /
> Similar / Adventure. If an earlier scan already embedded audiobooks, clean them
> out with `python tools/purge_audiobooks.py` (dry-run by default; `--apply` to
> delete; rebuilds on the next coordinator restart).

### Broken-track maintenance

Tracks that fail analysis are left with `analysis_status='error'` so workers do
not retry the same stale/missing file forever. Export them for library cleanup,
or requeue them after fixing files in Emby:

```bash
# Export id,title,artist,album,file_path,error for all broken tracks.
python tools/broken_tracks.py --db data/sonic.db export --output broken_tracks.csv

# Requeue selected tracks from a prior export CSV.
python tools/broken_tracks.py --db data/sonic.db requeue --csv broken_tracks.csv

# Or requeue every error row.
python tools/broken_tracks.py --db data/sonic.db requeue --all
```

Requeue changes `analysis_status` from `error` to `pending`, clears the claim and
error text, and lets the next running worker retry the track.

### Automatic analysis (worker as a service)

For hands-off operation, install the worker as an OS service so newly added
tracks are analysed without running anything manually. The
[Emby plugin](plugin/) already triggers a library scan on every add; the worker
then drains the queue on its own.

**Windows scheduled task:**

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

**Linux systemd service:**

```bash
# On the coordinator / server box:
sudo ./deploy/worker-install.sh

# On a separate GPU/CPU worker box:
sudo ./deploy/worker-install.sh --coordinator-url http://<coordinator-host>:8765
```

The Linux installer renders [`deploy/emby-sonic-worker.service`](deploy/emby-sonic-worker.service)
into `/etc/systemd/system`, runs from the repo venv, restarts on failure, starts
at boot, and reads `EMBY_URL` / `EMBY_API_KEY` from the repo `.env`.

### Docker (optional — for NAS / Linux hosts)

Coordinator-only NAS deploy:

```bash
EMBY_URL=http://<emby-host>:8096 EMBY_API_KEY=<key> docker compose up -d --build coordinator
```

Coordinator plus worker in Compose:

```bash
EMBY_URL=http://<emby-host>:8096 EMBY_API_KEY=<key> docker compose up -d --build
```

The Compose `worker` service builds the full [`Dockerfile`](Dockerfile), runs
`python worker.py`, and stores the CNN14 checkpoint in the named
`emby-sonic-panns` volume mounted at `/root/panns_data`, so the ~327 MB model is
not redownloaded on every container rebuild/restart. Workers stream audio from
Emby; no music library bind mount is needed.

Worker images are CPU-only by default. To build a CUDA-capable worker on an
x86_64 Linux host with an NVIDIA GPU, set `TORCH_VARIANT=cuda` before building
and run the worker with GPU access:

```bash
# Keep the coordinator running normally.
EMBY_URL=http://<emby-host>:8096 EMBY_API_KEY=<key> docker compose up -d --build coordinator

# Build a CUDA-capable worker image and run it with the NVIDIA runtime.
TORCH_VARIANT=cuda docker compose build worker
EMBY_URL=http://<emby-host>:8096 EMBY_API_KEY=<key> \
  docker compose run --rm --gpus all worker
```

For a detached GPU worker, uncomment `gpus: all` under the `worker` service in
[`docker-compose.yml`](docker-compose.yml), then run:

```bash
TORCH_VARIANT=cuda EMBY_URL=http://<emby-host>:8096 EMBY_API_KEY=<key> \
  docker compose up -d --build worker
```

The startup log is the proof: `docker compose run --rm --gpus all worker` prints
`[worker <id>] device=cuda` to the terminal. For a detached worker, check
`docker logs emby-sonic-worker`. If it says `device=cpu`, confirm the image was
built with `TORCH_VARIANT=cuda` and that `nvidia-smi` works inside a test
container.

> **Prebuilt images (public launch):** once the GHCR packages are public, set
> `COORDINATOR_IMAGE` / `WORKER_IMAGE` (e.g. `…-worker:latest` or `:cuda`) and
> drop `--build` to pull instead of build. They are private during the beta.

Standalone worker container:

```bash
docker build -t emby-sonic-worker .
docker run -d --name emby-sonic-worker \
  -e COORDINATOR_URL=http://<coordinator-host>:8765 \
  -e EMBY_URL=http://<emby-host>:8096 \
  -e EMBY_API_KEY=<key> \
  -v emby-sonic-panns:/root/panns_data \
  emby-sonic-worker python worker.py
```

Standalone NVIDIA GPU worker:

```bash
docker build --build-arg TORCH_VARIANT=cuda -t emby-sonic-worker:cuda .
docker run -d --name emby-sonic-worker-gpu --gpus all \
  -e COORDINATOR_URL=http://<coordinator-host>:8765 \
  -e EMBY_URL=http://<emby-host>:8096 \
  -e EMBY_API_KEY=<key> \
  -v emby-sonic-panns:/root/panns_data \
  emby-sonic-worker:cuda python worker.py
docker logs emby-sonic-worker-gpu | grep 'device='
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
| `EMBY_API_KEY` | *(required)* | Emby API key for coordinator admin calls and worker audio downloads |
| `WORKER_SECRET` | falls back to `EMBY_API_KEY` | Shared secret required in `X-Worker-Token` for worker routes |
| `HOST` | `0.0.0.0` | Bind address |
| `PORT` | `8765` | Bind port |
| `NUM_WINDOWS` | `3` | Windows sampled per track (speed/quality knob) |
| `WINDOW_SECONDS` | `30` | Duration of each analysis window |
| `EMBEDDING_DIM` | `128` | PCA target dimensionality |

## Phase 2 — Emby Plugin

A .NET 8 plugin (`plugin/`) that adds an Emby dashboard config page (set the
coordinator URL, view live analysis status — including a "skipped tracks" list
with the reason each couldn't be analysed — and trigger scans / mix rebuilds) and
fires an incremental scan when tracks are added to the library.

> **Requires Emby Server 4.8 or newer.** .NET assembly binding only resolves
> "upward", so a plugin must be built against an Emby SDK **no newer** than the
> target server or Emby silently skips it (it never appears on the Plugins page).
> The shipped builds target the 4.8 SDK, so they load on 4.8 / 4.9 stable and the
> 4.10 beta alike. If you build it yourself, use the oldest Emby you want to
> support — see the note in `plugin/EmbysonicPlugin.csproj`.

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
Adventure (a sonic journey from one track to another); the Artist Mix Creator
(pick a set of artists — the grid suggests sonically similar ones as you go —
then build a shuffled cross-artist mix, sized by the shared "tracks per mix"
setting); Stations (Library / Random Album / Decade radios); Recent Plays; offline
downloads (download a playlist or a whole audiobook's original source files for
browsing and playback with no network — audiobooks keep durable resume across the
offline→online boundary; Wi-Fi-only by default; managed under Settings → Downloads);
per-track **volume normalisation** (levels playback to a consistent loudness using
the coordinator's measured LUFS — a `GainAudioProcessor` in the audio sink, toggle
in Settings, on by default); a **configurable offline prefetch buffer** (Settings →
Offline prefetch, 3/5/10/15 tracks ahead) to ride through signal drops; and search
across music (tracks/albums/artists), audiobooks (books/authors), or everything
from Home.

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

### Android Auto (sideloaded build)

Because the app is sideloaded (not from the Play Store), Android Auto hides it by
default. To use it in the car, enable Android Auto **developer mode** and allow
unknown sources — once, on the phone:

1. Android Auto settings → tap the **Version** repeatedly to unlock *Developer settings*.
2. Developer settings → enable **Unknown sources**.

liquidWave then appears in Android Auto's app list. (A future Play Store release
won't need this.)

## License

TBD — intended to be open source / community-distributable.
