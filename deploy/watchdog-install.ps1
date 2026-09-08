<#
.SYNOPSIS
  Registers emby-transcode-watchdog.ps1 as a scheduled task on the Emby host.

.DESCRIPTION
  Runs the watchdog every -IntervalMinutes as SYSTEM, reading EMBY_API_KEY from
  -EnvFile at run time so the key never appears in the task definition.

  Does a dry run FIRST and refuses to install if it fails — a bad .env or an
  unreachable Emby should fail here, loudly, not silently every fifteen minutes
  in Task Scheduler's history where nobody looks.

  Re-running replaces the existing task. -Uninstall removes it.

  Why a file and not a one-liner: registering a task needs parentheses, nested
  quotes and a TimeSpan, none of which survive the sh → ssh → PowerShell quoting
  layers used to administer these hosts remotely. Same reason the service
  installers in this folder exist.

.PARAMETER EnvFile
  The .env that sets EMBY_API_KEY. Defaults to the repo's own, one level above
  this deploy folder.

.EXAMPLE
  .\watchdog-install.ps1
  Installs with the repo's own .env, every 15 minutes.

.EXAMPLE
  .\watchdog-install.ps1 -Uninstall
#>
[CmdletBinding()]
param(
    [string]$EnvFile,
    [string]$EmbyUrl = 'http://localhost:8096',
    [int]$IntervalMinutes = 15,
    [string]$TaskName = 'EmbyTranscodeWatchdog',
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
# Not $PSScriptRoot in the param block: under Windows PowerShell it is EMPTY
# while param defaults are evaluated, and this installer died on exactly that
# line the first time it ran on liquidBee. Resolve here, where it is reliable.
$here = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $EnvFile) { $EnvFile = Join-Path (Split-Path -Parent $here) '.env' }
$script = Join-Path $here 'emby-transcode-watchdog.ps1'
$existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue

if ($Uninstall) {
    if ($existing) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "Removed '$TaskName'." -ForegroundColor Cyan
    } else {
        Write-Host "'$TaskName' is not installed." -ForegroundColor Yellow
    }
    return
}

if (-not (Test-Path $script)) { throw "Watchdog script not found: $script" }
if (-not (Test-Path $EnvFile)) { throw "EnvFile not found: $EnvFile (it must set EMBY_API_KEY)" }

Write-Host "Dry run first:" -ForegroundColor Cyan
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -EmbyUrl $EmbyUrl -EnvFile $EnvFile
if ($LASTEXITCODE -ne 0) { throw "Dry run failed (exit $LASTEXITCODE); not installing." }

# -ExecutionPolicy Bypass is load-bearing: Windows PowerShell's default policy
# refuses to run a .ps1 at all, and a task that fails that way shows nothing
# useful anywhere.
$arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$script`" -EmbyUrl $EmbyUrl -EnvFile `"$EnvFile`" -Apply"
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
    -RepetitionInterval (New-TimeSpan -Minutes $IntervalMinutes)
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
# IgnoreNew: a run that is mid-sample must not be joined by a second one.
# The time limit is generous cover for the 20 s sample plus Emby being slow.
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 5)

if ($existing) { Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false }
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings `
    -Description 'Kills Emby ffmpeg transcodes that have outlived their session. See deploy/emby-transcode-watchdog.ps1.' | Out-Null

Write-Host "Installed '$TaskName': every $IntervalMinutes min as SYSTEM, first run within a minute." -ForegroundColor Cyan
