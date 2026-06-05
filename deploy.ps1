# deploy.ps1 — push emby-sonic to liquidBee
# Run from liquidHulk: .\deploy.ps1

$BEE = "liqui@192.168.1.9"
$REMOTE = "C:/emby-sonic"

Write-Host "Syncing to liquidBee:$REMOTE ..."

# rsync via WSL if available, otherwise robocopy
if (Get-Command wsl -ErrorAction SilentlyContinue) {
    wsl rsync -av --exclude='.git' --exclude='data/' --exclude='models/' --exclude='__pycache__' --exclude='.venv' `
        /mnt/c/Users/liqui/dev/emby-sonic/ "${BEE}:${REMOTE}/"
} else {
    # Robocopy fallback (no SSH sync — manual copy via mapped drive or SCP)
    Write-Host "WSL not available. Use scp or map liquidBee drive manually."
    Write-Host "scp -r C:\Users\liqui\dev\emby-sonic ${BEE}:C:/emby-sonic"
}

Write-Host ""
Write-Host "On liquidBee, first-time setup:"
Write-Host "  pip install torch --index-url https://download.pytorch.org/whl/cpu"
Write-Host "  pip install -r C:/emby-sonic/requirements.txt"
Write-Host "  copy C:/emby-sonic/.env.example C:/emby-sonic/.env  # then edit EMBY_API_KEY"
Write-Host "  python C:/emby-sonic/benchmark.py <any-music-file>"
Write-Host "  python C:/emby-sonic/main.py"
