[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('linux-x86_64', 'linux-aarch64', 'windows-x86_64')]
    [string]$Platform,

    [Parameter(Mandatory = $true)]
    [string]$Output
)

$ErrorActionPreference = 'Stop'
$scriptPath = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $scriptPath '..\..')).Path
$outputPath = if ([System.IO.Path]::IsPathRooted($Output)) {
    [System.IO.Path]::GetFullPath($Output)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $Output))
}
$wslScript = (wsl wslpath -a ((Join-Path $scriptPath 'build-managed-runtime.sh') -replace '\\', '/')).Trim()
$wslOutput = (wsl wslpath -a ($outputPath -replace '\\', '/')).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslScript) -or [string]::IsNullOrWhiteSpace($wslOutput)) {
    throw 'Unable to translate managed-runtime build paths for WSL.'
}

wsl bash $wslScript --platform $Platform --output $wslOutput
if ($LASTEXITCODE -ne 0) {
    throw "Managed-runtime build failed with exit code $LASTEXITCODE."
}
