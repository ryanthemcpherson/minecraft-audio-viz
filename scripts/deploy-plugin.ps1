# AudioViz - Build & Deploy Plugin to Minecraft Server
# Builds the plugin JAR, copies it to the remote server, and hot-reloads via PlugManX.
#
# Default behavior: Build, deploy, hot-reload (no server restart needed)
#
# Usage:
#   .\scripts\deploy-plugin.ps1                    # Build, deploy, hot-reload
#   .\scripts\deploy-plugin.ps1 -SkipBuild         # Deploy existing JAR only
#   .\scripts\deploy-plugin.ps1 -Restart            # Full server restart instead of hot-reload
#   .\scripts\deploy-plugin.ps1 -Server 10.0.0.5   # Custom server address

param(
    [string]$Server = "192.168.1.204",
    [string]$User = "ryan",
    [string]$PluginsDir = "/home/ryan/minecraft-server/plugins",
    [string]$ServerDir = "/home/ryan/minecraft-server",
    [string]$RconPort = "25575",
    [string]$RconPass = "audioviz123",
    [switch]$SkipBuild,
    [switch]$SkipTests,
    [switch]$Restart,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $ProjectRoot) { $ProjectRoot = (Get-Location).Path }
$PluginDir = Join-Path $ProjectRoot "minecraft_plugin"
$JarPattern = "audioviz-plugin-*-SNAPSHOT.jar"

Write-Host ""
Write-Host "  AudioViz Plugin Deploy" -ForegroundColor Cyan
Write-Host "  ======================" -ForegroundColor Cyan
Write-Host "  Server:     $User@$Server" -ForegroundColor White
Write-Host "  Plugins:    $PluginsDir" -ForegroundColor White
Write-Host ""

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: Build
# ─────────────────────────────────────────────────────────────────────────────
if (-not $SkipBuild) {
    Write-Host "[1/3] Building plugin JAR..." -ForegroundColor Yellow

    $mvnArgs = "package"
    if ($SkipTests) { $mvnArgs = "package -DskipTests" }

    Push-Location $PluginDir
    try {
        # Try mvnw first, fall back to mvn
        $mvnCmd = if (Test-Path "./mvnw.cmd") { "./mvnw.cmd" }
                  elseif (Test-Path "./mvnw") { "bash ./mvnw" }
                  elseif (Get-Command mvn -ErrorAction SilentlyContinue) { "mvn" }
                  else { throw "Maven not found. Install Maven or use the wrapper." }

        Write-Host "  Running: $mvnCmd $mvnArgs" -ForegroundColor Gray
        Invoke-Expression "$mvnCmd $mvnArgs" 2>&1 | ForEach-Object {
            if ($_ -match "BUILD (SUCCESS|FAILURE)") { Write-Host "  $_" -ForegroundColor $(if ($_ -match "SUCCESS") { "Green" } else { "Red" }) }
        }

        if ($LASTEXITCODE -ne 0) {
            Write-Host "  BUILD FAILED" -ForegroundColor Red
            exit 1
        }
    } finally {
        Pop-Location
    }

    # Find the built JAR
    $jarFile = Get-ChildItem (Join-Path $PluginDir "target") -Filter $JarPattern |
               Where-Object { $_.Name -notmatch "original" } |
               Sort-Object LastWriteTime -Descending |
               Select-Object -First 1

    if (-not $jarFile) {
        Write-Host "  ERROR: No JAR found matching $JarPattern" -ForegroundColor Red
        exit 1
    }
    Write-Host "  Built: $($jarFile.Name) ($([math]::Round($jarFile.Length / 1MB, 1)) MB)" -ForegroundColor Green
} else {
    Write-Host "[1/3] Skipping build (using existing JAR)" -ForegroundColor DarkGray

    $jarFile = Get-ChildItem (Join-Path $PluginDir "target") -Filter $JarPattern |
               Where-Object { $_.Name -notmatch "original" } |
               Sort-Object LastWriteTime -Descending |
               Select-Object -First 1

    if (-not $jarFile) {
        Write-Host "  ERROR: No existing JAR found. Run without -SkipBuild first." -ForegroundColor Red
        exit 1
    }
    Write-Host "  Using: $($jarFile.Name)" -ForegroundColor White
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: Deploy JAR
# ─────────────────────────────────────────────────────────────────────────────
Write-Host "[2/3] Deploying to $Server..." -ForegroundColor Yellow

if ($DryRun) {
    Write-Host "  DRY RUN: Would copy $($jarFile.Name) to ${User}@${Server}:${PluginsDir}/" -ForegroundColor DarkGray
} else {
    # Copy JAR via SCP
    $scpTarget = "${User}@${Server}:${PluginsDir}/"
    Write-Host "  Copying $($jarFile.Name) -> $scpTarget" -ForegroundColor Gray

    scp $jarFile.FullName $scpTarget
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  SCP FAILED - check SSH keys and server connectivity" -ForegroundColor Red
        exit 1
    }
    Write-Host "  Deployed successfully" -ForegroundColor Green
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 3: Reload or Restart
# ─────────────────────────────────────────────────────────────────────────────
if ($Restart) {
    Write-Host "[3/3] Restarting Minecraft server..." -ForegroundColor Yellow

    if ($DryRun) {
        Write-Host "  DRY RUN: Would run 'sudo systemctl restart minecraft.service'" -ForegroundColor DarkGray
    } else {
        ssh ${User}@${Server} "sudo systemctl restart minecraft.service"
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  RESTART FAILED" -ForegroundColor Red
            exit 1
        }

        Write-Host "  Waiting for server to start..." -ForegroundColor Gray
        Start-Sleep -Seconds 15

        # Health check
        $result = ssh ${User}@${Server} "systemctl is-active minecraft.service" 2>&1
        if ($result -match "active") {
            Write-Host "  Server is running" -ForegroundColor Green
        } else {
            Write-Host "  WARNING: Server may not have started. Check logs." -ForegroundColor Yellow
        }
    }
} else {
    Write-Host "[3/3] Hot-reloading plugin via PlugManX..." -ForegroundColor Yellow

    if ($DryRun) {
        Write-Host "  DRY RUN: Would send 'plugman reload audioviz' via mcrcon" -ForegroundColor DarkGray
    } else {
        # Hot-reload via mcrcon + PlugManX (no server restart needed)
        $reloadResult = ssh ${User}@${Server} "mcrcon -H 127.0.0.1 -P $RconPort -p '$RconPass' 'plugman reload audioviz'" 2>&1
        $exitCode = $LASTEXITCODE

        if ($exitCode -eq 0) {
            Write-Host "  Hot-reload successful" -ForegroundColor Green
            if ($reloadResult) {
                $reloadResult -split "`n" | Select-Object -First 5 | ForEach-Object {
                    Write-Host "  $_" -ForegroundColor Gray
                }
            }
        } else {
            Write-Host "  Hot-reload may have failed (exit code: $exitCode)" -ForegroundColor Yellow
            Write-Host "  Response: $reloadResult" -ForegroundColor Gray
            Write-Host "  Try with -Restart flag for a full server restart." -ForegroundColor Yellow
        }

        # Quick health check — verify plugin is enabled
        $infoResult = ssh ${User}@${Server} "mcrcon -H 127.0.0.1 -P $RconPort -p '$RconPass' 'plugman info audioviz'" 2>&1
        if ($infoResult -match "Enabled") {
            Write-Host "  AudioViz plugin is enabled" -ForegroundColor Green
        }
    }
}

# ─────────────────────────────────────────────────────────────────────────────
# Done
# ─────────────────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "  Deploy complete!" -ForegroundColor Green
Write-Host "  WebSocket: ws://${Server}:8765" -ForegroundColor Gray
Write-Host ""
