# AudioViz - VJ Server Mode
# Central server for multi-DJ setups

param(
    [int]$DJPort = 9000,
    [string]$MinecraftHost = "localhost",
    [int]$MinecraftPort = 8765,
    [int]$BrowserPort = 8766,
    [switch]$NoAuth
)

Write-Host "AudioViz - VJ Server Mode" -ForegroundColor Magenta
Write-Host "=========================" -ForegroundColor Magenta
Write-Host ""

# Build command
$cmd = "audioviz-vj --port $DJPort --minecraft-host $MinecraftHost --minecraft-port $MinecraftPort --broadcast-port $BrowserPort"

if ($NoAuth) {
    $cmd += " --no-auth"
    Write-Host "WARNING: Authentication disabled!" -ForegroundColor Red
}

Write-Host "DJ Connection Port:  $DJPort" -ForegroundColor White
Write-Host "Minecraft Server:    ${MinecraftHost}:${MinecraftPort}" -ForegroundColor White
Write-Host "Browser Preview:     http://localhost:8080" -ForegroundColor White
Write-Host ""
Write-Host "Waiting for DJ connections..." -ForegroundColor Gray
Write-Host "Press Ctrl+C to stop" -ForegroundColor Gray
Write-Host ""

# Run
Invoke-Expression $cmd
