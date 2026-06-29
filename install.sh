#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$ROOT_DIR/.env"
COMPOSE_FILE="$ROOT_DIR/docker-compose.installer.yml"

COORDINATOR_IMAGE="ghcr.io/liquidguru/emby-sonic-coordinator:latest"
WORKER_IMAGE_CPU="ghcr.io/liquidguru/emby-sonic-worker:latest"
WORKER_IMAGE_CUDA="ghcr.io/liquidguru/emby-sonic-worker:cuda"

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

prompt() {
  local label="$1"
  local default="${2:-}"
  local value
  if [ -n "$default" ]; then
    read -r -p "$label [$default]: " value
    printf '%s' "${value:-$default}"
  else
    read -r -p "$label: " value
    printf '%s' "$value"
  fi
}

prompt_secret() {
  local label="$1"
  local value
  if [ -t 0 ]; then
    read -r -s -p "$label: " value
    echo
  else
    read -r -p "$label: " value
  fi
  printf '%s' "$value"
}

yes_no() {
  local label="$1"
  local default="${2:-n}"
  local hint="[y/N]"
  [ "$default" = "y" ] && hint="[Y/n]"
  local value
  read -r -p "$label $hint: " value
  value="${value:-$default}"
  case "${value,,}" in
    y|yes) return 0 ;;
    *) return 1 ;;
  esac
}

random_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  elif [ -r /dev/urandom ]; then
    local secret
    secret="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 48 || true)"
    if [ -n "$secret" ]; then
      printf '%s\n' "$secret"
    else
      date +%s%N
    fi
  else
    date +%s%N
  fi
}

write_env() {
  local emby_url="$1"
  local emby_api_key="$2"
  local worker_secret="$3"
  local coordinator_url="$4"
  local worker_image="$5"

  cat >"$ENV_FILE" <<EOF_ENV
EMBY_URL=$emby_url
EMBY_API_KEY=$emby_api_key
WORKER_SECRET=$worker_secret
COORDINATOR_URL=$coordinator_url
WORKER_ID=docker-worker
WORKER_BATCH=16
NUM_WINDOWS=3
COORDINATOR_IMAGE=$COORDINATOR_IMAGE
WORKER_IMAGE=$worker_image
EOF_ENV
}

write_compose() {
  local include_coordinator="$1"
  local include_worker="$2"
  local use_gpu="$3"
  local worker_coordinator_url="$4"

  cat >"$COMPOSE_FILE" <<'EOF_COMPOSE'
services:
EOF_COMPOSE

  if [ "$include_coordinator" = "1" ]; then
    cat >>"$COMPOSE_FILE" <<'EOF_COMPOSE'
  coordinator:
    image: "${COORDINATOR_IMAGE:-ghcr.io/liquidguru/emby-sonic-coordinator:latest}"
    container_name: emby-sonic-coordinator
    restart: unless-stopped
    ports:
      - "8765:8765"
    environment:
      EMBY_URL: "${EMBY_URL}"
      EMBY_API_KEY: "${EMBY_API_KEY}"
      WORKER_SECRET: "${WORKER_SECRET:-}"
      NUM_WINDOWS: "${NUM_WINDOWS:-3}"
    volumes:
      - emby-sonic-data:/app/data
EOF_COMPOSE
  fi

  if [ "$include_worker" = "1" ]; then
    cat >>"$COMPOSE_FILE" <<EOF_COMPOSE
  worker:
    image: "\${WORKER_IMAGE:-ghcr.io/liquidguru/emby-sonic-worker:latest}"
    container_name: emby-sonic-worker
    restart: unless-stopped
EOF_COMPOSE
    if [ "$include_coordinator" = "1" ]; then
      cat >>"$COMPOSE_FILE" <<'EOF_COMPOSE'
    depends_on:
      - coordinator
EOF_COMPOSE
    fi
    cat >>"$COMPOSE_FILE" <<EOF_COMPOSE
    command: ["python", "worker.py"]
    environment:
      COORDINATOR_URL: "$worker_coordinator_url"
      EMBY_URL: "\${EMBY_URL}"
      EMBY_API_KEY: "\${EMBY_API_KEY}"
      WORKER_SECRET: "\${WORKER_SECRET:-}"
      WORKER_ID: "\${WORKER_ID:-docker-worker}"
      WORKER_BATCH: "\${WORKER_BATCH:-16}"
      NUM_WINDOWS: "\${NUM_WINDOWS:-3}"
      PANNS_CHECKPOINT_PATH: /root/panns_data/Cnn14_mAP=0.431.pth
    volumes:
      - emby-sonic-panns:/root/panns_data
EOF_COMPOSE
    if [ "$use_gpu" = "1" ]; then
      cat >>"$COMPOSE_FILE" <<'EOF_COMPOSE'
    gpus: all
EOF_COMPOSE
    fi
  fi

  cat >>"$COMPOSE_FILE" <<'EOF_COMPOSE'

volumes:
  emby-sonic-data:
  emby-sonic-panns:
EOF_COMPOSE
}

need_cmd docker
if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 is required. Install Docker Desktop or the Docker Compose plugin." >&2
  exit 1
fi

echo "Emby Sonic guided Docker setup"
echo
echo "NOTE: this installer pulls prebuilt images from GHCR, which are PRIVATE"
echo "during the closed beta. If the image pull fails with 'denied', the images"
echo "are not public yet — build from source instead:"
echo "  docker compose up -d --build"
echo
echo "Choose the setup closest to this machine:"
echo "  1) NAS + Docker coordinator"
echo "  2) Single Linux/Windows Docker box"
echo "  3) Separate worker rig"
read -r -p "Scenario [1-3]: " scenario
scenario="${scenario:-1}"

include_coordinator=0
include_worker=0
use_gpu=0
coordinator_url="http://coordinator:8765"

case "$scenario" in
  1)
    include_coordinator=1
    if yes_no "Also run a CPU worker on this NAS/Docker host?" "n"; then
      include_worker=1
    fi
    ;;
  2)
    include_coordinator=1
    include_worker=1
    if yes_no "Use an NVIDIA GPU for the worker on this machine?" "n"; then
      use_gpu=1
    fi
    ;;
  3)
    include_worker=1
    coordinator_url="$(prompt "Coordinator URL" "http://<coordinator-host>:8765")"
    if yes_no "Use an NVIDIA GPU for this worker?" "y"; then
      use_gpu=1
    fi
    ;;
  *)
    echo "Unknown scenario: $scenario" >&2
    exit 1
    ;;
esac

emby_url="$(prompt "Emby server URL" "http://192.168.1.50:8096")"
emby_api_key="$(prompt_secret "Emby API key")"
if [ -z "$emby_api_key" ]; then
  echo "EMBY_API_KEY is required." >&2
  exit 1
fi

if [ "$include_coordinator" = "1" ]; then
  # Coordinator and worker share this .env, so a fresh random shared secret is
  # written to both sides at once.
  worker_secret="$(random_secret)"
else
  # Separate worker rig: the coordinator already exists elsewhere and decides the
  # secret. Match it here, or leave blank to fall back to EMBY_API_KEY (the
  # coordinator default). A random value would never match and auth would 401.
  echo
  echo "The coordinator decides the worker token. Leave blank to use the Emby"
  echo "API key (the coordinator's default), or enter the WORKER_SECRET you set"
  echo "on the coordinator."
  worker_secret="$(prompt_secret "Coordinator WORKER_SECRET (blank = use Emby API key)")"
fi
worker_image="$WORKER_IMAGE_CPU"
if [ "$use_gpu" = "1" ]; then
  worker_image="$WORKER_IMAGE_CUDA"
fi

export EMBY_URL="$emby_url"
export EMBY_API_KEY="$emby_api_key"
export WORKER_SECRET="$worker_secret"
export COORDINATOR_URL="$coordinator_url"
export COORDINATOR_IMAGE
export WORKER_IMAGE="$worker_image"

if [ -f "$ENV_FILE" ] && ! yes_no "$ENV_FILE exists. Overwrite it?" "n"; then
  echo "Leaving existing .env in place."
else
  write_env "$emby_url" "$emby_api_key" "$worker_secret" "$coordinator_url" "$worker_image"
  chmod 600 "$ENV_FILE" 2>/dev/null || true
  echo "Wrote $ENV_FILE"
fi

write_compose "$include_coordinator" "$include_worker" "$use_gpu" "$coordinator_url"
echo "Wrote $COMPOSE_FILE"

services=()
[ "$include_coordinator" = "1" ] && services+=("coordinator")
[ "$include_worker" = "1" ] && services+=("worker")

echo
echo "Pulling GHCR images..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull "${services[@]}"

echo
echo "Starting Emby Sonic..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d "${services[@]}"

echo
echo "Done."
if [ "$include_coordinator" = "1" ]; then
  echo "Coordinator: http://localhost:8765"
  echo "Point the Emby plugin Python Service URL at http://<this-host>:8765"
fi
if [ "$include_worker" = "1" ]; then
  echo "Worker logs: docker logs -f emby-sonic-worker"
  if [ "$use_gpu" = "1" ]; then
    echo "Expected worker proof: [worker docker-worker] device=cuda"
  else
    echo "Expected worker proof: [worker docker-worker] device=cpu"
  fi
fi
