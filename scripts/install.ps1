# AudioViz - Installation Script
# Installs the Python package using UV or pip

param(
    [switch]$Dev,
    [switch]$Full,
    [switch]$UsePip
)

Write-Host "AudioViz - Installation" -ForegroundColor Cyan
Write-Host "=======================" -ForegroundColor Cyan
Write-Host ""

# Check for UV first
$useUV = $false
if (-not $UsePip) {
    try {
        $null = Get-Command uv -ErrorAction Stop
        $useUV = $true
        Write-Host "Using UV for installation" -ForegroundColor Green
    } catch {
        Write-Host "UV not found, falling back to pip" -ForegroundColor Yellow
    }
}

# Build extras string
$extras = ""
if ($Full) {
    $extras = "[full]"
    Write-Host "Installing with all optional dependencies" -ForegroundColor White
} elseif ($Dev) {
    $extras = "[dev]"
    Write-Host "Installing with development dependencies" -ForegroundColor White
}

# Install
if ($useUV) {
    Write-Host "Running: uv pip install -e .$extras" -ForegroundColor Gray
    uv pip install -e ".$extras"
} else {
    Write-Host "Running: pip install -e .$extras" -ForegroundColor Gray
    pip install -e ".$extras"
}

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "Installation successful!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Available commands:" -ForegroundColor White
    Write-Host "  audioviz          - Local DJ mode" -ForegroundColor Gray
    Write-Host "  audioviz-vj       - VJ server mode" -ForegroundColor Gray
    Write-Host "  audioviz --help   - Show all options" -ForegroundColor Gray
} else {
    Write-Host ""
    Write-Host "Installation failed!" -ForegroundColor Red
    Write-Host "Check the error messages above." -ForegroundColor Yellow
}
