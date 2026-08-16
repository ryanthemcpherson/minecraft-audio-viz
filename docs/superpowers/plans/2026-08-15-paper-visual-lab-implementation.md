# Paper Visual Lab Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task by task. Use `superpowers:test-driven-development` for each behavior change, `frontend-design:frontend-design` for the workstation UI, and `superpowers:verification-before-completion` before claiming any milestone complete.

**Goal:** Build and verify the standalone MCAV v2 Paper Visual Lab with deterministic simulation, packed Three.js rendering, five capability profiles, live authoring and comparison workflows, cost and compatibility auditing, and the Anatomical Voxel skull vertical slice.

**Architecture:** The application is an isolated Vite/React/TypeScript package under `v2/apps/visual-lab`. React owns controls while a framework-independent core validates declarative models, advances a fixed 20 Hz simulation, compiles stable profile-specific renderer plans, and writes packed frame state into an imperative Three.js renderer. Static topology and fallback compilation happen only when the model, profile, or quality policy changes; frame evaluation updates preallocated arrays and dirty indices. Stock Java is the completeness baseline, optional resource-pack and reference-shader layers are independent enhancements, and Bedrock through Geyser receives a dedicated lower-cost plan.

**Tech Stack:** Node.js, npm, TypeScript, Vite, React 19, Three.js 0.183.x, Ajv, Vitest, Testing Library, ESLint, Playwright Chromium

**Spec:** `docs/superpowers/specs/2026-08-15-paper-visual-lab-design.md`

## Global constraints

- Before implementation, use `superpowers:using-git-worktrees` and create `.worktrees/paper-visual-lab` on branch `feature/paper-visual-lab`.
- Preserve all unrelated tracked and untracked user work. Do not edit the legacy Lua runtime, the current site gallery, or existing v1 renderer plans.
- Use `apply_patch` for authored file changes. Package-manager lockfile generation and formatter rewrites may use their native commands.
- Follow red-green-refactor: observe every relevant new test fail before implementing behavior.
- Make every task one logical conventional commit after its focused verification passes.
- Do not add a React component per rendered entity. The hot path uses packed arrays, stable integer indices, pooled objects, and instanced batches.
- Do not recompile model topology every 20 Hz tick. Recompile only on model, profile, fallback, LOD, or quality-policy changes.
- Do not execute authored JavaScript, Lua, shader source, WASM, or native code.
- Stock Java must remain complete with resource-pack and shader presentation disabled.
- The shader profile cannot create required geometry or visibility.
- Bedrock baseline cannot depend on a Bedrock pack, Geyser extension, or custom client.
- Profile simulations must be labeled as simulations until real adapter conformance exists.
- Initial golden generation requires explicit review; tests must never silently update snapshots.
- Exact package versions are captured in the package lock. Do not use globally installed tools for verification.

## File map

### Application and tooling

- `v2/apps/visual-lab/package.json`: package scripts and dependencies.
- `v2/apps/visual-lab/package-lock.json`: reproducible npm dependency graph.
- `v2/apps/visual-lab/vite.config.ts`: Vite, Vitest, and deterministic test setup.
- `v2/apps/visual-lab/playwright.config.ts`: Chromium projects and stable graphics flags.
- `v2/apps/visual-lab/eslint.config.js`: TypeScript and React lint policy.
- `v2/apps/visual-lab/src/App.tsx`: workstation composition only.
- `v2/apps/visual-lab/src/styles/`: MCAV tokens, layout, controls, and accessibility states.

### Canonical core

- `src/model/`: schema, TypeScript contracts, semantic validation, normalization, hierarchy compilation, and frame storage.
- `src/animation/`: graph types, fixed-step evaluation, operator state, and property writes.
- `src/audio/`: deterministic synthetic and recorded-fixture sources.
- `src/core/`: monotonic simulation clock, PRNG, canonical hashing, color conversion, and shared math.
- `src/compiler/`: capability resolution, static renderer-plan compilation, budgets, diagnostics, and degradation.
- `src/profiles/`: the exact five named capability profiles and their material/particle maps.

### Rendering and models

- `src/renderer/`: imperative Three.js lifecycle, instanced batches, geometry/material factories, particles, presentation effects, cameras, and debug overlays.
- `src/models/skull/`: Anatomical Voxel skull geometry, animation graph, palette, and profile overrides.
- `src/models/catalog.ts`: loadable models and legacy review-queue metadata.

### Workstation UI

- `src/state/`: framework-independent lab store, commands, history, and persistence ports.
- `src/components/`: transport, navigator, viewport, inspector, timeline, diagnostics, audit, and comparison surfaces.
- `src/io/`: canonical file open/save, JSON fallback, screenshot capture, and fixtures.

### Verification

- Unit tests are colocated as `*.test.ts` or `*.test.tsx`.
- `v2/apps/visual-lab/tests/e2e/`: browser workflows, visual goldens, and benchmark collection.
- `v2/fixtures/audio/`: small deterministic audio fixtures.
- `v2/fixtures/models/`: normalized model fixtures and legacy review catalog.
- `v2/fixtures/renderer-plans/`: stable plan fixtures.
- `v2/fixtures/visual-goldens/`: approved fixed-profile captures.

---

## Task 1: Isolate and scaffold the lab package

**Files:**

- Create: `v2/apps/visual-lab/package.json`
- Create: `v2/apps/visual-lab/package-lock.json` through `npm install`
- Create: `v2/apps/visual-lab/index.html`
- Create: `v2/apps/visual-lab/tsconfig.json`
- Create: `v2/apps/visual-lab/tsconfig.app.json`
- Create: `v2/apps/visual-lab/tsconfig.node.json`
- Create: `v2/apps/visual-lab/vite.config.ts`
- Create: `v2/apps/visual-lab/eslint.config.js`
- Create: `v2/apps/visual-lab/playwright.config.ts`
- Create: `v2/apps/visual-lab/src/main.tsx`
- Create: `v2/apps/visual-lab/src/App.tsx`
- Create: `v2/apps/visual-lab/src/test/setup.ts`
- Create: `v2/apps/visual-lab/src/App.test.tsx`
- Create: `v2/apps/visual-lab/src/styles/tokens.css`
- Create: `v2/apps/visual-lab/src/styles/app.css`

### Step 1: Create the package and tool configuration

Use package scripts:

```json
{
  "dev": "vite",
  "build": "tsc -b && vite build",
  "lint": "eslint .",
  "typecheck": "tsc -b --pretty false",
  "test": "vitest run",
  "test:watch": "vitest",
  "test:e2e": "playwright test",
  "test:visual": "playwright test --project=visual",
  "benchmark": "playwright test --project=benchmark"
}
```

Add only the dependencies required by the approved architecture:

- Runtime: `react`, `react-dom`, `three`, `ajv`.
- Build/types: `vite`, `@vitejs/plugin-react`, `typescript`, `@types/node`, `@types/react`, `@types/react-dom`, `@types/three`.
- Unit/UI tests: `vitest`, `jsdom`, `@testing-library/react`, `@testing-library/user-event`, `@testing-library/jest-dom`.
- Lint: `eslint`, `@eslint/js`, `typescript-eslint`, `eslint-plugin-react-hooks`, `eslint-plugin-react-refresh`, `globals`.
- Browser verification: `@playwright/test`.

Run:

```powershell
cd v2/apps/visual-lab
npm install
npx playwright install chromium
```

Expected: `package-lock.json` is created and Chromium installs without modifying any other package.

### Step 2: Write the failing shell test

`src/App.test.tsx` must assert that the app exposes the product name and all five exact profile labels. Run:

```powershell
npm test -- src/App.test.tsx
```

Expected: FAIL because the shell does not yet render the required controls.

### Step 3: Implement the smallest accessible shell

Render semantic landmarks for the transport, navigator, viewport, inspector, and diagnostics areas. Use the canonical MCAV tokens and visible `:focus-visible` states. Do not implement model behavior yet.

### Step 4: Verify the scaffold

```powershell
npm run lint
npm run typecheck
npm test -- src/App.test.tsx
npm run build
```

Expected: all commands pass and Vite emits `dist/`.

### Step 5: Commit

```powershell
git add v2/apps/visual-lab
git commit -m "feat(lab): scaffold Paper visual lab"
```

---

## Task 2: Define and validate `ModelDefinitionV1`

**Files:**

- Create: `v2/apps/visual-lab/src/model/types.ts`
- Create: `v2/apps/visual-lab/src/model/model-definition-v1.schema.json`
- Create: `v2/apps/visual-lab/src/model/validateModel.ts`
- Create: `v2/apps/visual-lab/src/model/normalizeModel.ts`
- Create: `v2/apps/visual-lab/src/model/defineModel.ts`
- Create: `v2/apps/visual-lab/src/model/validateModel.test.ts`
- Create: `v2/apps/visual-lab/src/model/fixtures.ts`

### Step 1: Write failing contract tests

Cover:

- a valid minimal cuboid model;
- schema-version rejection;
- duplicate stable IDs;
- missing parents and hierarchy cycles;
- invalid quaternion and non-finite numeric values;
- zero or negative undeclared base scale;
- an unknown primitive kind;
- a required semantic role without stock-Java or Geyser fallback;
- deterministic normalization and stable node ordering.

Run:

```powershell
npm test -- src/model/validateModel.test.ts
```

Expected: FAIL because the contract does not exist.

### Step 2: Implement schema and semantic validation

Use JSON Schema through Ajv for structural validation. Add a focused semantic pass for graph integrity, stable IDs, finite values, quaternion normalization, required fallbacks, and actionable diagnostics containing model ID, node ID, property path, profile, severity, and remediation.

Keep `defineModel()` an identity helper that adds compile-time type checking only; it must not create an executable extension surface.

### Step 3: Implement canonical normalization

Normalize quaternions, defaults, ordering, and optional fields without mutating author input. Ensure canonical output is JSON-serializable and stable across runs.

### Step 4: Verify and commit

```powershell
npm test -- src/model/validateModel.test.ts
npm run typecheck
npm run lint
git add v2/apps/visual-lab/src/model
git commit -m "feat(lab): define canonical model contract"
```

---

## Task 3: Compile hierarchy and packed frame state

**Files:**

- Create: `v2/apps/visual-lab/src/model/compileHierarchy.ts`
- Create: `v2/apps/visual-lab/src/model/compileHierarchy.test.ts`
- Create: `v2/apps/visual-lab/src/model/FrameState.ts`
- Create: `v2/apps/visual-lab/src/model/FrameState.test.ts`
- Create: `v2/apps/visual-lab/src/core/transform.ts`
- Create: `v2/apps/visual-lab/src/core/transform.test.ts`
- Create: `v2/apps/visual-lab/src/core/color.ts`
- Create: `v2/apps/visual-lab/src/core/color.test.ts`

### Step 1: Write failing transform and hierarchy tests

Prove:

- composition order is `parent × translation × pivot × rotation × scale × inverse-pivot`;
- all three scale axes remain independent through parent composition;
- an articulated jaw rotates around its declared hinge;
- shortest-path quaternion slerp crosses the expected orientation;
- dirty propagation marks only a changed node and its descendants;
- stable IDs map to stable packed indices;
- perceptual color interpolation has stable endpoints and midpoint.

### Step 2: Implement packed hierarchy metadata

Topologically sort nodes once. Store parent indices, child ranges, importance, primitive kind, and stable-ID lookups in typed arrays or compact immutable arrays. Reject cycles before allocation.

### Step 3: Implement reusable frame storage

Preallocate previous and target position, quaternion, scale, color, brightness, visibility, world matrix, and dirty-bit storage. Expose explicit mutation methods; do not allocate from the steady-state update path.

### Step 4: Implement transform and color math

Use reusable Three.js math objects only at boundaries. Keep canonical packed state in typed arrays. Implement the approved perceptual color conversion with reference-value tests.

### Step 5: Verify and commit

```powershell
npm test -- src/model/compileHierarchy.test.ts src/model/FrameState.test.ts src/core/transform.test.ts src/core/color.test.ts
npm run typecheck
npm run lint
git add v2/apps/visual-lab/src/model v2/apps/visual-lab/src/core
git commit -m "feat(lab): add packed hierarchical frame state"
```

---

## Task 4: Add deterministic audio and fixed-step animation

**Files:**

- Create: `v2/apps/visual-lab/src/core/prng.ts`
- Create: `v2/apps/visual-lab/src/core/prng.test.ts`
- Create: `v2/apps/visual-lab/src/core/canonicalHash.ts`
- Create: `v2/apps/visual-lab/src/core/canonicalHash.test.ts`
- Create: `v2/apps/visual-lab/src/audio/types.ts`
- Create: `v2/apps/visual-lab/src/audio/syntheticAudio.ts`
- Create: `v2/apps/visual-lab/src/audio/fixtureAudio.ts`
- Create: `v2/apps/visual-lab/src/audio/audio.test.ts`
- Create: `v2/apps/visual-lab/src/animation/types.ts`
- Create: `v2/apps/visual-lab/src/animation/operators.ts`
- Create: `v2/apps/visual-lab/src/animation/evaluateGraph.ts`
- Create: `v2/apps/visual-lab/src/animation/evaluateGraph.test.ts`
- Create: `v2/apps/visual-lab/src/animation/FixedStepClock.ts`
- Create: `v2/apps/visual-lab/src/animation/FixedStepClock.test.ts`
- Create: `v2/fixtures/audio/skull-reference.json`

### Step 1: Write failing determinism tests

Cover:

- fixed 50 ms ticks with interpolation alpha independent of display frame cadence;
- pause, seek, restart, and single-tick stepping;
- identical seed and timestamp produce identical audio and random values;
- different seeds diverge;
- canonical hashes ignore object insertion order but include all state-bearing values;
- synthetic and recorded fixtures expose the same five-band audio contract.

### Step 2: Write failing operator tests

Cover remap, curve, clamp, add, multiply, mix, select, attack/release envelope, one-shot envelope, spring, LFO, delay, deterministic noise, and finite-state transition. Include boundary, large-`dt`, and reset behavior.

### Step 3: Implement the fixed-step scheduler and audio sources

The wall-clock adapter may use `performance.now()`, but evaluation accepts explicit integer ticks and timestamps. Seeking reconstructs state from a declared reset/checkpoint path; it must not depend on hidden wall-clock history.

Keep `skull-reference.json` intentionally small and deterministic. Store normalized audio frames, not WAV or MP3 data.

### Step 4: Implement declarative graph evaluation

Compile graph targets to packed node/property indices once. Store stateful operator memory in preallocated buffers. Apply layers in the approved order and reject unknown operators during compilation.

### Step 5: Verify and commit

```powershell
npm test -- src/core/prng.test.ts src/core/canonicalHash.test.ts src/audio/audio.test.ts src/animation
npm run typecheck
npm run lint
git add v2/apps/visual-lab/src/core v2/apps/visual-lab/src/audio v2/apps/visual-lab/src/animation v2/fixtures/audio
git commit -m "feat(lab): add deterministic animation simulation"
```

---

## Task 5: Compile the five capability-specific renderer plans

**Files:**

- Create: `v2/apps/visual-lab/src/profiles/types.ts`
- Create: `v2/apps/visual-lab/src/profiles/profiles.ts`
- Create: `v2/apps/visual-lab/src/profiles/materialMappings.ts`
- Create: `v2/apps/visual-lab/src/profiles/particleMappings.ts`
- Create: `v2/apps/visual-lab/src/profiles/profiles.test.ts`
- Create: `v2/apps/visual-lab/src/compiler/types.ts`
- Create: `v2/apps/visual-lab/src/compiler/resolveFallback.ts`
- Create: `v2/apps/visual-lab/src/compiler/compileRendererPlan.ts`
- Create: `v2/apps/visual-lab/src/compiler/compileRendererPlan.test.ts`
- Create: `v2/apps/visual-lab/src/compiler/costModel.ts`
- Create: `v2/apps/visual-lab/src/compiler/costModel.test.ts`
- Create: `v2/apps/visual-lab/src/compiler/degradePlan.ts`
- Create: `v2/apps/visual-lab/src/compiler/degradePlan.test.ts`

### Step 1: Write failing profile tests

Assert that exactly these stable profile IDs and labels exist:

|ID|Label|
|-|-|
|`java`|Java|
|`java_resource_pack`|Java + Resource Pack|
|`java_shader`|Java + Shaders|
|`java_resource_pack_shader`|Java + Resource Pack + Shaders|
|`bedrock_geyser`|Bedrock via Geyser|

Prove that resource-pack and shader flags are independent and that Bedrock is a dedicated renderer family rather than a Java cosmetic flag.

### Step 2: Write failing compiler and fallback tests

Cover:

- stable output order and stable primitive IDs;
- stock-Java resolution for every required role;
- resource-pack absence resolving to stock Java;
- shader disablement changing presentation only;
- Geyser-specific substitutions and reasons;
- missing required role as a compilation error;
- supporting/decorative substitution as a diagnostic;
- no authored input mutation;
- topology reuse when only frame state changes.

### Step 3: Write failing budget and degradation tests

Encode the approved Java and Geyser warning/failure thresholds. Verify the exact degradation order: decorative particles, decorative update rate, decorative LOD, material consolidation, supporting substitution, then required-role preservation. Assert that required nodes never disappear to make a cost report pass.

### Step 4: Implement static plan compilation

Split static compiled topology from mutable frame data. Resolve capabilities, material keys, particle mappings, batch keys, LOD, and cost weights only on topology/profile policy changes. Include a versioned cost-model ID and structured diagnostics.

### Step 5: Verify and commit

```powershell
npm test -- src/profiles src/compiler
npm run typecheck
npm run lint
git add v2/apps/visual-lab/src/profiles v2/apps/visual-lab/src/compiler
git commit -m "feat(lab): compile capability renderer plans"
```

---

## Task 6: Implement the allocation-conscious Three.js renderer

**Files:**

- Create: `v2/apps/visual-lab/src/renderer/types.ts`
- Create: `v2/apps/visual-lab/src/renderer/VisualLabRenderer.ts`
- Create: `v2/apps/visual-lab/src/renderer/SceneRuntime.ts`
- Create: `v2/apps/visual-lab/src/renderer/InstanceBatch.ts`
- Create: `v2/apps/visual-lab/src/renderer/batchPlan.ts`
- Create: `v2/apps/visual-lab/src/renderer/batchPlan.test.ts`
- Create: `v2/apps/visual-lab/src/renderer/geometryFactory.ts`
- Create: `v2/apps/visual-lab/src/renderer/materialFactory.ts`
- Create: `v2/apps/visual-lab/src/renderer/cameras.ts`
- Create: `v2/apps/visual-lab/src/renderer/debugOverlays.ts`
- Create: `v2/apps/visual-lab/tests/e2e/renderer-smoke.spec.ts`

### Step 1: Write failing batch-plan tests

Prove that compatible instances share a batch, incompatible profile/material/geometry keys split batches, stable primitive indices remain addressable, and only dirty instances are marked for matrix/color upload.

### Step 2: Implement batch planning and runtime ownership

`SceneRuntime` owns compiled topology, packed frame state, renderer batches, reusable scratch objects, and lifecycle cleanup. `VisualLabRenderer` owns the canvas, WebGL renderer, camera, lights, resize handling, animation frame, and context-loss recovery.

Use one box geometry per compatible cuboid family and `InstancedMesh` for repeated primitives. Update instance matrices and colors only for dirty indices. Rebuild batches only when the static renderer-plan signature changes.

### Step 3: Add deterministic cameras and debug overlays

Create fixed front, three-quarter, audience, side, and top presets. Debug overlays show bounds, pivots, local axes, stage bounds, and safe-distance guides but do not alter simulated Minecraft cost.

### Step 4: Add the browser smoke test

Expose a test-only diagnostics API under `window.__MCAV_LAB_TEST__` in development/test builds. The Playwright test loads a minimal model, waits for a rendered frame, and asserts nonzero pixels, expected batch counts, no console errors, and clean disposal after profile reload.

Run:

```powershell
npm test -- src/renderer/batchPlan.test.ts
npm run build
npm run test:e2e -- tests/e2e/renderer-smoke.spec.ts
```

Expected: all pass in installed Chromium.

### Step 5: Commit

```powershell
git add v2/apps/visual-lab/src/renderer v2/apps/visual-lab/tests/e2e/renderer-smoke.spec.ts
git commit -m "feat(lab): add packed Three.js renderer"
```

---

## Task 7: Add deterministic particles and presentation enhancements

**Files:**

- Create: `v2/apps/visual-lab/src/renderer/particles/ParticlePool.ts`
- Create: `v2/apps/visual-lab/src/renderer/particles/ParticlePool.test.ts`
- Create: `v2/apps/visual-lab/src/renderer/particles/ParticleRenderer.ts`
- Create: `v2/apps/visual-lab/src/renderer/particles/emitterShapes.ts`
- Create: `v2/apps/visual-lab/src/renderer/particles/emitterShapes.test.ts`
- Create: `v2/apps/visual-lab/src/renderer/presentation/ReferenceEffects.ts`
- Create: `v2/apps/visual-lab/src/renderer/presentation/resourcePackGeometry.ts`
- Create: `v2/apps/visual-lab/src/renderer/presentation/presentation.test.ts`
- Create: `v2/apps/visual-lab/tests/e2e/profile-presentation.spec.ts`

### Step 1: Write failing particle tests

Cover deterministic spawn order, point/box/sphere/ring/cone/directed shapes, rate and burst modes, velocity/drag/gravity integration, over-life curves, fixed-capacity eviction, profile caps, and reset/seek reproducibility.

### Step 2: Implement pooled particles

Use fixed-capacity typed arrays and a deterministic free/ring index policy. Render particles through instanced quads or points grouped by compatible material. Avoid object creation during emission and integration.

### Step 3: Write failing presentation tests

Prove that:

- resource-pack mode can replace declared geometry while preserving stable semantic identity;
- resource-pack failure falls back to stock geometry;
- shader-only mode changes no geometry or required visibility;
- combined mode enables both independent capabilities;
- Geyser mode enables neither Java enhancement.

### Step 4: Implement optional presentation layers

Use supported Three.js post-processing modules for restrained bloom, fog, tone mapping, and output conversion. Use reusable bevelled/rounded preview geometry only for nodes declaring an optional packed-model replacement. Do not implement resource-pack core-shader overrides.

### Step 5: Verify and commit

```powershell
npm test -- src/renderer/particles src/renderer/presentation
npm run test:e2e -- tests/e2e/profile-presentation.spec.ts
npm run typecheck
npm run lint
git add v2/apps/visual-lab/src/renderer v2/apps/visual-lab/tests/e2e/profile-presentation.spec.ts
git commit -m "feat(lab): add particles and profile presentation"
```

---

## Task 8: Build the Anatomical Voxel skull vertical slice

**Files:**

- Create: `v2/apps/visual-lab/src/models/skull/skull.model.ts`
- Create: `v2/apps/visual-lab/src/models/skull/skull.animation.ts`
- Create: `v2/apps/visual-lab/src/models/skull/skull.palette.ts`
- Create: `v2/apps/visual-lab/src/models/skull/skull.profileOverrides.ts`
- Create: `v2/apps/visual-lab/src/models/skull/skull.test.ts`
- Create: `v2/apps/visual-lab/src/models/catalog.ts`
- Create: `v2/fixtures/models/skull.normalized.json`
- Create: `v2/fixtures/renderer-plans/skull-java.json`
- Create: `v2/fixtures/renderer-plans/skull-bedrock-geyser.json`

### Step 1: Write failing structural model tests

Require:

- cranium, brow, cheekbone, nasal opening, maxilla, tooth, mandible, and eye-emitter semantic groups;
- mirrored left/right authored parts normalized to explicit stable nodes;
- the mandible parented to the head and rotating around a plausible rear hinge;
- independent negative-space volumes for both eye sockets;
- no required solid occupying either socket's protected inner volume;
- no decorative floating-block halo;
- required stock-Java and Geyser fallback for every required role;
- Java and Geyser plans below their failure thresholds.

### Step 2: Write failing animation tests

At fixed ticks and audio inputs, prove:

- kick/onset opens the jaw and the spring returns it smoothly;
- bass modulates X, Y, and Z scale independently without discontinuity;
- low musical phase creates restrained head yaw/roll;
- mid/high energy changes eye intensity;
- strong beats create bounded eye bursts while quiet sections use low-density trails;
- profile switching retains stable required semantic IDs.

### Step 3: Author proper hierarchical geometry

Use structured cuboid plates and beams to build anatomical volumes around deliberate empty sockets and nasal space. Keep the stock model complete. Declare optional packed-model replacements only for curvature and surface refinement.

The Bedrock plan should use explicit lower-count supporting/decorative fallbacks rather than relying on automatic arbitrary removal.

### Step 4: Author animation and palette data

Keep all behavior declarative. Use bone, shadow, eye-core, eye-accent, and tooth palette roles with stock mappings that remain readable without bloom.

### Step 5: Generate and review canonical fixtures

Add an explicit fixture-generation script only if normalization cannot be represented directly in tests. Review fixture diffs before staging. Generated output must be deterministic byte-for-byte.

### Step 6: Verify and commit

```powershell
npm test -- src/models/skull
npm test -- src/compiler src/animation
npm run typecheck
npm run lint
git add v2/apps/visual-lab/src/models v2/fixtures/models v2/fixtures/renderer-plans
git commit -m "feat(lab): add anatomical voxel skull"
```

---

## Task 9: Add the framework-independent lab store and workstation shell

**Files:**

- Create: `v2/apps/visual-lab/src/state/types.ts`
- Create: `v2/apps/visual-lab/src/state/LabStore.ts`
- Create: `v2/apps/visual-lab/src/state/LabStore.test.ts`
- Create: `v2/apps/visual-lab/src/state/react.ts`
- Create: `v2/apps/visual-lab/src/components/AppShell.tsx`
- Create: `v2/apps/visual-lab/src/components/TopTransport.tsx`
- Create: `v2/apps/visual-lab/src/components/ModelNavigator.tsx`
- Create: `v2/apps/visual-lab/src/components/ViewportPanel.tsx`
- Create: `v2/apps/visual-lab/src/components/InspectorPanel.tsx`
- Create: `v2/apps/visual-lab/src/components/DiagnosticsPanel.tsx`
- Create: `v2/apps/visual-lab/src/components/AudioTimeline.tsx`
- Create: `v2/apps/visual-lab/src/components/workstation.test.tsx`
- Modify: `v2/apps/visual-lab/src/App.tsx`
- Modify: `v2/apps/visual-lab/src/styles/app.css`

### Step 1: Write failing store tests

Cover model load, profile selection, play/pause, fixed tick, seek, seed, audio source, camera preset, selected node, debug overlays, compile errors, last-known-good scene retention, and renderer disposal.

The store publishes coarse slices so 20 Hz frame updates do not rerender the entire React tree.

### Step 2: Implement command-driven state

Use a small framework-independent store with explicit commands and `useSyncExternalStore` bindings. Keep live packed frame state outside React. Invalid edits publish diagnostics while retaining the previous valid compiled scene.

### Step 3: Write failing workstation tests

Assert semantic landmarks, all five profile controls, keyboard access, visible focus, model/hierarchy navigation, transport behavior, diagnostics labels, and simulation labeling.

### Step 4: Implement the distinctive workstation shell

Apply the canonical MCAV tokens, dark-only surface, cyan/indigo/amber accents, compact mono diagnostics, and dense VJ hierarchy. The center viewport dominates. Avoid generic card-grid styling, oversized headings, and decorative motion unrelated to audio or state.

Respect `prefers-reduced-motion` for interface transitions and glows. Model playback remains under explicit transport control.

### Step 5: Verify and commit

```powershell
npm test -- src/state src/components/workstation.test.tsx
npm run typecheck
npm run lint
npm run build
git add v2/apps/visual-lab/src/state v2/apps/visual-lab/src/components v2/apps/visual-lab/src/App.tsx v2/apps/visual-lab/src/styles
git commit -m "feat(lab): add VJ workstation shell"
```

---

## Task 10: Implement synchronized profile comparison

**Files:**

- Create: `v2/apps/visual-lab/src/state/ComparisonController.ts`
- Create: `v2/apps/visual-lab/src/state/ComparisonController.test.ts`
- Create: `v2/apps/visual-lab/src/components/ProfileComparison.tsx`
- Create: `v2/apps/visual-lab/src/components/ProfileSelector.tsx`
- Create: `v2/apps/visual-lab/src/components/CameraControls.tsx`
- Create: `v2/apps/visual-lab/src/renderer/ViewportController.ts`
- Create: `v2/apps/visual-lab/src/renderer/ViewportController.test.ts`
- Create: `v2/apps/visual-lab/tests/e2e/profile-comparison.spec.ts`

### Step 1: Write failing synchronization tests

Prove that single and side-by-side modes share model, audio fixture, seed, fixed tick, playback state, and camera pose while retaining independent profile compilers and diagnostics.

Verify profile changes rebuild only the affected static plan and do not reset show time or random sequence.

### Step 2: Implement comparison controllers

Drive one or two imperative viewport controllers from the same canonical simulation snapshot. Each controller owns its profile plan and presentation effects. Camera synchronization copies canonical pose data rather than sharing mutable Three.js camera instances.

### Step 3: Implement comparison UI

Support single, vertical split, and horizontal split layouts. Make stock Java vs Bedrock the default comparison pairing. Surface substitutions and budget state per viewport.

### Step 4: Add end-to-end coverage

The browser test switches every profile, enters comparison mode, scrubs to a fixed tick, changes camera, and asserts both viewports report the same canonical hash with different renderer-plan hashes where expected.

### Step 5: Verify and commit

```powershell
npm test -- src/state/ComparisonController.test.ts src/renderer/ViewportController.test.ts
npm run test:e2e -- tests/e2e/profile-comparison.spec.ts
npm run typecheck
npm run lint
git add v2/apps/visual-lab/src/state v2/apps/visual-lab/src/components v2/apps/visual-lab/src/renderer v2/apps/visual-lab/tests/e2e/profile-comparison.spec.ts
git commit -m "feat(lab): add synchronized profile comparison"
```

---

## Task 11: Add live editing, history, and canonical file persistence

**Files:**

- Create: `v2/apps/visual-lab/src/state/EditHistory.ts`
- Create: `v2/apps/visual-lab/src/state/EditHistory.test.ts`
- Create: `v2/apps/visual-lab/src/state/modelCommands.ts`
- Create: `v2/apps/visual-lab/src/state/modelCommands.test.ts`
- Create: `v2/apps/visual-lab/src/components/TransformInspector.tsx`
- Create: `v2/apps/visual-lab/src/components/AnimationInspector.tsx`
- Create: `v2/apps/visual-lab/src/components/MaterialInspector.tsx`
- Create: `v2/apps/visual-lab/src/components/ParticleInspector.tsx`
- Create: `v2/apps/visual-lab/src/components/FallbackInspector.tsx`
- Create: `v2/apps/visual-lab/src/io/modelFiles.ts`
- Create: `v2/apps/visual-lab/src/io/modelFiles.test.ts`
- Create: `v2/apps/visual-lab/tests/e2e/editing.spec.ts`

### Step 1: Write failing edit and history tests

Cover numeric transform entry, axis locking, node visibility, palette mapping, animation parameter changes, particle changes, fallback selection, undo, redo, reset, compound transactions, and invalid-edit rejection with last-known-good retention.

### Step 2: Implement immutable model commands and bounded history

Commands operate on the normalized authoring document, trigger semantic validation, and recompile static topology only when necessary. Parameter-only changes update the smallest applicable compiled structure. Bound history by entry count and serialized byte estimate.

### Step 3: Write failing file-port tests

Cover open, save, save-as, unsupported File System Access API fallback, canonical JSON ordering, schema errors, cancelled dialogs, permission denial, and download/import fallback.

### Step 4: Implement browser file persistence

Keep browser APIs behind an injected port for tests. Never write arbitrary paths from the dev server. Display failures explicitly and preserve unsaved state.

### Step 5: Implement inspectors and end-to-end flow

Exercise: select jaw, change hinge-safe rotation amplitude, lock X/Z scale, undo, redo, save canonical JSON, reset, import it, and confirm the same canonical hash.

### Step 6: Verify and commit

```powershell
npm test -- src/state/EditHistory.test.ts src/state/modelCommands.test.ts src/io/modelFiles.test.ts
npm run test:e2e -- tests/e2e/editing.spec.ts
npm run typecheck
npm run lint
git add v2/apps/visual-lab/src/state v2/apps/visual-lab/src/components v2/apps/visual-lab/src/io v2/apps/visual-lab/tests/e2e/editing.spec.ts
git commit -m "feat(lab): add model editing and persistence"
```

---

## Task 12: Add diagnostics and the five-profile compatibility audit

**Files:**

- Create: `v2/apps/visual-lab/src/diagnostics/types.ts`
- Create: `v2/apps/visual-lab/src/diagnostics/RuntimeMetrics.ts`
- Create: `v2/apps/visual-lab/src/diagnostics/RuntimeMetrics.test.ts`
- Create: `v2/apps/visual-lab/src/diagnostics/compatibilityAudit.ts`
- Create: `v2/apps/visual-lab/src/diagnostics/compatibilityAudit.test.ts`
- Create: `v2/apps/visual-lab/src/components/CompatibilityAudit.tsx`
- Create: `v2/apps/visual-lab/src/components/CostReport.tsx`
- Create: `v2/apps/visual-lab/src/components/SubstitutionReport.tsx`
- Create: `v2/apps/visual-lab/tests/e2e/compatibility-audit.spec.ts`

### Step 1: Write failing metrics tests

Cover frame count, fixed-step count, render duration samples, simulation duration samples, active primitives, dirty updates, particles, batch count, weighted network operations, warning/failure budgets, substitution counts, bounded sample windows, and reset behavior.

Do not label weighted operations as encoded bytes.

### Step 2: Write failing audit tests

The audit compiles all five profiles at one canonical model revision and proves:

- profile compilation result and plan hash;
- required/supporting/decorative role disposition;
- budget state and applied degradation;
- resource-pack and shader removal invariants;
- Geyser required-role coverage;
- deterministic hash consistency;
- distinction between simulated and adapter-proven status.

### Step 3: Implement bounded diagnostics and audit execution

Run the audit off the animation frame through an explicit command. Retain structured results by model revision. Invalidate stale reports after relevant edits.

### Step 4: Implement diagnostic UI and browser flow

Expose cost, degradation, substitution, and error details without hiding them behind hover-only affordances. The end-to-end test runs the skull audit and asserts five results, zero unsupported required roles, and no failure budget.

### Step 5: Verify and commit

```powershell
npm test -- src/diagnostics
npm run test:e2e -- tests/e2e/compatibility-audit.spec.ts
npm run typecheck
npm run lint
git add v2/apps/visual-lab/src/diagnostics v2/apps/visual-lab/src/components v2/apps/visual-lab/tests/e2e/compatibility-audit.spec.ts
git commit -m "feat(lab): add compatibility and cost audits"
```

---

## Task 13: Add snapshots, visual differences, goldens, and benchmarks

**Files:**

- Create: `v2/apps/visual-lab/src/renderer/SnapshotController.ts`
- Create: `v2/apps/visual-lab/src/renderer/SnapshotController.test.ts`
- Create: `v2/apps/visual-lab/src/renderer/DifferenceOverlay.ts`
- Create: `v2/apps/visual-lab/src/components/SnapshotControls.tsx`
- Create: `v2/apps/visual-lab/src/io/capture.ts`
- Create: `v2/apps/visual-lab/src/io/capture.test.ts`
- Create: `v2/apps/visual-lab/tests/e2e/visual-goldens.spec.ts`
- Create: `v2/apps/visual-lab/tests/e2e/benchmark.spec.ts`
- Create after review: `v2/fixtures/visual-goldens/visual/*.png`
- Modify: `v2/apps/visual-lab/playwright.config.ts`

### Step 1: Write failing snapshot and capture tests

Cover before/after snapshot metadata, identical camera/tick/seed enforcement, deterministic filenames, PNG export errors, stale snapshot invalidation, difference opacity, and cleanup of render targets.

### Step 2: Implement snapshot and difference rendering

Capture renderer output into explicitly owned render targets. Difference mode compares two synchronized captures and never mutates canonical scene state. Dispose all GPU resources on replacement and unmount.

### Step 3: Configure stable visual tests

Pin one Playwright Chromium project to stable viewport, device scale, color scheme, locale, timezone, animations, and software graphics flags. Set `snapshotPathTemplate` to `v2/fixtures/visual-goldens`.

Capture the skull for every profile at approved front and three-quarter cameras and representative quiet, beat, and eye-burst ticks. Use a documented small pixel tolerance for renderer variance. Never run snapshot update automatically in normal tests.

### Step 4: Add benchmark collection

Run a warm-up followed by a fixed 600-frame sample with the diagnostics API. Assert:

- no renderer-plan rebuild during steady-state playback;
- no failure-budget state;
- stable batch and capacity counts;
- p95 frame time below the documented 16.67 ms reference target in the dedicated benchmark project.

Keep the benchmark project separate from ordinary unit tests so hardware regressions are visible rather than hidden by a loose unit-test timeout.

### Step 5: Review and approve initial goldens

Start the lab, inspect each generated capture, and only then run:

```powershell
npm run test:visual -- --update-snapshots
npm run test:visual
npm run benchmark
```

Expected: reviewed goldens are written once, the immediate comparison passes, and the benchmark report records the reference environment.

### Step 6: Commit

```powershell
git add v2/apps/visual-lab/src/renderer v2/apps/visual-lab/src/components v2/apps/visual-lab/src/io v2/apps/visual-lab/tests v2/apps/visual-lab/playwright.config.ts v2/fixtures/visual-goldens
git commit -m "test(lab): add visual regression and benchmarks"
```

---

## Task 14: Seed the legacy review queue and document lab operation

**Files:**

- Create: `v2/fixtures/models/legacy-catalog.json`
- Create: `v2/apps/visual-lab/src/models/legacyCatalog.ts`
- Create: `v2/apps/visual-lab/src/models/legacyCatalog.test.ts`
- Modify: `v2/apps/visual-lab/src/models/catalog.ts`
- Modify: `v2/apps/visual-lab/src/components/ModelNavigator.tsx`
- Create: `v2/apps/visual-lab/README.md`
- Create: `docs/superpowers/plans/2026-08-15-visual-model-catalog-review.md`

### Step 1: Write the failing catalog test

Assert that every discovered legacy Lua visualization appears exactly once in the review fixture with source path, legacy name, broad category, overlap candidates, and initial `unreviewed` status. The skull maps to its new implementation without deleting its legacy source.

The fixture is intake metadata only; it must not execute or translate Lua.

### Step 2: Create the review queue

Populate the fixture from an explicit reviewed inventory of `patterns/*.lua`, excluding shared libraries. Preserve exact source names and paths. Add Draft/Review/Approved/Retired UI filters, with Retired remaining a metadata state that requires explicit user approval.

### Step 3: Document operation and limitations

The README must include installation, development, all verification commands, file format, profile meanings, file-save behavior, browser requirements, screenshot review, benchmark caveats, and the statement that Paper/Geyser profiles are simulations pending real-adapter replay.

### Step 4: Write the next catalog-review plan

Create a separate review plan that groups the legacy concepts into modeled, stage, organic/cosmic, geometric, particle, and merge-candidate waves. It must require explicit keep/merge/retire decisions before implementation and must not delete legacy files.

### Step 5: Verify and commit

```powershell
npm test -- src/models/legacyCatalog.test.ts
npm run typecheck
npm run lint
git add v2/fixtures/models/legacy-catalog.json v2/apps/visual-lab/src/models v2/apps/visual-lab/src/components/ModelNavigator.tsx v2/apps/visual-lab/README.md docs/superpowers/plans/2026-08-15-visual-model-catalog-review.md
git commit -m "docs(lab): seed model review workflow"
```

---

## Task 15: Run the completion audit and visual handoff

**Files:**

- Modify only if evidence finds a defect: files introduced by Tasks 1–14
- Create: `v2/apps/visual-lab/verification-report.md`

### Step 1: Reinstall from the lockfile

From a clean dependency directory in the feature worktree:

```powershell
cd v2/apps/visual-lab
npm ci
npx playwright install chromium
```

Expected: dependency installation succeeds solely from `package-lock.json`.

### Step 2: Run the complete automated gate

```powershell
npm run lint
npm run typecheck
npm test
npm run build
npm run test:e2e
npm run test:visual
npm run benchmark
```

Record exact commands, versions, exit codes, test counts, visual tolerance, and benchmark statistics in `verification-report.md`. A failure must be fixed at its root and the affected gate rerun before the full sequence is repeated.

### Step 3: Perform a requirement-by-requirement audit

Map authoritative evidence for:

- all five capability profiles;
- dedicated Geyser plan and required-role coverage;
- resource-pack/shader independence;
- deterministic fixed-step audio and replay;
- hierarchical modeling and articulated pivots;
- continuous X/Y/Z scale and smooth rotation interpolation;
- material/color behavior;
- deterministic bounded particles;
- cost budgets, degradation, and diagnostics;
- profile comparison, editing, history, persistence, snapshots, and visual diffs;
- Anatomical Voxel skull structure and animation;
- accessibility and reduced interface motion;
- no legacy deletion or Lua execution.

Classify each requirement as proven, contradicted, weak, or missing. Do not claim completion while any required item is weak or missing.

### Step 4: Inspect the running lab visually

Start on loopback:

```powershell
npm run dev -- --host 127.0.0.1
```

Use the in-app browser control skill to inspect the actual rendered workstation at desktop and narrow widths. Exercise all five profiles, comparison mode, playback, scrubbing, skull selection, jaw editing, undo/redo, audit, and snapshot capture. Check browser console output and the diagnostics panel.

### Step 5: Review changes and request code review

```powershell
git status --short
git diff --check main...HEAD
git log --oneline main..HEAD
```

Confirm only intentional feature-worktree files are included. Invoke `superpowers:requesting-code-review`, address verified findings, and rerun the affected checks.

### Step 6: Commit the verification evidence

```powershell
git add v2/apps/visual-lab/verification-report.md
git commit -m "test(lab): document visual lab verification"
```

### Step 7: Integration handoff

Use `superpowers:finishing-a-development-branch` to present the verified branch integration options. Do not merge, push, or delete the worktree without the user's explicit choice.

The active goal may be marked complete only if the final audit proves the full lab objective. Catalog model migration beyond the skull proceeds under the separately approved catalog-review plan and does not justify deleting any legacy implementation.
