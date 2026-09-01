[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('linux-x86_64', 'linux-aarch64', 'windows-x86_64')]
    [string]$Platform,

    [Parameter(Mandatory = $true)]
    [string]$Output,

    [string]$Version
)

$ErrorActionPreference = 'Stop'
$scriptPath = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $scriptPath '..\..')).Path
$outputPath = if ([System.IO.Path]::IsPathRooted($Output)) {
    [System.IO.Path]::GetFullPath($Output)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $Output))
}
if ($Platform -eq 'windows-x86_64') {
    $pythonScript = if ([string]::IsNullOrWhiteSpace($Version)) {
        Join-Path $scriptPath 'build_managed_runtime.py'
    } else {
        Join-Path $scriptPath 'build_release_runtime.py'
    }
    $arguments = if ([string]::IsNullOrWhiteSpace($Version)) {
        @($pythonScript, '--platform', $Platform, '--output', $outputPath)
    } else {
        @($pythonScript, '--target', $Platform, '--version', $Version, '--output', $outputPath)
    }
    & (Join-Path $scriptPath 'invoke-locked-python.ps1') @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Managed-runtime build failed with exit code $LASTEXITCODE."
    }
    exit 0
}

$wslScript = (wsl wslpath -a ((Join-Path $scriptPath 'build-managed-runtime.sh') -replace '\\', '/')).Trim()
$wslOutput = (wsl wslpath -a ($outputPath -replace '\\', '/')).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslScript) -or [string]::IsNullOrWhiteSpace($wslOutput)) {
    throw 'Unable to translate managed-runtime build paths for WSL.'
}

$wslArguments = @('--platform', $Platform, '--output', $wslOutput)
if (-not [string]::IsNullOrWhiteSpace($Version)) {
    $wslArguments += @('--version', $Version)
}
wsl bash $wslScript @wslArguments
if ($LASTEXITCODE -ne 0) {
    throw "Managed-runtime build failed with exit code $LASTEXITCODE."
}
