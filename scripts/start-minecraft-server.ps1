# Start Minecraft server with optimized JVM flags for AudioViz.
#
# Usage:
#   .\scripts\start-minecraft-server.ps1                    # default: paper.jar
#   .\scripts\start-minecraft-server.ps1 fabric-server.jar  # custom jar
#
# Expects to be run from the Minecraft server directory (where the jar lives).
# Requires Java 21+.

param(
    [string]$Jar = "paper.jar"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Jar)) {
    Write-Error "$Jar not found in $(Get-Location). Usage: .\start-minecraft-server.ps1 [server.jar]"
    exit 1
}

# Verify Java 21+
$javaVerOutput = & java -version 2>&1 | Select-Object -First 1
if ($javaVerOutput -match '"(\d+)') {
    $javaMajor = [int]$Matches[1]
    if ($javaMajor -lt 21) {
        Write-Error "Java 21+ required (found Java $javaMajor). ZGC generational requires Java 21."
        exit 1
    }
}

& java `
    -Xms4G -Xmx4G `
    -XX:+UseZGC -XX:+ZGenerational `
    -XX:+AlwaysPreTouch `
    -XX:+UseStringDeduplication `
    -jar $Jar nogui
