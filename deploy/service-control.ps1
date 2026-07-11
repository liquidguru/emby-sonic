#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Inspect or safely restart an Emby Sonic Windows scheduled service.

.DESCRIPTION
  The generated coordinator and worker launchers supervise a child Python
  process. Stopping their Scheduled Task only stops the launcher, so this tool
  also finds the matching child process before restarting the task. Matching is
  limited to the selected task, repository, generated launcher, configured
  Python executable, and service entry point.

.EXAMPLE
  ./deploy/service-control.ps1 -Service coordinator -Action status

.EXAMPLE
  ./deploy/service-control.ps1 -Service coordinator -Action restart -WhatIf

.EXAMPLE
  # Run elevated on the service host.
  ./deploy/service-control.ps1 -Service worker -Action restart
#>
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)]
    [ValidateSet('coordinator', 'worker')]
    [string]$Service,

    [ValidateSet('status', 'restart')]
    [string]$Action = 'status',

    [string]$RepoDir,
    [string]$TaskName,
    [string]$CoordinatorUrl = 'http://localhost:8765',
    [ValidateRange(1, 300)]
    [int]$WaitSeconds = 45
)

$ErrorActionPreference = 'Stop'

if (-not $RepoDir) { $RepoDir = Split-Path -Parent $PSScriptRoot }
$RepoDir = (Resolve-Path -LiteralPath $RepoDir).Path

$serviceConfig = if ($Service -eq 'coordinator') {
    [pscustomobject]@{
        TaskName = if ($TaskName) { $TaskName } else { 'EmbySonicCoordinator' }
        EntryPoint = 'main.py'
        Launcher = Join-Path $RepoDir 'coordinator_run.generated.py'
    }
}
else {
    [pscustomobject]@{
        TaskName = if ($TaskName) { $TaskName } else { 'EmbySonicWorker' }
        EntryPoint = 'worker.py'
        Launcher = Join-Path $RepoDir 'worker_run.generated.py'
    }
}

function Get-ConfiguredPython {
    if (-not (Test-Path -LiteralPath $serviceConfig.Launcher -PathType Leaf)) {
        throw "Generated launcher not found: $($serviceConfig.Launcher). Run the corresponding install script first."
    }

    $line = Get-Content -LiteralPath $serviceConfig.Launcher |
        Where-Object { $_ -like "PY = '*'" } |
        Select-Object -First 1
    if ($line -and $line -match "^PY = '([^']+)'$") {
        $pythonPath = $Matches[1] -replace '/', '\'
        return [IO.Path]::GetFullPath($pythonPath)
    }

    # Launchers generated before the supervisor update did not include a PY
    # assignment. The task action still records the exact pythonw executable.
    $task = Get-ScheduledTask -TaskName $serviceConfig.TaskName -ErrorAction SilentlyContinue
    $taskPython = $task.Actions.Execute | Select-Object -First 1
    if ($taskPython) {
        $taskPython = $taskPython -replace '(?i)pythonw\.exe$', 'python.exe'
        return [IO.Path]::GetFullPath($taskPython)
    }

    throw "Could not determine the configured Python path from the launcher or task '$($serviceConfig.TaskName)'. Re-run the install script."
}

function Get-ServiceProcesses {
    param([Parameter(Mandatory)][string]$PythonPath)

    $pythonFull = [IO.Path]::GetFullPath($PythonPath)
    $pythonwFull = $pythonFull -replace '(?i)python\.exe$', 'pythonw.exe'
    $launcherFull = [IO.Path]::GetFullPath($serviceConfig.Launcher)
    $entryPattern = "(?i)(^|[\s`"]){0}([\s`"]|$)" -f [regex]::Escape($serviceConfig.EntryPoint)

    @(Get-CimInstance Win32_Process | Where-Object {
        $commandLine = [string]$_.CommandLine
        $executable = if ($_.ExecutablePath) { [IO.Path]::GetFullPath($_.ExecutablePath) } else { '' }
        $isLauncher = $commandLine.IndexOf($launcherFull, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $commandLine.IndexOf($launcherFull.Replace('\', '/'), [StringComparison]::OrdinalIgnoreCase) -ge 0
        $usesConfiguredPython = $executable.Equals($pythonFull, [StringComparison]::OrdinalIgnoreCase) -or
            $executable.Equals($pythonwFull, [StringComparison]::OrdinalIgnoreCase)
        $isChild = $usesConfiguredPython -and $commandLine -match $entryPattern
        $isLauncher -or $isChild
    } | Sort-Object ProcessId)
}

function Get-CoordinatorHealth {
    $uri = $CoordinatorUrl.TrimEnd('/') + '/sonic/status'
    try {
        $response = Invoke-WebRequest -Uri $uri -Method Get -TimeoutSec 3 -SkipHttpErrorCheck
        return [pscustomobject]@{
            Ready = $response.StatusCode -in 200, 401, 403
            Detail = "HTTP $($response.StatusCode) from $uri"
        }
    }
    catch {
        return [pscustomobject]@{ Ready = $false; Detail = $_.Exception.Message }
    }
}

function Show-ServiceStatus {
    param([Parameter(Mandatory)][string]$PythonPath)

    $task = Get-ScheduledTask -TaskName $serviceConfig.TaskName -ErrorAction SilentlyContinue
    if (-not $task) {
        throw "Scheduled task '$($serviceConfig.TaskName)' was not found on this host."
    }

    $processes = Get-ServiceProcesses -PythonPath $PythonPath
    Write-Host "$Service task: $($serviceConfig.TaskName) ($($task.State))" -ForegroundColor Cyan
    Write-Host "  repo:      $RepoDir"
    Write-Host "  python:    $PythonPath"
    Write-Host "  processes: $($processes.Count)"
    foreach ($process in $processes) {
        Write-Host "    PID $($process.ProcessId): $($process.CommandLine)"
    }

    if ($Service -eq 'coordinator') {
        $health = Get-CoordinatorHealth
        $colour = if ($health.Ready) { 'Green' } else { 'Red' }
        Write-Host "  health:    $($health.Detail)" -ForegroundColor $colour
        if (-not $health.Ready) { return $false }
    }
    elseif ($processes.Count -gt 0) {
        Write-Host '  health:    matching worker process found' -ForegroundColor Green
    }
    elseif ($task.State -eq 'Ready') {
        Write-Host '  health:    idle-mode task is ready; no worker is currently running' -ForegroundColor Yellow
        return $true
    }
    else {
        Write-Host "  health:    no matching worker process found while task is $($task.State)" -ForegroundColor Red
        return $false
    }

    return $task.State -eq 'Running' -and $processes.Count -gt 0
}

$pythonPath = Get-ConfiguredPython

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)
if (-not $isAdmin) {
    # Win32_Process hides CommandLine/ExecutablePath for other accounts'
    # processes, and the scheduled services run as SYSTEM — so without
    # elevation the matcher cannot see them at all.
    Write-Warning 'Not elevated: processes owned by other accounts (e.g. the SYSTEM service child) are invisible here, so process counts may under-report. Elevate for a complete view.'
}

if ($Action -eq 'status') {
    if (-not (Show-ServiceStatus -PythonPath $pythonPath)) { exit 1 }
    exit 0
}

$task = Get-ScheduledTask -TaskName $serviceConfig.TaskName -ErrorAction SilentlyContinue
if (-not $task) {
    throw "Scheduled task '$($serviceConfig.TaskName)' was not found on this host."
}

if (-not $isAdmin -and -not $WhatIfPreference) {
    throw 'Restart must be run from an elevated PowerShell (Run as Administrator). Use -WhatIf to inspect the actions without elevation.'
}

if ($PSCmdlet.ShouldProcess($serviceConfig.TaskName, 'stop scheduled task')) {
    Stop-ScheduledTask -TaskName $serviceConfig.TaskName
}
# Enumerate AFTER the task stop: with the supervisor gone, a crash-looping
# child can no longer respawn between the sweep list being built and the
# kills. Repeat until clean — a child that exited/respawned around the task
# stop itself is caught on the next pass. (Under -WhatIf nothing is stopped,
# so one pass just previews the current matches.)
for ($sweep = 1; $sweep -le 5; $sweep++) {
    $processes = Get-ServiceProcesses -PythonPath $pythonPath
    if ($processes.Count -eq 0) { break }
    foreach ($process in $processes) {
        if ($PSCmdlet.ShouldProcess("PID $($process.ProcessId) ($($process.CommandLine))", 'stop matching service process')) {
            Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
    if ($WhatIfPreference) { break }
    Start-Sleep -Milliseconds 300
}
if (-not $WhatIfPreference) {
    $survivors = Get-ServiceProcesses -PythonPath $pythonPath
    if ($survivors.Count -gt 0) {
        throw "Matching processes survived the sweep (PID $($survivors.ProcessId -join ', ')). Not starting the task, which would create duplicates."
    }
}
if ($PSCmdlet.ShouldProcess($serviceConfig.TaskName, 'start scheduled task')) {
    Start-ScheduledTask -TaskName $serviceConfig.TaskName
}

if ($WhatIfPreference) {
    Write-Host 'Dry run complete; no task or process state was changed.' -ForegroundColor Yellow
    exit 0
}

$deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
do {
    Start-Sleep -Seconds 1
    $readyProcesses = Get-ServiceProcesses -PythonPath $pythonPath
    if ($Service -eq 'coordinator') {
        $ready = $readyProcesses.Count -gt 0 -and (Get-CoordinatorHealth).Ready
    }
    else {
        $ready = $readyProcesses.Count -gt 0
    }
} until ($ready -or [DateTime]::UtcNow -ge $deadline)

if (-not $ready) {
    throw "$Service did not become ready within $WaitSeconds seconds. Check its task state and logs in $RepoDir."
}

if (-not (Show-ServiceStatus -PythonPath $pythonPath)) {
    throw "$service restart completed but its final health check failed."
}
