# MCAV Visual Pack Delivery Design

**Status:** Approved
**Date:** 2026-08-21
**Scope:** Independently publish, stage, activate, pin, and roll back MCAV visuals without replacing the core Pterodactyl deployment

## 1. Purpose

MCAV visual content must evolve independently from the VJ server, Paper plugin, DJ client, and bundled Python runtimes. A server administrator should not need to upload and extract a new full deployment archive whenever a model, animation, material, particle treatment, preview, or platform-specific visual plan changes.

The system will distribute visuals as small, immutable, signed Visual Packs. The installed MCAV runtime downloads and validates packs in the background, while an authenticated administrator controls activation. Activation is atomic, rollback is immediate, and the currently active pack remains usable without registry access.

## 2. Design principles

1. **Separate content from capability.** Visual changes use packs; engine, protocol, renderer primitive, or Java behavior changes use core releases.
2. **No surprise changes during a show.** Packs may download automatically, but activation requires an administrator unless a maintenance-window policy explicitly permits it.
3. **Last-known-good always runs.** Download, validation, compilation, or activation failures cannot replace the active pack.
4. **Stock Java remains complete.** Resource-pack, shader, and Bedrock-specific artifacts enrich or adapt a visual but never carry its only essential meaning.
5. **Published content is declarative.** Published packs cannot contain arbitrary executable Lua, Python, JavaScript, Java, WASM, native libraries, or unrestricted shader source.
6. **One authored visual, capability-specific plans.** Java, Java with resource pack, Java with shaders, Java with both, and Bedrock through Geyser compile from the same semantic source.
7. **Offline operation is normal.** A registry outage does not affect an installed, verified pack.

## 3. Content and runtime boundary

Visual Packs may change:

- declarative models and semantic primitives;
- animation graphs, timelines, cues, and deterministic seeds;
- materials, color roles, color interpolation, and bounded color shifting;
- particle definitions and emitter behavior;
- profile-specific renderer plans and fallbacks;
- previews, thumbnails, categories, descriptions, and author metadata;
- optional Java resource-pack content;
- optional Bedrock/Geyser content compiled separately from Java assets.

A core release remains required for:

- a new protocol or ShowIR schema that the installed runtime cannot read;
- a new engine node, evaluator, or renderer primitive;
- changes to Paper plugin behavior or packet/entity implementation;
- new security, authentication, registry, signing, or sandbox behavior;
- runtime dependency changes;
- Java or native executable code.

The manifest declares exact core, protocol, schema, and capability requirements. Unsupported visuals use declared fallbacks or remain unavailable; one unsupported visual must not invalidate otherwise compatible visuals in the same pack.

## 4. Pack artifact

A published Visual Pack is an immutable ZIP artifact with the `.mcavpack` extension:

```text
manifest.json
authorgraph/
showir/
plans/
  java/
  java_resource_pack/
  java_shader/
  java_resource_pack_shader/
  bedrock_geyser/
assets/
resource-packs/
previews/
signature.ed25519
```

The initial implementation may omit directories that have no content, but their meanings are reserved.

### 4.1 Manifest

`manifest.json` contains:

- pack ID and semantic version;
- manifest schema version;
- minimum and maximum compatible MCAV core versions;
- required protocol and engine schema versions;
- required and optional capability IDs;
- stable visual IDs, display metadata, categories, and replacement history;
- the available renderer profiles and fallback graph for each visual;
- per-profile entity, particle, memory, bandwidth, and update-rate budgets;
- SHA-256 hash and byte size for every member;
- signing key ID and publication timestamp;
- optional Java and Bedrock resource-pack identity, hash, and client policy.

Visual IDs are stable across pack versions. Asset files are content-addressed and immutable. Pack members must not use absolute paths, parent traversal, symbolic links, duplicate canonical names, or platform-dependent filename aliases.

### 4.2 Capability model

Visuals target semantic capability IDs rather than implementation classes. Initial capability families include:

- `display.block`, `display.item`, and `display.text`;
- `transform.continuous` and bounded interpolation modes;
- `animation.graph`, cues, envelopes, and deterministic time sources;
- `material.color_shift` and semantic palette roles;
- bounded particle emitters and trails;
- stock-Java model composition;
- optional resource-pack model replacement;
- optional shader presentation flags;
- Geyser-safe Bedrock plans.

The capability vocabulary is versioned with the core runtime. Packs must include a stock-Java fallback for every essential visual role. Bedrock is a separately compiled renderer family, not a Java cosmetic toggle.

## 5. Registry and channels

The first registry is a static HTTPS origin, suitable for GitHub Releases or Cloudflare R2/CDN. It requires no database or separately operated application server.

The registry exposes isolated `stable`, `beta`, and `dev` channels. Each channel has a small signed manifest that references immutable pack URLs by ID, version, SHA-256 hash, and size. A server is configured for one channel and may pin an exact pack version.

The VJ server periodically checks the selected channel with bounded timeouts, response sizes, redirect policy, and retry backoff. It downloads only missing content-addressed artifacts. Conditional HTTP requests avoid repeatedly transferring unchanged channel manifests.

The signing public key and trusted key metadata ship with the MCAV core. Private signing keys exist only in the publication pipeline. Signed registry metadata supports key rotation, revocation, and a minimum accepted registry generation to prevent rollback attacks.

## 6. Persistent storage

Pterodactyl deployments store content beneath the existing persistent state directory:

```text
mcav-vj/state/content/
  downloads/
  staging/
  packs/<pack-id>/<version>/
  active.json
  rollback.json
  registry-cache.json
  audit.jsonl
```

Core deployment upgrades do not overwrite this directory. Files are downloaded to unique temporary paths, flushed, verified, extracted into a unique staging directory, and promoted with an atomic rename. Pack versions are never modified in place.

The default retention policy keeps the active version and the three most recently activated versions. Pinned versions are exempt from cleanup. Cleanup cannot remove an active, rollback, staged, or referenced resource-pack artifact.

## 7. Staging transaction

The server performs these steps without changing the active show:

1. Fetch and verify the signed channel manifest.
2. Select a candidate permitted by the configured channel and pin policy.
3. Download the immutable artifact to `downloads/`.
4. Verify artifact size and SHA-256 before parsing it.
5. Validate the pack signature and signing key status.
6. Validate archive structure, member count, expanded size, canonical paths, and member hashes.
7. Validate schemas, compatibility ranges, stable IDs, capabilities, fallbacks, and quotas.
8. Compile every applicable renderer profile in isolation.
9. Run deterministic smoke frames and budget checks without publishing output.
10. Promote the verified candidate to `packs/` and expose it as `ready` in the admin panel.

A failure records a bounded diagnostic and leaves the candidate unactivatable. It does not mutate `active.json`, the live runtime, zone state, or client resource-pack state.

## 8. Activation transaction

Activation is an authenticated, audited operation. By default it requires one click from an administrator. Automatic activation is allowed only through an explicit maintenance-window policy and is disabled while live mode is active.

At activation:

1. The control plane reserves an activation sequence and stops accepting conflicting pack operations.
2. The engine captures active visual IDs, zones, parameters, cue positions, show time, deterministic seeds, current pack identity, and applicable resource-pack state.
3. A new isolated runtime loads the already verified candidate.
4. Plans for all enabled profiles compile again against current runtime capabilities.
5. The new runtime warms without publishing output.
6. At a show-frame boundary, the engine atomically switches pack/runtime ownership for all zones.
7. Stable visual IDs preserve compatible parameters and cue positions. Explicit manifest migrations may rename IDs or parameters; undeclared incompatible state resets to pack defaults.
8. The old runtime and state remain retained throughout a bounded health window.
9. If compilation, runtime evaluation, Paper delivery, or health checks fail, the engine atomically restores the captured state and previous pack.
10. After the health window succeeds, the new pack becomes the rollback baseline and the old runtime is disposed.

Activation metadata is written atomically. A process restart at any point resolves to either the previous complete activation or the new complete activation, never a partially active pack.

## 9. Rollback

Rollback uses the same activation transaction and requires no download when the retained artifact is present. The administrator can restore the immediately previous activation with one click or select any retained compatible version.

Automatic rollback occurs when activation warm-up fails, the health window fails, or persistent metadata cannot be committed safely. Rollback remains available while offline. Audit records include actor, time, source and destination versions, reason, compatibility result, and success or failure.

## 10. Admin experience

The authenticated admin panel displays update state as:

- checking;
- downloading;
- validating;
- ready;
- incompatible;
- invalid;
- active;
- pinned;
- activation failed;
- rolled back.

An update review shows added, changed, replaced, and removed visual IDs; supported profiles; fallback coverage; performance budgets; resource-pack implications; required core version; signature identity; and validation diagnostics.

Available actions are:

- check for updates;
- download and stage;
- activate now;
- activate after the current cue;
- pin or unpin a version;
- roll back;
- select stable, beta, or dev subject to administrator permissions;
- configure a maintenance window and automatic activation policy.

Live mode blocks unattended activation. All mutating operations require current administrator authentication, CSRF protection where applicable, a monotonic operation token, and an audit entry.

## 11. Resource-pack and client behavior

Stock Java is always a complete fallback. A Visual Pack may reference an immutable Java resource-pack artifact by URL and SHA-256 hash. The server exposes or forwards the verified artifact only after staging succeeds.

Activation may either wait for connected Java players to report resource-pack readiness or proceed with stock plans while enhanced clients transition. The selected policy is explicit in the pack and constrained by the server administrator. Client rejection or download failure never prevents the stock visual from running.

Bedrock resource packs are separately compiled artifacts suitable for Geyser. Java resource packs are never treated as automatically convertible Bedrock content. A Bedrock asset failure selects the pack's Geyser-safe baseline plan.

## 12. Legacy migration

The existing VJ server's Lua file loader and hot-reload loop remain available initially as a local legacy adapter. Legacy content is not fetched from the signed public registry and is never represented as a trusted published pack.

The migration sequence is:

1. Introduce pack schema, signatures, validation, cache, staging, and rollback around current visual selection.
2. Package existing trusted visuals through a bounded adapter while preserving current behavior.
3. Export AuthorGraph/ShowIR drafts from the Visual Lab.
4. Compile declarative content into all five renderer profiles.
5. Publish only declarative signed packs.
6. Retire published Lua execution after feature parity; local legacy mode may remain behind an explicit development setting for a documented transition period.

Unsigned local drafts are visibly labeled and disabled by default. Enabling them requires an explicit development-mode setting and never expands their permissions beyond the current bounded Lua sandbox.

## 13. Publication pipeline

The publication pipeline:

1. Accepts a Visual Lab draft and locked asset inputs.
2. Validates schemas, graph types, cycles, bounds, stable identities, fallbacks, and quotas.
3. Runs deterministic simulation and replay-hash checks.
4. Compiles all five capability profiles independently.
5. Produces front and three-quarter captures for quiet, beat, and ignition states.
6. Runs visual regression review and budget benchmarks.
7. Executes Paper and Geyser compatibility tests.
8. Builds an immutable archive deterministically.
9. Emits checksums, provenance, and an SBOM for toolchain inputs.
10. Signs the pack and publishes it to an immutable location.
11. Atomically publishes a newly signed channel manifest.

Promotion between channels republishes registry metadata; it does not rebuild or mutate the pack artifact.

## 14. Failure behavior

The system must explicitly handle:

- registry timeouts, malformed responses, oversized responses, and outages;
- interrupted, truncated, corrupted, or redirected downloads;
- unknown, invalid, expired, or revoked signing keys;
- hash mismatches and archive traversal attempts;
- incompatible core, protocol, schema, or capability versions;
- missing profile assets or invalid fallback graphs;
- entity, particle, memory, bandwidth, and update-rate budget violations;
- compilation, warm-up, deterministic replay, or renderer failures;
- Paper or Geyser disconnect during activation;
- Java or Bedrock resource-pack rejection;
- process termination during download, staging, activation, or metadata commit;
- rollback while the registry is offline.

Every failure preserves the last-known-good active pack. Diagnostics exposed to administrators are bounded and redact secrets and local authentication material.

## 15. Observability

Metrics include registry check outcome and latency, downloaded bytes, cache hits, validation duration, compilation duration by profile, staged and active pack identity, activation duration, rollback count, failure reason category, retained storage, and resource-pack adoption by platform.

Structured logs and audit records share operation IDs. Pack identity and version are included in deterministic recordings and support bundles so a visual failure can be reproduced exactly.

## 16. Required verification

Automated verification must cover:

- canonical manifest serialization and signature verification;
- archive path, size, count, and decompression limits;
- content hash verification and content-addressed deduplication;
- compatibility and fallback resolution for all five profiles;
- deterministic compile and replay hashes;
- staging idempotence and concurrent-operation ordering;
- activation atomicity and state preservation;
- process restart at every transaction boundary;
- automatic and manual rollback while offline;
- pinning, channel isolation, key rotation, and revocation;
- authenticated admin actions and audit records;
- Java resource-pack fallback and Bedrock/Geyser fallback;
- Pterodactyl clean-install, upgrade, and persistent-state behavior;
- browser tests for review, activate, pin, and rollback workflows;
- performance tests proving registry work never runs on the real-time render path.

## 17. Delivery phases

1. **Pack foundation:** schema, canonicalization, hashing, signature verification, archive validation, local cache, and validation CLI.
2. **Safe distribution:** registry client, channels, staging, pinning, retention, restart recovery, and rollback metadata.
3. **Operator controls:** authenticated admin APIs and update, activation, pin, and rollback interfaces.
4. **Runtime activation:** atomic activation around the current loader, health window, state migration, and Paper/Geyser failure recovery.
5. **Authoring and publication:** Visual Lab export, deterministic compiler, capture/benchmark gates, signing, and channel publication.
6. **Declarative runtime:** production AuthorGraph/ShowIR execution and removal of executable content from published packs.
7. **Enhanced assets:** immutable Java resource-pack and separate Bedrock/Geyser artifact distribution.

Each phase must leave a working, testable system and must not weaken the current full-release deployment or its rollback path.
