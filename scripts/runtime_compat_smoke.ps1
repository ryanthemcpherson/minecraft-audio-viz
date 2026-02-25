param(
    [int]$CoordinatorPort = 18090,
    [int]$VjPort = 19000,
    [int]$VjBroadcastPort = 18766,
    [int]$VjMetricsPort = 19001,
    [int]$SitePort = 13100
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$coordOut = Join-Path $root "coordinator-runtime-smoke.out.log"
$coordErr = Join-Path $root "coordinator-runtime-smoke.err.log"
$vjOut = Join-Path $root "vj-runtime-smoke.out.log"
$vjErr = Join-Path $root "vj-runtime-smoke.err.log"
$siteOut = Join-Path $root "site-runtime-smoke.out.log"
$siteErr = Join-Path $root "site-runtime-smoke.err.log"
Remove-Item $coordOut,$coordErr,$vjOut,$vjErr,$siteOut,$siteErr -ErrorAction SilentlyContinue

$coordProc = $null
$vjProc = $null
$siteProc = $null
$previousDbUrl = $env:MCAV_DATABASE_URL

try {
    $env:MCAV_DATABASE_URL = "sqlite+aiosqlite:///./runtime_smoke.db"
    Push-Location (Join-Path $root "coordinator")
    try {
        & python -m alembic upgrade head *> $null
    } finally {
        Pop-Location
    }
    $coordProc = Start-Process -FilePath python -ArgumentList "-m uvicorn app.main:app --host 127.0.0.1 --port $CoordinatorPort" -WorkingDirectory (Join-Path $root "coordinator") -RedirectStandardOutput $coordOut -RedirectStandardError $coordErr -PassThru
    $vjProc = Start-Process -FilePath python -ArgumentList "-m vj_server.cli --no-auth --no-spectrograph --port $VjPort --broadcast-port $VjBroadcastPort --metrics-port $VjMetricsPort --minecraft-host 127.0.0.1 --minecraft-port 18765" -WorkingDirectory $root -RedirectStandardOutput $vjOut -RedirectStandardError $vjErr -PassThru
    $siteProc = Start-Process -FilePath npm -ArgumentList "run start -- --port $SitePort --hostname 127.0.0.1" -WorkingDirectory (Join-Path $root "site") -RedirectStandardOutput $siteOut -RedirectStandardError $siteErr -PassThru

    Start-Sleep -Seconds 10

    $checks = @(
        @{ name = "coordinator_health"; url = "http://127.0.0.1:$CoordinatorPort/health"; expect = '"status"' },
        @{ name = "vj_metrics"; url = "http://127.0.0.1:$VjMetricsPort/metrics"; expect = "mcav_" },
        @{ name = "site_home"; url = "http://127.0.0.1:$SitePort/"; expect = "<html" }
    )

    $failed = $false
    foreach ($check in $checks) {
        try {
            $resp = Invoke-WebRequest -UseBasicParsing $check.url -TimeoutSec 8
            $ok = $resp.StatusCode -eq 200 -and $resp.Content.Contains($check.expect)
            Write-Host "$($check.name): status=$($resp.StatusCode) match=$ok"
            if (-not $ok) { $failed = $true }
        } catch {
            Write-Host "$($check.name): FAIL $($_.Exception.Message)"
            $failed = $true
        }
    }

    if ($failed) {
        throw "Runtime compatibility smoke failed. Check logs: $coordErr, $vjErr, $siteErr"
    }
} finally {
    if ($null -ne $previousDbUrl) {
        $env:MCAV_DATABASE_URL = $previousDbUrl
    } else {
        Remove-Item Env:MCAV_DATABASE_URL -ErrorAction SilentlyContinue
    }
    if ($coordProc) { Stop-Process -Id $coordProc.Id -Force -ErrorAction SilentlyContinue }
    if ($vjProc) { Stop-Process -Id $vjProc.Id -Force -ErrorAction SilentlyContinue }
    if ($siteProc) { Stop-Process -Id $siteProc.Id -Force -ErrorAction SilentlyContinue }
}

Write-Host "Runtime compatibility smoke passed."
