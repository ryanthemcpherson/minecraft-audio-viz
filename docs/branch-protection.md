# Branch Protection Setup

Recommended GitHub branch protection rules for the `main` branch.

## Settings

### Require pull request reviews
- Required approving reviews: **1**
- Dismiss stale PR reviews on new pushes: **Yes**
- Require review from code owners: **Yes**

### Require status checks
- Require branches be up to date before merging: **Yes**
- Required checks:
  - `CI Passed` (the aggregate gate job)
  - `Secret Scan`

### Require signed commits
- **Yes** (GPG key already configured at `.github/GPG-PUBLIC-KEY.asc`)

### Other
- Require linear history: **No** (squash merges already enforced by convention)
- Include administrators: **No** (allows admin override for Dependabot merges)
- Restrict who can push: **Not configured** (rely on PR requirement)
- Allow force pushes: **Never**
- Allow deletions: **No**

## Setup Steps

1. Go to **Settings > Branches > Branch protection rules**
2. Click **Add rule**
3. Branch name pattern: `main`
4. Enable each setting as listed above
5. Click **Create** / **Save changes**

## Notes

- Admin override is intentionally left available for batch Dependabot merges
- The `CI Passed` job aggregates all required checks, so only one status check entry is needed
- Secret Scan is listed separately since it's a security-critical gate
