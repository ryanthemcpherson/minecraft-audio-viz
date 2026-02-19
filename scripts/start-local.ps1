# AudioViz - Local DJ Mode
# Captures audio from Spotify and sends to local Minecraft server

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

# Build command
$cmd = "audioviz --app $App --host $Host --port $Port"

if ($Preview) {
    $cmd += " --preview"
    Write-Host "Browser preview enabled at http://localhost:8080" -ForegroundColor Green
}

if ($Compact) {
    $cmd += " --compact"
}

if ($LowLatency) {
    $cmd += " --low-latency"
    Write-Host "Low-latency mode enabled (~20ms)" -ForegroundColor Yellow
}

Write-Host "Capturing audio from: $App" -ForegroundColor White
Write-Host "Sending to Minecraft: ${Host}:${Port}" -ForegroundColor White
Write-Host ""
Write-Host "Press Ctrl+C to stop" -ForegroundColor Gray
Write-Host ""

# Run
Invoke-Expression $cmd
