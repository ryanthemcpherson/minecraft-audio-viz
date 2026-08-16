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

function Assert-TagPolicyBase {
    param(
        [Parameter(Mandatory)] $Policy,
        [Parameter(Mandatory)] [string] $ExpectedName,
        [Parameter(Mandatory)] [string] $ExpectedPattern,
        [Parameter(Mandatory)] [object[]] $ExpectedRules
    )

    Assert-Equal $Policy.name $ExpectedName 'Unexpected tag ruleset name'
    Assert-Equal $Policy.target 'tag' "$ExpectedName target"
    Assert-Equal $Policy.enforcement 'active' "$ExpectedName enforcement"
    Assert-SequenceEqual @($Policy.conditions.ref_name.include) @($ExpectedPattern) "$ExpectedName include patterns"
    Assert-Equal @($Policy.conditions.ref_name.exclude).Count 0 "$ExpectedName exclusions"
    Assert-SequenceEqual @($Policy.rules.type) $ExpectedRules "$ExpectedName rules"
}

function Assert-ImmutableTagPolicy {
    param(
        [Parameter(Mandatory)] $Policy,
        [Parameter(Mandatory)] [string] $ExpectedName,
        [Parameter(Mandatory)] [string] $ExpectedPattern
    )

    Assert-TagPolicyBase $Policy $ExpectedName $ExpectedPattern @('update', 'deletion', 'non_fast_forward')

    $bypassActors = @($Policy.bypass_actors)
    Assert-Equal $bypassActors.Count 0 "$ExpectedName bypass count"
}

function Assert-TagCreationPolicy {
    param([Parameter(Mandatory)] $Policy)

    $expectedName = 'Paper release tag creation'
    Assert-TagPolicyBase $Policy $expectedName 'refs/tags/plugin-v*' @('creation')

    $bypassActors = @($Policy.bypass_actors)
    Assert-Equal $bypassActors.Count 1 "$expectedName bypass count"
    if ($null -ne $bypassActors[0].actor_id) {
        throw "$expectedName bypass actor ID must be null for a deploy key."
    }
    Assert-Equal $bypassActors[0].actor_type 'DeployKey' "$expectedName bypass actor type"
    Assert-Equal $bypassActors[0].bypass_mode 'always' "$expectedName bypass mode"
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

function Assert-PluginReleaseEnvironment {
    param([Parameter(Mandatory)] $Environment)

    Assert-Equal $Environment.name 'plugin-release' 'Release environment name'
    Assert-Equal $Environment.deployment_branch_policy.protected_branches $true 'Release protected-branch policy'
    Assert-Equal $Environment.deployment_branch_policy.custom_branch_policies $false 'Release custom-branch policy'

    $reviewRules = @($Environment.protection_rules | Where-Object { $_.type -eq 'required_reviewers' })
    Assert-Equal $reviewRules.Count 1 'Release required-reviewer rule count'
    Assert-Equal $reviewRules[0].prevent_self_review $false 'Release self-review policy'
    $reviewers = @($reviewRules[0].reviewers)
    Assert-Equal $reviewers.Count 1 'Release reviewer count'
    Assert-Equal $reviewers[0].type 'User' 'Release reviewer type'
    Assert-Equal $reviewers[0].reviewer.id 37377365 'Release reviewer ID'
}

$mainPolicy = Get-Content -Raw -LiteralPath (Join-Path $rulesetDirectory 'main.json') | ConvertFrom-Json
$pluginCreationPolicy = Get-Content -Raw -LiteralPath (Join-Path $rulesetDirectory 'plugin-tag-creation.json') | ConvertFrom-Json
$pluginPolicy = Get-Content -Raw -LiteralPath (Join-Path $rulesetDirectory 'plugin-tags.json') | ConvertFrom-Json
$modPolicy = Get-Content -Raw -LiteralPath (Join-Path $rulesetDirectory 'mod-tags.json') | ConvertFrom-Json

Assert-MainPolicy $mainPolicy
Assert-TagCreationPolicy $pluginCreationPolicy
Assert-ImmutableTagPolicy $pluginPolicy 'Paper release tag immutability' 'refs/tags/plugin-v*'
Assert-TagPolicyBase $modPolicy 'Fabric release tag quarantine' 'refs/tags/mod-v*' @('creation', 'update', 'deletion', 'non_fast_forward')
Assert-Equal @($modPolicy.bypass_actors).Count 0 'Fabric release tag quarantine bypass count'

$applyPolicyContent = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'apply-repository-policy.ps1')
if ($applyPolicyContent -notmatch 'environments/plugin-release') {
    throw 'Apply policy does not configure the plugin-release environment.'
}
if ($applyPolicyContent -notmatch "type\s*=\s*'User';\s*id\s*=\s*37377365") {
    throw 'Apply policy does not require the approved plugin-release reviewer.'
}
if ($applyPolicyContent -notmatch 'protected_branches\s*=\s*\$true') {
    throw 'Apply policy does not limit plugin-release to protected branches.'
}
if ($applyPolicyContent -notmatch 'custom_branch_policies\s*=\s*\$false') {
    throw 'Apply policy unexpectedly enables custom release branches.'
}

$releaseWorkflowContent = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot '.github\workflows\release-plugin.yml')
if ($releaseWorkflowContent -notmatch '(?m)^  workflow_dispatch:\s*$') {
    throw 'Plugin release workflow must be manually dispatched.'
}
if ($releaseWorkflowContent -match '(?m)^  push:\s*$') {
    throw 'Plugin release workflow must not use a push trigger.'
}
if ($releaseWorkflowContent -match 'setup-java|mvnw|maven') {
    throw 'Plugin release workflow must promote candidate bytes without rebuilding.'
}
if ($releaseWorkflowContent -notmatch '(?m)^    environment: plugin-release\s*$') {
    throw 'Plugin release job must use the protected plugin-release environment.'
}
if ($releaseWorkflowContent -notmatch 'refs/tags/plugin-v1\.1\.0') {
    throw 'Plugin release workflow must derive the immutable 1.1.0 tag internally.'
}
if ($releaseWorkflowContent -notmatch 'MCAV_PLUGIN_RELEASE_DEPLOY_KEY') {
    throw 'Plugin release workflow must use the environment-scoped deploy key.'
}

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
Assert-TagCreationPolicy (Get-LiveRuleset 'Paper release tag creation')
Assert-ImmutableTagPolicy (Get-LiveRuleset 'Paper release tag immutability') 'Paper release tag immutability' 'refs/tags/plugin-v*'
$liveModPolicy = Get-LiveRuleset 'Fabric release tag quarantine'
Assert-TagPolicyBase $liveModPolicy 'Fabric release tag quarantine' 'refs/tags/mod-v*' @('creation', 'update', 'deletion', 'non_fast_forward')
Assert-Equal @($liveModPolicy.bypass_actors).Count 0 'Fabric release tag quarantine bypass count'

$pluginReleaseEnvironment = gh api "repos/$repository/environments/plugin-release" | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to read the plugin-release environment.'
}
Assert-PluginReleaseEnvironment $pluginReleaseEnvironment

$allDeployKeys = @(gh api "repos/$repository/keys" | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to list repository deploy keys.'
}
$releaseDeployKeys = @($allDeployKeys | Where-Object {
    $_.title -eq 'MCAV plugin release environment'
})
Assert-Equal $releaseDeployKeys.Count 1 'Release deploy key count'
Assert-Equal $releaseDeployKeys[0].read_only $false 'Release deploy key write access'
Assert-Equal @($allDeployKeys | Where-Object { $_.read_only -eq $false }).Count 1 'Repository write deploy-key count'

$releaseEnvironmentSecrets = @(& gh secret list --repo $repository --env plugin-release --json name | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to list plugin-release environment secrets.'
}
Assert-Equal @($releaseEnvironmentSecrets | Where-Object {
    $_.name -eq 'MCAV_PLUGIN_RELEASE_DEPLOY_KEY'
}).Count 1 'Release deploy-key secret count'

$phaseZeroPolicy = Get-LiveRuleset 'Phase 0 release tag provenance'
Assert-Equal $phaseZeroPolicy.enforcement 'active' 'Phase 0 release tag enforcement'
Assert-SequenceEqual @($phaseZeroPolicy.conditions.ref_name.include) @('refs/tags/v*', 'refs/tags/dj-v*') 'Phase 0 tag patterns'
Assert-Equal @($phaseZeroPolicy.bypass_actors).Count 0 'Phase 0 bypass count'

$workflowSettings = gh api "repos/$repository/actions/permissions/workflow" | ConvertFrom-Json
Assert-Equal $workflowSettings.default_workflow_permissions 'read' 'Default workflow permissions'
Assert-Equal $workflowSettings.can_approve_pull_request_reviews $false 'Workflow PR approval permission'

& gh api --silent "repos/$repository/vulnerability-alerts"
if ($LASTEXITCODE -ne 0) {
    throw 'Dependabot vulnerability alerts are disabled.'
}

$securityUpdates = gh api "repos/$repository/automated-security-fixes" | ConvertFrom-Json
Assert-Equal $securityUpdates.enabled $true 'Dependabot security updates'

Write-Output 'Live repository policy verified.'
