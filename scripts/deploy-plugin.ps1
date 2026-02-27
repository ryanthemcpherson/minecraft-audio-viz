# AudioViz - Full Deploy: Mod JAR + Patterns + VJ Server + Frontends
#
# Syncs everything needed to the remote server and restarts services.
# Fabric mods require a full server restart (no hot-reload).
#
# Usage:
#   .\scripts\deploy-plugin.ps1                    # Build + deploy everything
#   .\scripts\deploy-plugin.ps1 -SkipBuild         # Deploy without rebuilding
#   .\scripts\deploy-plugin.ps1 -SkipTests         # Build without tests
#   .\scripts\deploy-plugin.ps1 -ModOnly           # JAR + restart only (no sync)
#   .\scripts\deploy-plugin.ps1 -Server 10.0.0.5   # Custom server

param(
    [string]$Server = "192.168.1.204",
    [string]$User = "ryan",
    [string]$ModsDir = "/home/ryan/minecraft-server/mods",
    [string]$RemoteProject = "/home/ryan/minecraft-audio-viz",
    [switch]$SkipBuild,
    [switch]$SkipTests,
    [switch]$ModOnly
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $ProjectRoot) { $ProjectRoot = (Get-Location).Path }
$ModDir = Join-Path $ProjectRoot "minecraft_mod"
$JarPattern = "audioviz-mod-*.jar"
$SshTarget = "${User}@${Server}"

$TotalSteps = if ($ModOnly) { 3 } else { 4 }

Write-Host ""
Write-Host "  AudioViz Deploy (Fabric)" -ForegroundColor Cyan
Write-Host "  ========================" -ForegroundColor Cyan
Write-Host "  Server:  $SshTarget" -ForegroundColor White
Write-Host "  Mode:    $(if ($ModOnly) { 'mod-only' } else { 'full sync' })" -ForegroundColor White
Write-Host ""

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: Build
# ─────────────────────────────────────────────────────────────────────────────
if (-not $SkipBuild) {
    Write-Host "[1/$TotalSteps] Building mod JAR..." -ForegroundColor Yellow

    $gradleArgs = @("build")
    if ($SkipTests) { $gradleArgs = @("build", "-x", "test") }

    Push-Location $ModDir
    try {
        Write-Host "  Running: ./gradlew $($gradleArgs -join ' ')" -ForegroundColor Gray
        & ./gradlew.bat @gradleArgs 2>&1 | ForEach-Object {
            if ($_ -match "BUILD (SUCCESSFUL|FAILED)") {
                Write-Host "  $_" -ForegroundColor $(if ($_ -match "SUCCESSFUL") { "Green" } else { "Red" })
            }
        }

        if ($LASTEXITCODE -ne 0) {
            Write-Host "  BUILD FAILED" -ForegroundColor Red
            exit 1
        }
    } finally {
        Pop-Location
    }

    $jarFile = Get-ChildItem (Join-Path $ModDir "build/libs") -Filter $JarPattern |
               Sort-Object LastWriteTime -Descending |
               Select-Object -First 1

    if (-not $jarFile) {
        Write-Host "  ERROR: No JAR found matching $JarPattern" -ForegroundColor Red
        exit 1
    }
    Write-Host "  Built: $($jarFile.Name) ($([math]::Round($jarFile.Length / 1MB, 1)) MB)" -ForegroundColor Green
} else {
    Write-Host "[1/$TotalSteps] Skipping build" -ForegroundColor DarkGray

    $jarFile = Get-ChildItem (Join-Path $ModDir "build/libs") -Filter $JarPattern |
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

# Always deploy the mod JAR
ssh $SshTarget "mkdir -p '$ModsDir' && rm -f '$ModsDir'/audioviz-mod-*.jar"
scp -q $jarFile.FullName "${SshTarget}:${ModsDir}/"
if ($LASTEXITCODE -ne 0) { Write-Host "  SCP FAILED" -ForegroundColor Red; exit 1 }
Write-Host "  Mod JAR deployed" -ForegroundColor Green

$VjChanged = $false

if (-not $ModOnly) {
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
# Step 3: Restart Minecraft server (Fabric requires full restart)
# ─────────────────────────────────────────────────────────────────────────────
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

# ─────────────────────────────────────────────────────────────────────────────
# Step 4: Restart VJ server if needed
# ─────────────────────────────────────────────────────────────────────────────
if (-not $ModOnly) {
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
