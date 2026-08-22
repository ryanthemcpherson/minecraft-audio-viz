param(
    [Parameter(Mandatory = $true)]
    [string]$Archive
)

$ErrorActionPreference = 'Stop'

function Assert-CanonicalPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $components = $Path.Split([char]'/', [StringSplitOptions]::None)
    $invalidComponent = @($components | Where-Object { $_ -in @('', '.', '..') }).Count -gt 0
    if ([string]::IsNullOrEmpty($Path) -or $Path.Contains([char]92) -or
        $Path.StartsWith('/', [StringComparison]::Ordinal) -or
        $Path -match '^[A-Za-z]:' -or $invalidComponent) {
        throw "Noncanonical ${Description}: $Path"
    }
}

function Get-ElfMachine {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchiveEntry]$Entry
    )

    $header = [byte[]]::new(20)
    $stream = $Entry.Open()
    try {
        $offset = 0
        while ($offset -lt $header.Length) {
            $read = $stream.Read($header, $offset, $header.Length - $offset)
            if ($read -eq 0) { break }
            $offset += $read
        }
    } finally {
        $stream.Dispose()
    }
    if ($offset -lt 20 -or $header[0] -ne 0x7f -or $header[1] -ne 0x45 -or
        $header[2] -ne 0x4c -or $header[3] -ne 0x46 -or $header[4] -ne 2) {
        throw "$($Entry.FullName) is not a 64-bit ELF executable"
    }
    if ($header[5] -eq 1) { return [int]$header[18] -bor ([int]$header[19] -shl 8) }
    if ($header[5] -eq 2) { return ([int]$header[18] -shl 8) -bor [int]$header[19] }
    throw "$($Entry.FullName) has an unsupported ELF byte order"
}

if (-not (Test-Path -LiteralPath $Archive -PathType Leaf)) {
    throw "Release archive not found: $Archive"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$resolvedArchive = (Resolve-Path -LiteralPath $Archive).Path
$zip = [System.IO.Compression.ZipFile]::OpenRead($resolvedArchive)
try {
    $entries = @($zip.Entries)
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in $entries) {
        if (-not $names.Add($entry.FullName)) {
            throw "Duplicate ZIP entries: $($entry.FullName)"
        }
        Assert-CanonicalPath -Path $entry.FullName -Description 'ZIP entry'
        if ([string]::IsNullOrEmpty($entry.Name)) {
            throw "Noncanonical ZIP entry: $($entry.FullName)"
        }
    }
    if ($names | Where-Object { -not $_.StartsWith('mcav-vj/', [StringComparison]::Ordinal) }) {
        throw 'Every release entry must be under the single mcav-vj/ root.'
    }

    $required = @(
        'mcav-vj/start-mcav.sh',
        'mcav-vj/VERSION',
        'mcav-vj/mcav.env.example',
        'mcav-vj/bin/linux-amd64/audioviz-vj',
        'mcav-vj/bin/linux-amd64/python/bin/python3.12',
        'mcav-vj/bin/linux-arm64/audioviz-vj',
        'mcav-vj/bin/linux-arm64/python/bin/python3.12',
        'mcav-vj/release/AudioViz.jar',
        'mcav-vj/release/plugin-config.default.yml',
        'mcav-vj/release/runtime-lock.json',
        'mcav-vj/vj_server/__init__.py',
        'mcav-vj/vj_server/auth.py',
        'mcav-vj/vj_server/cli.py',
        'mcav-vj/vj_server/config.py',
        'mcav-vj/vj_server/patterns.py',
        'mcav-vj/vj_server/vj_server.py',
        'mcav-vj/vj_server/web_gateway.py',
        'mcav-vj/patterns/lib.lua',
        'mcav-vj/patterns/bars.lua',
        'mcav-vj/admin_panel/index.html',
        'mcav-vj/admin_panel/runtime-config.js',
        'mcav-vj/preview_tool/frontend/index.html',
        'mcav-vj/preview_tool/frontend/runtime-config.js',
        'mcav-vj/MANIFEST.sha256'
    )
    foreach ($requiredEntry in $required) {
        if (-not $names.Contains($requiredEntry)) {
            throw "Required release entries missing: $requiredEntry"
        }
    }

    $forbidden = @($names | Where-Object {
        $_ -match '(^|/)(node_modules|\.git|\.venv|__pycache__|tests?)(/|$)' -or
        $_ -match '\.(pyc|pyo)$' -or
        $_ -match '(^|/)[^/]+\.(test|spec)\.[^/]+$' -or
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

    $expectedElfMachines = @{
        'mcav-vj/bin/linux-amd64/python/bin/python3.12' = 62
        'mcav-vj/bin/linux-arm64/python/bin/python3.12' = 183
    }
    foreach ($executableName in $expectedElfMachines.Keys) {
        $entry = $entries | Where-Object FullName -ceq $executableName | Select-Object -First 1
        $actualMachine = Get-ElfMachine -Entry $entry
        $expectedMachine = $expectedElfMachines[$executableName]
        if ($actualMachine -ne $expectedMachine) {
            $architecture = $executableName.Split('/')[2]
            throw "$architecture Python executable has ELF machine $actualMachine; expected $expectedMachine"
        }
    }

    $manifestEntry = $entries | Where-Object FullName -eq 'mcav-vj/MANIFEST.sha256' | Select-Object -First 1
    $reader = [System.IO.StreamReader]::new($manifestEntry.Open())
    try { $manifestText = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $manifest = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    foreach ($line in ($manifestText -split "`r?`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { throw "Malformed manifest line: $line" }
        $relativePath = $Matches[2]
        Assert-CanonicalPath -Path $relativePath -Description 'manifest path'
        if ($manifest.ContainsKey($relativePath)) {
            throw "Duplicate manifest entry: $relativePath"
        }
        $manifest.Add($relativePath, $Matches[1])
    }

    $payloadNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in $entries | Where-Object FullName -cne 'mcav-vj/MANIFEST.sha256') {
        [void]$payloadNames.Add($entry.FullName.Substring('mcav-vj/'.Length))
    }
    $missingManifest = @($payloadNames | Where-Object { -not $manifest.ContainsKey($_) } | Sort-Object)
    $extraManifest = @($manifest.Keys | Where-Object { -not $payloadNames.Contains($_) } | Sort-Object)
    if ($missingManifest.Count -gt 0 -or $extraManifest.Count -gt 0) {
        throw "Manifest coverage mismatch; missing=[$($missingManifest -join ', ')], extra=[$($extraManifest -join ', ')]"
    }

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        foreach ($entry in $entries | Where-Object FullName -cne 'mcav-vj/MANIFEST.sha256') {
            $relative = $entry.FullName.Substring('mcav-vj/'.Length)
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
