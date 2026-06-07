#!/usr/bin/env bash
# Install the Emby Sonic plugin on a Linux Emby Server.
#
#   ./install.sh                  # auto-detect Emby plugins dir, copy DLL
#   ./install.sh /path/plugins    # explicit plugins folder
#
# Run on the machine where Emby Server is installed. The DLL must sit next to
# this script (it does inside the release zip).

set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

dll=""
for c in "$here/EmbysonicPlugin.dll" \
         "$here/bin/Release/net8.0/EmbysonicPlugin.dll" \
         "$here/bin/Debug/net8.0/EmbysonicPlugin.dll"; do
    [ -f "$c" ] && dll="$c" && break
done
[ -n "$dll" ] || { echo "EmbysonicPlugin.dll not found; build it first." >&2; exit 1; }

plugins="${1:-}"
if [ -z "$plugins" ]; then
    for g in \
        "$HOME/.config/emby-server/programdata/plugins" \
        "/var/lib/emby/plugins" \
        "/opt/emby-server/programdata/plugins" \
        "/config/plugins"; do
        [ -d "$g" ] && plugins="$g" && break
    done
fi
[ -n "$plugins" ] || { echo "Could not find Emby plugins dir; pass it as an argument." >&2; exit 1; }

mkdir -p "$plugins"
cp -f "$dll" "$plugins/EmbysonicPlugin.dll"
echo "Installed -> $plugins/EmbysonicPlugin.dll"
echo "Restart Emby Server to load the plugin (e.g. 'systemctl restart emby-server' or restart your container)."
