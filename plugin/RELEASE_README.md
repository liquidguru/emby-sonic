# Emby Sonic — Plugin

Neural audio analysis for Emby. This plugin adds an **Emby Sonic** page to your
Emby dashboard: point it at the Emby Sonic coordinator service, watch analysis
status, and trigger library scans / mix rebuilds. It also auto-triggers an
incremental scan when tracks are added to your library.

> The plugin is a thin client. You also need the **Emby Sonic coordinator**
> (the Python service) running somewhere on your network. See the project README.

## Install

Emby (unlike Jellyfin) has no "upload plugin zip" in its dashboard, so the
plugin is installed by placing its DLL in Emby's plugins folder. These scripts
do that for you.

**Windows** (run on the Emby Server machine, in PowerShell):

```powershell
./install.ps1
```

**Linux** (run on the Emby Server machine):

```bash
./install.sh
```

Both auto-detect Emby's `programdata/plugins` folder; pass an explicit path if
detection fails (`-PluginsDir` / first argument). Restart Emby afterwards
(the Windows script offers to do it).

## Manual install

Copy `EmbysonicPlugin.dll` into Emby's plugins folder as a flat file:

- Windows: `%AppData%\Emby-Server\programdata\plugins\EmbysonicPlugin.dll`
- Linux:   `~/.config/emby-server/programdata/plugins/EmbysonicPlugin.dll`
  (or your distro/container's Emby data path)

Restart Emby. The plugin appears under **Dashboard → Plugins → Emby Sonic**.

## Configure

Open **Dashboard → Plugins → Emby Sonic** and set the **Python Service URL**
to your coordinator (e.g. `http://192.168.1.9:8765`). The status box confirms
the connection and shows analysis progress.
