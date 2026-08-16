[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$workflowDirectory = Join-Path $repositoryRoot '.github\workflows'
$workflowFiles = @(Get-ChildItem -LiteralPath $workflowDirectory -File | Where-Object { $_.Extension -in @('.yml', '.yaml') })
$violations = [System.Collections.Generic.List[string]]::new()

foreach ($file in $workflowFiles) {
    $content = Get-Content -Raw -LiteralPath $file.FullName
    $lines = Get-Content -LiteralPath $file.FullName

    $topLevelPermissionsLine = -1
    for ($permissionIndex = 0; $permissionIndex -lt $lines.Count; $permissionIndex++) {
        if ($lines[$permissionIndex] -match '^permissions:\s*(?<inline>.*)$') {
            $topLevelPermissionsLine = $permissionIndex
            $inlinePermissions = $Matches['inline'].Trim()
            break
        }
    }

    if ($topLevelPermissionsLine -lt 0) {
        $violations.Add("$($file.Name): workflow-level permissions must be declared")
    }
    elseif ($inlinePermissions -notin @('{}', 'read-all', '')) {
        $violations.Add("$($file.Name): unsupported workflow-level permissions value: $inlinePermissions")
    }
    elseif ($inlinePermissions -eq '') {
        $permissionBody = [System.Collections.Generic.List[string]]::new()
        for ($permissionIndex = $topLevelPermissionsLine + 1; $permissionIndex -lt $lines.Count; $permissionIndex++) {
            $permissionLine = $lines[$permissionIndex]
            if ($permissionLine -match '^\S' -and $permissionLine.Trim() -ne '') {
                break
            }
            if ($permissionLine.Trim() -ne '') {
                $permissionBody.Add($permissionLine)
            }
        }
        if ($permissionBody.Count -eq 0 -or $permissionBody -match ':\s*write\s*$') {
            $violations.Add("$($file.Name): workflow-level permissions may grant read scopes only")
        }
    }

    for ($lineIndex = 0; $lineIndex -lt $lines.Count; $lineIndex++) {
        $line = $lines[$lineIndex]
        if ($line -match '^\s*-?\s*uses:\s*([^\s#]+)') {
            $reference = $Matches[1]
            if ($reference -notmatch '^\./' -and $reference -notmatch '@[0-9a-f]{40}$') {
                $violations.Add("$($file.Name):$($lineIndex + 1) action is not pinned: $reference")
            }
        }
    }

    $checkoutBlocks = [regex]::Matches(
        $content,
        '(?ms)^\s*-\s+(?:name:.*?\r?\n\s+)?uses:\s+actions/checkout@[0-9a-f]{40}.*?(?=^\s*-\s+(?:name:|uses:|run:)|^\s{0,6}[A-Za-z][^\r\n]*:|\z)'
    )
    foreach ($checkoutBlock in $checkoutBlocks) {
        if ($checkoutBlock.Value -notmatch '(?m)^\s+persist-credentials:\s*false\s*$') {
            $violations.Add("$($file.Name): checkout must set persist-credentials: false")
        }
    }

    if ($file.Name -eq 'security.yml' -and $content -match '(?ms)^  pull_request:\s*\r?\n\s+paths:') {
        $violations.Add('security.yml: pull_request must run for every change targeting main')
    }
}

if ($violations.Count -gt 0) {
    throw ($violations -join [Environment]::NewLine)
}

Write-Output "Workflow policy verified: $($workflowFiles.Count) files use pinned actions and bounded permissions."
