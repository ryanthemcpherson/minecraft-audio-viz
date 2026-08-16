[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repository = 'ryanthemcpherson/minecraft-audio-viz'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$rulesetDirectory = Join-Path $repositoryRoot '.github\rulesets'

function Invoke-GhJson {
    param(
        [Parameter(Mandatory)] [string] $Endpoint,
        [ValidateSet('GET', 'POST', 'PUT')] [string] $Method = 'GET',
        [string] $InputPath
    )

    $arguments = @('api', '--method', $Method, $Endpoint)
    if ($InputPath) {
        $arguments += @('--input', $InputPath)
    }
    $output = & gh @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "GitHub API request failed: $Method $Endpoint"
    }
    return $output | ConvertFrom-Json
}

function Get-RepositoryRulesets {
    return @(Invoke-GhJson -Endpoint "repos/$repository/rulesets")
}

function Set-NamedRuleset {
    param(
        [Parameter(Mandatory)] [string] $PolicyPath,
        [string[]] $PreviousNames = @()
    )

    $policy = Get-Content -Raw -LiteralPath $PolicyPath | ConvertFrom-Json
    $rulesets = Get-RepositoryRulesets
    $existing = @($rulesets | Where-Object { $_.name -eq $policy.name })
    if ($existing.Count -eq 0 -and $PreviousNames.Count -gt 0) {
        $existing = @($rulesets | Where-Object { $_.name -in $PreviousNames })
    }
    if ($existing.Count -gt 1) {
        throw "Multiple rulesets match $($policy.name)"
    }

    if ($existing.Count -eq 1) {
        $result = Invoke-GhJson -Method PUT -Endpoint "repos/$repository/rulesets/$($existing[0].id)" -InputPath $PolicyPath
        Write-Output "Updated ruleset $($result.name) ($($result.id))."
    }
    else {
        $result = Invoke-GhJson -Method POST -Endpoint "repos/$repository/rulesets" -InputPath $PolicyPath
        Write-Output "Created ruleset $($result.name) ($($result.id))."
    }
}

gh auth status | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'GitHub CLI authentication is required.'
}

$pluginReleaseEnvironment = @{
    wait_timer = 0
    prevent_self_review = $false
    reviewers = @(@{ type = 'User'; id = 37377365 })
    deployment_branch_policy = @{
        protected_branches = $true
        custom_branch_policies = $false
    }
} | ConvertTo-Json -Depth 6
$pluginReleaseEnvironment | & gh api --silent --method PUT `
    "repos/$repository/environments/plugin-release" --input -
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to configure the plugin-release environment.'
}

$allDeployKeys = @(Invoke-GhJson -Endpoint "repos/$repository/keys")
$releaseDeployKeys = @($allDeployKeys | Where-Object {
    $_.title -eq 'MCAV plugin release environment'
})
if ($releaseDeployKeys.Count -ne 1 -or $releaseDeployKeys[0].read_only -ne $false) {
    throw 'A single write-enabled MCAV plugin release environment deploy key is required.'
}
if (@($allDeployKeys | Where-Object { $_.read_only -eq $false }).Count -ne 1) {
    throw 'The plugin release credential must be the repository''s only write deploy key.'
}
$releaseEnvironmentSecrets = @(& gh secret list --repo $repository --env plugin-release --json name | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to list plugin-release environment secrets.'
}
if (@($releaseEnvironmentSecrets | Where-Object { $_.name -eq 'MCAV_PLUGIN_RELEASE_DEPLOY_KEY' }).Count -ne 1) {
    throw 'The plugin-release environment requires MCAV_PLUGIN_RELEASE_DEPLOY_KEY.'
}

Set-NamedRuleset -PolicyPath (Join-Path $rulesetDirectory 'main.json')
Set-NamedRuleset -PolicyPath (Join-Path $rulesetDirectory 'plugin-tag-creation.json')
Set-NamedRuleset -PolicyPath (Join-Path $rulesetDirectory 'plugin-tags.json')
Set-NamedRuleset `
    -PolicyPath (Join-Path $rulesetDirectory 'mod-tags.json') `
    -PreviousNames @('Paper and Fabric release tag immutability')

& gh api --silent --method PUT "repos/$repository/actions/permissions/workflow" `
    -f default_workflow_permissions=read `
    -F can_approve_pull_request_reviews=false
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to set default workflow permissions.'
}

& gh api --silent --method PUT "repos/$repository/vulnerability-alerts"
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to enable Dependabot vulnerability alerts.'
}

& gh api --silent --method PUT "repos/$repository/automated-security-fixes"
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to enable Dependabot security updates.'
}

Write-Output 'Repository policy applied. Run verify-repository-policy.ps1 for live verification.'
