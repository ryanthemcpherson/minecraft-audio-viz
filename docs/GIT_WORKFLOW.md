# Git Workflow

This repository uses short-lived branches, atomic commits, required CI, and immutable release tags. The goal is to make every change traceable and every recovery operation reversible.

## Branches and worktrees

- Start normal work from the current `origin/main` after fetching.
- Use the existing branch families: `feature/`, `fix/`, `chore/`, `docs/`, `release/`, and `recovery/`.
- Use `.worktrees/` for isolated release and recovery work. The directory is ignored and must never be committed.
- A `recovery/` branch preserves historical or uncommitted work. Do not rewrite or delete it until an explicit cleanup list proves the same commit is reachable remotely and receives approval.
- Do not force-push shared branches. A failed non-fast-forward push must be resolved by fetching and reviewing the divergence.

## Commits and review

- Keep each commit to one logical change and use conventional prefixes such as `feat:`, `fix:`, `chore:`, `docs:`, `test:`, or `ci:`.
- Check `git status` and the staged diff before committing.
- Rebase a local topic branch onto current `origin/main` before final review when doing so does not rewrite other contributors' published work.
- Open pull requests into `main`; direct updates are prohibited by the repository ruleset.
- Required checks are `CI Passed` and `Security Summary`, both against an up-to-date branch.
- Resolve all review conversations before merge. Supported merge methods are squash and rebase.

## Dependencies and automation

- Dependabot updates are grouped per component and ecosystem, with at most three open version-update pull requests per entry.
- Security updates stay enabled and are triaged separately from routine version groups.
- GitHub Actions references use immutable 40-character commit SHAs.
- Workflows default to no token permissions and grant only the permissions required by each job.

## Releases

- Paper plugin releases use tags matching `plugin-v*`; DJ client releases use `dj-v*`; legacy generic `v*` tags remain quarantined.
- Release candidates are built once. Promotion consumes the attested candidate and creates the tag without rebuilding.
- Only the protected GitHub Actions promotion job may create a `plugin-v*` tag.
- Release tags cannot be updated, deleted, or force-moved.
- Never create a release tag from an unverified local artifact.

## Recovery and cleanup

- Recovery is additive: create a worktree at the exact base, apply or copy the evidence, commit it, push it, and verify the remote commit.
- Keep original stashes, worktrees, local branches, and untracked files during preservation.
- Cleanup requires a reviewed list of exact stash objects, worktree paths, branch names, pull requests, or files. Approval for one target does not authorize any other deletion.
- Never use `git reset --hard`, force push, stash drop, recursive deletion, or branch deletion as an implicit cleanup step.
