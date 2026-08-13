# Emby Sonic full image — optional containerised worker deployment.
#
# This image contains the audio analysis stack and can run either the
# coordinator (python main.py) or a worker (python worker.py). The
# docker-compose.yml uses the smaller Dockerfile.coordinator for the coordinator
# and this full image for workers.
#
# Build:  docker build -t emby-sonic-worker .
# GPU:    docker build --build-arg TORCH_VARIANT=cuda -t emby-sonic-worker:cuda .
# Run:    docker run -d --name emby-sonic-worker \
#           -e COORDINATOR_URL=http://<coordinator-host>:8765 \
#           -e EMBY_URL=http://<emby-host>:8096 \
#           -e EMBY_API_KEY=<key> \
#           -v emby-sonic-panns:/root/panns_data \
#           emby-sonic-worker python worker.py
#
# Workers stream audio through Emby's HTTP API, so no music library bind mount is
# required.

FROM python:3.12-slim

# System deps for librosa/soundfile audio decoding
RUN apt-get update && apt-get install -y --no-install-recommends \
    libsndfile1 \
    ffmpeg \
    wget \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install torch first so requirements.txt does not choose a different variant.
# TORCH_VARIANT:
#   cpu   = CPU-only PyTorch wheel, the default and smallest worker image.
#   cuda  = PyPI PyTorch wheel with bundled NVIDIA CUDA dependencies.
#   cuXXX = explicit PyTorch CUDA wheel index, for example cu126.
COPY requirements.txt .
ARG TORCH_VARIANT=cpu
RUN set -eux; \
    case "$TORCH_VARIANT" in \
        cpu) \
            pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu; \
            ;; \
        cuda) \
            pip install --no-cache-dir torch; \
            ;; \
        cu*) \
            pip install --no-cache-dir torch --index-url "https://download.pytorch.org/whl/${TORCH_VARIANT}"; \
            ;; \
        *) \
            echo "Unsupported TORCH_VARIANT=${TORCH_VARIANT}; expected cpu, cuda, or cuXXX" >&2; \
            exit 1; \
            ;; \
    esac; \
    pip install --no-cache-dir -r requirements.txt

# Essentia is intentionally NOT installed here — librosa fallback is used.
# To enable it on a supported base image, add requirements-optional.txt.

COPY . .

ENV HOST=0.0.0.0 PORT=8765
EXPOSE 8765

CMD ["python", "main.py"]
