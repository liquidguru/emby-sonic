#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Install the Emby Sonic coordinator (main.py) as an always-on Windows scheduled task.

.DESCRIPTION
  A bare `python main.py` in a terminal has nothing supervising it: close the
  window, let the box sleep, or hit an unhandled exception and the process —
  and its listening socket on :8765 — is just gone. The Emby plugin then shows
  "Service: offline" and workers see connection-refused (WinError 10061) when
  posting results, until something restarts it by hand.

  This registers main.py as a SYSTEM scheduled task that starts at boot. Crash
  recovery is NOT delegated to Task Scheduler's own RestartCount/RestartInterval
  settings — testing found Task Scheduler logs a killed/crashed task instance as
  "successfully completed" and does not reliably re-trigger the restart policy.
  Instead, the generated launcher (coordinator_run.generated.py) is itself a
  small supervisor: it runs main.py as a child process in a loop and relaunches
  it a few seconds after any exit, for any reason. Task Scheduler's own restart
  settings are still configured as a belt-and-suspenders backstop in case the
  supervisor process itself is ever killed.

  The launcher runs via pythonw.exe so no console ever flashes; the supervised
  main.py child runs via plain python.exe with stdout/stderr piped straight to
  coordinator.log (main.py has no logging of its own, unlike worker.py, so this
  is the only place uvicorn's log output goes).

.EXAMPLE
  # Run elevated, on the box that should host the coordinator.
  ./deploy/coordinator-install.ps1
#>
[CmdletBinding()]
param(
    [string]$TaskName = 'EmbySonicCoordinator',
    [string]$RepoDir,
    [string]$PythonExe
)

$ErrorActionPreference = 'Stop'

# --- resolve repo + python ---------------------------------------------------
if (-not $RepoDir) { $RepoDir = Split-Path -Parent $PSScriptRoot }   # script is in <repo>/deploy
$RepoDir = (Resolve-Path $RepoDir).Path
$mainPy = Join-Path $RepoDir 'main.py'
if (-not (Test-Path $mainPy)) {
    throw "main.py not found in $RepoDir. Pass -RepoDir <path-to-emby-sonic>."
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

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)
if (-not $isAdmin) {
    throw "Registers a SYSTEM task and must be run from an elevated PowerShell (Run as Administrator)."
}

# --- write the launcher the task will run ------------------------------------
# Same rationale as worker-install.ps1: pythonw.exe (not a PowerShell wrapper
# with -WindowStyle Hidden) so no console ever flashes, on any machine,
# regardless of the Windows 11 default-terminal setting.
$repoFwd = $RepoDir -replace '\\', '/'
$pyFwd = $PythonExe -replace '\\', '/'
$logPath = "$repoFwd/coordinator.log"

$PythonwExe = $PythonExe -replace '(?i)python\.exe$', 'pythonw.exe'
if (-not (Test-Path $PythonwExe)) {
    Write-Host "pythonw.exe not found next to $PythonExe; using python.exe (a console may flash)." -ForegroundColor Yellow
    $PythonwExe = $PythonExe
}

# The supervisor is a real, reviewed file in the repo (deploy/supervise.py),
# NOT generated here. It used to be emitted as a string array, which capped how
# much logic it could reasonably carry - and the job-object handling it now
# needs is well past that cap.
#
# That matters: the generated version orphaned its child. Stopping the task
# killed only the supervisor, the child kept running and kept port 8765, and
# every later restart quietly failed to bind while the orphan served stale code.
# One coordinator ran seven days across two "successful" restarts that way.
# supervise.py puts the child in a job object so the kernel kills it when the
# supervisor dies, including on a hard kill where no cleanup code could run.
$launcher = Join-Path $RepoDir 'deploy/supervise.py'
if (-not (Test-Path $launcher)) {
    throw "Supervisor not found at $launcher - is the repo checkout complete?"
}

$legacyLauncher = Join-Path $RepoDir 'coordinator_run.generated.py'
if (Test-Path $legacyLauncher) {
    Remove-Item $legacyLauncher -Force
    Write-Host "Removed superseded launcher -> $legacyLauncher" -ForegroundColor Yellow
}

$supervisorArgs = @(
    "`"$launcher`""
    "--python `"$pyFwd`""
    "--script main.py"
    "--cwd `"$repoFwd`""
    "--log `"$logPath`""
    "--label coordinator"
) -join ' '

# --- register the scheduled task --------------------------------------------
$action = New-ScheduledTaskAction -Execute $PythonwExe -Argument $supervisorArgs
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -MultipleInstances IgnoreNew -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero)
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName

# --- summary -----------------------------------------------------------------
$state = (Get-ScheduledTask -TaskName $TaskName).State
Write-Host ""
Write-Host "Installed '$TaskName' - state: $state" -ForegroundColor Cyan
Write-Host "  repo:   $RepoDir"
Write-Host "  python: $PythonExe"
Write-Host "  log:    $logPath"
Write-Host "  -> runs as SYSTEM at boot, always listening on :8765, restarts if it dies."
