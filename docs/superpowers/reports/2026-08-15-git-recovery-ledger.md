# Git Recovery Ledger — 2026-08-15

## Purpose and safety rules

This ledger records the authoritative pre-release Git state before any recovery or governance mutation for the Paper 26.2 release. Recovery is additive and non-destructive: copy or apply work into named branches, commit it, push it, and retain the original stash, worktree, branch, and untracked source until a separate deletion list is reviewed and approved.

The release worktree is `C:\Users\Ryan\Desktop\minecraft-audio-viz\.worktrees\phase0-containment` on `release/paper-26.2`. Its baseline commit for this ledger is `396a5cef190812bdcf6f203233f4ee6f062354d7`.

## Repository baseline

- Primary repository: `C:\Users\Ryan\Desktop\minecraft-audio-viz`
- Primary branch: `main` at `6d3da5e`, three commits ahead of `origin/main` at `1a1c94d`
- Release branch: `release/paper-26.2` at `396a5ce`, five commits ahead of `origin/main`
- Object integrity: `git fsck --full --no-reflogs` exited successfully; dangling objects were reported, but no connectivity or corruption errors were found
- Primary worktree: 74 untracked paths total
- Product recovery scope: 47 untracked paths
- Excluded local-tool scope: 27 untracked paths under `.codex/`, `.superpowers/`, plus root `AGENTS.md`
- Destructive cleanup authorized: none

## Stashes

|Source|Object and base|Contents|Recovery target|Status|
|-|-|-|-|-|
|`stash@{0}`|stash `789d63a479ed182bd61ab86e6e549cd3c9190d1e`; base `d770a8203899e57b1bcc16f7104f6ddb79f07a4d`; index `183152f407e85dd82ae7387e4f1c9efc01fbeead`|56 tracked files; 1,637 insertions and 9,795 deletions; paired with untracked admin, coordinator, plugin-handler, and VJ relay-test files|`recovery/stash-0-refactor` at `0b100510046dc261ef9d42183f97b7f0ca234834`|Preserved and pushed; stash retained|
|`stash@{1}`|stash `728505647db03c11d23c7d09ae43615829dbf18c`; base `139c177735da53b3dbe27472ef63e3fd4f169ece`; index `ee53cc369bf0c2444ca2936aaa9a261286c762f4`|9 dependency/security files; 229 insertions and 241 deletions|`recovery/stash-1-security` at `f76c1dbc1697291b8cefc7381f44dc21d4433a77`|Preserved and pushed; stash retained|

Both stashes must remain present after preservation.

## Product untracked paths in the primary worktree

The following paths are evidence-bearing product files. They are copied only to their named recovery target; the originals remain untouched.

### Paired with `stash@{0}`

```text
admin_panel/js/managers/BitmapManager.js
admin_panel/js/managers/PreviewManager.js
admin_panel/js/modules/ActionsManager.js
admin_panel/js/modules/AudioManager.js
admin_panel/js/modules/BannerManager.js
admin_panel/js/modules/ConnectCodeManager.js
admin_panel/js/modules/DJManager.js
admin_panel/js/modules/ElementCache.js
admin_panel/js/modules/EventWiring.js
admin_panel/js/modules/InitialState.js
admin_panel/js/modules/MessageRouter.js
admin_panel/js/modules/ParticleEffectsManager.js
admin_panel/js/modules/PatternManager.js
admin_panel/js/modules/SceneManager.js
admin_panel/js/modules/UIHelpers.js
admin_panel/js/modules/VoiceChatManager.js
admin_panel/js/modules/ZoneManager.js
admin_panel/js/ui/ModalDialog.js
coordinator/app/dependencies/server.py
coordinator/app/services/connect_service.py
coordinator/app/services/exceptions.py
coordinator/app/services/show_service.py
coordinator/tests/test_audit.py
coordinator/tests/test_auth_service.py
coordinator/tests/test_connect_service.py
coordinator/tests/test_discord_bot_notifier.py
coordinator/tests/test_discord_oauth.py
coordinator/tests/test_email_service.py
coordinator/tests/test_google_oauth.py
coordinator/tests/test_org_service.py
coordinator/tests/test_r2_storage.py
coordinator/tests/test_rate_limiter.py
coordinator/tests/test_server_service.py
coordinator/tests/test_show_service.py
minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/BaseHandler.java
minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/BitmapCoreHandler.java
minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/BitmapEffectsHandler.java
minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/CoreEntityHandler.java
minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/DjInfoHandler.java
minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/ParticleHandler.java
minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/RenderModeHandler.java
minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/StageHandler.java
minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/TypedMessageHandler.java
minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/VoiceHandler.java
vj_server/tests/test_relay.py
```

### Separate root-document recovery

```text
docs/plans/2026-03-01-three-features-impl.md
docs/superpowers/plans/2026-03-31-security-hardening.md
```

Target: `recovery/root-untracked-2026-08-15`.

## Excluded local-tool paths

The 27 paths under `.codex/`, `.superpowers/`, and root `AGENTS.md` are local operating state or instructions. They are intentionally excluded from product recovery and remain untouched in the primary worktree.

## Worktrees and local branches

|Branch|State at inventory|Preservation target|Status|
|-|-|-|-|
|`main`|Primary worktree; 74 untracked paths; at `6d3da5e`|No mutation; product paths copied to recovery branches|Unmapped|
|`release/paper-26.2`|Clean at `396a5ce`|Release execution branch|Preserved locally|
|`chore/dj-client-major-deps`|Clean at `a012b52`; upstream configuration points at `origin/main`|Push exact head after reachability audit|Unmapped|
|`feat/site-real-tests`|Clean at `825a17d`|Push exact head after reachability audit|Unmapped|
|`fix/remove-drei-ghost-dep`|Clean at `dbccbda`|Push exact head after reachability audit|Unmapped|
|`refactor/split-dj-client-app`|Dirty lockfile: 2 insertions and 2 deletions|Commit and push exact branch|Unmapped|
|`fix/a11y-focus-motion-labels`|Clean at `3743ae3`|Push exact head after reachability audit|Unmapped|
|`fix/error-handling-and-abort-controllers`|Dirty across four coordinator files: 23 insertions and 11 deletions|Verify, commit, and push exact branch|Unmapped|
|`refactor/coordinator-service-layer`|Clean at `a2aa80d`|Push exact head after reachability audit|Unmapped|
|`feature/low-latency-renderer`|Initially at `ed25bc3` with two untracked render tests; concurrent work committed the tests and their implementation as `a90d3b6`|Exact branch pushed at `a90d3b64387a7fad567dabce1ff95aab29c12951`|Preserved and pushed|
|`chore/security-hardening`|Local at `0821754`, one commit ahead of `origin/chore/security-hardening` at `e00c9a2`; PR 143 is dirty|Preserve local head as `recovery/security-hardening-local` without changing PR 143|Unmapped|

The low-latency branch includes these commits beyond `main`:

- `038b416 feat: canonicalize render protocol limits`
- `434d560 feat: add bounded render mailboxes`
- `ed25bc3 fix: stabilize connection message guards`

Its two untracked tests are:

```text
minecraft_plugin/src/test/java/com/audioviz/render/JsonRenderFrameDecoderTest.java
minecraft_plugin/src/test/java/com/audioviz/render/RenderFrameHubTest.java
```

## Remote pull requests and governance

- 47 open pull requests: 45 Dependabot, 2 authored
- 28 blocked and 19 dirty
- Authored PR 142: `chore/dj-client-major-deps`, dirty
- Authored PR 143: `chore/security-hardening`, dirty
- Secret scanning and push protection are enabled
- Workflow default permissions do not allow GitHub Actions to approve pull-request reviews
- Existing rulesets:
  - `18833547` — Paper/Fabric tag immutability
  - `18824190` — Phase0 release tag provenance
  - `12627523` — protect `main`
- Current tag creation restrictions prevent creating `plugin-v1.1.0`; governance repair must be applied and verified before release promotion
- Existing GitHub Actions are not yet fully SHA-pinned

## Recovery mapping log

|Source|Destination|Verification|Result|
|-|-|-|-|
|Baseline inventory|This ledger on `release/paper-26.2`|Review and atomic commit|Pending|
|`stash@{0}` plus paired product paths|`recovery/stash-0-refactor`|Remote head equals `0b100510046dc261ef9d42183f97b7f0ca234834`; all 45 copied files match their primary-worktree SHA-256; stash object remains present|Preserved|
|`stash@{1}`|`recovery/stash-1-security`|Remote head equals `f76c1dbc1697291b8cefc7381f44dc21d4433a77`; committed tree exactly equals stash tree; stash object remains present|Preserved|
|Low-latency untracked tests|`feature/low-latency-renderer`|Concurrent commit `a90d3b6` contains both tests; Java 25 focused suite passed 12 tests and full suite passed 978 tests; remote head equals `a90d3b64387a7fad567dabce1ff95aab29c12951`|Preserved|
|Split-DJ dirty lockfile|`refactor/split-dj-client-app`|Lock integrity, commit, push|Pending|
|Coordinator error-handling changes|`fix/error-handling-and-abort-controllers`|Relevant coordinator tests, commit, push|Pending|
|Clean/local-only branch heads|Exact branch or named recovery ref|Remote contains exact commit|Pending|
|Two root untracked documents|`recovery/root-untracked-2026-08-15`|Commit, push, originals retained|Pending|

## Cleanup boundary

No stash drop, worktree removal, branch deletion, untracked-file deletion, pull-request closure, force push, history rewrite, or tag mutation is authorized by this recovery pass. A later cleanup proposal must list exact targets and prove each target has an equivalent remote commit before asking for approval.

## Recovery notes

- The `stash@{0}` preservation commit intentionally retained the historical snapshot byte-for-byte. Repository hooks that would alter that snapshot were skipped: trailing whitespace, Ruff, and Ruff format. Bandit was also skipped because it flags 71 low-severity hardcoded test credentials in the recovered coordinator tests. Site ESLint and TypeScript hooks were skipped because this isolated historical-base worktree has no installed site dependencies; TypeScript therefore reported missing React, Next, Three.js, and Vitest modules. Non-mutating YAML, JSON, large-file, merge-conflict, private-key, and end-of-file hooks passed.
- The `stash@{1}` preservation commit also retained the exact stash tree. The end-of-file hook attempted to rewrite four generated Tauri schema files, so those hook changes were discarded from the recovery worktree and the mutating hook was skipped. JSON, large-file, merge-conflict, and private-key checks passed.
- While recovery was in progress, concurrent work advanced `feature/low-latency-renderer` from `ed25bc3` to `a90d3b6` and committed the two formerly untracked tests alongside four render implementation classes. Recovery adopted the new clean head, verified it on Java 25, and pushed that exact commit without rewriting it.
