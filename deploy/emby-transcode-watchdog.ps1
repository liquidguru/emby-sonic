<#
.SYNOPSIS
  Kills Emby transcodes that have outlived the session that started them.

.DESCRIPTION
  Emby keys one ffmpeg process to each play session. When a session ends,
  Emby asks its ffmpeg to stop — and sometimes that request is ignored. The
  process then sits there with nothing reading its output, holding a CPU
  core, until something kills it by hand. Observed on Emby 4.10 with both
  audio transcodes and a Live TV stream; a single bad session left five of
  them pinning all four cores for twelve minutes.

  This is a server-side fault, not a client one: any Emby client can trigger
  it, and the leaked process survives the session, the client, and the day.
  So it wants a watchdog on the Emby host regardless of what the clients do.

  Logic, deliberately conservative — it would rather leave an orphan for one
  more run than kill something live:

    1. Ask Emby how many sessions are transcoding right now (/Sessions,
       TranscodingInfo present). Anything beyond that count is an orphan.
    2. Only consider ffmpeg older than -MinAgeMinutes. A fresh transcode has
       every right to exist.
    3. Sample CPU time twice, -SampleSeconds apart. A live transcode — even a
       throttled one — advances; a dead one does not. Only a process that is
       BOTH excess AND stalled is killed.

  Dry run by default. Pass -Apply to actually kill. Matches the convention of
  tools/find_orphans.py in this repo.

.PARAMETER EmbyUrl
  Emby's own address, as seen from this host. Default http://localhost:8096.

.PARAMETER ApiKey
  An Emby API key (Dashboard → Advanced → API Keys). Read-only use.

.PARAMETER EnvFile
  Alternative to -ApiKey: a .env file that sets EMBY_API_KEY (the coordinator's
  own .env works). Lets a scheduled task run without the key ever appearing on
  its command line or in the task definition, and stays correct if the key is
  rotated in one place.

.EXAMPLE
  .\emby-transcode-watchdog.ps1 -ApiKey abc123
  Reports what it would kill.

.EXAMPLE
  .\emby-transcode-watchdog.ps1 -ApiKey abc123 -Apply
  Kills them. Suitable as a scheduled task every 15 minutes.

.EXAMPLE
  .\emby-transcode-watchdog.ps1 -EnvFile C:\emby-sonic\.env -Apply
  Same, with the key read from the coordinator's .env at run time.
#>
[CmdletBinding()]
param(
    [string]$EmbyUrl = "http://localhost:8096",
    [string]$ApiKey,
    [string]$EnvFile,
    [int]$MinAgeMinutes = 10,
    [int]$SampleSeconds = 20,
    [switch]$Apply
)

$ErrorActionPreference = "Stop"
$now = Get-Date

function Write-Line([string]$Message) {
    Write-Output ("[{0}] {1}" -f $now.ToString("yyyy-MM-dd HH:mm:ss"), $Message)
}

# --- 0. Resolve the API key -------------------------------------------------
if (-not $ApiKey -and $EnvFile) {
    if (-not (Test-Path $EnvFile)) { throw "EnvFile not found: $EnvFile" }
    $line = Get-Content $EnvFile | Where-Object { $_ -match '^\s*EMBY_API_KEY\s*=' } | Select-Object -First 1
    if ($line) { $ApiKey = ($line -split '=', 2)[1].Trim().Trim('"').Trim("'") }
}
if (-not $ApiKey) { throw "Provide -ApiKey, or -EnvFile pointing at a .env that sets EMBY_API_KEY." }

# --- 1. How many transcodes SHOULD be running? ------------------------------
try {
    $sessions = Invoke-RestMethod -Uri "$($EmbyUrl.TrimEnd('/'))/Sessions?api_key=$ApiKey" -TimeoutSec 10
} catch {
    # Emby unreachable means we cannot tell live from dead. Do nothing — a
    # watchdog that guesses while the server is down is worse than none.
    Write-Line "Emby not reachable at $EmbyUrl ($($_.Exception.Message)); skipping this run."
    exit 0
}
$expected = @($sessions | Where-Object { $null -ne $_.TranscodingInfo }).Count

# --- 2. What IS running, and for how long? ----------------------------------
$ffmpeg = @(Get-Process | Where-Object { $_.ProcessName -like "*ffmpeg*" })
if ($ffmpeg.Count -eq 0) {
    Write-Line "No ffmpeg running. Emby reports $expected transcoding session(s)."
    exit 0
}

$excess = $ffmpeg.Count - $expected
Write-Line ("ffmpeg running: {0}. Emby says {1} should be. Excess: {2}." -f $ffmpeg.Count, $expected, [Math]::Max($excess, 0))
if ($excess -le 0) { exit 0 }

$old = @($ffmpeg | Where-Object { ($now - $_.StartTime).TotalMinutes -ge $MinAgeMinutes })
if ($old.Count -eq 0) {
    Write-Line "All ffmpeg younger than $MinAgeMinutes min; leaving them to finish."
    exit 0
}

# --- 3. Which of the old ones are actually dead? ----------------------------
$before = @{}
foreach ($p in $old) { $before[$p.Id] = $p.TotalProcessorTime.TotalSeconds }
Start-Sleep -Seconds $SampleSeconds

$stalled = @()
foreach ($p in $old) {
    $live = Get-Process -Id $p.Id -ErrorAction SilentlyContinue
    if ($null -eq $live) { continue }          # ended on its own meanwhile
    $delta = $live.TotalProcessorTime.TotalSeconds - $before[$p.Id]
    if ($delta -lt 0.5) { $stalled += $live }
}

# Never kill more than the excess. If Emby says two are live and we see four
# stalled candidates, the oldest two are the safest bet — the orphans have by
# definition been around longer than the sessions that replaced them.
$victims = @($stalled | Sort-Object StartTime | Select-Object -First $excess)

if ($victims.Count -eq 0) {
    Write-Line "Excess ffmpeg present, but all still advancing CPU; not touching them."
    exit 0
}

foreach ($v in $victims) {
    $age = [Math]::Round(($now - $v.StartTime).TotalMinutes)
    $cpu = [Math]::Round($v.TotalProcessorTime.TotalSeconds)
    if ($Apply) {
        Stop-Process -Id $v.Id -Force
        Write-Line "KILLED pid $($v.Id): age ${age}m, cpu ${cpu}s, no CPU movement in ${SampleSeconds}s."
    } else {
        Write-Line "WOULD KILL pid $($v.Id): age ${age}m, cpu ${cpu}s, no CPU movement in ${SampleSeconds}s. (pass -Apply)"
    }
}
