[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$PythonArguments
)

$ErrorActionPreference = 'Stop'
$scriptPath = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$lockPath = Join-Path $scriptPath 'runtime-lock.json'
$cachePath = [System.IO.Path]::GetFullPath((Join-Path $scriptPath '.cache'))
$expectedTopFields = @('dependencies', 'python', 'release', 'runtimes', 'schema_version', 'source_date_epoch')
$expectedRuntimeFields = @('archive_size', 'entrypoint', 'pip_platforms', 'sha256', 'url')
. (Join-Path $scriptPath 'locked-python-io.ps1')

function Assert-ExactFields {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Value,
        [Parameter(Mandatory = $true)]
        [string[]]$Expected,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $wanted = @($Expected | Sort-Object)
    if (($actual -join "`n") -ne ($wanted -join "`n")) {
        throw "$Label fields do not match the runtime lock schema."
    }
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)

    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $hasher = [System.Security.Cryptography.SHA256]::Create()
        try {
            return [System.Convert]::ToHexString($hasher.ComputeHash($stream)).ToLowerInvariant()
        }
        finally {
            $hasher.Dispose()
        }
    }
    finally {
        $stream.Dispose()
    }
}

function Remove-OwnedCachePath {
    param([Parameter(Mandatory = $true)][string]$Path)

    $resolved = [System.IO.Path]::GetFullPath($Path)
    $expectedParent = [System.IO.Path]::GetFullPath($cachePath).TrimEnd([System.IO.Path]::DirectorySeparatorChar)
    $actualParent = [System.IO.Path]::GetDirectoryName($resolved).TrimEnd([System.IO.Path]::DirectorySeparatorChar)
    if (-not [string]::Equals($actualParent, $expectedParent, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove a path outside the managed runtime cache: $resolved"
    }
    if (Test-Path -LiteralPath $resolved) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

try {
    if ($null -eq $PythonArguments -or $PythonArguments.Count -eq 0) {
        throw 'At least one Python argument is required.'
    }
    $lock = Get-Content -LiteralPath $lockPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-ExactFields -Value $lock -Expected $expectedTopFields -Label 'Runtime lock'
    if ($lock.schema_version -ne 1 -or $lock.python -notmatch '^3\.12\.[0-9]+$') {
        throw 'Runtime lock schema or Python version is unsupported.'
    }
    $runtime = $lock.runtimes.'windows-x86_64'
    if ($null -eq $runtime) {
        throw 'Runtime lock does not define windows-x86_64.'
    }
    Assert-ExactFields -Value $runtime -Expected $expectedRuntimeFields -Label 'Windows runtime'
    $runtimeUri = [System.Uri]$runtime.url
    if (
        $runtimeUri.Scheme -ne 'https' -or
        $runtimeUri.Host -ne 'github.com' -or
        -not $runtimeUri.AbsolutePath.StartsWith('/astral-sh/python-build-standalone/releases/download/') -or
        -not [string]::IsNullOrEmpty($runtimeUri.Query) -or
        -not [string]::IsNullOrEmpty($runtimeUri.Fragment)
    ) {
        throw 'Portable Python URL is not an official pinned HTTPS release.'
    }
    $expectedDigest = [string]$runtime.sha256
    $expectedSize = [int64]$runtime.archive_size
    if ($expectedDigest -notmatch '^[0-9a-f]{64}$' -or $expectedSize -lt 20000000 -or $expectedSize -gt 200000000) {
        throw 'Portable Python digest or archive size is invalid.'
    }
    if ($runtime.entrypoint -ne 'python/python.exe') {
        throw 'Windows portable Python entrypoint is not canonical.'
    }

    [System.IO.Directory]::CreateDirectory($cachePath) | Out-Null
    $archivePath = Join-Path $cachePath "python-$($lock.python)-windows-x86_64.tar.gz"
    $archiveValid = (
        (Test-Path -LiteralPath $archivePath -PathType Leaf) -and
        (Get-Item -LiteralPath $archivePath).Length -eq $expectedSize -and
        (Get-Sha256 -Path $archivePath) -eq $expectedDigest
    )
    if (-not $archiveValid) {
        $downloadPath = Join-Path $cachePath ".python-$($lock.python)-windows-x86_64.download"
        if (Test-Path -LiteralPath $downloadPath) {
            Remove-Item -LiteralPath $downloadPath -Force
        }
        try {
            $client = [System.Net.Http.HttpClient]::new()
            try {
                $client.Timeout = [TimeSpan]::FromMinutes(5)
                $response = $client.GetAsync($runtimeUri, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
                try {
                    $response.EnsureSuccessStatusCode() | Out-Null
                    $source = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
                    try {
                        $destination = [System.IO.File]::Create($downloadPath)
                        try {
                            $contentLength = if ($response.Content.Headers.ContentLength.HasValue) {
                                $response.Content.Headers.ContentLength.Value
                            } else {
                                -1
                            }
                            Copy-BoundedStream `
                                -Source $source `
                                -Destination $destination `
                                -ExpectedSize $expectedSize `
                                -ContentLength $contentLength
                        }
                        finally {
                            $destination.Dispose()
                        }
                    }
                    finally {
                        $source.Dispose()
                    }
                }
                finally {
                    $response.Dispose()
                }
            }
            finally {
                $client.Dispose()
            }
            if (
                (Get-Item -LiteralPath $downloadPath).Length -ne $expectedSize -or
                (Get-Sha256 -Path $downloadPath) -ne $expectedDigest
            ) {
                throw 'Downloaded portable Python archive failed size or SHA-256 verification.'
            }
            Move-Item -LiteralPath $downloadPath -Destination $archivePath -Force
        }
        finally {
            if (Test-Path -LiteralPath $downloadPath) {
                Remove-Item -LiteralPath $downloadPath -Force
            }
        }
    }

    $runtimeRoot = Join-Path $cachePath ".locked-python-$([System.Guid]::NewGuid().ToString('N'))"
    [System.IO.Directory]::CreateDirectory($runtimeRoot) | Out-Null
    try {
        & tar.exe -xzf $archivePath -C $runtimeRoot
        if ($LASTEXITCODE -ne 0) {
            throw "Portable Python extraction failed with exit code $LASTEXITCODE."
        }
        $pythonPath = Join-Path $runtimeRoot 'python\python.exe'
        if (-not (Test-Path -LiteralPath $pythonPath -PathType Leaf)) {
            throw 'Portable Python archive did not contain python/python.exe.'
        }
        & $pythonPath @PythonArguments
        $pythonExitCode = $LASTEXITCODE
    }
    finally {
        Remove-OwnedCachePath -Path $runtimeRoot
    }
    exit $pythonExitCode
}
catch {
    [Console]::Error.WriteLine("Locked Python invocation failed: $($_.Exception.Message)")
    exit 1
}
