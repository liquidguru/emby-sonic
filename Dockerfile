# Emby Sonic analysis service — optional containerised deployment.
#
# This is the convenience path for users running Emby on a NAS / Linux box
# with Docker. Native install (pip + python main.py) remains the primary path
# for Windows and other no-Docker hosts.
#
# Build:  docker build -t emby-sonic .
# Run:    docker run -d --name emby-sonic -p 8765:8765 \
#           -e EMBY_URL=http://<emby-host>:8096 \
#           -e EMBY_API_KEY=<key> \
#           -v emby-sonic-data:/app/data \
#           -v emby-sonic-models:/app/models \
#           -v /path/to/music:/music:ro \
#           emby-sonic
#
# NOTE: the music library must be mounted at the SAME path the Emby API reports
# in each track's "Path" field, or the analyser can't find the files.

FROM python:3.12-slim

# System deps for librosa/soundfile audio decoding
RUN apt-get update && apt-get install -y --no-install-recommends \
    libsndfile1 \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# CPU-only torch first (smaller image, no CUDA), then the rest
COPY requirements.txt .
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir -r requirements.txt

# Essentia is intentionally NOT installed here — librosa fallback is used.
# To enable it on a supported base image, add requirements-optional.txt.

COPY . .

ENV HOST=0.0.0.0 PORT=8765
EXPOSE 8765

CMD ["python", "main.py"]
