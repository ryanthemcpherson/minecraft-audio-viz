# AudioViz - Local DJ Mode (DEPRECATED)
# The Python DJ CLI has been replaced by the Rust DJ Client (dj_client/).
# This script is kept for reference only.

Write-Warning "DEPRECATED: The Python DJ CLI (audioviz) has been replaced by the Rust DJ Client."
Write-Warning "See dj_client/ for the new implementation."
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
