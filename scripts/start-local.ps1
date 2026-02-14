# ============================================================================
# DEPRECATED SCRIPT - DO NOT USE
# ============================================================================
# The Python DJ CLI (audioviz) has been replaced by the Rust DJ Client.
#
# Instead of using this script:
#   1. Download the DJ Client from GitHub Releases:
#      https://github.com/ryanthemcpherson/minecraft-audio-viz/releases
#   2. Or build from source: cd dj_client && npm install && npm run tauri dev
#
# This script is kept for historical reference only and will be removed.
# ============================================================================

Write-Host ""
Write-Host "================================================================================" -ForegroundColor Red
Write-Host "  DEPRECATED: This script uses the old Python DJ CLI (audioviz)" -ForegroundColor Red
Write-Host "================================================================================" -ForegroundColor Red
Write-Host ""
Write-Host "The Python DJ CLI has been replaced by the Rust DJ Client." -ForegroundColor Yellow
Write-Host ""
Write-Host "Download the DJ Client:" -ForegroundColor Cyan
Write-Host "  https://github.com/ryanthemcpherson/minecraft-audio-viz/releases" -ForegroundColor White
Write-Host ""
Write-Host "Or build from source:" -ForegroundColor Cyan
Write-Host "  cd dj_client" -ForegroundColor White
Write-Host "  npm install" -ForegroundColor White
Write-Host "  npm run tauri dev" -ForegroundColor White
Write-Host ""
Write-Host "================================================================================" -ForegroundColor Red
Write-Host ""
Write-Host "Press Ctrl+C to exit, or wait 10 seconds to continue anyway..." -ForegroundColor Gray
Start-Sleep -Seconds 10
Write-Host ""

param(
    [string]$App = "spotify",
    [string]$Host = "localhost",
    [int]$Port = 8765,
    [switch]$Preview,
    [switch]$Compact,
    [switch]$LowLatency
)

Write-Host "AudioViz - Local DJ Mode" -ForegroundColor Cyan
Write-Host "========================" -ForegroundColor Cyan
Write-Host ""

# Build argument list
$arguments = @("--app", $App, "--host", $Host, "--port", $Port)

if ($Preview) {
    $arguments += "--preview"
    Write-Host "Browser preview enabled at http://localhost:8080" -ForegroundColor Green
}

if ($Compact) {
    $arguments += "--compact"
}

if ($LowLatency) {
    $arguments += "--low-latency"
    Write-Host "Low-latency mode enabled (~20ms)" -ForegroundColor Yellow
}

Write-Host "Capturing audio from: $App" -ForegroundColor White
Write-Host "Sending to Minecraft: ${Host}:${Port}" -ForegroundColor White
Write-Host ""
Write-Host "Press Ctrl+C to stop" -ForegroundColor Gray
Write-Host ""

# Run
& audioviz @arguments
