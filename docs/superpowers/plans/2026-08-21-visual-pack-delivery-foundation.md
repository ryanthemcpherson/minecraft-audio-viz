# Visual Pack Delivery Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a production-safe signed Visual Pack registry, validator, persistent cache, authenticated staging controls, version pinning, and rollback transaction to the existing Pterodactyl VJ runtime so future declarative visual runtimes can receive content without replacing the deployment ZIP.

**Architecture:** A focused `vj_server.visual_packs` package owns immutable pack verification, static-HTTPS registry access, persistent content storage, and serialized lifecycle operations. The existing authenticated browser WebSocket exposes status and operator commands, while runtime activation is behind an explicit `PackRuntimeActivator` interface; the initial adapter activates only non-executable catalog content and rejects unsupported ShowIR rather than executing downloaded Lua. The forthcoming ShowIR runtime registers a second adapter without changing the registry, cache, admin workflow, or pack contract.

**Tech Stack:** Python 3.11+, `msgspec`, `cryptography` Ed25519, standard-library `zipfile` and `urllib`, existing WebSocket control plane, vanilla ES modules, pytest, Node test runner, Pterodactyl portable runtime.

**Spec:** `docs/superpowers/specs/2026-08-21-visual-pack-delivery-design.md`

## Global Constraints

- Published packs cannot contain executable Lua, Python, JavaScript, Java, WASM, native libraries, or unrestricted shader source.
- Downloads stage automatically, but activation remains an authenticated one-click action unless an explicit maintenance-window policy is added in a later plan.
- Stock Java remains complete; optional Java resource-pack and Bedrock/Geyser assets fail back independently.
- The active pack remains usable offline and is never overwritten in place.
- All archive input is untrusted and bounded before extraction or allocation.
- Registry and filesystem work never runs on the real-time render path.
- The existing `patterns/*.lua` hot-reload behavior remains a local legacy facility and is not populated from the registry.
- This foundation must ship once in a core release; subsequent compatible pack updates use the registry path only.
- This plan does not port the Visual Lab evaluator into Python. New behavioral visuals become activatable when the separately reviewed ShowIR runtime implements `PackRuntimeActivator`.

---

## File structure

- `protocol/schemas/visual-packs/manifest-v1.schema.json`: source-of-truth pack manifest schema.
- `protocol/schemas/visual-packs/channel-v1.schema.json`: source-of-truth signed channel index schema.
- `vj_server/visual_packs/types.py`: immutable domain types and bounded parse functions.
- `vj_server/visual_packs/signing.py`: canonical JSON and Ed25519 verification.
- `vj_server/visual_packs/archive.py`: ZIP validation and safe staged extraction.
- `vj_server/visual_packs/store.py`: persistent paths, atomic metadata, retention, and recovery.
- `vj_server/visual_packs/registry.py`: bounded static-HTTPS client.
- `vj_server/visual_packs/runtime.py`: activation protocol and safe catalog adapter.
- `vj_server/visual_packs/manager.py`: serialized check, stage, activate, pin, and rollback orchestration.
- `vj_server/visual_packs/messages.py`: browser command parsing and status serialization.
- `admin_panel/js/modules/VisualPackManager.js`: updates UI state and authenticated operator actions.
- `deploy/pterodactyl/trusted-pack-keys.json`: initial trusted public keys and key IDs.

---

### Task 1: Define and verify signed metadata

**Files:**
- Create: `protocol/schemas/visual-packs/manifest-v1.schema.json`
- Create: `protocol/schemas/visual-packs/channel-v1.schema.json`
- Modify: `protocol/schemas/index.json`
- Create: `vj_server/visual_packs/__init__.py`
- Create: `vj_server/visual_packs/types.py`
- Create: `vj_server/visual_packs/signing.py`
- Test: `vj_server/tests/test_visual_pack_signing.py`
- Modify: `vj_server/pyproject.toml`
- Modify: `uv.lock`

**Interfaces:**
- Produces immutable value types: `PackRef`, `PackFile`, `VisualEntry`, `PackManifest`, `ChannelManifest`, `PackCandidate`, `InstalledPack`, `PackStatus`, and `AuditEvent`
- Produces: `parse_pack_manifest(payload: bytes) -> PackManifest`
- Produces: `parse_channel_manifest(payload: bytes) -> ChannelManifest`
- Produces: `canonical_json(value: object) -> bytes`
- Produces: `TrustedKeyRing.from_json(payload: bytes) -> TrustedKeyRing`
- Produces: `TrustedKeyRing.verify(key_id: str, payload: bytes, signature: bytes) -> None`

- [ ] **Step 1: Write schema and parser RED tests**

Cover duplicate visual IDs, unknown fields, non-semantic versions, missing stock-Java fallback, unsafe member names, non-finite budgets, excessive collection counts, and canonical JSON stability:

```python
def test_manifest_rejects_duplicate_visual_ids() -> None:
    payload = manifest_bytes(visuals=[visual("mcav:skull"), visual("mcav:skull")])
    with pytest.raises(ManifestError, match="duplicate visual id"):
        parse_pack_manifest(payload)


def test_canonical_json_is_order_independent() -> None:
    assert canonical_json({"b": 2, "a": 1}) == b'{"a":1,"b":2}'
```

- [ ] **Step 2: Run the focused tests and confirm RED**

Run from WSL:

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
../.venv/bin/pytest tests/test_visual_pack_signing.py -q
```

Expected: collection fails because `vj_server.visual_packs` does not exist.

- [ ] **Step 3: Add strict types and canonical parsing**

Use frozen `msgspec.Struct` types with `forbid_unknown_fields=True`. Enforce these v1 hard limits in one `ManifestLimits` value: 1 MiB manifest, 256 visuals, 5 profiles per visual, 4096 members, 256 UTF-8 bytes per member path, and unsigned 64-bit byte sizes. Validate semantic versions with a small explicit parser; do not add a version library.

```python
class PackManifest(msgspec.Struct, frozen=True, forbid_unknown_fields=True):
    schema_version: str
    pack_id: str
    version: str
    core_compatibility: VersionRange
    protocol_version: str
    files: tuple[PackFile, ...]
    visuals: tuple[VisualEntry, ...]
    signing_key_id: str
    published_at: str
```

- [ ] **Step 4: Add Ed25519 verification**

Add `cryptography>=45.0,<47.0` to the VJ package and locked portable runtime. Key-ring JSON contains `generation`, `minimum_registry_generation`, and base64-encoded 32-byte Ed25519 public keys. Reject unknown, revoked, malformed, or not-yet-valid keys before calling `Ed25519PublicKey.verify`.

- [ ] **Step 5: Run parser, signature, schema-index, and dependency-lock tests**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
../.venv/bin/pytest tests/test_visual_pack_signing.py -q
cd ..
node --test protocol/tests/phase0-schemas.test.mjs
```

Expected: all pass.

- [ ] **Step 6: Commit the metadata contract**

```bash
git add protocol/schemas/visual-packs protocol/schemas/index.json vj_server/visual_packs vj_server/tests/test_visual_pack_signing.py vj_server/pyproject.toml uv.lock
git commit -m "feat(vj): define signed visual pack metadata"
```

---

### Task 2: Validate and extract immutable pack archives

**Files:**
- Create: `vj_server/visual_packs/archive.py`
- Test: `vj_server/tests/test_visual_pack_archive.py`
- Modify: `vj_server/cli.py`
- Test: `vj_server/tests/test_cli_visual_pack.py`

**Interfaces:**
- Consumes: `PackManifest`, `TrustedKeyRing`
- Produces: `ArchiveLimits`
- Produces: `VerifiedPack(archive_path: Path, manifest: PackManifest, archive_sha256: str)`
- Produces: `verify_pack_archive(path: Path, keys: TrustedKeyRing, limits: ArchiveLimits) -> VerifiedPack`
- Produces: `extract_verified_pack(pack: VerifiedPack, destination: Path, limits: ArchiveLimits) -> None`
- Produces: `verification_summary(pack: VerifiedPack) -> dict[str, object]`
- Produces CLI: `audioviz-vj --verify-pack PATH --trusted-keys PATH`

- [ ] **Step 1: Write adversarial ZIP RED tests**

Build in-memory fixtures for `../escape`, absolute paths, backslashes, drive prefixes, Unicode/case collisions, duplicates, symlinks through `external_attr`, encrypted entries, unsupported compression, excessive entry count, excessive expanded size, size mismatch, hash mismatch, missing manifest coverage, extra members, and a compression ratio above 100:1.

```python
@pytest.mark.parametrize("member", ["../x", "/x", "C:/x", "a\\b"])
def test_archive_rejects_noncanonical_members(tmp_path: Path, member: str) -> None:
    archive = write_pack(tmp_path, extra_member=member)
    with pytest.raises(PackArchiveError):
        verify_pack_archive(archive, key_ring(), ArchiveLimits.production())
```

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
../.venv/bin/pytest tests/test_visual_pack_archive.py -q
```

Expected: import failure for `visual_packs.archive`.

- [ ] **Step 3: Implement two-pass archive verification**

Pass one reads central-directory metadata and rejects unsafe structure without extracting. Pass two streams every member through SHA-256 with a bounded reader and compares size/hash against `manifest.json`. Verify `signature.ed25519` over canonical manifest bytes. Never call `ZipFile.extract` or `extractall`.

- [ ] **Step 4: Implement safe staged extraction**

Create regular files with exclusive creation beneath a resolved staging root, stream at most the manifest-declared size, flush each file, and reject destination aliases. Delete only the unique staging directory created by this operation when extraction fails.

- [ ] **Step 5: Add the offline verification command**

The command reads no server credentials and starts no sockets. On success it prints one JSON object containing `pack_id`, `version`, `archive_sha256`, `visual_count`, and `profiles`; on failure it prints a bounded diagnostic to stderr and exits nonzero.

```python
if args.verify_pack is not None:
    keys = TrustedKeyRing.from_json(args.trusted_keys.read_bytes())
    result = verify_pack_archive(args.verify_pack, keys, ArchiveLimits.production())
    print(json.dumps(verification_summary(result), sort_keys=True))
    return 0
```

- [ ] **Step 6: Run archive and CLI tests and commit**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
../.venv/bin/pytest tests/test_visual_pack_archive.py tests/test_cli_visual_pack.py -q
cd ..
git add vj_server/visual_packs/archive.py vj_server/tests/test_visual_pack_archive.py vj_server/cli.py vj_server/tests/test_cli_visual_pack.py
git commit -m "feat(vj): validate visual pack archives safely"
```

---

### Task 3: Add persistent content storage and crash recovery

**Files:**
- Create: `vj_server/visual_packs/store.py`
- Test: `vj_server/tests/test_visual_pack_store.py`
- Modify: `vj_server/pterodactyl.py`
- Modify: `vj_server/tests/test_pterodactyl.py`

**Interfaces:**
- Produces: `ContentPaths.from_state_dir(state_dir: Path) -> ContentPaths`
- Produces: `PackStore.install(verified: VerifiedPack) -> InstalledPack`
- Produces: `PackStore.read_state() -> PackStoreState`
- Produces: `PackStore.commit_activation(new: PackRef, previous: PackRef | None, operation_id: str) -> None`
- Produces: `PackStore.pin(pack: PackRef | None) -> None`
- Produces: `PackStore.append_audit(event: AuditEvent) -> None`
- Produces: `PackStore.recover() -> RecoveryReport`
- Produces: `PackStore.prune(retain: int = 3) -> tuple[PackRef, ...]`

- [ ] **Step 1: Write transaction and restart RED tests**

Test first install, idempotent reinstall, immutable-version collision, interrupted download cleanup, interrupted staging cleanup, active metadata replacement, rollback preservation, pin retention, active/rollback protection, bounded audit records, redaction of secrets and paths, and recovery from a leftover transaction journal.

```python
def test_prune_never_removes_active_rollback_or_pinned(store: PackStore) -> None:
    seed_versions(store, ["1.0.0", "1.1.0", "1.2.0", "1.3.0", "1.4.0"])
    store.commit_activation(ref("1.4.0"), ref("1.3.0"), "op-7")
    store.pin(ref("1.0.0"))
    removed = store.prune(retain=1)
    assert ref("1.4.0") not in removed
    assert ref("1.3.0") not in removed
    assert ref("1.0.0") not in removed
```

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
../.venv/bin/pytest tests/test_visual_pack_store.py tests/test_pterodactyl.py -q
```

- [ ] **Step 3: Implement atomic state and recovery**

Use same-directory temporary files, `flush`, `os.fsync`, and `os.replace` for JSON metadata. An activation journal records `operation_id`, `previous`, `candidate`, and phase. Recovery treats `active.json` as authoritative after a completed atomic replacement and otherwise restores `previous`; it never guesses from directory mtimes.

- [ ] **Step 4: Extend Pterodactyl bootstrap**

Create `state/content`, `downloads`, `staging`, and `packs` with owner-only write permissions without changing or rotating existing credentials. Repeated bootstrap remains idempotent.

- [ ] **Step 5: Run tests and commit**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
../.venv/bin/pytest tests/test_visual_pack_store.py tests/test_pterodactyl.py -q
cd ..
git add vj_server/visual_packs/store.py vj_server/tests/test_visual_pack_store.py vj_server/pterodactyl.py vj_server/tests/test_pterodactyl.py
git commit -m "feat(vj): persist visual packs transactionally"
```

---

### Task 4: Fetch signed channels and stage updates off the render path

**Files:**
- Create: `vj_server/visual_packs/registry.py`
- Create: `vj_server/visual_packs/manager.py`
- Test: `vj_server/tests/test_visual_pack_registry.py`
- Test: `vj_server/tests/test_visual_pack_manager.py`
- Modify: `vj_server/config.py`
- Modify: `vj_server/cli.py`
- Modify: `vj_server/tests/test_config.py`

**Interfaces:**
- Produces: `RegistryClient.check(channel: str, etag: str | None) -> RegistryResult`
- Produces: `RegistryClient.download(candidate: PackCandidate, destination: Path) -> DownloadResult`
- Produces: `VisualPackManager.check() -> PackStatus`
- Produces: `VisualPackManager.stage(ref: PackRef | None = None) -> PackStatus`
- Produces: `VisualPackManager.pin(ref: PackRef | None) -> PackStatus`
- Produces: `VisualPackManager.snapshot() -> PackStatus`

- [ ] **Step 1: Write bounded-network and concurrency RED tests**

Use a local `ThreadingHTTPServer` fixture to prove TLS-policy rejection for non-loopback HTTP, redirect cap, timeout behavior, maximum manifest bytes, maximum pack bytes, ETag/304 caching, streamed hashing, interrupted download cleanup, channel isolation, semantic version selection, exact pinning, and newest-started-operation status ordering.

```python
async def test_overlapping_checks_cannot_publish_stale_status(manager: VisualPackManager) -> None:
    first = asyncio.create_task(manager.check())
    await registry.wait_until_first_started()
    second = asyncio.create_task(manager.check())
    registry.finish_second_then_first()
    await asyncio.gather(first, second)
    assert manager.snapshot().registry_generation == 2
```

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
../.venv/bin/pytest tests/test_visual_pack_registry.py tests/test_visual_pack_manager.py tests/test_config.py -q
```

- [ ] **Step 3: Implement the static registry client**

Use `urllib.request` in `asyncio.to_thread`, never on the event loop. Permit HTTPS and loopback HTTP only in explicit development mode. Read responses in 64 KiB chunks with hard byte limits, at most three redirects, hostname verification, and no credential-bearing URL support.

- [ ] **Step 4: Implement serialized manager operations**

An `asyncio.Lock` protects mutating lifecycle operations. Each operation receives a monotonic sequence; stale completions may populate the content-addressed cache but cannot overwrite current status, selection, pin, or diagnostics. Checking and staging never activate.

- [ ] **Step 5: Add configuration**

Add explicit settings and CLI/environment mapping:

```text
MCAV_PACK_REGISTRY_URL
MCAV_PACK_CHANNEL=stable
MCAV_PACK_CHECK_INTERVAL_SECONDS=900
MCAV_PACK_AUTO_STAGE=true
MCAV_PACK_TRUSTED_KEYS_FILE
MCAV_PACK_DEV_ALLOW_LOOPBACK_HTTP=false
```

Do not add automatic activation in this plan.

- [ ] **Step 6: Run tests and commit**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
../.venv/bin/pytest tests/test_visual_pack_registry.py tests/test_visual_pack_manager.py tests/test_config.py -q
cd ..
git add vj_server/visual_packs/registry.py vj_server/visual_packs/manager.py vj_server/tests/test_visual_pack_registry.py vj_server/tests/test_visual_pack_manager.py vj_server/config.py vj_server/cli.py vj_server/tests/test_config.py
git commit -m "feat(vj): stage visual packs from signed channels"
```

---

### Task 5: Add atomic runtime activation and rollback boundaries

**Files:**
- Create: `vj_server/visual_packs/runtime.py`
- Test: `vj_server/tests/test_visual_pack_runtime.py`
- Modify: `vj_server/visual_packs/manager.py`
- Modify: `vj_server/tests/test_visual_pack_manager.py`
- Modify: `vj_server/vj_server.py`
- Modify: `vj_server/tests/test_vj_server_helpers.py`

**Interfaces:**
- Produces: `PackRuntimeActivator.prepare(pack: InstalledPack) -> Awaitable[PreparedRuntime]`
- Produces: `PackRuntimeActivator.capture() -> Awaitable[RuntimeSnapshot]`
- Produces: `PackRuntimeActivator.commit(prepared: PreparedRuntime, snapshot: RuntimeSnapshot) -> Awaitable[ActiveRuntime]`
- Produces: `PackRuntimeActivator.health_check(active: ActiveRuntime) -> Awaitable[None]`
- Produces: `PackRuntimeActivator.restore(snapshot: RuntimeSnapshot) -> Awaitable[None]`
- Produces: `PackRuntimeActivator.dispose(runtime: PreparedRuntime | ActiveRuntime) -> Awaitable[None]`
- Produces: `VisualPackManager.activate(ref: PackRef) -> PackStatus`
- Produces: `VisualPackManager.rollback() -> PackStatus`

- [ ] **Step 1: Write activation RED tests using a deterministic fake adapter**

Exercise successful frame-boundary commit; prepare failure; commit failure; health failure; store-commit failure; concurrent activation/rollback; process cancellation at every awaited boundary; compatible state migration; and disposal only after health success.

```python
async def test_health_failure_restores_previous_runtime(manager: VisualPackManager) -> None:
    runtime.fail_health_for(ref("2.0.0"))
    result = await manager.activate(ref("2.0.0"))
    assert result.active == ref("1.0.0")
    assert runtime.current == ref("1.0.0")
    assert store.read_state().active == ref("1.0.0")
```

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
../.venv/bin/pytest tests/test_visual_pack_runtime.py tests/test_visual_pack_manager.py -q
```

- [ ] **Step 3: Implement the activation transaction**

Prepare and warm before acquiring the short frame-boundary gate. Capture live state, commit runtime ownership, atomically commit store metadata, and run a bounded health window. On any failure after capture, restore the snapshot before reporting failure. Use `asyncio.shield` only around the restore-and-metadata repair section so cancellation cannot strand a partial activation.

- [ ] **Step 4: Add the safe initial adapter**

The initial `CatalogPackActivator` may activate metadata, previews, profile availability, and references to visual IDs already implemented by the installed core. It must return `RuntimeCompatibilityError` for unknown ShowIR schemas or behavioral plans. It never copies registry content into `patterns/` and never instantiates `LuaPattern` from pack files.

- [ ] **Step 5: Integrate lifecycle startup and shutdown**

Construct the manager after Pterodactyl state paths and authentication load. Run `store.recover()` before accepting browser commands. Start periodic check/auto-stage as a background task, cancel it on shutdown, and leave Paper startup independent if updater initialization fails.

- [ ] **Step 6: Run tests and commit**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
../.venv/bin/pytest tests/test_visual_pack_runtime.py tests/test_visual_pack_manager.py tests/test_vj_server_helpers.py -q
cd ..
git add vj_server/visual_packs/runtime.py vj_server/visual_packs/manager.py vj_server/tests/test_visual_pack_runtime.py vj_server/tests/test_visual_pack_manager.py vj_server/vj_server.py vj_server/tests/test_vj_server_helpers.py
git commit -m "feat(vj): activate visual packs atomically"
```

---

### Task 6: Expose authenticated operator commands and metrics

**Files:**
- Create: `vj_server/visual_packs/messages.py`
- Test: `vj_server/tests/test_visual_pack_messages.py`
- Modify: `vj_server/relay.py`
- Modify: `vj_server/tests/test_relay.py`
- Modify: `vj_server/metrics.py`
- Modify: `vj_server/tests/test_metrics.py`
- Modify: `vj_server/METRICS.md`

**Interfaces:**
- Consumes browser commands: `visual_pack_check`, `visual_pack_stage`, `visual_pack_activate`, `visual_pack_rollback`, `visual_pack_pin`
- Produces browser event: `visual_pack_status`
- Produces: `parse_pack_command(message: object) -> PackCommand`
- Produces: `pack_status_message(status: PackStatus) -> dict[str, object]`

- [ ] **Step 1: Write authorization and command RED tests**

Prove unauthenticated clients cannot reach commands, malformed IDs/versions are rejected, only one bounded response is emitted per operation, status contains no local paths or key material, stale operations cannot overwrite a newer status, and activation/rollback audit the authenticated username.

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
../.venv/bin/pytest tests/test_visual_pack_messages.py tests/test_relay.py tests/test_metrics.py -q
```

- [ ] **Step 3: Add command routing after browser authentication**

Route commands through the same authenticated browser loop that currently handles VJ controls. Do not add an unauthenticated HTTP mutation endpoint. Return a stable error code and operator-safe message for invalid, incompatible, or busy operations.

- [ ] **Step 4: Add bounded metrics**

Expose counters and gauges for registry outcomes, cache hits, bytes, validation time, stage result, active pack labels, activation/rollback results, and retained bytes. Pack IDs and versions are bounded labels; exception strings, URLs, usernames, and filesystem paths are not metric labels.

- [ ] **Step 5: Run tests and commit**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
../.venv/bin/pytest tests/test_visual_pack_messages.py tests/test_relay.py tests/test_metrics.py -q
cd ..
git add vj_server/visual_packs/messages.py vj_server/tests/test_visual_pack_messages.py vj_server/relay.py vj_server/tests/test_relay.py vj_server/metrics.py vj_server/tests/test_metrics.py vj_server/METRICS.md
git commit -m "feat(vj): control and observe visual pack updates"
```

---

### Task 7: Add the admin-panel update workflow

**Files:**
- Create: `admin_panel/js/modules/VisualPackManager.js`
- Create: `admin_panel/js/modules/VisualPackManager.test.mjs`
- Modify: `admin_panel/js/admin-app.js`
- Modify: `admin_panel/js/modules/MessageRouter.js`
- Modify: `admin_panel/js/modules/ElementCache.js`
- Modify: `admin_panel/js/modules/EventWiring.js`
- Modify: `admin_panel/index.html`
- Modify: `admin_panel/css/admin.css`

**Interfaces:**
- Consumes: `visual_pack_status`
- Sends: the five authenticated visual-pack commands from Task 6
- Produces: `VisualPackManager.render(status)` and `VisualPackManager.request(action, ref)`

- [ ] **Step 1: Write DOM-free state and command RED tests**

Test all status phases, exact outgoing messages, disabled actions during operations, activate confirmation, incompatible diagnostic rendering, pin/unpin, rollback target, reconnect status request, and safe text rendering for untrusted metadata.

```javascript
test('activate requires confirmation and sends an exact reference', async () => {
  const manager = fixture({ confirm: async () => true });
  await manager.activate({ packId: 'mcav:official', version: '2.0.0' });
  assert.deepEqual(manager.sent.at(-1), {
    type: 'visual_pack_activate', pack_id: 'mcav:official', version: '2.0.0'
  });
});
```

- [ ] **Step 2: Run Node tests and confirm RED**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz
node --test admin_panel/js/modules/VisualPackManager.test.mjs
```

- [ ] **Step 3: Implement the module and message routing**

Keep update state outside the 3D preview render loop. Use `textContent`, existing modal controls, and existing WebSocket authentication. Reconnect requests current status but never repeats a mutating action automatically.

- [ ] **Step 4: Add the updates panel**

Add a compact System-section panel showing active, available, channel, signature, profiles, budget summary, and diagnostics. Actions are Check, Stage, Activate, Pin/Unpin, and Rollback. “Live mode” visibly disables unattended activation copy; this plan exposes no auto-activate toggle.

- [ ] **Step 5: Run frontend tests and commit**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz
node --test admin_panel/js/services/WebSocketService.test.mjs admin_panel/js/modules/VisualPackManager.test.mjs
git add admin_panel/js/modules/VisualPackManager.js admin_panel/js/modules/VisualPackManager.test.mjs admin_panel/js/admin-app.js admin_panel/js/modules/MessageRouter.js admin_panel/js/modules/ElementCache.js admin_panel/js/modules/EventWiring.js admin_panel/index.html admin_panel/css/admin.css
git commit -m "feat(admin): manage staged visual pack updates"
```

---

### Task 8: Ship trusted keys and verify the Pterodactyl release

**Files:**
- Create: `deploy/pterodactyl/trusted-pack-keys.json`
- Modify: `deploy/pterodactyl/build-release.sh`
- Modify: `deploy/pterodactyl/release_archive.py`
- Modify: `deploy/pterodactyl/test-build-release.sh`
- Modify: `deploy/pterodactyl/mcav.env.example`
- Modify: `deploy/pterodactyl/start-mcav.sh`
- Modify: `docs/deployment/PTERODACTYL.md`
- Create: `docs/deployment/VISUAL_PACKS.md`
- Test: `deploy/pterodactyl/test-start-mcav.sh`

**Interfaces:**
- Consumes: trusted-key path and registry settings from Task 4
- Produces: a portable release whose runtime contains `cryptography`, the updater modules, trusted keys, and persistent `state/content`

- [ ] **Step 1: Extend release RED assertions**

Require the archive to contain every `visual_packs/*.py` module and `trusted-pack-keys.json`; require the bundled Python to import `cryptography.hazmat.primitives.asymmetric.ed25519`; require startup to pass registry configuration without ever printing credentials or private state.

- [ ] **Step 2: Run release tests and confirm RED**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz
bash deploy/pterodactyl/test-build-release.sh
bash deploy/pterodactyl/test-start-mcav.sh
```

- [ ] **Step 3: Package the updater and document operations**

Ship the public key ring read-only beneath `release/`, point the generated environment at it, and document stable/beta/dev selection, staging, activation, pinning, offline rollback, storage retention, and the fact that private signing keys never belong on a game server.

- [ ] **Step 4: Run complete verification**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
../.venv/bin/pytest -q
cd ../minecraft_plugin
mvn test
cd ..
node --test admin_panel/js/services/WebSocketService.test.mjs admin_panel/js/modules/VisualPackManager.test.mjs protocol/tests/phase0-schemas.test.mjs
bash deploy/pterodactyl/test-build-release.sh
bash deploy/pterodactyl/test-start-mcav.sh
git diff --check
```

Expected: every command exits zero. The Pterodactyl test proves Paper still starts when registry initialization is forced to fail.

- [ ] **Step 5: Build and verify the release artifact**

```powershell
.\deploy\pterodactyl\build-release.ps1 -Version 26.2
.\deploy\pterodactyl\verify-release.ps1 -Archive .\dist\mcav-pterodactyl-26.2.zip
```

Expected: the archive and SHA-256 file are generated and verification exits zero.

- [ ] **Step 6: Commit packaging and operations documentation**

```bash
git add deploy/pterodactyl docs/deployment/PTERODACTYL.md docs/deployment/VISUAL_PACKS.md
git commit -m "build: ship visual pack update support"
```

---

## Follow-on plans required for behavioral visual updates

The foundation intentionally rejects downloaded executable patterns. Two follow-on plans attach behavioral content without changing this delivery subsystem:

1. **ShowIR runtime integration:** merge the low-latency renderer contract, define generated ShowIR bindings, and implement a bounded runtime adapter for continuous three-axis transforms, quaternions, semantic colors, particles, stable topology, and all five capability profiles.
2. **Visual Lab publication:** export the approved model/animation document, compile the five renderer plans, create captures and budgets, deterministically build `.mcavpack`, sign in CI, and promote signed channel manifests.

Until the ShowIR runtime plan lands, the admin panel can safely check, download, validate, cache, inspect, pin, and reject incompatible behavioral packs. Existing local Lua patterns continue to run unchanged and are never sourced from the registry.
