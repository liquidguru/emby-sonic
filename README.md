# Emby Sonic

Self-hosted neural audio analysis for Emby — a privacy-first equivalent of
Plexamp's Sonic Analysis. It maps a music library into a multi-dimensional
"sonic space" using audio embeddings (not genre tags), enabling sonically
intelligent discovery: similar tracks/artists/albums, track radio, sonic
adventures (mood-transitioning playlists), auto-curated mixes, and a Guest DJ.

> **Status:** Phase 1 (Python analysis service) in active development.
> See [`docs/spec.md`](docs/spec.md) for the full architecture and roadmap.

## Architecture

```
Emby Server (existing)
└── Emby Plugin (C#, thin agnostic proxy — Phase 2)
    └── Python Analysis Service (FastAPI — Phase 1, this repo)
        ├── Audio analysis (librosa; Essentia optional)
        ├── Embeddings (PANNs CNN14 → 128-dim via PCA)
        ├── FAISS vector store (cosine similarity)
        └── SQLite metadata DB
```

The mobile apps (Android/iOS, Phases 3–4) are HTTP clients of this service.

## Phase 1 — Python Analysis Service

### Requirements

- Python 3.11+
- Runs anywhere Emby runs (Windows / Linux / macOS, x86 or ARM)

### Setup

```bash
# CPU-only PyTorch (smaller; fine for CPU inference)
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install -r requirements.txt

# Optional: higher-quality mood/vocal features (Linux/macOS only)
# pip install -r requirements-optional.txt   # Essentia

cp .env.example .env   # then set EMBY_URL and EMBY_API_KEY
python main.py         # serves on http://0.0.0.0:8765
```

### Benchmark before a full scan

```bash
python benchmark.py /path/to/a/track.flac
```

Reports per-stage timing and a real-time factor so you know how long a full
library scan will take on your hardware.

### Docker (optional — for NAS/Linux hosts)

```bash
docker build -t emby-sonic .
docker run -d --name emby-sonic -p 8765:8765 \
  -e EMBY_URL=http://<emby-host>:8096 -e EMBY_API_KEY=<key> \
  -v emby-sonic-data:/app/data -v emby-sonic-models:/app/models \
  -v /path/to/music:/music:ro emby-sonic
```

> The music library must be mounted at the **same path** Emby reports in each
> track's `Path` field, or the analyser can't locate the files.

## API

All routes are under `/sonic` and require an `X-Emby-Token` header (validated
against Emby's `/Users/Me`). Key endpoints:

| Endpoint | Method | Description |
|---|---|---|
| `/sonic/status` | GET | Analysis progress + library stats |
| `/sonic/tracks/{id}/similar` | GET | Sonically similar tracks |
| `/sonic/tracks/{id}/radio` | GET | Track radio playlist |
| `/sonic/adventure` | POST | Mood-transitioning playlist A→B |
| `/sonic/mixes` | GET | Auto-curated mixes |
| `/sonic/library/scan` | POST | Trigger full/incremental analysis |

Interactive API docs at `http://<host>:8765/docs` once running.

## License

TBD — intended to be open source / community-distributable (see spec goals).
