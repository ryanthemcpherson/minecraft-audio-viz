# MCAV v2 Platform Design

**Status:** Approved design; revised for Paper-only delivery and cross-client compatibility

**Date:** 2026-07-10

**Revised:** 2026-08-15

**Scope:** First complete MCAV v2 architecture, beginning with the renderer and real-time platform

**Strategy:** Greenfield v2 with explicit containment and cutover gates

## 1. Objective

MCAV v2 will be a professional live visual-performance platform built around Minecraft rather than a collection of loosely connected applications. It must deliver impressive visuals, predictable low latency, strong in-game performance, safe extensibility, and a coherent operator experience.

The product model has two planes:

- A self-hosted performance plane runs shows locally and remains fully functional without internet access.
- A hosted control plane provides identity, organizations, discovery, pairing bootstrap, signed show packs, releases, and opt-in telemetry.

The hosted control plane must never be required for an active show. Cloud failure cannot stop playback, visual rendering, cueing, or use of previously verified packs.

## 2. Approved product decisions

- MCAV v2 is a full architectural rewrite, not an in-place refactor.
- Paper is the first and only server target for the initial release.
- Vanilla Minecraft clients receive a complete baseline experience.
- Optional Java resource-pack and client-shader profiles enrich the baseline without carrying essential visual meaning.
- Stock Bedrock clients through Geyser receive a required compatibility plan with explicit fallbacks.
- One authoritative Rust Show Engine owns show time, graph evaluation, scene state, recording, and renderer-plan compilation.
- VJs and server administrators can create visuals without writing code.
- The Festival performance profile is the release acceptance target.
- Latency is a release-blocking service-level objective, not a later optimization.
- No arbitrary remote Lua, JavaScript, shader source, native library, or WASM executes in v1.

## 3. Non-goals for the first release

- Fabric server or client support
- Arbitrary community-developed executable plugins
- A public code/WASM extension marketplace
- Cloud-hosted show execution
- Collaborative multi-user graph editing
- Mobile authoring
- Blind one-for-one porting of every legacy implementation detail

These exclusions keep the first implementation focused without constraining later program-specific designs.

## 4. System architecture

### 4.1 Hosted control plane

The hosted control plane owns:

- user identity, organizations, roles, and server ownership;
- short-lived pairing bootstrap and discovery;
- signed show-pack distribution and compatibility metadata;
- dedicated signed release manifests for each product;
- opt-in health telemetry and audit records.

It communicates with local components through HTTPS APIs and signed artifacts. Verified identities, peer pins, pack manifests, and assets are cached locally for offline operation.

The internal implementation of the hosted control plane is intentionally deferred to its own program specification. Its contract with the performance plane is fixed here: standard HTTPS/OIDC-style identity, short-lived scoped grants, signed artifacts, no live render traffic, and no authority over an already-running show.

### 4.2 Local performance plane

The local performance plane contains four independently testable components:

1. **DJ Capture Node**
   - Captures system, application, or device audio.
   - Produces timestamped calibrated spectral and transient features.
   - Reports source format, clipping, silence, discontinuities, and capture health.
   - Never produces Minecraft entities or renderer-specific values.

2. **VJ Studio**
   - Provides graph authoring, live mixing, layers, cues, timeline automation, macros, MIDI/OSC mapping, preview, diagnostics, and rehearsal.
   - Manages the Show Engine lifecycle on the operator machine.
   - Uses local IPC and never mutates engine internals directly.

3. **Rust Show Engine**
   - Owns the authoritative monotonic show clock and show epoch.
   - Fuses audio features and performs beat, onset, phase, and BPM analysis.
   - Evaluates the declarative graph on a deterministic multi-rate scheduler.
   - Advances authoritative ShowIR scene state.
   - Compiles capability-specific renderer plans.
   - Records replayable inputs, controls, pack identity, engine version, and deterministic seeds.

4. **Verified Show-Pack Cache**
   - Stores signed packs and content-addressed assets.
   - Keeps cached shows usable without an internet connection.
   - Distinguishes trusted local drafts from signed published artifacts.

### 4.3 Render plane

The render plane consumes renderer plans but does not analyze audio or execute AuthorGraphs.

#### Paper adapter

The Java 21 Paper adapter owns only Minecraft-specific behavior:

- server identity, permissions, stage anchors, zones, and player membership;
- per-viewer visibility, culling, distance, and quality policy;
- packet-virtualized display entities, vanilla particles, sounds, text, and resource-pack models;
- per-viewer Java and Geyser capability classification and renderer-plan selection;
- strict main-thread access for Bukkit/Paper world state;
- off-thread network decode, validation, diffing, LOD planning, packet construction, and compression;
- deterministic cleanup and safe-scene recovery;
- deterministic profile fallback and substitution reporting.

It does not contain pattern implementations, DSP, beat detection, authoring logic, or a second scene runtime.

#### Java presentation profiles

Stock Java is the complete baseline. Optional resource-pack and generic reference-shader profiles may enrich geometry, textures, lighting, fog, bloom, and material response, but they cannot supply essential silhouette, timing, visibility, or semantic color meaning. Resource-pack and shader capabilities remain independent.

#### Bedrock through Geyser

Bedrock is a required compatibility target with a separately compiled plan. The baseline uses Geyser-safe primitives and requires no Bedrock resource pack. It preserves recognizable silhouette, articulation, musical timing, and semantic color roles under a lower cost policy. An optional future Geyser bridge may add Bedrock-native enhancements but is never required for baseline completeness.

#### Paper Visual Lab

The Three.js lab consumes versioned semantic models and renderer capability profiles. It supports:

- all five Java and Bedrock capability-profile simulations;
- side-by-side comparison;
- deterministic visual regression captures;
- stage bounds and LOD visualization;
- CPU, GPU, entity, memory, bandwidth, and update-rate estimates.

## 5. AuthorGraph, ShowIR, and renderer plans

MCAV uses three distinct representations:

1. **AuthorGraph** is the editable node graph, layers, timeline, macros, semantic primitives, and deterministic seeds.
2. **ShowIR** is the authoritative versioned runtime scene representation. It is deterministic and not edited directly.
3. **Renderer plans** are capability-specific projections compiled from ShowIR for stock Java, optional Java enhancements, Bedrock through Geyser, and the Paper Visual Lab.

Separating these formats prevents authoring concerns, runtime state, and platform-specific rendering from becoming coupled.

Each stable scene object has an immutable identity. Assets are addressed by content hash. Messages reference stable IDs rather than repeatedly transmitting complete scenes.

Every visual effect has:

- a semantic core shared by all renderers;
- an intentional vanilla implementation or fallback;
- optional resource-pack, shader-presentation, or future Bedrock-native layers that retain the same timing, parameters, palette, and cue semantics;
- a declared cost model;
- explicit capability requirements.

The design does not force effects to the lowest common denominator. Optional profiles may substantially enrich an effect, but the enriched effect remains part of the same graph and show timeline.

## 6. Real-time pipeline

### 6.1 Multi-rate audio features

The capture and engine pipeline has separate lanes:

- The **reactive fast lane** publishes onset, envelope, kick, and transient features at 120–240 Hz using small-window filter-bank analysis with a 3–8 ms response target.
- The **spectral detail lane** publishes calibrated band texture and longer-window analysis at 60 Hz with a maximum 21 ms analysis window.
- The **reliable control lane** carries cues, scene changes, lifecycle operations, and operator commands with an explicit effective show timestamp.

This split avoids making every visual response wait for a full FFT window while preserving spectral accuracy for detailed motion.

### 6.2 Time synchronization

All components use monotonic clocks. Capture timestamps are mapped to the Show Engine clock through continuous round-trip offset and drift estimation.

- LAN jitter buffering is small and adaptive.
- Late continuous frames are dropped rather than queued.
- Clock corrections are gradual and cannot cause visible discontinuities.
- High-confidence beat phase may schedule expected beats ahead of time.
- Live onset observations correct prediction gradually.
- Prediction is disabled when confidence is insufficient.

### 6.3 Engine scheduling

The engine uses a deterministic multi-rate scheduler:

- reactive signal nodes run at 120 Hz;
- selected onset publishers may update at 240 Hz;
- geometry and material graph nodes run at 60 Hz;
- expensive ambient nodes explicitly declare 30 Hz or 10 Hz rates and interpolate;
- Paper materialization is budgeted independently of engine simulation.

The same input recording, engine version, signed pack, seed, and control stream must reproduce the same sequence of ShowIR hashes.

### 6.4 Transport

- Same-host high-rate capture data uses a bounded shared-memory ring buffer to minimize copies, framing, and scheduler wakeups.
- Same-host control and lifecycle operations use authenticated operating-system-native IPC.
- Network traffic uses authenticated QUIC with TLS 1.3.
- QUIC datagrams carry lossy, latest-wins continuous features and eligible transform deltas.
- Reliable QUIC streams carry authentication, schemas, snapshots, cues, lifecycle operations, and pack changes.
- FlatBuffers provides code-generated binary envelopes and ShowIR structures for Rust, Java, and TypeScript consumers.
- Every untrusted buffer is size-limited, verified, schema-versioned, and semantically bounded before allocation.

No JSON is permitted on the real-time data path.

### 6.5 Scene streaming

Each message carries a schema version, show epoch, sequence, session timestamp, and bounded payload length.

- Snapshots establish complete state at join, reconnect, or pack change.
- Ordered deltas update stable scene identities.
- Reliable events carry cues, beats, scene changes, and lifecycle operations.
- Continuous transforms and amplitudes are latest-wins values.
- A sequence gap invalidates dependent deltas and triggers a fresh snapshot.
- Reconnect never replays a stale real-time backlog.

## 7. Latency and performance contract

### 7.1 Festival profile

Release acceptance uses documented reference hardware and a repeatable benchmark containing:

- 100 simulated Minecraft players;
- a 2,000-object semantic scene with per-viewer LOD and culling;
- stock-Java and Geyser renderer plans under their declared cost policies;
- sustained Paper operation at 20 TPS;
- Paper adapter main-thread work below 3 ms p95;
- Show Engine graph evaluation and renderer compilation below 5 ms per 60 Hz step;
- Visual Lab single-profile rendering at 60 FPS on the reference GPU;
- bounded network and memory use with no unbounded queue.

### 7.2 Latency objectives

- Stock-Java LAN onset-to-visible latency: at most 75 ms p95.
- Bedrock-through-Geyser LAN onset-to-visible latency: at most 100 ms p95.
- Capture-to-Paper-packet latency: at most 25 ms p95.
- The original 100 ms Festival target is the outer failure guardrail, not the normal operating target.

Visible latency is measured using instrumented stock Java and Bedrock reference clients running their declared render paths.

### 7.3 Per-hop traces

Frames carry comparable monotonic timestamps for:

`capture_read → fast_feature_ready → engine_ingress → graph_applied → plan_ready → adapter_send → client_visible`

VJ Studio displays p50, p95, and p99 histograms by hop. Performance regression tests fail if a component exceeds its assigned budget by more than 10 percent unless the complete end-to-end path improves and the exception is documented.

## 8. Backpressure and failure behavior

The system may reduce visual detail, but it may not convert load into growing latency.

The Paper adapter has a strict per-tick command budget and no unbounded message queue. Its degradation order is:

1. preserve scene lifecycle, deterministic cleanup, safety operations, and primary beat cues;
2. coalesce redundant transforms and material updates;
3. reduce per-viewer distance, LOD, and low-priority update rates;
4. pause decorative emitters until the server recovers.

Every disconnection has an explicit audience-safe outcome:

- **Capture loss:** release audio-reactive values smoothly while manual cues remain controllable.
- **Missing delta:** discard dependent deltas and request a snapshot.
- **Engine loss:** hold interpolation briefly, fade to a safe scene, then clean up deterministically.
- **Paper lag:** descend the quality ladder and report the reason to VJ Studio.
- **Invalid graph or pack:** keep the last-known-good graph active and reject the candidate atomically.
- **Cloud outage:** continue the active show and all verified cached packs without interruption.

## 9. VJ and server-admin authoring

### 9.1 VJ Studio

The professional VJ surface includes:

- a typed node graph;
- layers, masks, palettes, blend/composition controls, and transitions;
- cue banks, scene changes, and crossfades;
- timeline and tempo/phase-aware automation;
- exposed macros and live overrides;
- MIDI and OSC mappings;
- undo/redo, autosave, version history, and rehearsal;
- all five Visual Lab capability profiles;
- live cost, latency, bandwidth, entity, CPU, and GPU diagnostics.

Node categories include:

- audio and control inputs;
- envelopes, LFOs, springs, sequencers, deterministic random, and spatial fields;
- semantic grids, ribbons, beams, particle fields, meshes, text, and stage layers;
- palette, material, mask, transition, cue, and capability nodes.

### 9.2 Server-admin surface

Server administrators use guided templates over the same underlying AuthorGraph model. They can:

- install a signed pack;
- map stage anchors and zones;
- select a renderer quality policy;
- configure permissions and audience scope;
- adjust only author-exposed macros;
- run preflight and rehearsal checks;
- inspect compatibility and resource usage.

They are not required to understand the node graph.

### 9.3 Compilation and hot swap

The pack toolchain performs:

1. type and cycle validation;
2. range, asset, capability, and fallback validation;
3. CPU, GPU, entity, memory, bandwidth, and update-rate cost estimation;
4. capability-specific ShowIR plan compilation;
5. resource-pack and profile-specific asset compilation;
6. manifest construction, content hashing, and signing.

Compilation occurs in the background. A candidate graph is activated atomically at a show-frame boundary only after full validation. Failure leaves the running last-known-good graph unchanged.

## 10. Show-pack trust model

A published Show Pack is an immutable signed artifact containing:

- manifest and compatibility metadata;
- editable AuthorGraph source;
- compiled renderer plans;
- resource-pack content;
- optional profile-specific assets;
- content hashes and pack quotas;
- signer identity and signature.

Authors in the initial v2 release compose a versioned built-in node library and constrained material graph. They cannot supply executable Lua, JavaScript, native libraries, arbitrary shader source, or WASM.

Assets are content-addressed, size-limited, decoded and validated before show execution, and referenced by hash. Trusted local drafts are visually and operationally distinct from signed published packs.

## 11. Security architecture

### 11.1 Network and local identity

- Every listener defaults to loopback.
- Non-loopback startup requires a configured identity and authenticated transport.
- Show Engine and Paper identities are mutually authenticated and pinned.
- Local IPC uses operating-system ownership and access controls.
- Pairing grants are single-use, narrowly scoped, short-lived, rate-limited, and stored only as hashes where persistence is required.
- Per-viewer renderer capabilities and audience membership are validated before profile-specific plans are emitted.
- Tokens and secrets never appear in public metadata, query strings, logs, browser storage, or plaintext plugin JSON.
- Desktop secrets use operating-system credential storage.

### 11.2 Input and resource limits

Before allocation or execution, every boundary validates:

- message size and schema version;
- decompression ratio and resulting size;
- collection counts and string lengths;
- graph node and edge counts;
- scene object and emitter counts;
- texture dimensions and asset totals;
- update rates, runtime budgets, and capability declarations.

Malformed or unsupported input fails closed with a structured reason. No compatibility mode may silently weaken authentication or limits.

### 11.3 Supply chain

- Studio, Show Engine, Paper adapter, Visual Lab, and show packs use separate signed release manifests.
- Stable, beta, and nightly release channels are isolated.
- Reproducible locked builds emit checksums, provenance, signatures, and SBOMs.
- Dependency, license, static-analysis, and vulnerability checks are blocking.
- Threat models are maintained beside each externally reachable boundary.
- Key rotation, revocation, and rollback are tested release operations.

## 12. Operations and diagnostics

The Show Engine ships as a signed service/binary managed by VJ Studio or an explicit headless supervisor. Configuration is schema-validated and safe by default.

Diagnostics include:

- structured redacted logs with correlation IDs;
- per-hop latency histograms;
- Paper TPS and main-thread time;
- queue depth, allocation, memory, bandwidth, and dropped-update metrics;
- renderer capability and LOD decisions;
- pack, engine, protocol, Paper, Minecraft, and client versions;
- clear degraded-state and recovery reasons.

Shows remain local by default. Telemetry is opt-in, documented, sampled, and excludes audio, secrets, and authored content.

VJ Studio can create a user-reviewed support bundle containing redacted versions, topology, metrics, errors, and recent trace summaries.

## 13. Verification strategy

### 13.1 Every change

- Rust, Java, and TypeScript unit tests
- property tests for deterministic and bounded behavior
- formatting, linting, type checking, and static analysis
- generated protocol/ShowIR binding verification
- schema compatibility and golden wire fixtures
- deterministic replay hash tests
- bounded visual golden captures for all five Visual Lab capability profiles
- blocking dependency and security checks

### 13.2 Nightly integration

- disposable Paper server with the real adapter;
- simulated viewers, reconnects, cleanup, world changes, and permissions;
- loss, duplication, reordering, corruption, latency, and clock-drift injection;
- protocol version-skew and snapshot recovery tests;
- recorded audio fixtures across supported formats;
- traversal, auth replay, quota exhaustion, malformed pack, and decompression tests;
- latency and resource trend reporting.

### 13.3 Release candidate

- complete Festival benchmark;
- eight-hour active-show soak with representative music and operator changes;
- forced capture, engine, Paper, network, resource-pack, shader-profile, and Geyser failures;
- clean-machine install and first-run pairing;
- signed updater, rollback, migration, diagnostics export, and uninstall;
- DJ, VJ, and server-admin human acceptance on fresh accounts and machines.

## 14. Release gates

A public release requires all gates:

1. **Security:** no unresolved critical or high findings, fuzz-clean exposed parsers, authorization coverage, reviewed threat model, signed artifacts.
2. **Performance:** Festival profile and latency SLOs pass on documented reference hardware.
3. **Reliability:** eight-hour active-show soak and forced-failure recovery pass without leaks, hangs, orphaned state, or manual repair.
4. **Compatibility:** declared Paper/Minecraft, stock Java, optional resource-pack, reference-shader, Geyser/Bedrock, installer, firewall, and upgrade matrix passes.
5. **Product:** first-run setup, demo show, authoring, admin configuration, diagnostics, updater, rollback, docs, and uninstall pass end-to-end.

Security and audit commands cannot be hidden behind `continue-on-error` or `|| true`. A report-only check must be labeled report-only and cannot contribute to a green release gate.

## 15. Greenfield v2 repository boundary

V2 is built under an isolated top-level namespace so current code and user work remain untouched during development:

```text
v2/
  crates/
    show-engine/
    audio-capture/
    audio-features/
    author-graph/
    show-ir/
    protocol/
    show-pack/
    recorder/
    benchmark/
  apps/
    studio/
    visual-lab/
  adapters/
    paper/
  services/
    control-plane/
  fixtures/
    recordings/
    protocols/
    visual-goldens/
```

The Rust crates form one workspace with explicit dependency direction. Java and TypeScript consumers use generated protocol and ShowIR bindings and conformance fixtures; they do not duplicate domain definitions manually.

The hosted control-plane directory is reserved but is implemented only after the local performance plane passes the Phase 3 Festival, reliability, packaging, and security gates.

## 16. Migration program

### Phase 0: Contain v1

Before further public distribution of the current product:

- disable remote Lua delivery and execution;
- fix Windows static-file containment;
- make Minecraft WebSocket authentication work end-to-end and fail closed;
- fix OAuth state validation and empty-secret fail-open behavior;
- freeze known-broken Docker/demo/updater release paths.

This is containment, not the v2 architecture.

### Phase 1: Foundations

Deliver:

- v2 repository boundary and Rust workspace;
- ShowIR and AuthorGraph core types;
- FlatBuffers schemas and generated-binding CI;
- identity and pairing primitives;
- deterministic recorder/replay harness;
- latency trace model and benchmark harness;
- build, package, and release skeletons.

### Phase 2: Measured vertical slice

Deliver one complete path:

`one audio source → fast/detail features → one AuthorGraph → Show Engine → one Paper stage → Paper Visual Lab`

The slice must include authentication, snapshots/deltas, safe failure, metrics, packaging, and latency measurements. It is not complete if it runs only from developer commands.

### Phase 3: Production vanilla platform

Deliver:

- packet-virtualized display entities;
- stage anchoring and permissions;
- per-viewer culling, LOD, and quality ladder;
- resource-pack compilation and distribution;
- safe-scene recovery and deterministic cleanup;
- Festival load and latency gates;
- production Show Engine and Paper packages.

### Phase 4: VJ Studio and show packs

Deliver:

- full AuthorGraph editor;
- live mixer, timeline, cues, macros, MIDI/OSC, and rehearsal;
- capability compiler and five-profile Visual Lab preview;
- show-pack signing, registry contract, cache, and admin mode;
- atomic live graph replacement and diagnostics.

### Phase 5: Cross-client compatibility

Deliver:

- per-viewer Java and Bedrock capability classification;
- stock-Java, optional resource-pack, and reference-shader profile conformance;
- dedicated Geyser renderer plans and lower-cost quality policies;
- mixed Java/Bedrock audience validation;
- stock fallback conformance for every shipped node;
- optional Geyser bridge feasibility and compatibility matrix.

### Phase 6: Hosted control plane and cutover

Deliver:

- v2 identity, organizations, discovery, pairing bootstrap, signed pack registry, and release channels;
- import tooling for supported v1 configuration and authored content;
- public beta with rollback;
- end-to-end release-gate evidence;
- explicit user-approved cutover to v2 as the default product.

## 17. Legacy preservation and cutover

No current source, data, or functionality is deleted during v2 development. The current implementation remains available as a reference and rollback path until:

- v2 passes every release gate;
- supported v1 data/configuration migration is verified;
- operator documentation and rollback are complete;
- the cutover is explicitly approved.

V2 does not blindly port legacy internals. It preserves validated DSP behavior, visualization intent, brand assets, user workflows, and useful test fixtures. Trust boundaries, protocol, packaging, renderer architecture, and performance-critical paths are rebuilt from the approved requirements.

## 18. Definition of done

Every phase and feature ships with:

- implementation and bounded public interfaces;
- automated unit, integration, regression, and failure tests;
- assigned latency and resource budgets;
- structured metrics and diagnostics;
- secure defaults and threat-boundary coverage;
- installable packages and clean-machine smoke tests;
- migration and rollback behavior;
- operator and user documentation.

A feature is not done if it works only in a development checkout, lacks failure behavior, has no measurable budget, or depends on a non-blocking release check.

## 19. Acceptance summary

The platform is ready for program planning when this written specification is approved. The first implementation plan will cover Phase 0 containment only. Phase 1 foundations will then be decomposed into focused design/specification cycles before implementation; Phase 2 begins only after those foundation contracts and the benchmark harness are verified.
