# MCAV v2 Paper Visual Lab Design

**Status:** Approved design; awaiting written-spec review

**Date:** 2026-08-15

**Scope:** A standalone Three.js authoring, rehearsal, comparison, and validation lab for MCAV v2 visualization models

## 1. Objective

The Paper Visual Lab is MCAV v2's first greenfield vertical slice. It gives authors one place to model, animate, compare, tune, budget, and approve every forward-going visualization before the production Show Engine and renderer adapters are complete.

The lab must answer four questions for every model:

1. Does it look intentional and react musically?
2. Does it remain complete on a stock Java client connected to Paper?
3. What do optional resource-pack and shader enhancements add?
4. Does the dedicated Geyser plan preserve the model's identity for stock Bedrock clients?

The first complete model is the approved Anatomical Voxel skull: a properly constructed, articulated skull with negative-space eye sockets, a hinged jaw, inner eye light, and restrained particles.

## 2. Product decisions

- The lab lives under the isolated `v2/` namespace and does not retrofit the legacy Lua preview.
- Paper is the only Minecraft server target.
- Fabric server and client support are out of scope.
- Stock Java is the authoritative completeness baseline.
- A resource pack is optional enrichment, never a requirement for identity, silhouette, timing, or essential color meaning.
- Shader support is one stable generic **MCAV Reference Shader** approximation, not a dependency on a named third-party shader pack.
- Stock Bedrock through Geyser is a required compatibility target with its own compiled renderer plan.
- A future optional Geyser bridge or extension may add Bedrock-native enhancements, but baseline support cannot depend on it.
- The lab is a simulation. It must clearly distinguish simulated capability checks from compatibility proven against real Paper and Geyser adapters.
- Model and behavior data are versioned and declarative. No arbitrary Lua, JavaScript, shader source, native library, or WASM runs as authored content.

## 3. Capability profiles

The interface exposes five named profiles:

|Profile|Contract|
|-|-|
|Java|Complete stock-Paper baseline|
|Java + Resource Pack|Baseline plus optional geometry and texture enrichment|
|Java + Shaders|Baseline plus MCAV reference lighting, fog, bloom, and material response|
|Java + Resource Pack + Shaders|Combined optional Java enhancements|
|Bedrock via Geyser|Required stock-Bedrock compatibility plan|

Resource-pack and shader capabilities remain independent. Shader presentation may alter lighting, shadows, bloom, fog, water, and material response, but cannot provide geometry, animation timing, essential visibility, or semantic color information.

The profile switch recompiles the canonical scene into a renderer plan. It is not a collection of cosmetic visibility toggles.

## 4. Repository boundary and implementation shape

The first implementation is a standalone Vite, React, and TypeScript application:

```text
v2/
  apps/
    visual-lab/
      src/
        core/
        model/
        animation/
        compiler/
        profiles/
        renderer/
        models/
        ui/
      tests/
  fixtures/
    audio/
    models/
    renderer-plans/
    visual-goldens/
```

React owns the control surface. The simulation, model compiler, and renderer core remain framework-independent. Three.js is driven imperatively in the render hot path; individual MCAV entities are not represented as React components.

The first renderer uses Three.js with WebGL2 for broad, deterministic browser support. The renderer boundary must permit a later WebGPU implementation without changing model definitions or simulation semantics.

## 5. Canonical model contract

Every visualization is a versioned, JSON-serializable `ModelDefinition`. TypeScript builder utilities may generate repetitive static geometry at load time, but per-frame authored code is prohibited. The normalized output can be exported as canonical JSON for future ShowIR ingestion.

Each node contains:

- stable ID and optional parent ID;
- semantic role and author-facing name;
- importance class: `required`, `supporting`, or `decorative`;
- local position, quaternion rotation, independent three-axis scale, dimensions, and explicit pivot;
- primitive kind and primitive-specific data;
- semantic palette/material role;
- LOD membership;
- capability requirements and explicit renderer fallbacks;
- optional animation and particle bindings.

Initial primitive kinds are:

- cuboid;
- stock block display;
- stock item display;
- text display;
- particle emitter;
- optional packed-model replacement.

The contract models authoring intent rather than Three.js objects or Minecraft packets.

## 6. Transform and hierarchy semantics

Local transforms compose in this order:

`parent × translation × pivot × rotation × scale × inverse-pivot`

Scale is a continuous three-component vector. Animation does not rebuild or respawn a primitive when any axis changes. Invalid zero-length transforms are rejected or clamped at a declared model boundary rather than producing undefined renderer behavior.

The compiler flattens authored hierarchies into world matrices for renderers that do not support parent-child scene graphs. A dirty node invalidates only its affected descendants. Stable IDs survive compilation and profile changes so comparison, diagnostics, and future network diffing remain meaningful.

Quaternion interpolation uses the shortest-path spherical interpolation. Position and scale use bounded linear interpolation unless an authored curve overrides them. The lab displays pivots, local axes, world bounds, and hierarchy links for debugging.

## 7. Declarative animation graph

The animation graph connects deterministic inputs and operators to node properties.

Inputs include:

- five frequency bands;
- peak, beat, kick, BPM, and beat phase;
- elapsed show time and fixed simulation tick;
- deterministic seeded noise;
- authored parameters and live tuning overrides.

Initial operators include:

- clamp, remap, curve, and quantize;
- add, multiply, mix, and conditional select;
- attack/release envelope and one-shot envelope;
- spring;
- LFO;
- delay;
- deterministic random/noise;
- finite state transition.

Targets include position, rotation, each scale axis, visibility, palette/material role, brightness, and every supported particle property.

Layers compose in a fixed order:

1. base pose;
2. authored clip;
3. procedural audio modulation;
4. live tuning override;
5. capability and LOD correction.

The canonical simulation advances at 20 Hz to reproduce Paper update cadence. The display loop interpolates previous and target state at the browser refresh rate. Timeline scrubbing and single-tick stepping evaluate the same deterministic scheduler used during playback.

## 8. Color and material behavior

Authors target semantic palette roles, not renderer-specific material IDs. A profile resolves each role to:

- a stock Java block, item, text, glow, or particle representation;
- an optional resource-pack model or texture;
- an optional reference-shader response;
- a Geyser-safe Bedrock representation.

Supported continuous color changes interpolate in a perceptual color space and are converted to the renderer's linear working space. Discrete Minecraft materials switch using authored thresholds with hysteresis so noisy audio does not flicker between materials.

If a required palette role has no valid profile mapping, compilation fails. The compiler never silently chooses an unrelated material.

## 9. Deterministic particles

Particle emitters are nodes in the model hierarchy and support:

- stable seed and attachment space;
- point, box, sphere, ring, cone, and directed shapes;
- continuous rate and explicit burst modes;
- lifetime, velocity, spread, gravity, and drag;
- size, color, opacity, and velocity-over-life curves;
- renderer-specific particle mappings and budgets.

Particles use fixed-capacity pools and ring buffers. Allocation, eviction, and degradation are deterministic for the same model, profile, seed, audio fixture, and timestamp.

## 10. Renderer-plan compilation

The compiler accepts a normalized model, animation state, capability profile, and quality policy. It produces a deterministic renderer plan containing:

- flattened stable primitive IDs and transforms;
- resolved geometry and material keys;
- resolved visibility, brightness, glow, and particle properties;
- profile substitutions and their reasons;
- LOD selection and degradation state;
- packed batch membership;
- cost counters and validation diagnostics.

Compilation follows these rules:

1. Required semantic roles must survive every required profile.
2. Supporting and decorative nodes may be substituted or degraded only through declared policies.
3. Java enhancement layers must fall back to the same complete stock-Java model.
4. Geyser plans use translator-safe primitives and lower default budgets.
5. Unsupported required roles are errors, not warnings.
6. Output order is stable so state hashes, fixtures, and diffs are reproducible.

The planned production path classifies connected viewers and sends profile-specific plans. Floodgate is the preferred Bedrock identity signal when installed. Mixed Java and Bedrock audiences require per-viewer visibility or packet streams; the design cannot assume one shared entity representation is correct for both audiences.

## 11. Bedrock and Geyser support

Bedrock support has two tiers:

### Required Geyser compatibility tier

- Requires no Bedrock resource pack or custom client.
- Uses only primitives declared safe by the versioned Geyser capability profile.
- Preserves recognizable silhouette, articulation, musical timing, and semantic color roles.
- May use lower counts, simplified materials, reduced particle density, or alternate primitive types.
- Produces a visible substitution report in the lab.

### Optional future MCAV Geyser bridge

An optional Geyser extension or companion may later consume MCAV semantic plans and emit Bedrock-native effects that ordinary Java-to-Bedrock translation cannot preserve. This tier is an enhancement only. Models cannot depend on it to pass baseline approval.

Java resource packs are not treated as Bedrock assets. A future Bedrock pack would be a separate compiled artifact and capability profile.

## 12. Lab interface and authoring workflow

The visual language follows the existing MCAV product direction: dark, dense, low-chrome, cyan-led, and suitable for a VJ workstation.

### Layout

- **Top transport:** model selector, five-profile control, play/pause, time, seed, audio fixture, camera preset, and capture controls.
- **Left navigator:** searchable catalog, hierarchy, semantic roles, LOD groups, and Draft/Review/Approved/Retired state.
- **Center viewport:** orbit and fly cameras, stage bounds, grid, safe viewing distances, selection, pivots, bounds, and particle-path overlays.
- **Right inspector:** geometry, transforms, animation, palette/material, particles, fallbacks, importance, and LOD.
- **Bottom diagnostics:** audio bands, beat timeline, primitive and update counts, batches, particles, estimated Paper cost, Geyser substitutions, frame time, and budget violations.

### Required workflows

- Single-profile editing and synchronized side-by-side comparison.
- Identical camera, audio, timestamp, and seed between compared profiles.
- Before/after snapshots and visual-difference overlay.
- Synthetic audio, recorded fixtures, scrubbing, single-tick stepping, and controlled jitter/loss simulation.
- Live editing with undo/redo, reset, copy/paste, numeric entry, and axis locking.
- Canonical file open/save through the browser file picker with JSON import/export fallback.
- One-command compatibility audit across all profiles.
- Golden captures with fixed viewport, camera, timestamp, and seed.

Reduced-motion preferences disable nonessential interface motion. Model playback remains explicitly controlled because evaluating motion is the lab's purpose.

## 13. Performance model

Every renderer plan emits a versioned cost report. Initial authoring guardrails are intentionally conservative and are not claims about final server capacity.

|Per-scene metric|Java warning / failure|Geyser warning / failure|
|-|-|-|
|Visible rendered primitives|256 / 384|96 / 160|
|Transform or appearance updates per tick|128 / 256|48 / 96|
|Particles per second per viewer|1,200 / 2,400|300 / 600|
|Geometry/material batches|32 / 64|16 / 32|
|Unsupported required roles|0 / 1|0 / 1|

Network cost begins as versioned weighted operations. It switches to actual serialized packet bytes when production adapters exist. JSON payload length must never be presented as Minecraft packet cost.

The Three.js renderer uses:

- `InstancedMesh` or equivalent packed draw paths grouped by compatible geometry and material;
- typed arrays and reused matrices, vectors, quaternions, and colors;
- dirty-branch transform propagation;
- property-specific update thresholds;
- pooled particles;
- preview frustum culling that does not alter simulated Minecraft cost;
- zero routine allocation inside steady-state simulation and render loops.

## 14. Quality degradation

When a plan exceeds its selected policy, degradation proceeds in semantic order:

1. reduce decorative particle density;
2. reduce decorative-node update frequency while retaining interpolation;
3. select lower decorative LODs;
4. consolidate materials and palette variants;
5. substitute renderer-specific supporting primitives;
6. preserve required silhouette, articulation, beat timing, and color roles.

The lab reports every applied degradation and the budget that caused it. Required roles are never silently removed to make a budget pass.

## 15. Anatomical Voxel skull vertical slice

The skull establishes the standard for future modeled visuals.

### Geometry

- layered cranium rather than a spherical point cloud;
- pronounced brow ridge;
- deep eye sockets constructed as actual negative space;
- cheekbones and tapered mid-face;
- nasal opening;
- upper jaw and individually readable teeth groups;
- hinged mandible with an anatomically plausible pivot;
- mirrored authoring helpers that normalize to explicit nodes.

### Motion and audio response

- restrained whole-head breathing through independent axis scale modulation;
- jaw opening driven by kick/onset envelopes with a spring return;
- subtle cranium yaw/roll driven by low-frequency musical phase;
- eye intensity driven by mid/high energy;
- eye particle bursts on strong beats and low-density trails between them;
- palette transitions that remain readable without shader bloom.

### Profile behavior

- Stock Java uses complete cuboid/display geometry and stock particles.
- Resource-pack mode may consolidate curved regions and enrich teeth and bone texture.
- Reference-shader mode enriches inner eye light, shadow depth, fog, and bloom only.
- Geyser mode uses a lower-count cuboid silhouette, preserves jaw articulation, and reduces eye particles.

The skull is rejected if any profile turns it back into an unstructured point cloud or depends on floating-block clutter for its identity.

## 16. Validation and error handling

Model loading and compilation fail with structured diagnostics that include model ID, node ID, property path, profile, severity, and remediation. The running last-known-good scene remains visible after an invalid edit.

An Approved model must satisfy all of these gates:

- identical input, timestamp, and seed produce identical canonical state hashes;
- all five profiles compile;
- stock Java is visually complete without enhancements;
- disabling a resource pack or shader removes no essential information;
- the Geyser plan has no unsupported required role;
- all profiles remain below failure budgets;
- continuous axis scaling and articulation produce no respawn or discontinuity;
- fixed golden captures exist for each profile;
- every degradation and substitution is visible in diagnostics.

Verification layers include:

- unit tests for schema validation, hierarchy, transform composition, interpolation, animation operators, fallbacks, deterministic particles, and cost accounting;
- fixture tests for stable compiled renderer plans and canonical hashes;
- browser integration tests for profile switching, synchronized comparison, editing, persistence, and audits;
- Playwright visual captures at fixed cameras, timestamps, seeds, and viewport sizes;
- allocation and frame-time benchmarks for steady-state preview operation;
- later conformance replay through disposable Paper and Geyser environments.

Golden screenshots detect changes but do not approve aesthetics automatically. A human review remains required before a model enters Approved state.

## 17. Catalog migration policy

Legacy Lua files are reference material, not migration units. Each concept is audited for:

- distinct silhouette;
- distinct motion language;
- clear musical role;
- credible stock-Java implementation;
- credible Geyser fallback;
- acceptable cost.

Strong concepts are rebuilt through the new model contract. Overlapping concepts may be merged, and weak concepts may be marked Retired only after explicit user approval. No existing file or functionality is deleted as part of the lab foundation.

## 18. Delivery sequence

1. Scaffold the standalone lab, tooling, test harness, and design tokens.
2. Implement canonical types, validation, deterministic clock/audio fixtures, and animation operators.
3. Implement capability profiles, renderer-plan compiler, cost model, and degradation rules.
4. Implement the packed Three.js renderer, particles, cameras, and diagnostics.
5. Implement the lab workstation UI, comparison, editing, persistence, and capture flows.
6. Build and tune the Anatomical Voxel skull across all five profiles.
7. Add deterministic, integration, visual, and performance verification.
8. Audit the legacy catalog and migrate approved concepts in reviewable waves.

## 19. Success criteria

The lab foundation is complete when an author can load the skull, play or scrub deterministic audio, change geometry and behavior, observe smooth three-axis transforms and particles, compare all five profiles under identical conditions, understand every substitution and cost, save the result, and reproduce the same capture later.

The broader goal remains active after the skull vertical slice: every retained forward-going MCAV model must ultimately pass the same review and approval workflow.

## 20. Reference constraints

- [Paper display entities](https://docs.papermc.io/paper/dev/display-entities/) define the stock Java transformation and interpolation surface the lab simulates.
- [Three.js InstancedMesh](https://threejs.org/docs/pages/InstancedMesh.html) is the initial packed browser draw path.
- [Geyser resource packs](https://geysermc.org/wiki/geyser/packs/) require Bedrock-specific packs rather than automatic Java-pack conversion.
- [Mojang's unsupported core-shader override notice](https://feedback.minecraft.net/hc/en-us/articles/35891577995277-Minecraft-Java-Edition-Snapshot-25w16a) excludes resource-pack core-shader overrides from the supported shader design.
