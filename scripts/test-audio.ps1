# AudioViz - Test Audio Capture
# Test audio capture without connecting to Minecraft

param(
    [string]$App = "spotify",
    [switch]$ListApps,
    [switch]$ListDevices
)

Write-Host "AudioViz - Audio Test Mode" -ForegroundColor Yellow
Write-Host "==========================" -ForegroundColor Yellow
Write-Host ""

if ($ListApps) {
    Write-Host "Listing active audio applications..." -ForegroundColor White
    audioviz --list-apps
    exit
}

if ($ListDevices) {
    Write-Host "Listing audio devices..." -ForegroundColor White
    audioviz --list-devices
    exit
}

Write-Host "Testing audio capture from: $App" -ForegroundColor White
Write-Host "This will display the spectrograph without Minecraft" -ForegroundColor Gray
Write-Host ""
Write-Host "Press Ctrl+C to stop" -ForegroundColor Gray
Write-Host ""

audioviz --app $App --test
