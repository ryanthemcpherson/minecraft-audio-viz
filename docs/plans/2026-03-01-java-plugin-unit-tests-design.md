# Java Plugin Unit Test Expansion — Pure Logic Coverage

**Date**: 2026-03-01
**Goal**: Safety net for refactoring — deep unit tests on the most algorithmically complex pure-logic classes in the Minecraft plugin.
**Approach**: Approach A (pure logic only, no Bukkit mocking beyond what's already in place).

## Scope

### New Test Class

| Class | File | Why |
|-|-|-|
| BeatProjectionUtil | `protocol/BeatProjectionUtil.java` | Beat synthesis from BPM/tempo/phase — pure math, zero tests, complex edge cases |

### Expanded Test Classes

| Class | Existing Tests | Expansion Focus |
|-|-|-|
| BitmapFrameBuffer | Basic pixel access, fill | Drawing methods (fillRect, fillCircle, drawRing, scroll), color utils (lerpColor, fromHSB, heatMapColor), gradient fills, boundary clipping, buffer overflow protection |
| ColorPalette | Basic LUT mapping | `mapSmooth()` interpolation, `fromGradient()` with <2 colors / segment boundaries, brightness calc edge cases, NaN/Inf intensity |
| CellGridMerger | Basic grid build | Greedy merge algorithm correctness, odd pixel heights, empty/zero-size inputs, consumed-state tracking |
| AdaptiveEntityAssigner | Basic assignment | FrameDiff dirty detection (geometry/color/uniform changes), slot exhaustion, NaN in pixel scale, cache invalidation |
| EffectsProcessor | Basic enable/disable | Effect chain ordering, strobe decay underflow, freeze frame persistence, RGB-split buffer size mismatch, beat count overflow |
| LayerCompositor | Basic blend | All 7 blend modes with mathematical verification (additive overflow clamping, overlay channel boundary at base=128, screen formula) |
| TransitionManager | Basic lifecycle | Double-buffer render cycle, duration≤0 instant cut, cancel during transition, progress clamping, pattern render exception swallowing |
| AudioState | Basic accessors | Band index out of range [0-4], NaN/Inf in fields, `silent()` factory invariants, null bands default |

## Out of Scope

- Bukkit-heavy classes: BeatEventManager, RendererRegistry, EntityPoolManager, ZoneManager, menus
- Integration / E2E tests
- MockBukkit framework adoption

## Conventions (Match Existing)

- `*Test.java` suffix, same package as source
- `@Nested` + `@DisplayName` for test groups
- `@ParameterizedTest` + `@CsvSource`/`@ValueSource` for boundary sweeps
- Helper factory methods per test class (no shared utilities)
- JUnit 5 assertions (`assertEquals`, `assertTrue`, `assertThrows`)
- Mockito only where BitmapFrameBuffer needs a Bukkit `Color` (minimal)

## Estimated Output

- 1 new test class + 7 expanded test classes
- ~150-200 new test methods
- Focus: edge cases (NaN, Infinity, boundary values, overflow, empty inputs, algorithmic invariants)

## Success Criteria

- All new tests pass with `mvn test`
- No changes to production code
- Tests document expected behavior for edge cases (serve as living spec)
