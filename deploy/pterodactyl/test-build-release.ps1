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

function Write-ElfFixture {
    param(
        [string]$RelativePath,
        [int]$Machine
    )

    $path = Join-Path $mcavRoot $RelativePath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    $header = [byte[]]::new(64)
    $header[0] = 0x7f
    $header[1] = 0x45
    $header[2] = 0x4c
    $header[3] = 0x46
    $header[4] = 2
    $header[5] = 1
    $header[18] = $Machine -band 0xff
    $header[19] = ($Machine -shr 8) -band 0xff
    [IO.File]::WriteAllBytes($path, $header)
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
        'mcav.env.example',
        'release/AudioViz.jar',
        'release/plugin-config.default.yml',
        'release/runtime-lock.json',
        'bin/linux-amd64/audioviz-vj',
        'bin/linux-amd64/python/bin/python3.12',
        'bin/linux-arm64/audioviz-vj',
        'bin/linux-arm64/python/bin/python3.12',
        'admin_panel/index.html',
        'admin_panel/runtime-config.js',
        'preview_tool/frontend/index.html',
        'preview_tool/frontend/runtime-config.js',
        'vj_server/__init__.py',
        'vj_server/auth.py',
        'vj_server/cli.py',
        'vj_server/config.py',
        'vj_server/patterns.py',
        'vj_server/vj_server.py',
        'vj_server/web_gateway.py',
        'patterns/lib.lua',
        'patterns/bars.lua'
    )) {
        Write-FixtureFile -RelativePath $relativePath
    }
    $runtimeLockText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runtime-lock.json') -Raw
    Write-FixtureFile -RelativePath 'release/runtime-lock.json' -Content $runtimeLockText
    $runtimeLock = $runtimeLockText | ConvertFrom-Json
    foreach ($architecture in @('linux-amd64', 'linux-arm64')) {
        foreach ($dependency in $runtimeLock.dependencies) {
            $distribution = [regex]::Replace([string]$dependency.name, '[-_.]+', '_')
            $metadataPath = "bin/$architecture/python/lib/python3.12/site-packages/$distribution-$($dependency.version).dist-info/METADATA"
            $metadata = "Name: $($dependency.name)`nVersion: $($dependency.version)`n"
            Write-FixtureFile -RelativePath $metadataPath -Content $metadata
        }
    }
    Write-ElfFixture -RelativePath 'bin/linux-amd64/python/bin/python3.12' -Machine 62
    Write-ElfFixture -RelativePath 'bin/linux-arm64/python/bin/python3.12' -Machine 183
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
