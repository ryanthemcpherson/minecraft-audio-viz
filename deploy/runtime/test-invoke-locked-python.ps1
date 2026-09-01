[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptPath = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$cachePath = [System.IO.Path]::GetFullPath((Join-Path $scriptPath '.cache'))
[System.IO.Directory]::CreateDirectory($cachePath) | Out-Null
$capturePath = Join-Path $cachePath ".invoke-locked-python-test-$([System.Guid]::NewGuid().ToString('N')).txt"
. (Join-Path $scriptPath 'locked-python-io.ps1')

try {
    $oversizedSource = [System.IO.MemoryStream]::new([byte[]](1, 2, 3, 4, 5))
    $boundedDestination = [System.IO.MemoryStream]::new()
    try {
        $boundedFailure = $null
        try {
            Copy-BoundedStream `
                -Source $oversizedSource `
                -Destination $boundedDestination `
                -ExpectedSize 4
        }
        catch {
            $boundedFailure = $_.Exception.Message
        }
        if ($boundedFailure -notmatch 'exceeded its locked size') {
            throw 'Bounded stream copy did not reject an oversized response.'
        }
    }
    finally {
        $boundedDestination.Dispose()
        $oversizedSource.Dispose()
    }

    & (Join-Path $scriptPath 'invoke-locked-python.ps1') `
        '-c' `
        'import pathlib,sys; pathlib.Path(sys.argv[1]).write_text(sys.executable, encoding="utf-8")' `
        $capturePath
    if ($LASTEXITCODE -ne 0) {
        throw "Locked Python fixture failed with exit code $LASTEXITCODE."
    }
    $executable = (Get-Content -LiteralPath $capturePath -Raw -Encoding UTF8).Trim()
    if (-not $executable.EndsWith('python\python.exe', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Locked Python fixture used an unexpected executable: $executable"
    }
    $runtimeRoot = [System.IO.Path]::GetDirectoryName(
        [System.IO.Path]::GetDirectoryName($executable)
    )
    if (Test-Path -LiteralPath $runtimeRoot) {
        throw "Ephemeral locked Python extraction was retained: $runtimeRoot"
    }
    Write-Output 'Locked Python ephemeral extraction test passed.'
}
finally {
    if (Test-Path -LiteralPath $capturePath) {
        Remove-Item -LiteralPath $capturePath -Force
    }
}
