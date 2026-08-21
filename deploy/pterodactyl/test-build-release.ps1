param(
    [string]$Version = "26.1-powershell-exclusion-$PID"
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$distRoot = Join-Path $repoRoot 'dist'
$stagingRoot = Join-Path $distRoot "pterodactyl-$Version-staging"
$mcavRoot = Join-Path $stagingRoot 'mcav-vj'
$archive = Join-Path $distRoot "mcav-pterodactyl-$Version.zip"
$checksum = Join-Path $distRoot "mcav-pterodactyl-$Version.sha256"
$buildScript = Join-Path $PSScriptRoot 'build-release.ps1'
$verifyScript = Join-Path $PSScriptRoot 'verify-release.ps1'

function Write-FixtureFile {
    param(
        [string]$RelativePath,
        [string]$Content = 'fixture'
    )

    $path = Join-Path $mcavRoot $RelativePath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    [IO.File]::WriteAllText($path, $Content, [Text.UTF8Encoding]::new($false))
}

function Get-ArchiveNames {
    param([string]$Path)

    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        return @($zip.Entries | Where-Object { -not [string]::IsNullOrEmpty($_.Name) } |
            ForEach-Object FullName)
    } finally {
        $zip.Dispose()
    }
}

function Add-ManifestedSpecFile {
    param([string]$Path)

    $relativePath = 'preview_tool/frontend/injected.spec.mjs'
    $payload = [Text.Encoding]::UTF8.GetBytes('spec files must not ship')
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = [Convert]::ToHexString($sha.ComputeHash($payload)).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }

    $zip = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry = $zip.CreateEntry("mcav-vj/$relativePath")
        $stream = $entry.Open()
        try { $stream.Write($payload, 0, $payload.Length) } finally { $stream.Dispose() }

        $manifestEntry = $zip.Entries | Where-Object FullName -eq 'mcav-vj/MANIFEST.sha256' |
            Select-Object -First 1
        $reader = [IO.StreamReader]::new($manifestEntry.Open())
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $writer = [IO.StreamWriter]::new($manifestEntry.Open())
        try { $writer.Write($manifest.TrimEnd("`r", "`n") + "`n$digest  $relativePath`n") }
        finally { $writer.Dispose() }
    } finally {
        $zip.Dispose()
    }
}

try {
    foreach ($path in @($stagingRoot, $archive, $checksum)) {
        if (Test-Path -LiteralPath $path) { throw "Test path already exists: $path" }
    }

    New-Item -ItemType Directory -Force -Path $mcavRoot | Out-Null
    foreach ($relativePath in @(
        'start-mcav.sh',
        'VERSION',
        'release/AudioViz.jar',
        'release/plugin-config.default.yml',
        'bin/linux-amd64/audioviz-vj',
        'bin/linux-amd64/python/bin/python3.12',
        'bin/linux-arm64/audioviz-vj',
        'bin/linux-arm64/python/bin/python3.12',
        'admin_panel/index.html',
        'preview_tool/frontend/index.html'
    )) {
        Write-FixtureFile -RelativePath $relativePath
    }
    Write-FixtureFile -RelativePath 'admin_panel/tests/control.test.mjs'
    Write-FixtureFile -RelativePath 'preview_tool/frontend/component.spec.mjs'

    & $buildScript -Version $Version -PackageExisting
    if (-not $?) { throw 'PowerShell release builder failed.' }

    $forbiddenArchiveFiles = @(Get-ArchiveNames -Path $archive | Where-Object {
        $_ -match '(^|/)(tests?)(/|$)' -or $_ -match '(^|/)[^/]+\.(test|spec)\.[^/]+$'
    })
    if ($forbiddenArchiveFiles) {
        throw "PowerShell release builder included test assets: $($forbiddenArchiveFiles -join ', ')"
    }

    Add-ManifestedSpecFile -Path $archive
    $verificationFailure = $null
    try {
        & $verifyScript -Archive $archive
    } catch {
        $verificationFailure = $_
    }
    if ($null -eq $verificationFailure) {
        throw 'PowerShell release verifier accepted a manifest-covered spec file.'
    }
    if ($verificationFailure.Exception.Message -notmatch 'Forbidden development or secret entries: mcav-vj/preview_tool/frontend/injected\.spec\.mjs') {
        throw "PowerShell release verifier rejected the spec file for an unexpected reason: $($verificationFailure.Exception.Message)"
    }

    Write-Host 'PowerShell Pterodactyl release exclusion test passed.'
} finally {
    foreach ($path in @($stagingRoot, $archive, $checksum)) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
    }
}
