#!/usr/bin/env pwsh
# Build Emby Sonic plugin in Release and package a versioned release zip.
#
#   ./build.ps1            # build + package
#   ./build.ps1 -NoZip     # build only
#
# Output: dist/EmbysonicPlugin_<version>.zip  (DLL + install scripts + README)

param([switch]$NoZip)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location $root

# --- version from csproj -----------------------------------------------------
[xml]$csproj = Get-Content "$root/EmbysonicPlugin.csproj"
$version = ($csproj.Project.PropertyGroup.AssemblyVersion | Where-Object { $_ }) -as [string]
if (-not $version) { $version = '0.0.0.0' }
Write-Host "Building Emby Sonic plugin v$version (Release)..." -ForegroundColor Cyan

# --- build -------------------------------------------------------------------
dotnet build -c Release | Write-Host
$dll = "$root/bin/Release/net8.0/EmbysonicPlugin.dll"
if (-not (Test-Path $dll)) { throw "Build did not produce $dll" }

if ($NoZip) {
    Write-Host "Built: $dll" -ForegroundColor Green
    return
}

# --- package -----------------------------------------------------------------
$dist = "$root/dist"
$stage = "$dist/stage"
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Get-ChildItem $stage | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

Copy-Item $dll "$stage/EmbysonicPlugin.dll"
foreach ($f in 'install.ps1', 'install.sh', 'RELEASE_README.md') {
    if (Test-Path "$root/$f") { Copy-Item "$root/$f" "$stage/$f" }
}

$zip = "$dist/EmbysonicPlugin_$version.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path "$stage/*" -DestinationPath $zip
Remove-Item $stage -Recurse -Force

$kb = [math]::Round((Get-Item $zip).Length / 1KB)
Write-Host "Packaged: $zip ($kb KB)" -ForegroundColor Green
