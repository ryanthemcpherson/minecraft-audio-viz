param(
    [Parameter(Mandatory = $true)]
    [string]$Archive
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Archive -PathType Leaf)) {
    throw "Release archive not found: $Archive"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$resolvedArchive = (Resolve-Path -LiteralPath $Archive).Path
$zip = [System.IO.Compression.ZipFile]::OpenRead($resolvedArchive)
try {
    $entries = @($zip.Entries | Where-Object { -not [string]::IsNullOrEmpty($_.Name) })
    $names = @($entries | ForEach-Object { $_.FullName })
    $duplicates = @($names | Group-Object | Where-Object Count -gt 1)
    if ($duplicates) { throw "Duplicate ZIP entries: $($duplicates.Name -join ', ')" }
    if ($names | Where-Object { -not $_.StartsWith('mcav-vj/') }) {
        throw 'Every release entry must be under the single mcav-vj/ root.'
    }

    $required = @(
        'mcav-vj/start-mcav.sh',
        'mcav-vj/VERSION',
        'mcav-vj/bin/linux-amd64/audioviz-vj',
        'mcav-vj/bin/linux-amd64/python/bin/python3.12',
        'mcav-vj/bin/linux-arm64/audioviz-vj',
        'mcav-vj/bin/linux-arm64/python/bin/python3.12',
        'mcav-vj/release/AudioViz.jar',
        'mcav-vj/release/plugin-config.default.yml',
        'mcav-vj/admin_panel/index.html',
        'mcav-vj/preview_tool/frontend/index.html',
        'mcav-vj/MANIFEST.sha256'
    )
    foreach ($requiredEntry in $required) {
        if ($requiredEntry -notin $names) { throw "Required release entry missing: $requiredEntry" }
    }

    $forbidden = @($names | Where-Object {
        $_ -match '(^|/)(node_modules|\.git|\.venv|__pycache__|tests?)(/|$)' -or
        $_ -match '\.(pyc|pyo)$' -or
        $_ -eq 'mcav-vj/state/dj_auth.json' -or
        $_ -eq 'mcav-vj/state/runtime.env' -or
        $_ -eq 'mcav-vj/state/tls.key' -or
        $_ -eq 'mcav-vj/FIRST_LOGIN.txt'
    })
    if ($forbidden) { throw "Forbidden development or secret entries: $($forbidden -join ', ')" }

    $runtimeRoots = @($names | ForEach-Object {
        if ($_ -match '^mcav-vj/bin/([^/]+)/') { $Matches[1] }
    } | Sort-Object -Unique)
    if (($runtimeRoots -join ',') -ne 'linux-amd64,linux-arm64') {
        throw "Unexpected runtime architectures: $($runtimeRoots -join ', ')"
    }

    foreach ($executableName in @(
        'mcav-vj/start-mcav.sh',
        'mcav-vj/bin/linux-amd64/audioviz-vj',
        'mcav-vj/bin/linux-arm64/audioviz-vj',
        'mcav-vj/bin/linux-amd64/python/bin/python3.12',
        'mcav-vj/bin/linux-arm64/python/bin/python3.12'
    )) {
        $entry = $entries | Where-Object FullName -eq $executableName | Select-Object -First 1
        $unixMode = ($entry.ExternalAttributes -shr 16) -band 0xFFFF
        if (($unixMode -band 0x49) -eq 0) {
            throw "Executable mode is missing from: $executableName"
        }
    }

    $manifestEntry = $entries | Where-Object FullName -eq 'mcav-vj/MANIFEST.sha256' | Select-Object -First 1
    $reader = [System.IO.StreamReader]::new($manifestEntry.Open())
    try { $manifestText = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $manifest = @{}
    foreach ($line in ($manifestText -split "`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { throw "Malformed manifest line: $line" }
        $manifest[$Matches[2].TrimEnd("`r")] = $Matches[1]
    }

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        foreach ($entry in $entries | Where-Object FullName -ne 'mcav-vj/MANIFEST.sha256') {
            $relative = $entry.FullName.Substring('mcav-vj/'.Length)
            if (-not $manifest.ContainsKey($relative)) { throw "Manifest entry missing: $relative" }
            $stream = $entry.Open()
            try { $actual = [Convert]::ToHexString($sha.ComputeHash($stream)).ToLowerInvariant() }
            finally { $stream.Dispose() }
            if ($actual -ne $manifest[$relative]) { throw "Manifest digest mismatch: $relative" }
        }
    } finally {
        $sha.Dispose()
    }

    Write-Host "Verified release: $resolvedArchive"
    Write-Host "Entries: $($entries.Count)"
} finally {
    $zip.Dispose()
}
