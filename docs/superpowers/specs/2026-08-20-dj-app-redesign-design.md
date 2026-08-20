# MCAV DJ App End-to-End Redesign

**Date:** 2026-08-20
**Status:** Approved direction; detailed design selected under the user's instruction to use the best product and design choices

## Summary

Redesign the Tauri DJ client around the two moments that matter during a show: getting safely ready and understanding live state at a glance. The disconnected experience becomes an integrated preflight flow. The connected experience becomes a unified performance cockpit. Both use the canonical MCAV visual system and the same responsive component hierarchy.

The redesign preserves the Rust commands, WebSocket protocol, authentication model, audio processing, preset behavior, queue behavior, and voice-streaming behavior. It may expose existing frontend state more clearly and improve frontend error handling, but it does not add backend services or change cross-runtime contracts.

## Goals

- Make first-run and repeat connection setup obvious without a blocking tutorial.
- Make live, standby, routing, latency, audio energy, BPM, preset, source, voice, and queue state readable within one glance.
- Give the DJ client the visual authority of professional VJ software while remaining unmistakably MCAV.
- Keep the primary controls visible without clutter or deep navigation.
- Work cleanly at the default Tauri window size and down to the supported minimum size.
- Surface realistic audio and connection failures in the interface instead of relying on console logging.
- Preserve keyboard access, screen-reader semantics, visible focus, and reduced-motion behavior.
- Avoid new runtime dependencies unless an existing platform capability cannot solve the problem.

## Non-goals

- New audio-analysis algorithms, frequency bands, presets, or capture backends.
- Changes to coordinator, VJ server, Minecraft plugin, or protocol schemas.
- A pattern browser, full mixer, timeline, scene editor, or other new workstation subsystem.
- Light mode or a user-configurable theme system.
- Replacing Tauri's native window chrome.

## Selected Direction

Use a performance-first unified cockpit.

A cosmetic reskin was rejected because it would preserve the current fragmented hierarchy. A multi-page workstation was rejected because this client has one focused job and would gain complexity without improving live use. The selected direction restructures the existing capabilities into a clearer workflow and establishes a distinctive signal-flow identity without widening product scope.

## Product Structure

The app has two primary states inside one persistent shell:

1. **Preflight:** prepare identity, session, and audio input, then connect.
2. **Performance:** monitor the live signal and show state, adjust the existing runtime controls, and disconnect safely.

Authentication remains available in the header but does not interrupt code-based connection. Email verification remains a compact actionable banner. Advanced self-hosted connection settings remain available without competing with the normal connect-code path.

## Preflight Experience

Replace the blocking welcome overlay with a preflight surface that teaches through its structure.

The preflight surface contains:

- A compact product header with MCAV identity, optional account state, and no decorative marketing copy.
- A three-part readiness sequence: **Identity**, **Show**, and **Audio input**. These labels describe real requirements rather than decorative steps.
- DJ name as a labeled field, prefilled from saved state when available.
- Connect code as the primary session field with automatic uppercase formatting and visible `XXXX-XXXX` grouping.
- Audio source selection with refresh, source type, and an explicit ten-second input test using the existing capture test capability.
- A small five-band test visualization and a clear testing/stopped/error state.
- A single primary action labeled **Connect to show**. While pending it becomes **Connecting…** and retains layout width.
- Self-hosted host and port controls inside a collapsed **Advanced connection** disclosure.
- Inline error guidance adjacent to the failed boundary. Errors explain what failed and the next action.

First-launch guidance is a short, dismissible callout embedded above the readiness sequence. It uses the existing `mcav.onboardingComplete` preference so current users are not shown onboarding again. It never blocks interaction.

Connection readiness is computed from a non-empty DJ name, a complete connect code, and a selected source. A failed source enumeration or missing source prevents connection and explains how to retry.

## Performance Cockpit

The connected state uses four stable regions.

### Performance header

- DJ identity and show name on the left.
- A prominent live or standby state in the center-left, never encoded by color alone.
- Account/profile affordance on the right when signed in.
- Beat feedback belongs to the audio stage rather than consuming isolated header space.

### Audio stage

The five-band meter is the visual and informational center of the app. It replaces the current split between fixed-size `FrequencyMeter` and compact `FrequencyStrip` presentations with one responsive component.

- Five bands retain the protocol order: bass, low-mid, mid, high-mid, and high.
- Each band has a readable label, normalized value, and consistent cyan-to-indigo-to-amber progression.
- BPM is a large, stable data point rather than a small footer label.
- Beat response is concentrated into one short pulse on the signal path and bass energy, avoiding scattered animation.
- The canvas responds to container size and device pixel ratio instead of using a fixed 400-by-160 CSS size.
- Presets sit directly below the meter as a labeled mode selector. The selected preset is conveyed by shape, text, and color.

### System rail

- Live/standby state and current active DJ.
- Coordinator/VJ route, Minecraft route, and capture mode.
- Latency with normal, warning, and degraded labels based on existing status data.
- Queue position and roster, ordered as supplied by the server.
- Empty, disconnected-route, and no-other-DJs states provide direct explanations.

### Control dock

- Current audio source with source-type context and refresh.
- Voice streaming toggle with explicit on/off wording and availability state.
- Disconnect at the far edge and visually separated from routine controls.
- Disconnect is a two-step frontend action: the first activation arms **Confirm disconnect** for three seconds; the second disconnects. Escape or timer expiry cancels. The existing Ctrl/Cmd+D shortcut follows the same safety behavior.

## Signature Element: Signal Bus

A thin signal bus visually links the selected input, audio stage, and output status. It is not decorative: its state communicates the real pipeline.

- Muted gray means no detected energy or an unavailable route.
- Cyan and indigo intensity follow current peak/band energy.
- A short amber impulse marks a beat.
- The output end changes to success green only when the app is connected and the Minecraft route is available.
- Reduced-motion mode keeps the same state colors without travel or pulse animation.

The signal bus is implemented with a focused component that reads the existing audio ref on `requestAnimationFrame` and updates its own CSS custom properties. It must not cause React renders for every audio frame.

## Visual System

Use the canonical shared tokens as the source of truth.

- Deep background: `#08090d`
- Raised surface: `#0f1118`
- Glass card fill: `rgba(255,255,255,0.03)`
- Subtle border: `rgba(255,255,255,0.06)`
- Primary signal: `#00CCFF`
- Secondary signal: `#5B6AFF`
- Beat/warm signal: `#FFAA00`
- Success: `#2fe098`
- Warning: `#ffd166`
- Danger: `#ff6767`

Space Grotesk carries product identity, state headings, and major BPM typography. Inter carries interface labels and body copy. JetBrains Mono carries codes, latency, percentages, routes, and other machine data. The HTML font request must load all three families so the shared Inter token does not silently fall back.

Cards use restrained glass treatment, compact radii, and low-contrast borders. Color comes from live data and state rather than large filled backgrounds. The current cyan-to-green connect gradient is replaced with the brand cyan-to-indigo relationship; amber remains reserved for beat energy and warm emphasis.

## Layout and Window Behavior

The primary Tauri window becomes 960 by 640 pixels. The minimum supported size becomes 640 by 480 pixels. The window remains resizable and uses native decorations.

- At 860 pixels and above, the performance view uses a dominant audio stage and a 260-to-280-pixel system rail.
- Below 860 pixels, the system rail moves below the audio stage and begins with a compact always-visible status summary.
- Below 720 pixels, preflight fields stack, the dock wraps into two rows, and secondary status details become collapsible.
- Short-height windows keep the header and control dock stable while the content region scrolls.
- The DOM keeps one semantic copy of each control and status region. CSS changes placement; React does not render separate compact and expanded versions.

## Component Boundaries

Keep orchestration in `App.tsx` and move state-specific presentation into focused components.

- `DisconnectedView` becomes the preflight composition while retaining its existing module identity.
- `ConnectedView` becomes the cockpit composition while retaining its existing module identity.
- `TopBar` becomes a shared product/performance header with state-aware content.
- A new `AudioStage` owns the responsive meter, BPM, beat feedback, presets, and accessible text alternative.
- A new `SystemRail` composes route/status and queue information.
- A new `ControlDock` owns source, voice, and guarded disconnect controls.
- A small reusable inline notice handles actionable info, warning, and error messages.
- Existing legacy presentation components may remain in the repository if no longer referenced; deletion requires separate confirmation under repository policy.

The components depend on typed view models and callbacks, not entire hook return objects where a smaller interface is practical. `any` is removed from shared presentation props when the existing auth types can express the value.

## State and Data Flow

`useConnection` remains the authority for connection, show, live status, audio frames, presets, voice, capture mode, and roster. `useAudioSources` remains the authority for source enumeration, selection, and test capture.

Frontend changes may add:

- Exposed test bands and source-operation error state from `useAudioSources`.
- Explicit pending/error feedback for source changes, presets, voice toggling, and disconnect.
- Rollback to the previous source selection if a connected source change fails.
- A local disconnect-armed state and timer, isolated from backend connection state.

No audio frame is copied into React state at frame rate. Canvas and signal animation continue to read from refs. Event listeners and timers are cleaned up on unmount and state transition.

## Error Handling

Errors are handled at the boundary that can fail and remain recoverable.

- Source enumeration: show **Audio sources unavailable** with a retry action.
- Audio test start or polling: stop the test safely and show a retryable message.
- Code resolution: preserve the existing not-found, full-show, offline, and authentication-specific messages.
- Capture start after connection: disconnect the partially established session before showing the error, preventing a connected-without-audio state.
- Connected source change: restore the previous selection if the backend rejects the change.
- Preset and voice changes: retain the last confirmed UI state and show a compact actionable notice.
- Disconnect failure: keep the connected state visible and report that the session could not be closed.

Console logging remains useful for diagnostics but is never the only feedback for a user-triggered failure.

## Accessibility

- Every field has a programmatic label and stable ID.
- Status updates use a restrained `aria-live` region; audio frame changes do not announce continuously.
- Live/standby, route health, voice state, and selected preset are never indicated only by color.
- The canvas has an accessible summary that reports band values and BPM at a throttled, non-live frequency.
- All buttons have explicit types and meaningful accessible names.
- Keyboard order follows the visual workflow in both preflight and performance states.
- Focus-visible styling meets the 3:1 component contrast target.
- Text and controls target WCAG AA contrast.
- `prefers-reduced-motion` disables pulses, traveling signal effects, glow animation, and nonessential transitions.
- Touch targets remain at least 36 pixels in the desktop app's compact layout.

## Verification Strategy

Implementation is complete only when all of the following evidence is collected:

- `npm run build` passes in `dj_client`.
- `npm run test:containment` passes in `dj_client`.
- `cargo test --manifest-path src-tauri/Cargo.toml` passes or any environment-specific failure is reported with exact output and diagnosis.
- The Tauri development app or local Vite surface is visually inspected at 960-by-640, 700-by-520, and 640-by-480.
- Preflight is verified for first-run guidance, saved-user behavior, direct-connect disclosure, disabled/enabled readiness, testing state, and visible errors.
- Performance is verified for live and standby hierarchy, audio-stage scaling, preset selection, queue states, source changes, voice states, disconnect arming, and route degradation.
- Keyboard focus, Ctrl/Cmd shortcuts, Escape behavior, and reduced-motion rendering are manually checked.
- Browser console output is checked for new runtime errors during the verified frontend flows.

## Acceptance Criteria

- A first-time DJ can understand what is required and reach a ready-to-connect state without dismissing a modal.
- A returning DJ can connect using saved identity/source state with minimal interaction.
- A performing DJ can identify live/standby state, signal presence, BPM, route health, latency, preset, and queue position without navigating or scrolling at the primary window size.
- The UI remains usable without clipping at 640 by 480 pixels.
- The visual hierarchy and typography match the MCAV dark, neon-accented, performance-oriented brand.
- Audio reactivity is smooth and does not introduce frame-rate React rendering.
- Existing backend and protocol interfaces continue to work unchanged.
- User-triggered I/O failures produce visible recovery guidance.
- The production frontend build and relevant automated tests pass.
