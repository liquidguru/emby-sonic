#!/usr/bin/env pwsh
# Install the Emby Sonic plugin on a Windows Emby Server.
#
#   ./install.ps1                       # auto-detect Emby, copy DLL, offer restart
#   ./install.ps1 -PluginsDir <path>    # explicit plugins folder
#   ./install.ps1 -NoRestart            # skip the Emby restart
#
# Run on the machine where Emby Server is installed. The DLL must sit next to
# this script (it does inside the release zip; in a dev tree pass -Dll).

param(
    [string]$PluginsDir,
    [string]$Dll,
    [switch]$NoRestart
)

$ErrorActionPreference = 'Stop'

# --- locate the DLL ----------------------------------------------------------
if (-not $Dll) {
    $candidates = @(
        "$PSScriptRoot/EmbysonicPlugin.dll",                       # release zip
        "$PSScriptRoot/bin/Release/net8.0/EmbysonicPlugin.dll",    # dev (Release)
        "$PSScriptRoot/bin/Debug/net8.0/EmbysonicPlugin.dll"       # dev (Debug)
    )
    $Dll = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $Dll -or -not (Test-Path $Dll)) {
    throw "EmbysonicPlugin.dll not found. Build it first (./build.ps1) or pass -Dll <path>."
}

# --- locate Emby's plugins folder -------------------------------------------
if (-not $PluginsDir) {
    $guesses = @(
        "$env:APPDATA/Emby-Server/programdata/plugins",
        "$env:LOCALAPPDATA/Emby-Server/programdata/plugins",
        "C:/ProgramData/Emby-Server/programdata/plugins"
    )
    $PluginsDir = $guesses | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $PluginsDir) {
    throw "Could not find Emby's plugins folder. Pass -PluginsDir <Emby-Server/programdata/plugins>."
}

New-Item -ItemType Directory -Force -Path $PluginsDir | Out-Null
$dest = Join-Path $PluginsDir 'EmbysonicPlugin.dll'
Copy-Item $Dll $dest -Force
Write-Host "Installed -> $dest" -ForegroundColor Green

# --- restart Emby ------------------------------------------------------------
if ($NoRestart) {
    Write-Host "Restart Emby Server to load the plugin." -ForegroundColor Yellow
    return
}

$svc = Get-Service -Name 'EmbyServer' -ErrorAction SilentlyContinue
if ($svc) {
    Restart-Service $svc -Force
    Write-Host "Restarted EmbyServer service." -ForegroundColor Green
} else {
    $proc = Get-Process -Name 'EmbyServer' -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Host "Emby is running as a process (not a service). Restart it manually to load the plugin." -ForegroundColor Yellow
    } else {
        Write-Host "Emby not currently running. Start it to load the plugin." -ForegroundColor Yellow
    }
}
