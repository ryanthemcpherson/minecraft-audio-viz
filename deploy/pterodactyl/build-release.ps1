param(
    [string]$Version = '26.1',
    [switch]$PackageExisting
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$distRoot = Join-Path $repoRoot 'dist'
$stagingRoot = Join-Path $distRoot "pterodactyl-$Version-staging"
$mcavRoot = Join-Path $stagingRoot 'mcav-vj'
$archive = Join-Path $distRoot "mcav-pterodactyl-$Version.zip"
$checksum = Join-Path $distRoot "mcav-pterodactyl-$Version.sha256"

New-Item -ItemType Directory -Force -Path $distRoot | Out-Null
if (-not $PackageExisting -and (Test-Path -LiteralPath $stagingRoot)) {
    $resolvedStaging = (Resolve-Path -LiteralPath $stagingRoot).Path
    if (-not $resolvedStaging.StartsWith($distRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe staging path: $resolvedStaging"
    }
    Remove-Item -LiteralPath $resolvedStaging -Recurse -Force
}
if ($PackageExisting -and -not (Test-Path -LiteralPath $mcavRoot -PathType Container)) {
    throw "Existing staging root not found: $mcavRoot"
}

if (-not $PackageExisting) {
New-Item -ItemType Directory -Force -Path $mcavRoot | Out-Null

Write-Host 'Building Paper plugin...'
$javaCandidates = @(
    'C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot',
    'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
)
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $env:JAVA_HOME = $javaCandidates | Where-Object {
        Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')
    } | Select-Object -First 1
}
if (-not $env:JAVA_HOME) { throw 'A Java 21+ JDK is required to build the plugin.' }
Push-Location (Join-Path $repoRoot 'minecraft_plugin')
try {
    & .\mvnw.cmd package
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
} finally {
    Pop-Location
}
$pluginJar = Get-ChildItem (Join-Path $repoRoot 'minecraft_plugin\target') -Filter 'audioviz-plugin-*.jar' |
    Where-Object Name -NotLike 'original-*' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $pluginJar) { throw 'Built AudioViz plugin JAR was not found.' }

New-Item -ItemType Directory -Force -Path (Join-Path $mcavRoot 'release') | Out-Null
Copy-Item -LiteralPath $pluginJar.FullName -Destination (Join-Path $mcavRoot 'release\AudioViz.jar')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'plugin-config.default.yml') -Destination (Join-Path $mcavRoot 'release\plugin-config.default.yml')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-mcav.sh') -Destination $mcavRoot
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcav.env.example') -Destination $mcavRoot
Set-Content -LiteralPath (Join-Path $mcavRoot 'VERSION') -Value $Version -NoNewline

New-Item -ItemType Directory -Force -Path (Join-Path $mcavRoot 'vj_server') | Out-Null
Get-ChildItem (Join-Path $repoRoot 'vj_server') -Filter '*.py' -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $mcavRoot 'vj_server')
}
Copy-Item -LiteralPath (Join-Path $repoRoot 'admin_panel') -Destination $mcavRoot -Recurse
New-Item -ItemType Directory -Force -Path (Join-Path $mcavRoot 'preview_tool') | Out-Null
Copy-Item -LiteralPath (Join-Path $repoRoot 'preview_tool\frontend') -Destination (Join-Path $mcavRoot 'preview_tool') -Recurse
Copy-Item -LiteralPath (Join-Path $repoRoot 'patterns') -Destination $mcavRoot -Recurse
New-Item -ItemType Directory -Force -Path (Join-Path $mcavRoot 'configs') | Out-Null
Copy-Item -LiteralPath (Join-Path $repoRoot 'configs\dj_auth.example.json') -Destination (Join-Path $mcavRoot 'configs')
foreach ($configDirectory in @('scenes', 'banners')) {
    $source = Join-Path $repoRoot "configs\$configDirectory"
    if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination (Join-Path $mcavRoot 'configs') -Recurse }
}

Write-Host 'Building portable AMD64 and ARM64 runtimes...'
$wslMcavRoot = (& wsl wslpath -a ($mcavRoot -replace '\\', '/')).Trim()
$wslRepoRoot = (& wsl wslpath -a ($repoRoot -replace '\\', '/')).Trim()
& wsl bash -lc "cd '$wslRepoRoot' && bash deploy/pterodactyl/build-runtime.sh '$wslMcavRoot'"
if ($LASTEXITCODE -ne 0) { throw 'Portable runtime build failed.' }
}

$developmentAssetPattern = '(^|/)(node_modules|\.git|\.venv|__pycache__|\.pytest_cache|tests?)(/|$)|(^|/)\.coverage($|/)|\.(pyc|pyo)$|(^|/)[^/]+\.(test|spec)\.[^/]+$'
Get-ChildItem -LiteralPath $mcavRoot -Recurse -File | Where-Object {
    $relativePath = $_.FullName.Substring($mcavRoot.Length + 1).Replace('\', '/')
    $relativePath -match $developmentAssetPattern
} | Remove-Item -Force

$manifestPath = Join-Path $mcavRoot 'MANIFEST.sha256'
$manifestLines = Get-ChildItem -LiteralPath $mcavRoot -Recurse -File |
    Where-Object FullName -ne $manifestPath |
    Sort-Object FullName |
    ForEach-Object {
        $relative = $_.FullName.Substring($mcavRoot.Length + 1).Replace('\', '/')
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
        "$hash  $relative"
    }
[IO.File]::WriteAllText($manifestPath, (($manifestLines -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))

if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$fileStream = [IO.File]::Open($archive, [IO.FileMode]::CreateNew)
$zip = [IO.Compression.ZipArchive]::new($fileStream, [IO.Compression.ZipArchiveMode]::Create, $false)
try {
    foreach ($file in Get-ChildItem -LiteralPath $mcavRoot -Recurse -File) {
        $relative = $file.FullName.Substring($stagingRoot.Length + 1).Replace('\', '/')
        $entry = $zip.CreateEntry($relative, [IO.Compression.CompressionLevel]::Optimal)
        $isExecutable = $relative -eq 'mcav-vj/start-mcav.sh' -or
            $relative -match '^mcav-vj/bin/[^/]+/audioviz-vj$' -or
            $relative -match '^mcav-vj/bin/[^/]+/python/bin/python3\.12$'
        $mode = if ($isExecutable) { 0x81ED } else { 0x81A4 }
        $entry.ExternalAttributes = $mode -shl 16
        $input = $file.OpenRead()
        $output = $entry.Open()
        try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
    }
} finally {
    $zip.Dispose()
    $fileStream.Dispose()
}

$archiveHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($checksum, "$archiveHash  $([IO.Path]::GetFileName($archive))`n", [Text.UTF8Encoding]::new($false))
& (Join-Path $PSScriptRoot 'verify-release.ps1') -Archive $archive
if (-not $?) { throw 'Release verification failed.' }
Write-Host "Release: $archive"
Write-Host "SHA-256: $archiveHash"
