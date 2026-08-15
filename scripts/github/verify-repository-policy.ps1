[CmdletBinding()]
param(
    [switch] $StaticOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repository = 'ryanthemcpherson/minecraft-audio-viz'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$rulesetDirectory = Join-Path $repositoryRoot '.github\rulesets'

function Assert-Equal {
    param(
        [Parameter(Mandatory)] $Actual,
        [Parameter(Mandatory)] $Expected,
        [Parameter(Mandatory)] [string] $Message
    )
    if ($Actual -ne $Expected) {
        throw "$Message. Expected '$Expected', got '$Actual'."
    }
}

function Assert-SequenceEqual {
    param(
        [Parameter(Mandatory)] [object[]] $Actual,
        [Parameter(Mandatory)] [object[]] $Expected,
        [Parameter(Mandatory)] [string] $Message
    )
    $actualValue = (@($Actual | Sort-Object) -join '|')
    $expectedValue = (@($Expected | Sort-Object) -join '|')
    Assert-Equal -Actual $actualValue -Expected $expectedValue -Message $Message
}

function Assert-ImmutableTagPolicy {
    param(
        [Parameter(Mandatory)] $Policy,
        [Parameter(Mandatory)] [string] $ExpectedName,
        [Parameter(Mandatory)] [string] $ExpectedPattern,
        [Parameter(Mandatory)] [bool] $AllowActionsBypass
    )

    Assert-Equal $Policy.name $ExpectedName 'Unexpected tag ruleset name'
    Assert-Equal $Policy.target 'tag' "$ExpectedName target"
    Assert-Equal $Policy.enforcement 'active' "$ExpectedName enforcement"
    Assert-SequenceEqual @($Policy.conditions.ref_name.include) @($ExpectedPattern) "$ExpectedName include patterns"
    Assert-Equal @($Policy.conditions.ref_name.exclude).Count 0 "$ExpectedName exclusions"
    Assert-SequenceEqual @($Policy.rules.type) @('creation', 'update', 'deletion', 'non_fast_forward') "$ExpectedName rules"

    $bypassActors = @($Policy.bypass_actors)
    if ($AllowActionsBypass) {
        Assert-Equal $bypassActors.Count 1 "$ExpectedName bypass count"
        Assert-Equal $bypassActors[0].actor_id 15368 "$ExpectedName bypass actor ID"
        Assert-Equal $bypassActors[0].actor_type 'Integration' "$ExpectedName bypass actor type"
        Assert-Equal $bypassActors[0].bypass_mode 'always' "$ExpectedName bypass mode"
    }
    else {
        Assert-Equal $bypassActors.Count 0 "$ExpectedName bypass count"
    }
}

function Assert-MainPolicy {
    param([Parameter(Mandatory)] $Policy)

    Assert-Equal $Policy.name 'Protect main' 'Unexpected main ruleset name'
    Assert-Equal $Policy.target 'branch' 'Main ruleset target'
    Assert-Equal $Policy.enforcement 'active' 'Main ruleset enforcement'
    Assert-Equal @($Policy.bypass_actors).Count 0 'Main ruleset bypass count'
    Assert-SequenceEqual @($Policy.conditions.ref_name.include) @('refs/heads/main') 'Main include patterns'
    Assert-Equal @($Policy.conditions.ref_name.exclude).Count 0 'Main exclusions'
    Assert-SequenceEqual @($Policy.rules.type) @('deletion', 'non_fast_forward', 'pull_request', 'required_status_checks') 'Main rules'

    $pullRequestRule = @($Policy.rules | Where-Object { $_.type -eq 'pull_request' })[0]
    Assert-Equal $pullRequestRule.parameters.required_approving_review_count 0 'Main approval count'
    Assert-Equal $pullRequestRule.parameters.dismiss_stale_reviews_on_push $true 'Dismiss stale reviews'
    Assert-Equal $pullRequestRule.parameters.required_review_thread_resolution $true 'Conversation resolution'
    Assert-SequenceEqual @($pullRequestRule.parameters.allowed_merge_methods) @('squash', 'rebase') 'Allowed merge methods'

    $statusRule = @($Policy.rules | Where-Object { $_.type -eq 'required_status_checks' })[0]
    Assert-Equal $statusRule.parameters.strict_required_status_checks_policy $true 'Strict status checks'
    Assert-SequenceEqual @($statusRule.parameters.required_status_checks.context) @('CI Passed', 'Security Summary') 'Required status checks'
}

function Get-LiveRuleset {
    param([Parameter(Mandatory)] [string] $Name)
    $rulesets = @((gh api "repos/$repository/rulesets" | ConvertFrom-Json))
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to list live repository rulesets.'
    }
    $matches = @($rulesets | Where-Object { $_.name -eq $Name })
    if ($matches.Count -ne 1) {
        throw "Expected one live ruleset named '$Name', found $($matches.Count)."
    }
    $live = gh api "repos/$repository/rulesets/$($matches[0].id)" | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read live ruleset '$Name'."
    }
    return $live
}

$mainPolicy = Get-Content -Raw -LiteralPath (Join-Path $rulesetDirectory 'main.json') | ConvertFrom-Json
$pluginPolicy = Get-Content -Raw -LiteralPath (Join-Path $rulesetDirectory 'plugin-tags.json') | ConvertFrom-Json
$modPolicy = Get-Content -Raw -LiteralPath (Join-Path $rulesetDirectory 'mod-tags.json') | ConvertFrom-Json

Assert-MainPolicy $mainPolicy
Assert-ImmutableTagPolicy $pluginPolicy 'Paper release tag provenance' 'refs/tags/plugin-v*' $true
Assert-ImmutableTagPolicy $modPolicy 'Fabric release tag quarantine' 'refs/tags/mod-v*' $false

if ($StaticOnly) {
    Write-Output 'Static repository policy verified.'
    exit 0
}

gh auth status | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'GitHub CLI authentication is required.'
}

$actionsApp = gh api apps/github-actions | ConvertFrom-Json
Assert-Equal $actionsApp.id 15368 'GitHub Actions integration ID'

Assert-MainPolicy (Get-LiveRuleset 'Protect main')
Assert-ImmutableTagPolicy (Get-LiveRuleset 'Paper release tag provenance') 'Paper release tag provenance' 'refs/tags/plugin-v*' $true
Assert-ImmutableTagPolicy (Get-LiveRuleset 'Fabric release tag quarantine') 'Fabric release tag quarantine' 'refs/tags/mod-v*' $false

$phaseZeroPolicy = Get-LiveRuleset 'Phase 0 release tag provenance'
Assert-Equal $phaseZeroPolicy.enforcement 'active' 'Phase 0 release tag enforcement'
Assert-SequenceEqual @($phaseZeroPolicy.conditions.ref_name.include) @('refs/tags/v*', 'refs/tags/dj-v*') 'Phase 0 tag patterns'
Assert-Equal @($phaseZeroPolicy.bypass_actors).Count 0 'Phase 0 bypass count'

$workflowSettings = gh api "repos/$repository/actions/permissions/workflow" | ConvertFrom-Json
Assert-Equal $workflowSettings.default_workflow_permissions 'read' 'Default workflow permissions'
Assert-Equal $workflowSettings.can_approve_pull_request_reviews $false 'Workflow PR approval permission'

$securityUpdates = gh api "repos/$repository/automated-security-fixes" | ConvertFrom-Json
Assert-Equal $securityUpdates.enabled $true 'Dependabot security updates'

Write-Output 'Live repository policy verified.'
