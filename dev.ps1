[CmdletBinding()]
param(
    [ValidateSet('bootstrap', 'check', 'test')]
    [string]$Command = 'test'
)

$ErrorActionPreference = 'Stop'
$RepoDir = $PSScriptRoot
$VenvDir = Join-Path $RepoDir '.venv'
$VenvPython = Join-Path $VenvDir 'Scripts\python.exe'

function Get-VenvPython {
    if (-not (Test-Path -LiteralPath $VenvPython -PathType Leaf)) {
        throw "Development environment not found. Run: .\dev.ps1 bootstrap"
    }

    try {
        $version = & $VenvPython -c "import sys; print('.'.join(map(str, sys.version_info[:3])))" 2>$null
    }
    catch {
        throw "The existing .venv is broken or its base Python was removed. Run: .\dev.ps1 bootstrap"
    }

    if ($LASTEXITCODE -ne 0 -or -not $version) {
        throw "The existing .venv is broken or its base Python was removed. Run: .\dev.ps1 bootstrap"
    }
    if (-not $version.StartsWith('3.12.')) {
        throw "The existing .venv uses Python $version; this project is tested on Python 3.12. Run: .\dev.ps1 bootstrap"
    }

    return $VenvPython
}

function Get-Python312 {
    if (-not (Get-Command py -ErrorAction SilentlyContinue)) {
        throw "The Python launcher was not found. Install Python 3.12, then run: .\dev.ps1 bootstrap"
    }

    & py -3.12 -c "import sys; print(sys.executable)" *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Python 3.12 is not installed. Install it from python.org or with 'winget install --id Python.Python.3.12', then run: .\dev.ps1 bootstrap"
    }

    return 'py'
}

function Assert-VenvNotInUse {
    # On a service host (coordinator-host) the scheduled coordinator/worker run FROM
    # this .venv; clearing it under a live python.exe hits Windows file locks
    # and leaves a half-destroyed environment beneath a running service.
    # Task state is visible without elevation, which covers the SYSTEM-run
    # services that a non-elevated process scan cannot see.
    $runningTasks = @('EmbySonicCoordinator', 'EmbySonicWorker') | ForEach-Object {
        Get-ScheduledTask -TaskName $_ -ErrorAction SilentlyContinue
    } | Where-Object { $_ -and $_.State -eq 'Running' }
    if ($runningTasks) {
        $names = ($runningTasks | ForEach-Object TaskName) -join ', '
        throw "Cannot recreate .venv while these scheduled services are running: $names. Stop them first: ./deploy/service-control.ps1 -Service <coordinator|worker> -Action restart (or Stop-ScheduledTask), then re-run bootstrap."
    }

    $venvProcesses = @(Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($VenvDir, [StringComparison]::OrdinalIgnoreCase)
    })
    if ($venvProcesses.Count -gt 0) {
        $pids = ($venvProcesses | ForEach-Object ProcessId) -join ', '
        throw "Cannot recreate .venv while processes are running from it (PID $pids). Stop them, then re-run bootstrap."
    }
}

function Invoke-Bootstrap {
    $launcher = Get-Python312

    if (Test-Path -LiteralPath $VenvDir) {
        Assert-VenvNotInUse
        Write-Host 'Recreating .venv with Python 3.12...'
        & $launcher -3.12 -m venv --clear $VenvDir
    }
    else {
        Write-Host 'Creating .venv with Python 3.12...'
        & $launcher -3.12 -m venv $VenvDir
    }
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to create the Python virtual environment.'
    }

    $python = Get-VenvPython
    & $python -m pip install --upgrade pip
    if ($LASTEXITCODE -ne 0) { throw 'Failed to upgrade pip.' }

    & $python -m pip install torch --index-url https://download.pytorch.org/whl/cpu
    if ($LASTEXITCODE -ne 0) { throw 'Failed to install CPU PyTorch.' }

    & $python -m pip install -r (Join-Path $RepoDir 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Failed to install development dependencies.' }

    Write-Host 'Development environment is ready.'
}

function Invoke-Checks {
    $python = Get-VenvPython
    & $python -c "import sys; print(f'Python {sys.version.split()[0]} ({sys.executable})')"
    if ($LASTEXITCODE -ne 0) { throw 'Python environment check failed.' }

    & $python -c "import fastapi, faiss, numpy, sqlalchemy, torch; print('Core imports: OK')"
    if ($LASTEXITCODE -ne 0) { throw 'One or more core dependencies could not be imported.' }
}

function Invoke-Tests {
    Invoke-Checks

    Push-Location $RepoDir
    try {
        & $VenvPython -m unittest discover tests
        if ($LASTEXITCODE -ne 0) { throw 'Python tests failed.' }

        if (Get-Command node -ErrorAction SilentlyContinue) {
            & node --check webapp\app.js
            if ($LASTEXITCODE -ne 0) { throw 'Web app JavaScript syntax check failed.' }
        }
        else {
            Write-Warning 'Node.js is not installed; skipped the web app JavaScript syntax check.'
        }
    }
    finally {
        Pop-Location
    }
}

switch ($Command) {
    'bootstrap' {
        Invoke-Bootstrap
        Invoke-Tests
    }
    'check' { Invoke-Checks }
    'test' { Invoke-Tests }
}
