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

function Get-ZipEntryBytes {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchiveEntry]$Entry
    )

    $stream = $Entry.Open()
    $memory = [System.IO.MemoryStream]::new()
    try {
        $stream.CopyTo($memory)
        return ,$memory.ToArray()
    } finally {
        $memory.Dispose()
        $stream.Dispose()
    }
}

function Assert-ZipEntryType {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchiveEntry]$Entry,
        [Parameter(Mandatory = $true)]
        [int]$MadeByPlatform
    )

    $unixMode = ($Entry.ExternalAttributes -shr 16) -band 0xFFFF
    $unixType = $unixMode -band 0xF000
    $dosAttributes = $Entry.ExternalAttributes -band 0xFFFF
    $pathIsDirectory = $Entry.FullName.EndsWith('/', [StringComparison]::Ordinal)

    if (($dosAttributes -band 0x440) -ne 0) {
        throw "Unsafe Windows ZIP entry attributes: $($Entry.FullName)"
    }
    if ($unixType -notin @(0, 0x4000, 0x8000)) {
        throw "Non-regular ZIP entry type: $($Entry.FullName)"
    }

    if ($unixType -eq 0) {
        if ($MadeByPlatform -ne 0) {
            throw "Ambiguous ZIP entry type metadata: $($Entry.FullName)"
        }
        $metadataIsDirectory = ($dosAttributes -band 0x10) -ne 0
    } else {
        $metadataIsDirectory = $unixType -eq 0x4000
        if (($dosAttributes -band 0x10) -ne 0 -and -not $metadataIsDirectory) {
            throw "ZIP entry type does not match path: $($Entry.FullName)"
        }
    }

    if ($metadataIsDirectory -ne $pathIsDirectory) {
        throw "ZIP entry type does not match path: $($Entry.FullName)"
    }
    return $metadataIsDirectory
}

function Get-ZipCentralDirectoryPlatforms {
    param(
        [Parameter(Mandatory = $true)][string]$ArchivePath,
        [Parameter(Mandatory = $true)][object[]]$Entries
    )

    $stream = [System.IO.File]::Open(
        $ArchivePath,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read
    )
    $reader = [System.IO.BinaryReader]::new($stream, [System.Text.Encoding]::UTF8, $true)
    try {
        if ($stream.Length -lt 22) { throw 'ZIP end-of-central-directory record is missing' }
        $tailLength = [int][Math]::Min(65557L, $stream.Length)
        $stream.Position = $stream.Length - $tailLength
        $tail = $reader.ReadBytes($tailLength)
        if ($tail.Length -ne $tailLength) { throw 'Cannot read ZIP central-directory trailer' }

        $eocdIndex = -1
        for ($index = $tail.Length - 22; $index -ge 0; $index--) {
            if ([BitConverter]::ToUInt32($tail, $index) -ne 0x06054B50) { continue }
            $commentLength = [BitConverter]::ToUInt16($tail, $index + 20)
            if ($index + 22 + $commentLength -eq $tail.Length) {
                $eocdIndex = $index
                break
            }
        }
        if ($eocdIndex -lt 0) { throw 'ZIP end-of-central-directory record is missing' }

        $eocdOffset = $stream.Length - $tailLength + $eocdIndex
        $diskNumber = [BitConverter]::ToUInt16($tail, $eocdIndex + 4)
        $centralDirectoryDisk = [BitConverter]::ToUInt16($tail, $eocdIndex + 6)
        $entriesOnDisk16 = [BitConverter]::ToUInt16($tail, $eocdIndex + 8)
        $entryCount16 = [BitConverter]::ToUInt16($tail, $eocdIndex + 10)
        $centralSize32 = [BitConverter]::ToUInt32($tail, $eocdIndex + 12)
        $centralOffset32 = [BitConverter]::ToUInt32($tail, $eocdIndex + 16)
        $usesZip64 = $entriesOnDisk16 -eq 0xFFFF -or $entryCount16 -eq 0xFFFF -or
            $centralSize32 -eq [uint32]::MaxValue -or
            $centralOffset32 -eq [uint32]::MaxValue

        if ($usesZip64) {
            $locatorOffset = $eocdOffset - 20
            if ($locatorOffset -lt 0) { throw 'ZIP64 locator is missing' }
            $stream.Position = $locatorOffset
            $locator = $reader.ReadBytes(20)
            if ($locator.Length -ne 20 -or [BitConverter]::ToUInt32($locator, 0) -ne 0x07064B50) {
                throw 'ZIP64 locator is missing'
            }
            if ([BitConverter]::ToUInt32($locator, 4) -ne 0 -or
                [BitConverter]::ToUInt32($locator, 16) -ne 1) {
                throw 'Multi-disk ZIP archives are not supported'
            }
            $zip64OffsetValue = [BitConverter]::ToUInt64($locator, 8)
            if ($zip64OffsetValue -gt [uint64][long]::MaxValue) {
                throw 'ZIP64 end-of-central-directory offset is out of range'
            }
            $zip64Offset = [long]$zip64OffsetValue
            if ($zip64Offset -lt 0 -or $zip64Offset + 56 -gt $locatorOffset) {
                throw 'ZIP64 end-of-central-directory record is invalid'
            }
            $stream.Position = $zip64Offset
            $zip64Header = $reader.ReadBytes(56)
            if ($zip64Header.Length -ne 56 -or
                [BitConverter]::ToUInt32($zip64Header, 0) -ne 0x06064B50) {
                throw 'ZIP64 end-of-central-directory record is invalid'
            }
            $zip64RecordSize = [BitConverter]::ToUInt64($zip64Header, 4)
            if ($zip64RecordSize -lt 44 -or
                $zip64RecordSize -gt [uint64][long]::MaxValue -or
                $zip64Offset + 12 + [long]$zip64RecordSize -ne $locatorOffset) {
                throw 'ZIP64 end-of-central-directory record is invalid'
            }
            if ([BitConverter]::ToUInt32($zip64Header, 16) -ne 0 -or
                [BitConverter]::ToUInt32($zip64Header, 20) -ne 0) {
                throw 'Multi-disk ZIP archives are not supported'
            }
            $entriesOnDisk64 = [BitConverter]::ToUInt64($zip64Header, 24)
            $entryCount64 = [BitConverter]::ToUInt64($zip64Header, 32)
            if ($entriesOnDisk64 -ne $entryCount64) {
                throw 'Multi-disk ZIP archives are not supported'
            }
            $centralSizeValue = [BitConverter]::ToUInt64($zip64Header, 40)
            $centralOffsetValue = [BitConverter]::ToUInt64($zip64Header, 48)
            if ($entryCount64 -gt [uint64][int]::MaxValue -or
                $centralSizeValue -gt [uint64][long]::MaxValue -or
                $centralOffsetValue -gt [uint64][long]::MaxValue) {
                throw 'ZIP64 central directory is too large'
            }
            if (($entriesOnDisk16 -ne 0xFFFF -and $entriesOnDisk16 -ne $entriesOnDisk64) -or
                ($entryCount16 -ne 0xFFFF -and $entryCount16 -ne $entryCount64) -or
                ($centralSize32 -ne [uint32]::MaxValue -and
                    $centralSize32 -ne $centralSizeValue) -or
                ($centralOffset32 -ne [uint32]::MaxValue -and
                    $centralOffset32 -ne $centralOffsetValue)) {
                throw 'ZIP64 central-directory metadata is inconsistent'
            }
            $entryCount = [int]$entryCount64
            $centralSize = [long]$centralSizeValue
            $centralOffset = [long]$centralOffsetValue
            $expectedCentralEnd = $zip64Offset
        } else {
            if ($diskNumber -ne 0 -or $centralDirectoryDisk -ne 0 -or
                $entriesOnDisk16 -ne $entryCount16) {
                throw 'Multi-disk ZIP archives are not supported'
            }
            $entryCount = [int]$entryCount16
            $centralSize = [long]$centralSize32
            $centralOffset = [long]$centralOffset32
            $expectedCentralEnd = $eocdOffset
        }

        if ($entryCount -ne $Entries.Count) {
            throw 'ZIP central-directory entry count does not match ZipArchive'
        }
        if ($centralOffset -lt 0 -or $centralSize -lt 0 -or
            $centralOffset -gt $expectedCentralEnd -or
            $centralSize -ne ($expectedCentralEnd - $centralOffset)) {
            throw 'ZIP central-directory bounds are invalid'
        }

        $platforms = [Collections.Generic.Dictionary[string, int]]::new([StringComparer]::Ordinal)
        $utf8 = [System.Text.UTF8Encoding]::new($false, $true)
        $legacyEncoding = $null
        $stream.Position = $centralOffset
        for ($index = 0; $index -lt $entryCount; $index++) {
            if ($stream.Position + 46 -gt $expectedCentralEnd) {
                throw 'ZIP central-directory entry is truncated'
            }
            $header = $reader.ReadBytes(46)
            if ($header.Length -ne 46 -or [BitConverter]::ToUInt32($header, 0) -ne 0x02014B50) {
                throw 'ZIP central-directory entry signature is invalid'
            }
            $madeByPlatform = [int]$header[5]
            $flags = [BitConverter]::ToUInt16($header, 8)
            $nameLength = [BitConverter]::ToUInt16($header, 28)
            $extraLength = [BitConverter]::ToUInt16($header, 30)
            $commentLength = [BitConverter]::ToUInt16($header, 32)
            $centralAttributes = [BitConverter]::ToUInt32($header, 38)
            $variableLength = [long]$nameLength + $extraLength + $commentLength
            if ($stream.Position + $variableLength -gt $expectedCentralEnd) {
                throw 'ZIP central-directory entry is truncated'
            }
            $nameBytes = $reader.ReadBytes($nameLength)
            if ($nameBytes.Length -ne $nameLength) {
                throw 'ZIP central-directory entry name is truncated'
            }
            if (($flags -band 0x800) -ne 0) {
                $name = $utf8.GetString($nameBytes)
            } else {
                if ($null -eq $legacyEncoding) {
                    try {
                        $legacyEncoding = [System.Text.Encoding]::GetEncoding(
                            437,
                            [System.Text.EncoderFallback]::ExceptionFallback,
                            [System.Text.DecoderFallback]::ExceptionFallback
                        )
                    } catch {
                        throw "Cannot decode legacy ZIP entry names: $($_.Exception.Message)"
                    }
                }
                $name = $legacyEncoding.GetString($nameBytes)
            }
            $stream.Position += [long]$extraLength + $commentLength

            $entry = $Entries[$index]
            if ($entry.FullName -cne $name) {
                throw "ZIP central-directory entry order/name mismatch: $name"
            }
            $entryAttributes = [uint32]([int64]$entry.ExternalAttributes -band 0xFFFFFFFFL)
            if ($entryAttributes -ne $centralAttributes) {
                throw "ZIP central-directory attributes mismatch: $name"
            }
            if ($platforms.ContainsKey($name)) {
                throw "Duplicate ZIP entries in central directory: $name"
            }
            $platforms.Add($name, $madeByPlatform)
        }
        if ($stream.Position -ne $expectedCentralEnd) {
            throw 'ZIP central-directory size does not match its entries'
        }
        return ,$platforms
    } finally {
        $reader.Dispose()
        $stream.Dispose()
    }
}

function Get-ZipEntryHeader {
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
        return [pscustomobject]@{ Bytes = $header; Length = $offset }
    } finally {
        $stream.Dispose()
    }
}

function Get-NativeElfMachine {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchiveEntry]$Entry
    )

    $headerResult = Get-ZipEntryHeader -Entry $Entry
    $header = $headerResult.Bytes
    if ($headerResult.Length -lt 20 -or $header[0] -ne 0x7f -or $header[1] -ne 0x45 -or
        $header[2] -ne 0x4c -or $header[3] -ne 0x46) {
        throw "Native library is not ELF: $($Entry.FullName)"
    }
    if ($header[4] -ne 2) { throw "Native library is not 64-bit ELF: $($Entry.FullName)" }
    if ($header[5] -eq 1) { return [int]$header[18] -bor ([int]$header[19] -shl 8) }
    if ($header[5] -eq 2) { return ([int]$header[18] -shl 8) -bor [int]$header[19] }
    throw "Native ELF has unsupported byte order: $($Entry.FullName)"
}

function Test-PythonAbiCompatible {
    param([string]$PythonTag, [string]$AbiTag)

    if ($PythonTag -ceq 'cp312' -and $AbiTag -cin @('cp312', 'abi3', 'none')) {
        return $true
    }
    $stableAbi = [regex]::Match($PythonTag, '^cp3([0-9]+)$')
    if ($stableAbi.Success -and $AbiTag -ceq 'abi3') {
        return [int]$stableAbi.Groups[1].Value -le 12
    }
    return $PythonTag -cin @('py3', 'py312') -and $AbiTag -ceq 'none'
}

function Test-WheelTagCompatible {
    param(
        [Parameter(Mandatory = $true)][string]$Tag,
        [Parameter(Mandatory = $true)]
        [Collections.Generic.HashSet[string]]$Platforms
    )

    $parts = $Tag.Split([char]'-', [StringSplitOptions]::None)
    if ($parts.Count -ne 3) { return $false }
    $pythonTags = $parts[0].Split([char]'.', [StringSplitOptions]::None)
    $abiTags = $parts[1].Split([char]'.', [StringSplitOptions]::None)
    $platformTags = $parts[2].Split([char]'.', [StringSplitOptions]::None)
    $pythonAbiCompatible = $false
    foreach ($pythonTag in $pythonTags) {
        foreach ($abiTag in $abiTags) {
            if (Test-PythonAbiCompatible -PythonTag $pythonTag -AbiTag $abiTag) {
                $pythonAbiCompatible = $true
            }
        }
    }
    if (-not $pythonAbiCompatible) { return $false }
    $platformCompatible = $false
    foreach ($platformTag in $platformTags) {
        if ($platformTag -ceq 'any' -or $Platforms.Contains($platformTag)) {
            $platformCompatible = $true
        }
    }
    if (-not $platformCompatible) { return $false }
    if ($platformTags -ccontains 'any' -and $abiTags -cnotcontains 'none') { return $false }
    return $true
}

function Get-RecordRows {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchiveEntry]$Entry
    )

    Add-Type -AssemblyName Microsoft.VisualBasic
    $stream = $Entry.Open()
    $encoding = [System.Text.UTF8Encoding]::new($false, $true)
    $parser = [Microsoft.VisualBasic.FileIO.TextFieldParser]::new($stream, $encoding, $true)
    try {
        $parser.TextFieldType = [Microsoft.VisualBasic.FileIO.FieldType]::Delimited
        $parser.SetDelimiters(',')
        $parser.HasFieldsEnclosedInQuotes = $true
        $rows = [Collections.Generic.List[object]]::new()
        while (-not $parser.EndOfData) {
            $fields = $parser.ReadFields()
            if ($fields.Count -ne 3) {
                throw "Malformed RECORD row in $($Entry.FullName)"
            }
            $relativePath = [string]$fields[0]
            $digest = [string]$fields[1]
            $size = [string]$fields[2]
            Assert-CanonicalPath -Path $relativePath -Description 'RECORD path'
            if ([string]::IsNullOrEmpty($digest) -ne [string]::IsNullOrEmpty($size)) {
                throw "Malformed RECORD row in $($Entry.FullName): $relativePath"
            }
            if (-not [string]::IsNullOrEmpty($digest) -and
                $digest -cnotmatch '^sha256=[A-Za-z0-9_-]{43}$') {
                throw "Malformed RECORD hash in $($Entry.FullName): $relativePath"
            }
            if (-not [string]::IsNullOrEmpty($size) -and $size -cnotmatch '^[0-9]+$') {
                throw "Malformed RECORD size in $($Entry.FullName): $relativePath"
            }
            $rows.Add([pscustomobject]@{
                Path = $relativePath
                Digest = $digest
                Size = $size
            })
        }
        if ($rows.Count -eq 0) { throw "Installed RECORD is empty: $($Entry.FullName)" }
        return $rows.ToArray()
    } catch {
        throw $_
    } finally {
        $parser.Dispose()
        $stream.Dispose()
    }
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
    if ($null -eq $lock -or -not ($lock -is [pscustomobject])) {
        throw 'Invalid packaged runtime lock root object'
    }
    $schemaVersionIsInteger = $lock.schema_version -is [int] -or
        $lock.schema_version -is [long]
    if (-not $schemaVersionIsInteger -or [long]$lock.schema_version -ne 1) {
        throw 'Invalid packaged runtime lock schema_version'
    }
    $lockPropertyNames = @($lock.PSObject.Properties.Name)
    $requiredLockProperties = @('schema_version', 'python', 'release', 'runtimes', 'dependencies')
    if ($lockPropertyNames.Count -ne $requiredLockProperties.Count -or
        @($requiredLockProperties | Where-Object { $lockPropertyNames -cnotcontains $_ }).Count -gt 0) {
        throw 'Invalid packaged runtime lock properties'
    }
    if (-not ($lock.python -is [string]) -or $lock.python -cnotmatch '^3\.12\.[0-9]+$') {
        throw 'Invalid packaged runtime lock Python version'
    }
    if (-not ($lock.release -is [string]) -or [string]::IsNullOrEmpty($lock.release)) {
        throw 'Invalid packaged runtime lock release'
    }
    if (-not ($lock.runtimes -is [pscustomobject])) {
        throw 'Invalid packaged runtime lock runtimes object'
    }
    $architectures = @('linux-amd64', 'linux-arm64')
    $runtimeNames = @($lock.runtimes.PSObject.Properties.Name)
    if ($runtimeNames.Count -ne 2 -or
        @($architectures | Where-Object { $runtimeNames -cnotcontains $_ }).Count -gt 0) {
        throw 'Packaged runtime lock must define exactly linux-amd64 and linux-arm64'
    }
    $platformSuffixes = @{
        'linux-amd64' = 'x86_64'
        'linux-arm64' = 'aarch64'
    }
    $runtimePlatforms = @{}
    foreach ($architecture in $architectures) {
        $runtime = $lock.runtimes.PSObject.Properties[$architecture].Value
        if (-not ($runtime -is [pscustomobject])) {
            throw "Invalid packaged runtime lock $architecture runtime object"
        }
        $runtimePropertyNames = @($runtime.PSObject.Properties.Name)
        $requiredRuntimeProperties = @('url', 'sha256', 'pip_platforms')
        if ($runtimePropertyNames.Count -ne $requiredRuntimeProperties.Count -or
            @($requiredRuntimeProperties | Where-Object {
                $runtimePropertyNames -cnotcontains $_
            }).Count -gt 0) {
            throw "Invalid packaged runtime lock $architecture runtime properties"
        }
        if (-not ($runtime.url -is [string]) -or
            -not $runtime.url.StartsWith('https://', [StringComparison]::Ordinal) -or
            -not ($runtime.sha256 -is [string]) -or
            $runtime.sha256 -cnotmatch '^[0-9a-f]{64}$') {
            throw "Invalid packaged runtime lock $architecture runtime identity"
        }
        if (-not ($runtime.pip_platforms -is [System.Array])) {
            throw "Invalid packaged runtime lock $architecture pip_platforms list"
        }
        $platforms = @($runtime.pip_platforms)
        if ($platforms.Count -eq 0) {
            throw "Invalid packaged runtime lock $architecture pip_platforms list"
        }
        $platformSet = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($platform in $platforms) {
            if (-not ($platform -is [string]) -or [string]::IsNullOrEmpty($platform) -or
                $platform -cnotmatch "^(?:manylinux[^/]*|musllinux[^/]*)_$($platformSuffixes[$architecture])$" -or
                -not $platformSet.Add($platform)) {
                throw "Invalid packaged runtime lock $architecture pip_platforms list"
            }
        }
        $runtimePlatforms[$architecture] = $platformSet
    }

    $expected = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    if (-not ($lock.dependencies -is [System.Array])) {
        throw 'Packaged runtime lock dependencies must be an array'
    }
    $dependencies = $lock.dependencies
    if ($dependencies.Count -eq 0) { throw 'Packaged runtime lock has no dependencies' }
    foreach ($dependency in $dependencies) {
        if (-not ($dependency -is [pscustomobject])) {
            throw 'Packaged runtime lock dependency must be an object'
        }
        if (-not ($dependency.name -is [string]) -or
            -not ($dependency.version -is [string])) {
            throw 'Packaged runtime lock dependency name and version must be strings'
        }
        $name = $dependency.name
        $version = $dependency.version
        if ([string]::IsNullOrEmpty($name) -or [string]::IsNullOrEmpty($version)) {
            throw 'Packaged runtime lock dependency name and version must be non-empty'
        }
        $normalizedName = Get-NormalizedPackageName -Name $name
        if ($expected.ContainsKey($normalizedName)) {
            throw "Duplicate packaged runtime dependency: $name"
        }
        if (-not ($dependency.wheels -is [pscustomobject])) {
            throw "Packaged runtime dependency $name wheels must be an object"
        }
        $wheelArchitectures = @($dependency.wheels.PSObject.Properties.Name)
        if ($wheelArchitectures.Count -ne 2 -or
            @($architectures | Where-Object { $wheelArchitectures -cnotcontains $_ }).Count -gt 0) {
            throw "Packaged runtime dependency $name must lock exactly both architectures"
        }
        foreach ($architecture in $architectures) {
            $wheel = $dependency.wheels.PSObject.Properties[$architecture].Value
            if (-not ($wheel -is [pscustomobject])) {
                throw "Packaged runtime dependency $name has an invalid $architecture wheel"
            }
            $wheelPropertyNames = @($wheel.PSObject.Properties.Name)
            if ($wheelPropertyNames.Count -ne 2 -or
                $wheelPropertyNames -cnotcontains 'filename' -or
                $wheelPropertyNames -cnotcontains 'sha256' -or
                -not ($wheel.filename -is [string]) -or
                $wheel.filename -cnotmatch '^[^/\\]+\.whl$' -or
                -not ($wheel.sha256 -is [string]) -or
                $wheel.sha256 -cnotmatch '^[0-9a-f]{64}$') {
                throw "Packaged runtime dependency $name has an invalid $architecture wheel"
            }
            $filenameMatch = [regex]::Match(
                $wheel.filename,
                '^.+-([^-]+)-([^-]+)-([^-]+)\.whl$'
            )
            if (-not $filenameMatch.Success) {
                throw "Invalid packaged runtime lock $architecture wheel filename tags"
            }
            $filenameTag = "$($filenameMatch.Groups[1].Value)-$($filenameMatch.Groups[2].Value)-$($filenameMatch.Groups[3].Value)"
            if (-not (Test-WheelTagCompatible -Tag $filenameTag -Platforms $runtimePlatforms[$architecture])) {
                throw "Invalid packaged runtime lock $architecture wheel filename tags"
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

        $ownership = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
        foreach ($directory in $distInfoDirectories) {
            $recordName = "${prefix}${directory}/RECORD"
            if (-not $EntriesByName.ContainsKey($recordName)) {
                throw "$architecture installed dist-info is missing RECORD: $directory"
            }
            $recordRows = @(Get-RecordRows -Entry $EntriesByName[$recordName])
            foreach ($recordRow in $recordRows) {
                $relativePath = [string]$recordRow.Path
                if ($ownership.ContainsKey($relativePath)) {
                    throw "Ambiguous RECORD ownership for ${relativePath}: $($ownership[$relativePath]) and $recordName"
                }
                $ownership.Add($relativePath, $recordName)
                $installedName = "${prefix}${relativePath}"
                if (-not $EntriesByName.ContainsKey($installedName)) {
                    throw "RECORD file is missing: $relativePath"
                }
                $installedBytes = Get-ZipEntryBytes -Entry $EntriesByName[$installedName]
                if (-not [string]::IsNullOrEmpty([string]$recordRow.Size) -and
                    $installedBytes.LongLength -ne [long]$recordRow.Size) {
                    throw "RECORD size mismatch for ${relativePath}: expected $($recordRow.Size), got $($installedBytes.LongLength)"
                }
                if (-not [string]::IsNullOrEmpty([string]$recordRow.Digest)) {
                    $sha = [System.Security.Cryptography.SHA256]::Create()
                    try { $hashBytes = $sha.ComputeHash($installedBytes) } finally { $sha.Dispose() }
                    $encodedHash = [Convert]::ToBase64String($hashBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
                    $expectedHash = ([string]$recordRow.Digest).Substring('sha256='.Length)
                    if ($encodedHash -cne $expectedHash) {
                        throw "RECORD SHA-256 mismatch for $relativePath"
                    }
                }
            }
        }

        $actualSitePackages = [Collections.Generic.Dictionary[string, System.IO.Compression.ZipArchiveEntry]]::new([StringComparer]::Ordinal)
        foreach ($entry in $Entries) {
            if (-not $entry.FullName.StartsWith($prefix, [StringComparison]::Ordinal)) { continue }
            $relativePath = $entry.FullName.Substring($prefix.Length)
            $actualSitePackages.Add($relativePath, $entry)
        }
        $unowned = @($actualSitePackages.Keys | Where-Object {
            -not $ownership.ContainsKey($_)
        } | Sort-Object)
        if ($unowned.Count -gt 0) {
            throw "Unowned site-packages file: $($unowned[0])"
        }

        $expectedMachine = if ($architecture -ceq 'linux-amd64') { 62 } else { 183 }
        foreach ($relativePath in $actualSitePackages.Keys) {
            $entry = $actualSitePackages[$relativePath]
            $headerResult = Get-ZipEntryHeader -Entry $entry
            $header = $headerResult.Bytes
            $extensionMarksNative = $relativePath -match '\.so(\.[^/]*)?$|\.(pyd|dll|dylib|node)$'
            $magic = if ($headerResult.Length -ge 4) {
                '{0:X2}{1:X2}{2:X2}{3:X2}' -f $header[0], $header[1], $header[2], $header[3]
            } else { '' }
            $magicMarksNative = $magic -in @(
                '7F454C46', '4D5A0000', 'FEEDFACE', 'CEFAEDFE', 'FEEDFACF', 'CFFAEDFE'
            ) -or ($headerResult.Length -ge 2 -and $header[0] -eq 0x4d -and $header[1] -eq 0x5a)
            if (-not $extensionMarksNative -and -not $magicMarksNative) { continue }
            $actualMachine = Get-NativeElfMachine -Entry $entry
            if ($actualMachine -ne $expectedMachine) {
                throw "$architecture native ELF machine $actualMachine; expected ${expectedMachine}: $relativePath"
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
    $entryPlatforms = Get-ZipCentralDirectoryPlatforms `
        -ArchivePath $resolvedArchive `
        -Entries $entries
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
        $isDirectory = Assert-ZipEntryType `
            -Entry $entry `
            -MadeByPlatform $entryPlatforms[$entry.FullName]
        if ($isDirectory -or [string]::IsNullOrEmpty($entry.Name)) {
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
