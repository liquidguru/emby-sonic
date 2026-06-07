# Emby Sonic

Self-hosted neural audio analysis for Emby — a privacy-first equivalent of
Plexamp's Sonic Analysis. Maps a music library into a multi-dimensional "sonic
space" using audio embeddings (not genre tags), enabling sonically intelligent
discovery: similar tracks/artists/albums, track radio, sonic adventures, auto-curated
mixes, and a Guest DJ.

> **Status:** Phase 1 (Python analysis service) and Phase 2 (C# Emby plugin) complete.
> Phase 3 (Android app) next. See [`docs/spec.md`](docs/spec.md) for the full architecture and roadmap.

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

**PANNs CNN14 checkpoint** — pre-download before first scan (the `panns_inference`
library uses `wget` which is absent on Windows):

```bash
# Download to ~/panns_data/ (or set PANNS_DATA env var)
curl -L -o ~/panns_data/Cnn14_mAP=0.431.pth \
  https://zenodo.org/record/3987831/files/Cnn14_mAP%3D0.431.pth
curl -L -o ~/panns_data/class_labels_indices.csv \
  https://raw.githubusercontent.com/qiuqiangkong/audioset_tagging_cnn/master/metadata/class_labels_indices.csv
```

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
| `/sonic/mixes` | GET | Auto-curated mixes |
| `/sonic/queue/inject` | POST | Guest DJ queue injection |
| `/sonic/artists/{id}/similar` | GET | Similar artists |
| `/sonic/albums/{id}/similar` | GET | Similar albums |
| `/sonic/library/scan` | POST | Trigger library sync |
| `/sonic/library/build-mixes` | POST | Rebuild auto-curated mixes (k-means) |

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

## License

TBD — intended to be open source / community-distributable.
