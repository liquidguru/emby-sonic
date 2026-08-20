#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Install the Emby Sonic analysis worker as a Windows scheduled task.

.DESCRIPTION
  Registers worker.py to run automatically, in one of two modes:

    -Mode service : always-on daemon. Runs as SYSTEM, starts at boot, restarts
                    if it dies. Best on the coordinator / server box so newly
                    added tracks get analysed with no interaction.

    -Mode idle    : runs only while the machine is idle and stops the moment you
                    touch it (runs as the current user). Best on a GPU desktop
                    you also use day to day, so analysis never fights your work.

  Both can run at once on different machines; the coordinator's leases stop them
  double-processing.

  Runs the worker windowless via pythonw.exe through deploy/supervise.py — so no
  empty console/Terminal window ever appears, on any machine, regardless of the
  Windows 11 default-terminal setting.

  supervise.py runs worker.py as a child in a loop, relaunching a few seconds
  after any exit, rather than relying on Task Scheduler's own RestartCount /
  RestartInterval — testing on the coordinator side found Task Scheduler logs a
  killed task instance as "successfully completed" and does not reliably
  re-trigger its restart policy. Those settings are still configured below as a
  backstop in case the supervisor itself dies.

  It also puts the child in a Windows job object so stopping the task actually
  stops the worker. The previous generated launcher did not: the child outlived
  it, kept its port, and every later "restart" quietly failed while the orphan
  went on serving stale code.

  Handles the two things that trip up a hand-rolled setup:
    * panns_inference hardcodes ~/panns_data. Under SYSTEM the home dir is
      \systemprofile (no model there) -> the supervisor is passed USERPROFILE
      pointing at a real profile so the model + labels are found.
    * COORDINATOR_URL defaults to localhost; a worker on a different box than the
      coordinator must be told where the coordinator is.

.EXAMPLE
  # On the coordinator/server box (always-on). Run elevated.
  ./deploy/worker-install.ps1 -Mode service

.EXAMPLE
  # On a separate GPU desktop, helping out while idle.
  ./deploy/worker-install.ps1 -Mode idle -CoordinatorUrl http://192.168.1.50:8765
#>
[CmdletBinding()]
param(
    [ValidateSet('service', 'idle')]
    [string]$Mode = 'service',
    [string]$CoordinatorUrl,
    [int]$IdleMinutes = 10,
    [string]$TaskName = 'EmbySonicWorker',
    [string]$RepoDir,
    [string]$PythonExe,
    [string]$UserProfileDir = $env:USERPROFILE
)

$ErrorActionPreference = 'Stop'

# --- resolve repo + python ---------------------------------------------------
if (-not $RepoDir) { $RepoDir = Split-Path -Parent $PSScriptRoot }   # script is in <repo>/deploy
$RepoDir = (Resolve-Path $RepoDir).Path
$worker = Join-Path $RepoDir 'worker.py'
if (-not (Test-Path $worker)) {
    throw "worker.py not found in $RepoDir. Pass -RepoDir <path-to-emby-sonic>."
}

if (-not $PythonExe) {
    $PythonExe = @(
        (Join-Path $RepoDir '.venv/Scripts/python.exe'),
        (Join-Path $RepoDir 'venv/Scripts/python.exe')
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $PythonExe) { $PythonExe = (Get-Command python -ErrorAction SilentlyContinue).Source }
}
if (-not $PythonExe -or -not (Test-Path $PythonExe)) {
    throw "Python not found. Create the venv (see README) or pass -PythonExe <path>."
}

# default coordinator: localhost for service mode (same box as the coordinator)
if (-not $CoordinatorUrl) {
    if ($Mode -eq 'idle') {
        throw "Pass -CoordinatorUrl http://<coordinator-host>:8765 (an idle GPU box is usually separate from the coordinator)."
    }
    $CoordinatorUrl = 'http://localhost:8765'
}

if ($Mode -eq 'service') {
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
        ).IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)
    if (-not $isAdmin) {
        throw "Service mode registers a SYSTEM task and must be run from an elevated PowerShell (Run as Administrator)."
    }
}

# --- write the launcher the task will run ------------------------------------
# A tiny Python launcher (run by pythonw.exe) rather than a PowerShell wrapper:
# pythonw.exe has no console, so the task is windowless on every machine,
# regardless of the Windows 11 "default terminal application" setting (Windows
# Terminal ignores -WindowStyle Hidden, which used to pop an empty window on each
# idle cycle). worker.py logs to worker.log itself via its own FileHandler, so
# the supervised child's stdout is sent to DEVNULL (worker.py also conditionally
# adds a stdout StreamHandler when sys.stdout isn't None, which would otherwise
# duplicate every line into worker.log); stderr goes to a separate supervisor
# log so a crash that happens before/outside worker.py's own logging is still
# visible somewhere.
$repoFwd = $RepoDir -replace '\\', '/'
$profFwd = $UserProfileDir -replace '\\', '/'
$pyFwd = $PythonExe -replace '\\', '/'
$supervisorLogPath = "$repoFwd/worker_supervisor.log"

# pythonw.exe sits next to python.exe in the same venv/install.
$PythonwExe = $PythonExe -replace '(?i)python\.exe$', 'pythonw.exe'
if (-not (Test-Path $PythonwExe)) {
    Write-Host "pythonw.exe not found next to $PythonExe; using python.exe (a console may flash)." -ForegroundColor Yellow
    $PythonwExe = $PythonExe
}

# Shared, reviewed supervisor - see the comment in coordinator-install.ps1 for
# why this is no longer generated. Short version: the generated one orphaned
# its child, so stopping the task left a process holding the port and every
# later restart silently failed to bind.
$launcher = Join-Path $RepoDir 'deploy/supervise.py'
if (-not (Test-Path $launcher)) {
    throw "Supervisor not found at $launcher - is the repo checkout complete?"
}

$supervisorArgs = @(
    "`"$launcher`""
    "--python `"$pyFwd`""
    "--script worker.py"
    "--cwd `"$repoFwd`""
    "--log `"$supervisorLogPath`""
    "--label worker"
    # panns_inference resolves ~/panns_data from USERPROFILE, which SYSTEM does
    # not inherit usefully.
    "--env `"USERPROFILE=$profFwd`""
    "--env `"COORDINATOR_URL=$CoordinatorUrl`""
    # The worker's stdout is verbose progress output; its useful output is on stderr.
    "--no-stdout-to-log"
) -join ' '

# Remove superseded launchers from older installs, if present.
foreach ($stale in @('worker_run.generated.ps1', 'worker_run.generated.py')) {
    $stalePath = Join-Path $RepoDir $stale
    if (Test-Path $stalePath) {
        Remove-Item $stalePath -Force
        Write-Host "Removed superseded launcher -> $stalePath" -ForegroundColor Yellow
    }
}

# --- register the scheduled task --------------------------------------------
if ($Mode -eq 'service') {
    $action = New-ScheduledTaskAction -Execute $PythonwExe -Argument $supervisorArgs
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -MultipleInstances IgnoreNew -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
        -ExecutionTimeLimit ([TimeSpan]::Zero)
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
        -Principal $principal -Settings $settings -Force | Out-Null
    Start-ScheduledTask -TaskName $TaskName
}
else {
    # New-ScheduledTaskTrigger has no idle option, so create via schtasks then tune.
    schtasks /Create /TN $TaskName /TR "`"$PythonwExe`" $supervisorArgs" /SC ONIDLE /I $IdleMinutes /F | Out-Null
    $t = Get-ScheduledTask -TaskName $TaskName
    $t.Settings.IdleSettings.StopOnIdleEnd = $true    # bow out when the user returns
    $t.Settings.IdleSettings.RestartOnIdle = $true    # resume on the next idle period
    Set-ScheduledTask -TaskName $TaskName -Settings $t.Settings | Out-Null
}

# --- summary -----------------------------------------------------------------
$state = (Get-ScheduledTask -TaskName $TaskName).State
Write-Host ""
Write-Host "Installed '$TaskName' ($Mode mode) - state: $state" -ForegroundColor Cyan
Write-Host "  repo:        $RepoDir"
Write-Host "  python:      $PythonExe"
Write-Host "  coordinator: $CoordinatorUrl"
Write-Host "  log:         $repoFwd/worker.log"
Write-Host "  supervisor:  $supervisorLogPath"
if ($Mode -eq 'service') {
    Write-Host "  -> runs as SYSTEM at boot, always polling for pending tracks."
}
else {
    Write-Host "  -> runs after ${IdleMinutes}m idle, stops the moment you return."
}
if (-not (Test-Path (Join-Path $UserProfileDir 'panns_data'))) {
    Write-Host "NOTE: $UserProfileDir\panns_data not found - the model (~300MB) auto-downloads on first run." -ForegroundColor Yellow
}
