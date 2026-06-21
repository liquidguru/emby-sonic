#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Install the Emby Sonic analysis worker as a Linux systemd service.

Usage:
  sudo ./deploy/worker-install.sh [options]

Options:
  --coordinator-url URL   Coordinator URL (default: http://localhost:8765)
  --repo-dir PATH        Repo directory (default: parent of this script)
  --python-exe PATH      Python executable (default: repo .venv/bin/python)
  --service-name NAME    systemd unit name (default: emby-sonic-worker)
  --user USER            Linux user to run as (default: sudo caller, else root)
  --home-dir PATH        HOME/USERPROFILE for model cache (default: user's home)
  -h, --help             Show this help

The service reads EMBY_URL and EMBY_API_KEY from <repo>/.env, sets
COORDINATOR_URL, runs worker.py from the repo, restarts on failure, and starts
at boot.
EOF
}

coordinator_url="http://localhost:8765"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$script_dir/.." && pwd)"
python_exe=""
service_name="emby-sonic-worker"
service_user="${SUDO_USER:-$(id -un)}"
home_dir=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --coordinator-url)
      coordinator_url="${2:?missing value for --coordinator-url}"
      shift 2
      ;;
    --repo-dir)
      repo_dir="${2:?missing value for --repo-dir}"
      shift 2
      ;;
    --python-exe)
      python_exe="${2:?missing value for --python-exe}"
      shift 2
      ;;
    --service-name)
      service_name="${2:?missing value for --service-name}"
      shift 2
      ;;
    --user)
      service_user="${2:?missing value for --user}"
      shift 2
      ;;
    --home-dir)
      home_dir="${2:?missing value for --home-dir}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
  echo "Service install writes to /etc/systemd/system; rerun with sudo." >&2
  exit 1
fi

repo_dir="$(cd -- "$repo_dir" && pwd)"
worker_py="$repo_dir/worker.py"
template="$repo_dir/deploy/emby-sonic-worker.service"
unit_path="/etc/systemd/system/${service_name}.service"

if [[ ! -f "$worker_py" ]]; then
  echo "worker.py not found in $repo_dir. Pass --repo-dir <path-to-emby-sonic>." >&2
  exit 1
fi

if [[ ! -f "$template" ]]; then
  echo "Unit template not found: $template" >&2
  exit 1
fi

if [[ -z "$python_exe" ]]; then
  for candidate in "$repo_dir/.venv/bin/python" "$repo_dir/venv/bin/python"; do
    if [[ -x "$candidate" ]]; then
      python_exe="$candidate"
      break
    fi
  done
fi

if [[ -z "$python_exe" || ! -x "$python_exe" ]]; then
  echo "Python venv not found. Create .venv, or pass --python-exe <path>." >&2
  exit 1
fi

if ! id "$service_user" >/dev/null 2>&1; then
  echo "User '$service_user' does not exist." >&2
  exit 1
fi

if [[ -z "$home_dir" ]]; then
  home_dir="$(getent passwd "$service_user" | cut -d: -f6)"
fi
if [[ -z "$home_dir" || ! -d "$home_dir" ]]; then
  echo "Home directory not found for '$service_user'. Pass --home-dir <path>." >&2
  exit 1
fi

unit="$(<"$template")"
unit="${unit//__USER__/$service_user}"
unit="${unit//__REPO_DIR__/$repo_dir}"
unit="${unit//__PYTHON_EXE__/$python_exe}"
unit="${unit//__COORDINATOR_URL__/$coordinator_url}"
unit="${unit//__HOME_DIR__/$home_dir}"

printf '%s\n' "$unit" > "$unit_path"
chmod 0644 "$unit_path"

systemctl daemon-reload
systemctl enable --now "${service_name}.service"

state="$(systemctl is-active "${service_name}.service" || true)"
echo "Installed ${service_name}.service"
echo "  state:       $state"
echo "  unit:        $unit_path"
echo "  repo:        $repo_dir"
echo "  python:      $python_exe"
echo "  user:        $service_user"
echo "  coordinator: $coordinator_url"
echo "  logs:        journalctl -u ${service_name}.service -f"

if [[ ! -d "$home_dir/panns_data" ]]; then
  echo "NOTE: $home_dir/panns_data not found; the CNN14 checkpoint downloads on first run."
fi
