# deploy.ps1 — push emby-sonic to the coordinator host
# Run from your dev box: .\deploy.ps1
# Set the deploy target via env var, e.g.:
#   $env:EMBY_SONIC_DEPLOY_TARGET = "user@192.168.x.y"

$BEE = "$env:EMBY_SONIC_DEPLOY_TARGET"  # user@<coordinator-host-ip>
$REMOTE = "C:/emby-sonic"
$SRC = (Resolve-Path "$PSScriptRoot").Path

if (-not $BEE) {
    Write-Host "Set EMBY_SONIC_DEPLOY_TARGET to user@<host> first." ; exit 1
}

Write-Host "Syncing to ${BEE}:$REMOTE ..."

# rsync via WSL if available, otherwise fall back to scp
if (Get-Command wsl -ErrorAction SilentlyContinue) {
    $wslSrc = "/mnt/" + ($SRC.Substring(0,1).ToLower()) + ($SRC.Substring(2) -replace '\\','/') + "/"
    wsl rsync -av --exclude='.git' --exclude='data/' --exclude='models/' --exclude='__pycache__' --exclude='.venv' `
        $wslSrc "${BEE}:${REMOTE}/"
} else {
    Write-Host "WSL not available. Use scp or map the host drive manually."
    Write-Host "scp -r `"$SRC`" ${BEE}:C:/emby-sonic"
}

Write-Host ""
Write-Host "On the coordinator host, first-time setup:"
Write-Host "  pip install torch --index-url https://download.pytorch.org/whl/cpu"
Write-Host "  pip install -r C:/emby-sonic/requirements.txt"
Write-Host "  copy C:/emby-sonic/.env.example C:/emby-sonic/.env  # then edit EMBY_API_KEY"
Write-Host "  python C:/emby-sonic/benchmark.py <any-music-file>"
Write-Host "  python C:/emby-sonic/main.py"
