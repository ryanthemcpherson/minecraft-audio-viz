# MCAV Paper 26.2 Release Design

Date: 2026-08-15

Status: Approved

Target release: `plugin-v1.1.0`

## 1. Decision summary

MCAV will take the fastest supportable public-release route: port the existing Paper plugin and its proven JSON WebSocket path directly to Paper 26.2. The release does not wait for the unimplemented v2 platform, the experimental low-latency binary renderer, a packaged VJ server, the DJ desktop installer, Fabric, Docker distribution, or hosted coordinator features.

The public binary is the Paper plugin JAR. The Python VJ server, pattern files, admin panel, and preview remain source-installed companion components. The show continues to work without the hosted site or coordinator.

Git recovery, repository governance, dependency security, reproducible packaging, and clean-machine verification are release prerequisites rather than follow-up work.

## 2. Supported product boundary

### 2.1 Supported

- Minecraft server: Paper 26.2 only.
- Java runtime and compiler: Java 25 or newer, with Java 25 used for release builds and primary verification.
- Plugin version: `1.1.0`.
- Release tag: `plugin-v1.1.0`.
- Release artifact: `mcav-paper-1.1.0.jar`.
- VJ server: Python 3.11 or newer, installed from source.
- VJ-to-Paper transport: authenticated WebSocket on an explicit loopback address.
- Client compatibility: vanilla Java Edition clients supported by Paper 26.2.
- Optional integrations: Geyser, Floodgate, and Simple Voice Chat may be detected when installed, but their absence cannot prevent plugin enablement.

### 2.2 Not supported by this release

- Paper, Spigot, or Minecraft 1.21.x compatibility.
- A dual-version Paper compatibility adapter.
- Fabric distribution or Fabric feature parity.
- Bedrock client compatibility claims.
- DJ desktop installers, auto-update, or signed desktop packages.
- Docker images or Docker-based distribution.
- A packaged VJ server binary.
- The v2 platform architecture.
- The `sbe.v1` low-latency render plane as a release prerequisite.

The existing low-latency design and its dirty implementation worktree must be preserved. If it becomes release-ready independently, it may be integrated only after it is rebased to Java 25, passes every gate in this document, and retains JSON fallback. The Paper 26.2 release must not be delayed by it.

## 3. Authoritative platform versions

- Paper API dependency: `io.papermc.paper:paper-api:26.2.build.112-stable`.
- Plugin metadata: `api-version: '26.2'`.
- Maven compiler release: `25`.
- Release build JDK: Temurin 25.

Paper's current project setup documentation requires Java 25 or newer, and its plugin metadata documentation supports the `26.2` API version:

- https://docs.papermc.io/paper/dev/project-setup/
- https://docs.papermc.io/paper/dev/plugin-yml/
- https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml

The release branch must not use the moving Paper snapshot coordinate.

## 4. Runtime architecture

The release preserves the existing ownership boundary:

1. A source-built DJ client or another compatible producer sends audio state to the VJ server.
2. The VJ server remains authoritative for show state, Lua pattern evaluation, reconnect rehydration, and control-plane behavior.
3. `VizClient` connects to the Paper plugin on loopback, authenticates, and sends bounded JSON protocol messages.
4. The plugin validates and classifies messages off the server thread where safe.
5. Bukkit and Paper world/entity mutations occur only on the server main thread.
6. The plugin owns visualization zones, entity pools, permissions, lifecycle cleanup, and Minecraft-facing diagnostics.

The hosted site, coordinator, Discord services, and community bot are not required for a local show.

## 5. Git recovery and repository integrity

Git recovery is phase G0 and happens before product implementation.

### 5.1 Preservation rules

- Never delete a stash, worktree, branch, untracked file, tag, or reflog entry during recovery.
- Never run destructive reset, checkout, clean, prune, or aggressive garbage collection commands.
- Record the commit, status, diff summary, and untracked paths for every existing worktree.
- Record both stashes, including their base commits, tracked diffs, and any relationship to root untracked files.
- Exclude local Codex state under `.codex/` and `.superpowers/` from product recovery commits.
- Do not treat `AGENTS.md` as product work unless the user separately approves adding it.

### 5.2 Recovery outputs

- A committed recovery ledger at `docs/superpowers/reports/2026-08-15-git-recovery-ledger.md`.
- A pushed recovery branch for the `stash@{0}` refactor plus its matching product-source untracked files.
- A pushed recovery branch for `stash@{1}` security work.
- Pushed snapshot commits for each dirty linked worktree.
- A pushed snapshot branch for remaining root product untracked files that do not belong to `stash@{0}`.
- A final mapping from every pre-recovery local change to a commit and remote branch.

Cleanup remains a separate, reviewed action. Recovery completion does not authorize dropping stashes or removing worktrees.

### 5.3 Release integration branch

- Integration branch: `release/paper-26.2`.
- Base: the newest preserved local `main` commit, currently `6d3da5e`.
- Handwritten changes are rebased before review.
- Dependency-only work is grouped by ecosystem and may be squash-merged.
- Every commit is atomic and uses conventional commit messages.
- Unrelated service-layer, UI-split, and DJ-client refactors do not enter the release branch.

## 6. GitHub governance

### 6.1 Main branch

The active `main` ruleset must require:

- A pull request.
- All review conversations resolved.
- The branch updated with current `main` before merge.
- Required checks `CI Passed` and `Security Summary`.
- No force pushes.
- No deletion.

The current repository-role bypass is removed. A solo-maintainer repository cannot truthfully require an independent approval from its only maintainer, so the ineffective one-approval rule is replaced by required checks, required conversation resolution, and an explicit release-environment approval. The repository may add an independent reviewer later without redesigning the release process.

### 6.2 Tag provenance

- `plugin-v*` tags are immutable after creation.
- Only the GitHub Actions integration may bypass the tag-creation restriction.
- The integration bypass is limited to the tag ruleset; it does not bypass `main`.
- Only the `release-plugin.yml` tag-creation job receives `contents: write`.
- That job uses the protected `plugin-release` environment and requires human approval.
- Other release workflows remain quarantined and cannot create Paper, Fabric, DJ, Docker, or generic `v*` releases.

### 6.3 Workflow supply chain

- Every third-party or GitHub-owned action is pinned to a full 40-character commit SHA with its release tag retained in a comment.
- Workflow-level permissions default to empty or read-only.
- Write permissions are granted per job only where required.
- Checkout credentials are not persisted in untrusted jobs.
- Release output is built once and promoted without rebuilding different bytes.

### 6.4 Dependency automation

- Dependabot security updates are enabled.
- Routine updates are grouped per ecosystem and component.
- Open-PR limits are reduced to keep the queue reviewable.
- Existing dependency PRs are classified as superseded, cleanly rebasable, conflicting, or security-critical.
- Branches and PRs are not bulk-deleted without a reviewed target list.
- Zero unresolved critical or high vulnerabilities is a release gate for every audited repository component, even if that component is not distributed in this release.

### 6.5 Local repository defaults

Repository-local Git configuration sets:

- `fetch.prune=true`
- `pull.rebase=true`
- `rebase.autoStash=true`
- `rerere.enabled=true`
- `rerere.autoupdate=true`

Project worktrees live under ignored `.worktrees/` directories. No automatic worktree removal is introduced.

## 7. Paper 26.2 port

### 7.1 Build metadata

- Update the Paper API, compiler release, plugin API version, and project version together.
- Use Maven's `release` setting instead of separate source/target values.
- Produce the final file name `mcav-paper-${project.version}.jar`.
- Never generate or modify `dependency-reduced-pom.xml` in the source tree.
- Keep Paper and optional integration APIs out of the shaded JAR.
- Continue relocating embedded WebSocket and Gson packages.

### 7.2 API compatibility audit

Compile and test every Paper-facing area, including:

- Display entity creation and transforms.
- Material and particle APIs.
- Scheduler and asynchronous boundaries.
- Commands, permissions, and inventory menus.
- Chat events and Adventure text APIs.
- Scoreboards and player metadata.
- World load/unload behavior.
- Optional Geyser, Floodgate, and Simple Voice Chat integrations.

Deprecated or removed APIs are migrated directly to their Paper 26.2 equivalents. No reflection-based version adapter is added.

### 7.3 Test framework

MockBukkit remains only if a release compatible with the Paper 26.2 API is available and proves useful. Tests that cannot accurately model 26.2 behavior move to focused Mockito tests or the real Paper integration harness. Existing coverage is preserved; incompatible tests are migrated, not deleted to make the build green.

## 8. Secure first run and connection behavior

### 8.1 Secret generation

- On first enable, an empty or whitespace-only `ws-secret` is replaced with a cryptographically random 32-byte secret encoded as URL-safe Base64 without padding.
- The generated secret is persisted to the plugin's private configuration before the WebSocket listener starts.
- The secret is never written to normal logs, exception messages, metrics, status payloads, or GitHub artifacts.
- The console tells the operator where to retrieve it and how to pass it as `MINECRAFT_WS_SECRET` without printing the value.
- Existing non-empty secrets are preserved byte-for-byte after surrounding whitespace validation.

### 8.2 Listener and authentication

- Only explicit loopback addresses are accepted.
- Remote VJ connectivity uses an encrypted tunnel terminating on loopback.
- Authentication completes before heartbeat, renderer, control, or visualization messages are accepted.
- Invalid authentication, malformed JSON, and oversize frames produce sanitized diagnostics.
- A startup bind failure leaves the plugin enabled in diagnostic-only mode with no active visual renderer.

### 8.3 Backpressure

- High-frequency transient render updates use bounded latest-state/drop-oldest behavior.
- Durable control operations are never silently discarded.
- Queue depth, accepted frames, rejected frames, and dropped transient frames are observable without exposing payloads or secrets.

### 8.4 Disconnect and lifecycle

- A VJ disconnect starts a configurable grace period.
- Reconnection within the grace period cancels pending cleanup and requires a fresh authentication handshake and complete state rehydration.
- Expiry hides or removes active visualization entities while preserving saved zones and stages.
- World unload removes entities for that world.
- World reload permits later full rehydration.
- Plugin disable, Paper shutdown, failed startup, and uninstall leave no plugin-owned tasks, threads, sockets, entities, or player references.

## 9. Verification

### 9.1 Automated tests

- Plugin unit and lifecycle tests pass on Java 25.
- VJ server tests pass in WSL or Linux with Python 3.11 or newer.
- JSON protocol schema tests pass.
- Authentication, secret generation, frame limits, queue limits, reconnect, state rehydration, world unload, shutdown, and optional-integration absence have explicit coverage.
- Paper dependency checks fail closed on scanner errors and on critical/high findings.
- Every required CI and security summary job succeeds for the exact candidate commit on `main`.

### 9.2 Real Paper integration

A disposable Paper 26.2 server must verify:

1. Plugin load on Java 25.
2. First-run secret generation and persistence.
3. Authenticated VJ connection.
4. Zone and entity-pool initialization.
5. A representative JSON visualization update producing visible display entities.
6. Disconnect and reconnect with full state rehydration.
7. World unload cleanup.
8. Graceful shutdown and restart without orphan entities.
9. Bad-secret, malformed-frame, oversized-frame, VJ crash, Paper restart, and port-conflict behavior.

### 9.3 Performance and soak gates

Using a documented reference machine and a representative 256-display-entity scene:

- Sustained server rate is 20 TPS, with 19.8 TPS the minimum accepted sample.
- Same-host p95 VJ-to-visible-entity latency is at most 100 ms.
- Plugin p95 main-thread update time is at most 10 ms.
- Queues remain within configured caps and dropped transient frames are counted.
- An eight-hour show with reconnects and operator changes completes without hangs, leaks, or orphan entities.
- Heap, thread, queue, and entity counts return close to their recorded baseline after teardown.

The experimental binary renderer has its own stricter 3 ms adapter budget, but that work is not required for this JSON release.

## 10. Packaging and publication

### 10.1 Candidate

The candidate workflow runs on an exact `main` commit after CI and security success and produces:

- `mcav-paper-1.1.0.jar`.
- `SHA256SUMS.txt`.
- A CycloneDX JSON SBOM for the plugin.
- GitHub artifact provenance/attestation.
- Installation, pairing, compatibility, rollback, and known-limitations documentation.

Candidate bytes are retained and used for clean-machine, failure, performance, and soak verification.

### 10.2 Publication

After protected-environment approval, the publication job:

1. Verifies the candidate commit is exactly current `main`.
2. Verifies successful `CI Passed` and `Security Summary` push runs for that SHA.
3. Downloads the retained candidate.
4. Verifies its checksum and attestation.
5. Creates immutable tag `plugin-v1.1.0` at that SHA.
6. Publishes the same candidate bytes without rebuilding.

The GitHub release title is `MCAV Paper Plugin 1.1.0`. Release notes state Paper 26.2 and Java 25+ without claiming Spigot, Purpur, Fabric, Bedrock, or 1.21 compatibility.

## 11. Rollback

- Published tags and assets are never modified.
- A faulty release is deprecated or withdrawn rather than silently replaced.
- A correction uses a new patch version such as `plugin-v1.1.1`.
- Installation documentation requires a configuration and world backup.
- Uninstall and rollback instructions verify plugin-owned entity cleanup.
- The Paper 1.21 artifact is historical and is not a rollback artifact for a Paper 26.2 server.

## 12. Release gates

Publication is prohibited until all gates pass:

1. G0: every local change is mapped to a durable commit and remote recovery branch.
2. G1: GitHub rulesets, action pinning, dependency automation, and workflow permissions are repaired and verified.
3. G2: the Paper 26.2 port builds and passes unit, protocol, and real-server integration tests.
4. G3: all required CI and security scans are green with zero unresolved critical/high findings.
5. G4: performance targets and the eight-hour soak pass.
6. G5: clean-machine install, pairing, recovery, shutdown, uninstall, and rollback documentation pass.
7. G6: checksum, SBOM, provenance, candidate identity, and release notes are reviewed.
8. G7: a human approves the protected release environment and only then may the immutable tag be created.

The number of open dependency PRs is not itself a release blocker once the underlying dependency state is secure and the queue has been classified.

## 13. Definition of done

This project is release-ready only when:

- Every pre-existing local Git change is preserved and traceable.
- The release branch contains no unrelated recovered refactor work.
- The repository prevents unreviewed main updates and unproven release tags.
- The plugin compiles and runs against Paper 26.2 with Java 25.
- First-run authentication is secure by default.
- A source-installed VJ server drives a real Paper visualization over authenticated loopback WebSocket.
- Failure, lifecycle, performance, soak, and cleanup gates pass.
- The exact tested JAR has checksum, SBOM, and provenance.
- A clean-machine administrator can install, pair, operate, recover, and uninstall using only published documentation.
- `plugin-v1.1.0` and its GitHub release contain the verified candidate bytes.
