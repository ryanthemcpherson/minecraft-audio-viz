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

function Get-ZipEntryText {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchiveEntry]$Entry
    )

    $reader = [System.IO.StreamReader]::new($Entry.Open())
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

function Get-NormalizedPackageName {
    param([Parameter(Mandatory = $true)][string]$Name)
    return ([regex]::Replace($Name, '[-_.]+', '-')).ToLowerInvariant()
}

function Get-MetadataField {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Field
    )

    $match = [regex]::Match($Text, "(?m)^$([regex]::Escape($Field)):\s*(.+?)\r?$")
    if (-not $match.Success) { return $null }
    return $match.Groups[1].Value.Trim()
}

function Assert-PackagedRuntimeClosure {
    param(
        [Parameter(Mandatory = $true)][object[]]$Entries,
        [Parameter(Mandatory = $true)]
        [Collections.Generic.Dictionary[string, System.IO.Compression.ZipArchiveEntry]]$EntriesByName
    )

    $lockName = 'mcav-vj/release/runtime-lock.json'
    try {
        $lock = Get-ZipEntryText -Entry $EntriesByName[$lockName] | ConvertFrom-Json
    } catch {
        throw "Invalid packaged runtime lock: $($_.Exception.Message)"
    }
    if ($lock.schema_version -ne 1) { throw 'Invalid packaged runtime lock schema_version' }
    $architectures = @('linux-amd64', 'linux-arm64')
    $runtimeNames = @($lock.runtimes.PSObject.Properties.Name)
    if ($runtimeNames.Count -ne 2 -or
        @($architectures | Where-Object { $runtimeNames -cnotcontains $_ }).Count -gt 0) {
        throw 'Packaged runtime lock must define exactly linux-amd64 and linux-arm64'
    }

    $expected = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    $dependencies = @($lock.dependencies)
    if ($dependencies.Count -eq 0) { throw 'Packaged runtime lock has no dependencies' }
    foreach ($dependency in $dependencies) {
        $name = [string]$dependency.name
        $version = [string]$dependency.version
        if ([string]::IsNullOrEmpty($name) -or [string]::IsNullOrEmpty($version)) {
            throw 'Packaged runtime lock dependency name and version must be non-empty'
        }
        $normalizedName = Get-NormalizedPackageName -Name $name
        if ($expected.ContainsKey($normalizedName)) {
            throw "Duplicate packaged runtime dependency: $name"
        }
        $wheelArchitectures = @($dependency.wheels.PSObject.Properties.Name)
        if ($wheelArchitectures.Count -ne 2 -or
            @($architectures | Where-Object { $wheelArchitectures -cnotcontains $_ }).Count -gt 0) {
            throw "Packaged runtime dependency $name must lock exactly both architectures"
        }
        foreach ($architecture in $architectures) {
            $wheel = $dependency.wheels.PSObject.Properties[$architecture].Value
            if ([string]$wheel.filename -notmatch '\.whl$' -or
                [string]$wheel.sha256 -cnotmatch '^[0-9a-f]{64}$') {
                throw "Packaged runtime dependency $name has an invalid $architecture wheel"
            }
        }
        $expected.Add($normalizedName, [pscustomobject]@{ Name = $name; Version = $version })
    }

    foreach ($architecture in $architectures) {
        $prefix = "mcav-vj/bin/$architecture/python/lib/python3.12/site-packages/"
        $distInfoDirectories = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($entry in $Entries) {
            if (-not $entry.FullName.StartsWith($prefix, [StringComparison]::Ordinal)) { continue }
            $relativePath = $entry.FullName.Substring($prefix.Length)
            $components = $relativePath.Split([char]'/', [StringSplitOptions]::None)
            for ($index = 0; $index -lt $components.Count; $index++) {
                $component = $components[$index]
                if (-not $component.EndsWith('.dist-info', [StringComparison]::OrdinalIgnoreCase)) {
                    continue
                }
                if ($index -ne 0 -or
                    -not $component.EndsWith('.dist-info', [StringComparison]::Ordinal)) {
                    throw "$architecture has noncanonical installed dist-info: $relativePath"
                }
                [void]$distInfoDirectories.Add($component)
            }
        }

        $installed = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
        foreach ($directory in $distInfoDirectories) {
            $metadataName = "${prefix}${directory}/METADATA"
            if (-not $EntriesByName.ContainsKey($metadataName)) {
                throw "$architecture installed dist-info is missing METADATA: $directory"
            }
            $metadataText = Get-ZipEntryText -Entry $EntriesByName[$metadataName]
            $name = Get-MetadataField -Text $metadataText -Field 'Name'
            $version = Get-MetadataField -Text $metadataText -Field 'Version'
            if ([string]::IsNullOrEmpty($name) -or [string]::IsNullOrEmpty($version)) {
                throw "$architecture installed metadata is incomplete: $metadataName"
            }
            $normalizedName = Get-NormalizedPackageName -Name $name
            if ($installed.ContainsKey($normalizedName)) {
                throw "$architecture duplicate installed dependency: $name"
            }
            $installed.Add($normalizedName, [pscustomobject]@{ Name = $name; Version = $version })
        }

        $missing = @($expected.Keys | Where-Object { -not $installed.ContainsKey($_) } | Sort-Object)
        $extra = @($installed.Keys | Where-Object { -not $expected.ContainsKey($_) } | Sort-Object)
        if ($missing.Count -gt 0) {
            throw "$architecture missing installed dependencies: $($missing -join ', ')"
        }
        if ($extra.Count -gt 0) {
            throw "$architecture extra installed dependencies: $($extra -join ', ')"
        }
        foreach ($normalizedName in $expected.Keys) {
            if ($installed[$normalizedName].Version -cne $expected[$normalizedName].Version) {
                throw "$architecture installed $($expected[$normalizedName].Name) version $($installed[$normalizedName].Version) does not match $($expected[$normalizedName].Version)"
            }
        }
    }
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
    $caseFoldedNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $entriesByName = [Collections.Generic.Dictionary[string, System.IO.Compression.ZipArchiveEntry]]::new([StringComparer]::Ordinal)
    foreach ($entry in $entries) {
        if (-not $names.Add($entry.FullName)) {
            throw "Duplicate ZIP entries: $($entry.FullName)"
        }
        if (-not $caseFoldedNames.Add($entry.FullName)) {
            throw "Case-fold path collision in ZIP: $($entry.FullName)"
        }
        $entriesByName.Add($entry.FullName, $entry)
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
        $entry = $entriesByName[$executableName]
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
        $entry = $entriesByName[$executableName]
        $actualMachine = Get-ElfMachine -Entry $entry
        $expectedMachine = $expectedElfMachines[$executableName]
        if ($actualMachine -ne $expectedMachine) {
            $architecture = $executableName.Split('/')[2]
            throw "$architecture Python executable has ELF machine $actualMachine; expected $expectedMachine"
        }
    }

    $manifestEntry = $entriesByName['mcav-vj/MANIFEST.sha256']
    $manifestText = Get-ZipEntryText -Entry $manifestEntry
    $manifest = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    $caseFoldedManifestPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($line in ($manifestText -split "`r?`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { throw "Malformed manifest line: $line" }
        $relativePath = $Matches[2]
        Assert-CanonicalPath -Path $relativePath -Description 'manifest path'
        if ($manifest.ContainsKey($relativePath)) {
            throw "Duplicate manifest entry: $relativePath"
        }
        if (-not $caseFoldedManifestPaths.Add($relativePath)) {
            throw "Case-fold path collision in manifest: $relativePath"
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

    Assert-PackagedRuntimeClosure -Entries $entries -EntriesByName $entriesByName

    Write-Host "Verified release: $resolvedArchive"
    Write-Host "Entries: $($entries.Count)"
} finally {
    $zip.Dispose()
}
