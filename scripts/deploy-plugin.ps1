# AudioViz - Full Deploy: Plugin JAR + Patterns + VJ Server + Frontends
#
# Syncs everything needed to the remote server, hot-reloads the Minecraft
# plugin, and restarts the VJ server only when its Python code changed.
#
# Usage:
#   .\scripts\deploy-plugin.ps1                    # Build + deploy everything
#   .\scripts\deploy-plugin.ps1 -SkipBuild         # Deploy without rebuilding
#   .\scripts\deploy-plugin.ps1 -SkipTests         # Build without tests
#   .\scripts\deploy-plugin.ps1 -Restart            # Full MC server restart
#   .\scripts\deploy-plugin.ps1 -PluginOnly         # JAR + reload only (no sync)
#   .\scripts\deploy-plugin.ps1 -Server 10.0.0.5   # Custom server

param(
    [string]$Server = "192.168.1.204",
    [string]$User = "ryan",
    [string]$PluginsDir = "/home/ryan/minecraft-server/plugins",
    [string]$RemoteProject = "/home/ryan/minecraft-audio-viz",
    [string]$RconPort = "25575",
    [string]$RconPass = $env:MCAV_RCON_PASSWORD,
    [switch]$SkipBuild,
    [switch]$SkipTests,
    [switch]$Restart,
    [switch]$PluginOnly
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $ProjectRoot) { $ProjectRoot = (Get-Location).Path }
$PluginDir = Join-Path $ProjectRoot "minecraft_plugin"
$JarPattern = "audioviz-plugin-*-SNAPSHOT.jar"
$SshTarget = "${User}@${Server}"

$TotalSteps = if ($PluginOnly) { 3 } else { 4 }

Write-Host ""
Write-Host "  AudioViz Deploy" -ForegroundColor Cyan
Write-Host "  ===============" -ForegroundColor Cyan
Write-Host "  Server:  $SshTarget" -ForegroundColor White
Write-Host "  Mode:    $(if ($PluginOnly) { 'plugin-only' } else { 'full sync' })" -ForegroundColor White
Write-Host ""

# Auto-detect RCON password from server if not set
if (-not $RconPass) {
    $RconPass = (ssh $SshTarget "grep '^rcon.password=' /home/ryan/minecraft-server/server.properties 2>/dev/null | cut -d= -f2" 2>$null)
}
if (-not $RconPass) {
    Write-Host "  ERROR: RCON password not found. Set MCAV_RCON_PASSWORD or pass -RconPass" -ForegroundColor Red
    exit 1
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: Build
# ─────────────────────────────────────────────────────────────────────────────
if (-not $SkipBuild) {
    Write-Host "[1/$TotalSteps] Building plugin JAR..." -ForegroundColor Yellow

    $mvnArgs = "package"
    if ($SkipTests) { $mvnArgs = "package -DskipTests" }

    Push-Location $PluginDir
    try {
        if (Test-Path "./mvnw.cmd") { $mvnCmd = "./mvnw.cmd" }
        elseif (Get-Command mvn -ErrorAction SilentlyContinue) { $mvnCmd = "mvn" }
        else { throw "Maven not found. Install Maven or use the wrapper." }

        $mvnArgList = $mvnArgs -split '\s+'
        Write-Host "  Running: $mvnCmd $mvnArgs" -ForegroundColor Gray
        & $mvnCmd @mvnArgList 2>&1 | ForEach-Object {
            if ($_ -match "BUILD (SUCCESS|FAILURE)") {
                Write-Host "  $_" -ForegroundColor $(if ($_ -match "SUCCESS") { "Green" } else { "Red" })
            }
        }

        if ($LASTEXITCODE -ne 0) {
            Write-Host "  BUILD FAILED" -ForegroundColor Red
            exit 1
        }
    } finally {
        Pop-Location
    }

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
    Write-Host "[1/$TotalSteps] Skipping build" -ForegroundColor DarkGray

    $jarFile = Get-ChildItem (Join-Path $PluginDir "target") -Filter $JarPattern |
               Where-Object { $_.Name -notmatch "original" } |
               Sort-Object LastWriteTime -Descending |
               Select-Object -First 1

    if (-not $jarFile) {
        Write-Host "  ERROR: No existing JAR found. Run without -SkipBuild." -ForegroundColor Red
        exit 1
    }
    Write-Host "  Using: $($jarFile.Name)" -ForegroundColor White
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: Sync files to server
# ─────────────────────────────────────────────────────────────────────────────
Write-Host "[2/$TotalSteps] Deploying to $Server..." -ForegroundColor Yellow

# Always deploy the plugin JAR
ssh $SshTarget "mkdir -p '$PluginsDir'"
scp -q $jarFile.FullName "${SshTarget}:${PluginsDir}/"
if ($LASTEXITCODE -ne 0) { Write-Host "  SCP FAILED" -ForegroundColor Red; exit 1 }
Write-Host "  Plugin JAR deployed" -ForegroundColor Green

$VjChanged = $false

if (-not $PluginOnly) {
    # Snapshot VJ server hashes before sync
    $vjHashBefore = ssh $SshTarget "md5sum '${RemoteProject}/vj_server/vj_server.py' '${RemoteProject}/vj_server/patterns.py' '${RemoteProject}/vj_server/config.py' '${RemoteProject}/vj_server/cli.py' 2>/dev/null | sort" 2>$null

    # --- Patterns (Lua) ---
    $luaFiles = Get-ChildItem (Join-Path $ProjectRoot "patterns") -Filter "*.lua"
    foreach ($f in $luaFiles) {
        scp -q $f.FullName "${SshTarget}:${RemoteProject}/patterns/"
    }
    Write-Host "  Patterns synced ($($luaFiles.Count) files)" -ForegroundColor Green

    # --- VJ Server (Python) ---
    $vjFiles = Get-ChildItem (Join-Path $ProjectRoot "vj_server") -Filter "*.py"
    foreach ($f in $vjFiles) {
        scp -q $f.FullName "${SshTarget}:${RemoteProject}/vj_server/"
    }
    Write-Host "  VJ server synced" -ForegroundColor Green

    # --- python_client library ---
    $clientFiles = Get-ChildItem (Join-Path $ProjectRoot "python_client") -Filter "*.py"
    foreach ($f in $clientFiles) {
        scp -q $f.FullName "${SshTarget}:${RemoteProject}/python_client/"
    }
    Write-Host "  python_client synced" -ForegroundColor Green

    # --- Admin Panel ---
    scp -q (Join-Path $ProjectRoot "admin_panel/index.html") "${SshTarget}:${RemoteProject}/admin_panel/"
    Get-ChildItem (Join-Path $ProjectRoot "admin_panel/js") -Filter "*.js" | ForEach-Object {
        scp -q $_.FullName "${SshTarget}:${RemoteProject}/admin_panel/js/"
    }
    Get-ChildItem (Join-Path $ProjectRoot "admin_panel/css") -Filter "*.css" | ForEach-Object {
        scp -q $_.FullName "${SshTarget}:${RemoteProject}/admin_panel/css/"
    }
    Write-Host "  Admin panel synced" -ForegroundColor Green

    # --- Preview Tool ---
    scp -q (Join-Path $ProjectRoot "preview_tool/frontend/index.html") "${SshTarget}:${RemoteProject}/preview_tool/frontend/"
    Get-ChildItem (Join-Path $ProjectRoot "preview_tool/frontend/js") -Filter "*.js" | ForEach-Object {
        scp -q $_.FullName "${SshTarget}:${RemoteProject}/preview_tool/frontend/js/"
    }
    Get-ChildItem (Join-Path $ProjectRoot "preview_tool/frontend/css") -Filter "*.css" | ForEach-Object {
        scp -q $_.FullName "${SshTarget}:${RemoteProject}/preview_tool/frontend/css/"
    }
    Write-Host "  Preview tool synced" -ForegroundColor Green

    # Check if VJ server Python code changed
    $vjHashAfter = ssh $SshTarget "md5sum '${RemoteProject}/vj_server/vj_server.py' '${RemoteProject}/vj_server/patterns.py' '${RemoteProject}/vj_server/config.py' '${RemoteProject}/vj_server/cli.py' 2>/dev/null | sort" 2>$null

    if ($vjHashBefore -ne $vjHashAfter) {
        $VjChanged = $true
        Write-Host "  VJ server code changed - will restart" -ForegroundColor Yellow
    } else {
        Write-Host "  VJ server code unchanged - patterns will hot-reload" -ForegroundColor Green
    }
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 3: Hot-reload or Restart Minecraft plugin
# ─────────────────────────────────────────────────────────────────────────────
if ($Restart) {
    Write-Host "[3/$TotalSteps] Restarting Minecraft server..." -ForegroundColor Yellow
    ssh $SshTarget "sudo systemctl restart minecraft.service"

    Write-Host "  Waiting for server..." -ForegroundColor Gray
    Start-Sleep -Seconds 15

    $result = ssh $SshTarget "systemctl is-active minecraft.service" 2>&1
    if ($result -match "active") {
        Write-Host "  Server is running" -ForegroundColor Green
    } else {
        Write-Host "  WARNING: Server may not have started. Check logs." -ForegroundColor Yellow
    }
} else {
    Write-Host "[3/$TotalSteps] Hot-reloading plugin..." -ForegroundColor Yellow

    $reloadResult = ssh $SshTarget "mcrcon -H 127.0.0.1 -P $RconPort -p '$RconPass' 'plugman reload audioviz'" 2>&1
    if ($reloadResult -match "reload") {
        Write-Host "  Plugin reloaded" -ForegroundColor Green
    } else {
        Write-Host "  Reload response: $reloadResult" -ForegroundColor Gray
    }
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 4: Restart VJ server if needed
# ─────────────────────────────────────────────────────────────────────────────
if (-not $PluginOnly) {
    Write-Host "[4/$TotalSteps] VJ server..." -ForegroundColor Yellow

    if ($VjChanged) {
        Write-Host "  Restarting VJ server (Python code changed)..." -ForegroundColor Yellow

        # Find and kill existing VJ server
        $vjPid = ssh $SshTarget "ps aux | grep 'vj_server.cli' | grep -v grep | grep python" 2>$null
        if ($vjPid) {
            $pid = ($vjPid -split '\s+')[1]
            ssh $SshTarget "kill $pid" 2>$null
            Start-Sleep -Seconds 2
        }

        # Start fresh VJ server
        ssh $SshTarget "cd '${RemoteProject}' && nohup .venv/bin/python -m vj_server.cli --no-auth --minecraft-host ${Server} > /tmp/vj_server.log 2>&1 &"
        Start-Sleep -Seconds 3

        # Verify
        $vjCheck = ssh $SshTarget "ps aux | grep 'vj_server.cli' | grep -v grep | grep python" 2>$null
        if ($vjCheck) {
            Write-Host "  VJ server restarted" -ForegroundColor Green
        } else {
            Write-Host "  WARNING: VJ server may not have started. Check /tmp/vj_server.log" -ForegroundColor Yellow
        }
    } else {
        Write-Host "  No restart needed (Lua patterns hot-reload automatically)" -ForegroundColor Green
    }
}

# ─────────────────────────────────────────────────────────────────────────────
# Done
# ─────────────────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "  Deploy complete!" -ForegroundColor Green
Write-Host "  WebSocket:     ws://${Server}:8765" -ForegroundColor Gray
Write-Host "  Admin Panel:   http://${Server}:8081" -ForegroundColor Gray
Write-Host "  Preview:       http://${Server}:8080" -ForegroundColor Gray
Write-Host "  VJ Server:     ws://${Server}:9000" -ForegroundColor Gray
Write-Host ""
