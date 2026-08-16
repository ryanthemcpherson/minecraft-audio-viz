# Git Recovery and Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Map every current local product change to a durable remote branch, make local Git behavior predictable, clear the security dependency backlog, and enforce reviewable main/tag provenance without deleting recoverable work.

**Architecture:** Recovery branches preserve incomplete work before any cleanup. Static policy files and idempotent PowerShell verifiers make GitHub rules, action pins, and local expectations reviewable; live GitHub settings are then applied and verified against those files.

**Tech Stack:** Git, PowerShell 7, GitHub CLI/API, GitHub Actions, Dependabot, npm

**Spec:** `docs/superpowers/specs/2026-08-15-paper-26-2-release-design.md`

## Global Constraints

- Work from `release/paper-26.2` in the existing isolated `.worktrees/phase0-containment` worktree.
- Preserve both stashes and all existing worktrees after snapshotting.
- Never use `git reset --hard`, `git clean`, `git checkout --`, force-push, recursive delete, worktree removal, stash drop, branch deletion, or aggressive GC.
- Exclude `.codex/`, `.superpowers/`, and root `AGENTS.md` from recovery commits.
- Use recovery branches only for preservation; do not merge recovered refactors into the release branch.
- Use conventional commits and push each recovery branch immediately after its preservation commit.
- Require zero critical/high audit findings before the release branch can merge.

---

### Task 1: Capture the authoritative recovery ledger

**Files:**
- Create: `docs/superpowers/reports/2026-08-15-git-recovery-ledger.md`

**Interfaces:**
- Consumes: current main/release refs, `git worktree list`, both stashes, root untracked files, remote refs
- Produces: immutable before-state inventory and a mapping column filled as later tasks preserve each item

- [ ] **Step 1: Record repository and remote identity**

  Run from the primary checkout:

  ```powershell
  git rev-parse --show-toplevel
  git status --short --branch
  git log --oneline --decorate -12
  git remote -v
  git config --local --list --show-origin
  ```

- [ ] **Step 2: Record every worktree with status and diff summary**

  ```powershell
  $worktreePaths = git worktree list --porcelain |
    Where-Object { $_ -like 'worktree *' } |
    ForEach-Object { $_.Substring(9) }
  foreach ($worktreePath in $worktreePaths) {
    Write-Output "WORKTREE $worktreePath"
    git -C $worktreePath status --short --branch
    git -C $worktreePath diff --stat
    git -C $worktreePath ls-files --others --exclude-standard
  }
  ```

- [ ] **Step 3: Record both stashes without modifying them**

  ```powershell
  git stash list --format='%gd %H %gs'
  git stash show --stat 'stash@{0}'
  git stash show --stat 'stash@{1}'
  git show --no-patch --format='%H %P %s' 'stash@{0}' 'stash@{1}'
  ```

- [ ] **Step 4: Record connectivity and remote branches**

  ```powershell
  git fsck --full --no-reflogs
  git branch -vv
  git ls-remote --heads --tags origin
  gh pr list --state open --limit 200 --json number,title,headRefName,mergeStateStatus,author,url
  ```

- [ ] **Step 5: Write the ledger**

  Use these exact sections:

  ```markdown
  # Git Recovery Ledger — 2026-08-15

  ## Repository baseline
  ## Primary checkout untracked files
  ## Stash preservation
  ## Linked worktrees
  ## Local-only branches
  ## Remote dependency pull requests
  ## Recovery mapping
  ## Deferred cleanup targets
  ## Verification
  ```

  In `Recovery mapping`, give every dirty/untracked/stashed item one status: `unmapped`, `preserved at <commit>`, or `local-only tool state excluded by design`.

- [ ] **Step 6: Validate and commit the ledger before recovery mutations**

  ```powershell
  git diff --check
  git add docs/superpowers/reports/2026-08-15-git-recovery-ledger.md
  git commit -m "docs(git): inventory recovery state"
  ```

---

### Task 2: Preserve `stash@{0}` and matching split modules

**Files:**
- Modify on recovery branch: tracked files represented by `stash@{0}`
- Copy on recovery branch: `admin_panel/js/managers/`, `admin_panel/js/modules/`, `admin_panel/js/ui/`, `coordinator/app/dependencies/server.py`, `coordinator/app/services/connect_service.py`, `coordinator/app/services/exceptions.py`, `coordinator/app/services/show_service.py`, `coordinator/tests/`, `minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/`, `vj_server/tests/test_relay.py`
- Modify on release branch: `docs/superpowers/reports/2026-08-15-git-recovery-ledger.md`

**Interfaces:**
- Consumes: `stash@{0}` and explicitly listed root product-source paths
- Produces: remote branch `recovery/stash-0-refactor` and a commit hash recorded in the ledger

- [ ] **Step 1: Resolve and verify safe paths**

  ```powershell
  $repoRoot = (git rev-parse --show-toplevel).Trim()
  $recoveryPath = Join-Path $repoRoot '.worktrees/recovery-stash-0-refactor'
  $resolvedParent = (Resolve-Path $repoRoot).Path
  $resolvedRecoveryParent = (Resolve-Path (Split-Path $recoveryPath -Parent)).Path
  if ($resolvedRecoveryParent -ne (Join-Path $resolvedParent '.worktrees')) {
    throw "Recovery worktree escaped .worktrees"
  }
  git check-ignore .worktrees
  git rev-parse 'stash@{0}^1'
  ```

- [ ] **Step 2: Create the preservation worktree at the stash base**

  ```powershell
  $stashBase = (git rev-parse 'stash@{0}^1').Trim()
  git worktree add $recoveryPath -b recovery/stash-0-refactor $stashBase
  git -C $recoveryPath stash apply 'stash@{0}'
  git -C $recoveryPath status --short
  ```

  Expected: the stash still appears in `git stash list`; its tracked changes apply on their original base.

- [ ] **Step 3: Copy only untracked files under the paired product paths with native PowerShell**

  ```powershell
  $pairedPrefixes = @(
    'admin_panel/js/managers/',
    'admin_panel/js/modules/',
    'admin_panel/js/ui/',
    'coordinator/app/dependencies/server.py',
    'coordinator/app/services/connect_service.py',
    'coordinator/app/services/exceptions.py',
    'coordinator/app/services/show_service.py',
    'coordinator/tests/',
    'minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/',
    'vj_server/tests/test_relay.py'
  )
  $pairedFiles = git -C $repoRoot ls-files --others --exclude-standard |
    Where-Object {
      $candidate = $_
      $pairedPrefixes | Where-Object { $candidate.StartsWith($_) }
    }
  if (-not $pairedFiles) { throw 'No paired untracked files were found' }
  $pairedFiles
  foreach ($relativePath in $pairedFiles) {
    $source = Join-Path $repoRoot $relativePath
    if (-not (Test-Path -LiteralPath $source)) { throw "Missing $relativePath" }
    $destination = Join-Path $recoveryPath $relativePath
    New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Force
  }
  ```

- [ ] **Step 4: Verify exclusions and commit the snapshot**

  ```powershell
  git -C $recoveryPath status --short
  if (git -C $recoveryPath status --short | Select-String -Pattern '(^|/)(\.codex|\.superpowers)(/|$)|AGENTS\.md') {
    throw 'Local tool state entered recovery branch'
  }
  git -C $recoveryPath diff --check
  git -C $recoveryPath add -A
  git -C $recoveryPath commit -m "wip(recovery): preserve stashed refactor"
  git -C $recoveryPath push -u origin recovery/stash-0-refactor
  ```

- [ ] **Step 5: Record the remote commit without dropping the stash**

  Add the result of `git -C $recoveryPath rev-parse HEAD` and the remote branch URL to the ledger. Commit:

  ```powershell
  git add docs/superpowers/reports/2026-08-15-git-recovery-ledger.md
  git commit -m "docs(git): map stashed refactor recovery"
  ```

---

### Task 3: Preserve `stash@{1}` and every dirty linked worktree

**Files:**
- Modify on preservation branches: the existing dirty files in each worktree
- Modify: `docs/superpowers/reports/2026-08-15-git-recovery-ledger.md`

**Interfaces:**
- Consumes: `stash@{1}`, `feature/low-latency-renderer`, `refactor/split-dj-client-app`, `fix/error-handling-and-abort-controllers`
- Produces: pushed preservation commits and a complete recovery mapping

- [ ] **Step 1: Preserve the second stash on its original base**

  ```powershell
  $repoRoot = (git rev-parse --show-toplevel).Trim()
  $stashOnePath = Join-Path $repoRoot '.worktrees/recovery-stash-1-security'
  $stashOneBase = (git rev-parse 'stash@{1}^1').Trim()
  git worktree add $stashOnePath -b recovery/stash-1-security $stashOneBase
  git -C $stashOnePath stash apply 'stash@{1}'
  git -C $stashOnePath diff --check
  git -C $stashOnePath add -A
  git -C $stashOnePath commit -m "wip(recovery): preserve security changes"
  git -C $stashOnePath push -u origin recovery/stash-1-security
  git stash list
  ```

- [ ] **Step 2: Snapshot the low-latency worktree without changing its content**

  ```powershell
  $path = Join-Path $repoRoot '.worktrees/feature-low-latency-renderer'
  git -C $path diff --check
  git -C $path add minecraft_plugin/src protocol/schemas protocol/tests
  git -C $path commit -m "wip(recovery): snapshot render foundation"
  git -C $path push -u origin feature/low-latency-renderer
  ```

- [ ] **Step 3: Snapshot the DJ client split lockfile**

  ```powershell
  $path = Join-Path $repoRoot '.claude/worktrees/agent-a866290d'
  git -C $path diff --check
  git -C $path add dj_client/package-lock.json
  git -C $path commit -m "wip(recovery): snapshot DJ client split lockfile"
  git -C $path push -u origin refactor/split-dj-client-app
  ```

- [ ] **Step 4: Snapshot the error-handling worktree**

  ```powershell
  $path = Join-Path $repoRoot '.claude/worktrees/agent-ad61c2ee'
  git -C $path diff --check
  git -C $path add coordinator
  git -C $path commit -m "wip(recovery): snapshot coordinator error handling"
  git -C $path push -u origin fix/error-handling-and-abort-controllers
  ```

- [ ] **Step 5: Snapshot remaining root product documents separately**

  Create `recovery/root-untracked-2026-08-15` from current local `main`, then copy only root untracked files remaining after excluding the Task 2 paths, `.codex/`, `.superpowers/`, and `AGENTS.md`. Inspect the list before adding:

  ```powershell
  $rootSnapshot = Join-Path $repoRoot '.worktrees/recovery-root-untracked'
  git worktree add $rootSnapshot -b recovery/root-untracked-2026-08-15 main
  $pairedPrefixes = @(
    'admin_panel/js/managers/',
    'admin_panel/js/modules/',
    'admin_panel/js/ui/',
    'coordinator/app/dependencies/server.py',
    'coordinator/app/services/connect_service.py',
    'coordinator/app/services/exceptions.py',
    'coordinator/app/services/show_service.py',
    'coordinator/tests/',
    'minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/',
    'vj_server/tests/test_relay.py'
  )
  $remaining = git -C $repoRoot ls-files --others --exclude-standard |
    Where-Object {
      $candidate = $_
      $candidate -ne 'AGENTS.md' -and
      $candidate -notlike '.codex/*' -and
      $candidate -notlike '.superpowers/*' -and
      -not ($pairedPrefixes | Where-Object { $candidate.StartsWith($_) })
    }
  $remaining
  foreach ($relativePath in $remaining) {
    $source = Join-Path $repoRoot $relativePath
    $destination = Join-Path $rootSnapshot $relativePath
    New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Force
  }
  git -C $rootSnapshot status --short
  git -C $rootSnapshot add -A
  git -C $rootSnapshot commit -m "wip(recovery): preserve root untracked product files"
  git -C $rootSnapshot push -u origin recovery/root-untracked-2026-08-15
  ```

- [ ] **Step 6: Complete the mapping and verify no product state remains unmapped**

  Update the ledger with every preservation commit. `unmapped` may remain only for explicitly excluded local tool state. Commit:

  ```powershell
  git add docs/superpowers/reports/2026-08-15-git-recovery-ledger.md
  git commit -m "docs(git): complete recovery mapping"
  ```

---

### Task 4: Set safe repository-local Git defaults and document the workflow

**Files:**
- Create: `docs/GIT_WORKFLOW.md`
- Modify: `docs/superpowers/reports/2026-08-15-git-recovery-ledger.md`

**Interfaces:**
- Consumes: recovered branch topology
- Produces: deterministic local pull/fetch/rebase behavior and a documented worktree/cleanup process

- [ ] **Step 1: Set repository-local defaults**

  ```powershell
  git config --local fetch.prune true
  git config --local pull.rebase true
  git config --local rebase.autoStash true
  git config --local rerere.enabled true
  git config --local rerere.autoupdate true
  ```

- [ ] **Step 2: Verify exact values**

  ```powershell
  $expected = @{
    'fetch.prune' = 'true'
    'pull.rebase' = 'true'
    'rebase.autoStash' = 'true'
    'rerere.enabled' = 'true'
    'rerere.autoupdate' = 'true'
  }
  foreach ($key in $expected.Keys) {
    if ((git config --local --get $key).Trim() -ne $expected[$key]) {
      throw "Unexpected $key"
    }
  }
  git check-ignore .worktrees
  ```

- [ ] **Step 3: Write the workflow document**

  Document branch prefixes, atomic commits, rebase-before-review, `.worktrees/`, recovery branches, required checks, dependency grouping, release tags, and the rule that cleanup requires a reviewed explicit target list.

- [ ] **Step 4: Commit**

  ```powershell
  git add docs/GIT_WORKFLOW.md docs/superpowers/reports/2026-08-15-git-recovery-ledger.md
  git commit -m "docs(git): define repository workflow"
  ```

---

### Task 5: Remove current npm critical/high findings in atomic component commits

**Files:**
- Modify: `package-lock.json`
- Modify: `dj_client/package-lock.json`
- Modify: `site/package.json`
- Modify: `site/package-lock.json`
- Modify: `worker/package.json`
- Modify: `worker/package-lock.json`

**Interfaces:**
- Consumes: npm registry advisories current on 2026-08-15
- Produces: four independently testable component states with zero critical/high audit findings

- [ ] **Step 1: Fix root transitive PostCSS and nanoid versions**

  ```powershell
  npm install --package-lock-only
  npm audit --audit-level=high
  npm test --if-present
  npm run build --if-present
  git add package-lock.json
  git commit -m "fix(deps): resolve root npm advisories"
  ```

- [ ] **Step 2: Fix DJ client transitive PostCSS and nanoid versions**

  ```powershell
  Push-Location dj_client
  npm install --package-lock-only
  npm audit --audit-level=high
  npm test -- --watch=false
  npm run build
  Pop-Location
  git add dj_client/package-lock.json
  git commit -m "fix(deps): resolve DJ client npm advisories"
  ```

- [ ] **Step 3: Upgrade the site to patched direct versions**

  ```powershell
  Push-Location site
  npm install next@16.3.1 eslint-config-next@16.3.1 @tailwindcss/postcss@4.3.3
  npm audit --audit-level=high
  npm run lint
  npm test --if-present
  npm run build
  Pop-Location
  git add site/package.json site/package-lock.json
  git commit -m "fix(deps): patch site security advisories"
  ```

- [ ] **Step 4: Upgrade Wrangler to the patched line**

  ```powershell
  Push-Location worker
  npm install --save-dev wrangler@4.123.0
  npm audit --audit-level=high
  npm test --if-present
  npm run build --if-present
  Pop-Location
  git add worker/package.json worker/package-lock.json
  git commit -m "fix(deps): patch worker security advisories"
  ```

- [ ] **Step 5: Re-run the aggregate audit**

  ```powershell
  foreach ($directory in @('.', 'dj_client', 'site', 'worker')) {
    Push-Location $directory
    npm audit --audit-level=high
    Pop-Location
  }
  ```

  Expected: all four commands exit 0. Moderate findings are reviewed and fixed when a non-breaking patched version is available; none may be ignored silently.

---

### Task 6: Group Dependabot updates and verify the static policy

**Files:**
- Modify: `.github/dependabot.yml`
- Create: `scripts/github/verify-dependabot-policy.ps1`

**Interfaces:**
- Consumes: nine current ecosystem entries and the existing 47-PR queue
- Produces: grouped weekly updates, bounded PR counts, security-update readiness, and a dependency triage report

- [ ] **Step 1: Write a failing static verifier**

  The verifier reads `.github/dependabot.yml` as text and fails unless every entry has `open-pull-requests-limit: 3`, every npm/pip entry contains `groups:`, and GitHub Actions uses a single group. Core assertion:

  ```powershell
  $content = Get-Content -Raw '.github/dependabot.yml'
  $entries = [regex]::Matches($content, '(?m)^  - package-ecosystem:')
  $limits = [regex]::Matches($content, '(?m)^    open-pull-requests-limit: 3$')
  if ($entries.Count -ne $limits.Count) { throw 'Every ecosystem must use PR limit 3' }
  if ($content -notmatch '(?ms)package-ecosystem: "github-actions".*groups:.*actions:') {
    throw 'GitHub Actions updates must be grouped'
  }
  ```

- [ ] **Step 2: Run it and observe failure**

  ```powershell
  pwsh -File scripts/github/verify-dependabot-policy.ps1
  ```

  Expected: FAIL because the current limits are 5 or 10 and groups are absent.

- [ ] **Step 3: Add per-component groups and reduce every limit to three**

  For each entry, add a group named for its component and ecosystem. Example:

  ```yaml
    open-pull-requests-limit: 3
    groups:
      site-production:
        dependency-type: "production"
      site-development:
        dependency-type: "development"
  ```

  The Maven entry uses `paper-runtime` and `paper-test-build`; Cargo uses `dj-rust`; GitHub Actions uses `actions` with `patterns: ["*"]`.

- [ ] **Step 4: Verify and commit**

  ```powershell
  pwsh -File scripts/github/verify-dependabot-policy.ps1
  git diff --check
  git add .github/dependabot.yml scripts/github/verify-dependabot-policy.ps1
  git commit -m "chore(deps): group Dependabot updates"
  ```

- [ ] **Step 5: Produce a reviewed PR triage list without closing anything**

  ```powershell
  gh pr list --state open --limit 200 --json number,title,headRefName,mergeStateStatus,url |
    Set-Content -Encoding utf8 docs/superpowers/reports/2026-08-15-dependabot-triage.json
  git add docs/superpowers/reports/2026-08-15-dependabot-triage.json
  git commit -m "docs(deps): inventory dependency pull requests"
  ```

  Closing superseded PRs is an explicit later checkpoint after this exact file is reviewed. Do not close or delete in this task.

---

### Task 7: Pin every GitHub Action and enforce least privilege

**Files:**
- Modify: `.github/workflows/*.yml`
- Create: `scripts/github/verify-workflow-pins.ps1`

**Interfaces:**
- Consumes: every `uses:` reference in repository workflows
- Produces: full-SHA action references and a verifier required by CI

- [ ] **Step 1: Write the failing verifier**

  ```powershell
  $violations = @()
  Get-ChildItem '.github/workflows' -Filter '*.yml' | ForEach-Object {
    $lineNumber = 0
    Get-Content $_.FullName | ForEach-Object {
      $lineNumber++
      if ($_ -match '^\s*-?\s*uses:\s*([^\s#]+)' -and $Matches[1] -notmatch '@[0-9a-f]{40}$') {
        $violations += "$($_.Name):$lineNumber $($Matches[1])"
      }
    }
  }
  if ($violations.Count) { throw ($violations -join [Environment]::NewLine) }
  ```

- [ ] **Step 2: Run it and observe every moving tag**

  ```powershell
  pwsh -File scripts/github/verify-workflow-pins.ps1
  ```

  Expected: FAIL on `actions/checkout@v4`, setup actions, artifact actions, Rust actions, Docker actions, labeler, and release actions.

- [ ] **Step 3: Resolve each existing tag through the authoritative GitHub API**

  For annotated tags, dereference the tag object to its commit. Record `owner/repo`, tag, and full SHA in the commit body. Example:

  ```powershell
  $ref = gh api 'repos/actions/checkout/git/ref/tags/v4' | ConvertFrom-Json
  $object = gh api $ref.object.url | ConvertFrom-Json
  if ($object.type -eq 'tag') { $object = gh api $object.object.url | ConvertFrom-Json }
  if ($object.type -ne 'commit' -or $object.sha -notmatch '^[0-9a-f]{40}$') {
    throw 'checkout v4 did not resolve to a commit'
  }
  $object.sha
  ```

  Replace references with `owner/action@<40-char-sha> # vN` for every unique action.

- [ ] **Step 4: Apply least-privilege defaults**

  Add `permissions: {}` at workflow scope unless an existing narrower read scope is required. Add job permissions only for checkout/read, reports, attestations, or release publication. Set `persist-credentials: false` on checkouts outside the protected tag-creation job. Change `security.yml` to run for every pull request targeting `main`; a required `Security Summary` cannot use a path-filtered trigger because unchanged paths would leave an unfulfillable required check.

- [ ] **Step 5: Verify YAML and commit**

  ```powershell
  pwsh -File scripts/github/verify-workflow-pins.ps1
  pre-commit run check-yaml --all-files
  git diff --check
  git add .github/workflows scripts/github/verify-workflow-pins.ps1
  git commit -m "ci(security): pin actions and minimize permissions"
  ```

---

### Task 8: Codify, apply, and verify main/tag rulesets

**Files:**
- Create: `.github/rulesets/main.json`
- Create: `.github/rulesets/plugin-tags.json`
- Create: `scripts/github/apply-repository-policy.ps1`
- Create: `scripts/github/verify-repository-policy.ps1`

**Interfaces:**
- Consumes: live rulesets `12627523`, `18833547`, and `18824190`; GitHub Actions integration ID `15368`
- Produces: strict main checks, workflow-only plugin tag creation, immutable plugin tags, and live verification

- [ ] **Step 1: Add the main ruleset payload**

  `.github/rulesets/main.json` contains active conditions for `refs/heads/main`, no bypass actors, deletion/non-fast-forward rules, PR conversation resolution with zero required approvals for the solo-maintainer state, and strict required checks:

  ```json
  {
    "name": "Protect main",
    "target": "branch",
    "enforcement": "active",
    "bypass_actors": [],
    "conditions": {"ref_name": {"include": ["refs/heads/main"], "exclude": []}},
    "rules": [
      {"type": "deletion"},
      {"type": "non_fast_forward"},
      {"type": "pull_request", "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": true,
        "required_reviewers": [],
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": true,
        "allowed_merge_methods": ["squash", "rebase"]
      }},
      {"type": "required_status_checks", "parameters": {
        "strict_required_status_checks_policy": true,
        "do_not_enforce_on_create": false,
        "required_status_checks": [
          {"context": "CI Passed"},
          {"context": "Security Summary"}
        ]
      }}
    ]
  }
  ```

- [ ] **Step 2: Add the plugin-tag payload**

  `.github/rulesets/plugin-tags.json` targets `refs/tags/plugin-v*`, blocks creation/update/deletion/non-fast-forward, and permits only the GitHub Actions integration to bypass:

  ```json
  {
    "name": "Paper release tag provenance",
    "target": "tag",
    "enforcement": "active",
    "bypass_actors": [
      {"actor_id": 15368, "actor_type": "Integration", "bypass_mode": "always"}
    ],
    "conditions": {"ref_name": {"include": ["refs/tags/plugin-v*"], "exclude": []}},
    "rules": [
      {"type": "creation"},
      {"type": "update"},
      {"type": "deletion"},
      {"type": "non_fast_forward"}
    ]
  }
  ```

- [ ] **Step 3: Implement idempotent application by ruleset name**

  `apply-repository-policy.ps1` requires `gh auth status`, loads both JSON files, discovers current IDs by exact name, and uses `POST` when missing or `PUT` when present. It also disables the obsolete overlapping `Paper and Fabric release tag immutability` rule only after the replacement verifies, while leaving generic `v*`/`dj-v*` quarantine active.

- [ ] **Step 4: Implement live verification**

  `verify-repository-policy.ps1` compares live JSON to the checked-in conditions, rules, checks, strictness, and bypass actor. It additionally verifies:

  ```powershell
  $actionsApp = gh api 'apps/github-actions' | ConvertFrom-Json
  if ($actionsApp.id -ne 15368) { throw 'Unexpected GitHub Actions integration ID' }
  $settings = gh api 'repos/ryanthemcpherson/minecraft-audio-viz/actions/permissions/workflow' | ConvertFrom-Json
  if ($settings.default_workflow_permissions -ne 'read') { throw 'Default workflow permissions must be read' }
  ```

- [ ] **Step 5: Test static payloads before external mutation**

  ```powershell
  Get-Content -Raw .github/rulesets/main.json | ConvertFrom-Json | Out-Null
  Get-Content -Raw .github/rulesets/plugin-tags.json | ConvertFrom-Json | Out-Null
  pwsh -File scripts/github/verify-repository-policy.ps1 -StaticOnly
  ```

- [ ] **Step 6: Commit the policy before applying it**

  ```powershell
  git add .github/rulesets scripts/github/apply-repository-policy.ps1 scripts/github/verify-repository-policy.ps1
  git commit -m "ci(git): codify repository rulesets"
  ```

- [ ] **Step 7: Apply and verify live settings**

  ```powershell
  pwsh -File scripts/github/apply-repository-policy.ps1
  pwsh -File scripts/github/verify-repository-policy.ps1
  gh api --method PUT 'repos/ryanthemcpherson/minecraft-audio-viz/actions/permissions/workflow' -f default_workflow_permissions=read -F can_approve_pull_request_reviews=false
  gh api --method PUT 'repos/ryanthemcpherson/minecraft-audio-viz/automated-security-fixes'
  ```

  Expected: `main` has no bypass, requires up-to-date `CI Passed` and `Security Summary`; only GitHub Actions bypasses plugin tag creation; Dependabot security updates are enabled.

---

### Task 9: Verify the governance checkpoint and stop before cleanup

**Files:**
- Modify: `docs/superpowers/reports/2026-08-15-git-recovery-ledger.md`

**Interfaces:**
- Consumes: Tasks 1-8 commits and live policy verification
- Produces: reviewable local governance checkpoint and an explicit deferred cleanup list; the release branch remains unpushed until the complete release plan is ready for one PR

- [ ] **Step 1: Rebase and run all governance checks**

  ```powershell
  git fetch --prune origin
  git rebase origin/main
  pwsh -File scripts/github/verify-dependabot-policy.ps1
  pwsh -File scripts/github/verify-workflow-pins.ps1
  pwsh -File scripts/github/verify-repository-policy.ps1
  foreach ($directory in @('.', 'dj_client', 'site', 'worker')) {
    Push-Location $directory
    npm audit --audit-level=high
    Pop-Location
  }
  git status --short --branch
  ```

- [ ] **Step 2: Record verified recovery and policy evidence**

  Update the ledger with remote branch links, live ruleset IDs, verifier output, and a `Deferred cleanup targets` list. The list may name stashes, worktrees, branches, and superseded PRs but performs no cleanup.

- [ ] **Step 3: Keep the release integration branch local and clean**

  ```powershell
  git add docs/superpowers/reports/2026-08-15-git-recovery-ledger.md
  git commit -m "docs(git): record governance verification"
  git status --short --branch
  git log --oneline origin/main..HEAD
  ```

  Expected: clean worktree and an atomic local commit series. Do not push `release/paper-26.2` yet; recovery branches are already remote, while the integration branch will be rebased once and pushed once before the final PR.

- [ ] **Step 4: Checkpoint before destructive cleanup**

  Present the exact deferred cleanup list for explicit approval. Until that approval arrives, both stashes, all worktrees, all preservation branches, and all currently open PRs remain intact.
