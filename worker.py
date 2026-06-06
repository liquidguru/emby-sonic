"""
Standalone analysis worker.

Runs anywhere on the network — the coordinator's own box, or a beefier machine
(e.g. liquidHulk's RTX 4070). It repeatedly claims pending tracks from the
coordinator, streams each track's audio from Emby, embeds it (GPU auto-detected),
and posts the results back. Run as many workers as you like, on as many machines.

Usage:
    python worker.py

Config via environment (same EMBY_URL + EMBY_API_KEY as the service):
    COORDINATOR_URL   default http://localhost:8765
    WORKER_ID         default = hostname
    WORKER_BATCH      default 16
"""

from __future__ import annotations

import base64
import os
import socket
import sys
import time

import httpx
import numpy as np

from config import settings
from analysis import emby
from analysis.audio import load_windows, extract_features
from analysis.embeddings import embedder

COORDINATOR = os.environ.get("COORDINATOR_URL", "http://localhost:8765").rstrip("/")
WORKER_ID = os.environ.get("WORKER_ID", socket.gethostname())
BATCH = int(os.environ.get("WORKER_BATCH", "16"))
_HEADERS = {"X-Worker-Token": settings.emby_api_key}


def _claim(client: httpx.Client) -> list[dict]:
    r = client.post(
        f"{COORDINATOR}/sonic/worker/claim",
        json={"worker_id": WORKER_ID, "batch_size": BATCH},
        headers=_HEADERS,
    )
    r.raise_for_status()
    return r.json()["tracks"]


def _analyse(track: dict) -> dict:
    suffix = os.path.splitext(track.get("path") or "")[1]
    path = emby.download_track(track["id"], suffix=suffix)
    try:
        windows = load_windows(path)
        if not windows:
            raise ValueError("no decodable audio")
        window_audio = np.concatenate(windows)
        feats = extract_features(path, window_audio, settings.sample_rate)
        raw = embedder.embed_raw(windows)
    finally:
        try:
            os.remove(path)
        except OSError:
            pass
    return {
        "track_id": track["id"],
        "raw_vector": base64.b64encode(raw.tobytes()).decode("ascii"),
        **feats,
    }


def main() -> None:
    print(f"[worker {WORKER_ID}] coordinator={COORDINATOR} batch={BATCH}")
    embedder._ensure_loaded()  # warm the model so the device is reported up front
    print(f"[worker {WORKER_ID}] device={embedder._device}")

    with httpx.Client(timeout=300.0) as client:
        announced_idle = False
        while True:
            try:
                tracks = _claim(client)
            except Exception as exc:
                print(f"[worker] claim failed: {exc}; retry in 30s")
                time.sleep(30)
                continue

            if not tracks:
                if not announced_idle:
                    print("[worker] no pending tracks; waiting...")
                    announced_idle = True
                time.sleep(30)
                continue
            announced_idle = False

            results = []
            for track in tracks:
                try:
                    results.append(_analyse(track))
                except Exception as exc:
                    results.append({"track_id": track["id"], "error": str(exc)[:500]})

            r = client.post(
                f"{COORDINATOR}/sonic/worker/results",
                json={"results": results},
                headers=_HEADERS,
            )
            r.raise_for_status()
            out = r.json()
            print(f"[worker] batch done: stored={out['stored']} failed={out['failed']}")


if __name__ == "__main__":
    sys.exit(main())
