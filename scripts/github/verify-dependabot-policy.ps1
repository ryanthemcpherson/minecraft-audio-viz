[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$policyPath = Join-Path $repositoryRoot '.github\dependabot.yml'
$content = Get-Content -Raw -LiteralPath $policyPath

$entryMatches = [regex]::Matches(
    $content,
    '(?ms)^  - package-ecosystem: "(?<ecosystem>[^"]+)"(?<body>.*?)(?=^  - package-ecosystem:|\z)'
)

if ($entryMatches.Count -eq 0) {
    throw 'Dependabot policy contains no update entries.'
}

$violations = [System.Collections.Generic.List[string]]::new()

foreach ($entry in $entryMatches) {
    $ecosystem = $entry.Groups['ecosystem'].Value
    $body = $entry.Groups['body'].Value

    if ($body -notmatch '(?m)^    open-pull-requests-limit: 3$') {
        $violations.Add("$ecosystem entry must use open-pull-requests-limit: 3")
    }

    if ($ecosystem -in @('npm', 'pip') -and $body -notmatch '(?m)^    groups:$') {
        $violations.Add("$ecosystem entry must define dependency groups")
    }
}

$actionsEntries = @($entryMatches | Where-Object { $_.Groups['ecosystem'].Value -eq 'github-actions' })
if ($actionsEntries.Count -ne 1) {
    $violations.Add('Exactly one github-actions entry is required')
}
elseif ($actionsEntries[0].Groups['body'].Value -notmatch '(?ms)^    groups:\s+actions:\s+patterns:\s+- "\*"') {
    $violations.Add('GitHub Actions updates must use one actions group matching all dependencies')
}

if ($violations.Count -gt 0) {
    throw ($violations -join [Environment]::NewLine)
}

Write-Output "Dependabot policy verified: $($entryMatches.Count) entries, PR limit 3, required groups present."
