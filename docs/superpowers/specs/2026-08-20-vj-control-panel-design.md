# Performance-First VJ Control Panel Design

**Date:** 2026-08-20
**Status:** Ready for written-spec review
**Target:** `admin_panel/`

## Summary

Redesign the MCAV browser control panel around a performance-first, 1920×1080 live workspace. The new interface must keep every current capability available while making show-critical controls visible or one action away. Configuration and diagnostics move into focused secondary workspaces so they no longer compete with live operation.

The application remains a static vanilla HTML/CSS/ES-module frontend served by the VJ server. The WebSocket protocol and server-authoritative show state do not change. The implementation will finish and preserve the existing untracked module extraction rather than introducing a framework rewrite or a second competing architecture.

## Problem

The current panel began as a compact mixer and accumulated stage management, multi-zone rendering, bitmap walls, scenes, particles, DJ administration, banner editing, voice chat, synchronization, parity checks, and a 3D preview. These capabilities now share a mostly linear tab-and-section hierarchy. The result is excessive scrolling, weak separation between live and setup tasks, and insufficient prominence for the preview and emergency controls.

The runtime is also split between a tracked `admin-app.js` of roughly 5,900 lines and an untracked, unwired extraction under `js/modules/`, `js/managers/`, and `js/ui/`. The redesign must resolve that duplication without discarding the user's in-progress work.

## Goals

- Make the Live workspace usable without vertical scrolling at 1920×1080.
- Keep the stage output, show state, audio response, launch controls, and emergency actions continuously understandable.
- Separate live operation from visual setup, zone setup, DJ administration, and system diagnostics.
- Preserve every existing control and backend capability.
- Retain the static frontend deployment model and current WebSocket protocol.
- Finish the existing module extraction and leave `AdminApp` as a small composition root.
- Meet WCAG AA expectations for text, controls, focus, motion, and non-color state cues.
- Maintain low-cost targeted DOM updates during high-frequency audio traffic.

## Non-Goals

- Rewriting the panel in React or another framework.
- Adding a dockable or user-programmable workspace system.
- Adding a new cue/program bus, A/B mixer, or backend show protocol.
- Changing VJ-server, DJ-client, or Minecraft-plugin message contracts.
- Removing controls or reducing existing functionality.
- Optimizing for mobile as a primary show-control environment.

## Primary Context

The primary operator is a VJ running a live show on a standard 1920×1080 desktop display. The interface should feel closer to Resolume or VDMX than to a general-purpose admin dashboard: dark, dense, glanceable, and focused on output state. Narrow desktop and tablet layouts remain functional, but they may stack configuration content and use drawers.

## Information Architecture

### Global command bar

The command bar remains visible in every workspace and contains:

- MCAV identity and current workspace name.
- VJ-server connection and reconnect action.
- Minecraft service/backend status.
- Active DJ identity and health summary.
- Stage and zone selection.
- Compact latency, synchronization, and FPS telemetry.
- Fixed blackout and freeze actions.

Blackout and freeze never move between workspaces. Their active state must be unmistakable and must not depend on color alone.

### Navigation rail

A compact left rail provides five destinations:

1. Live
2. Visuals
3. Zones
4. DJs
5. System

Live is the default. The last non-Live workspace may be remembered locally, but reconnecting or opening the panel without a saved preference starts in Live.

### Capability mapping

|Existing capability|Destination|
|-|-|
|Connection, Minecraft status, stage/zone selection|Global command bar|
|Pattern, preset, BPM, beat, meters, frame/FPS, latency|Live output and telemetry|
|Blackout, freeze, tap tempo|Fixed show controls|
|Patterns, scenes, transition timing|Live launch deck|
|One-shot effects and particle triggers|Live effects deck|
|Band sensitivity and common audio shaping|Live audio strip|
|Advanced audio response settings|Expanded Live audio strip|
|Bitmap patterns, palettes, effects, text, layers, DJ logo|Visuals|
|Particle visualization and particle configuration|Visuals|
|Stages, zones, render modes, entity settings, materials, dimensions, overlays, zone actions|Zones|
|DJ roster, pending approvals, connect codes, DJ profile and banner|DJs|
|Visual sync, voice chat, parity checks, service details and diagnostics|System|

The Visuals and Zones workspaces include a smaller sticky output preview so configuration changes retain visual context.

## Live Workspace

The desktop Live view uses a 12-column grid beneath the command bar and beside the navigation rail.

### Stage Output

The upper-left eight columns contain the largest surface: the existing Three.js preview. Lightweight overlays show the active stage and zones, current pattern or scene, BPM, beat state, and preview FPS. Five band meters sit along the preview edge without obscuring the visualization. Camera reset and auto-rotate remain available but visually secondary.

### Show Control rack

The upper-right four columns contain:

- Current scene, pattern, and preset.
- Transition duration and transition activity.
- Tap tempo and BPM.
- Active DJ and synchronization quality.
- Fixed blackout and freeze controls.

The rack favors large, stable hit targets and does not reorder itself as state changes.

### Launch deck

The lower-left eight columns contain switchable Scene and Pattern banks. Recently used and locally favorited items appear first, followed by the full collection. Search filters the visible collection without changing server data. Pattern keyboard shortcuts remain supported and are exposed in focus and hover hints.

### Effects deck

The lower-right four columns contain one-shot visual effects and enabled particle-effect triggers. Controls expose firing, unavailable, and disabled states. Configuration for these effects lives in Visuals.

### Audio strip

A compact strip spans the bottom of Live and exposes the five bands, master sensitivity, gain, attack, release, and beat sensitivity. Less-used controls expand in place without navigating away. Meter updates remain animation-frame throttled.

## Secondary Workspaces

### Visuals

Visuals groups bitmap-wall content, particle presentation, advanced pattern presentation, and reusable visual assets. It uses focused sub-navigation rather than one long page. A sticky mini-preview remains visible on desktop.

### Zones

Zones combines the stage/zone tree with the selected zone's render mode, entity or particle properties, band materials, dimensions, overlays, and maintenance actions. Multi-zone selection remains supported. Destructive actions such as cleanup retain confirmation.

### DJs

DJs provides the roster, pending approvals, connect-code management, per-DJ synchronization health, profiles, logo palette, and banner controls. The current DJ remains summarized globally and in Live.

### System

System contains visual synchronization, voice chat, link and service state, parity checks, reconnect details, and diagnostics. Show-critical health summaries are duplicated in compact form in the command bar; detailed controls live here.

## Visual System

- Use `#08090d` for the deepest background and `#0f1118` for raised surfaces.
- Use translucent card fills sparingly; the preview and data should dominate the chrome.
- Use cyan for active output and primary actions, indigo for secondary or selected context, amber for timing and warnings, and red only for danger or emergency state.
- Use Space Grotesk for workspace and section titles, Inter for controls and prose, and JetBrains Mono for live numeric data.
- Use compact 40–44 px controls, with larger emergency and launch targets.
- Consolidate spacing, typography, radius, elevation, state, and motion values into canonical tokens.
- Avoid ornamental gradients, excessive glass, and permanent glow. Glow communicates beat, active output, focus, or urgency.

## Interaction and Safety

- Blackout, freeze, tap tempo, reconnect, and established keyboard shortcuts remain immediately available.
- Blackout and freeze execute immediately. Their state uses text/icon changes and surface treatment in addition to color.
- Configuration actions that destroy or reset state require confirmation through the existing modal system.
- Selected, active-output, previewed, unavailable, stale, loading, disconnected, and error states are distinct.
- Disconnection leaves the last known show state visible but marked stale. Unsafe mutations are disabled until reconnection.
- Focus order follows visual order. Focus rings are always visible for keyboard users.
- Animations are short and restrained. `prefers-reduced-motion` removes beat pulses, glow animation, and nonessential transitions.
- Favorites, recent items, active workspace, and presentation preferences may use local storage. Show state never does.

## Responsive Behavior

- At 1600 px and wider, show the complete command bar, labeled navigation, full telemetry, and no-scroll Live grid.
- From 1280–1599 px, shorten labels and collapse secondary telemetry before reducing show controls.
- From 900–1279 px, use an icon-first rail and compact telemetry; configuration pages may stack.
- Below 900 px, use a stacked, scrollable layout. All actions remain reachable, but this is not the primary live-performance mode.
- Touch targets remain at least 44 px where the layout is expected to support touch.

## Frontend Architecture

### Composition root

`AdminApp` will own startup, shared state, the WebSocket service, and manager construction. Domain behavior moves to the existing extracted modules:

- `ElementCache` owns stable element lookup.
- `EventWiring` binds global and workspace events once.
- `MessageRouter` dispatches incoming messages.
- `AudioManager`, `PatternManager`, `SceneManager`, `ActionsManager`, `ZoneManager`, `DJManager`, `ConnectCodeManager`, `BannerManager`, `ParticleEffectsManager`, and `VoiceChatManager` own their domains.
- `PreviewManager` and `BitmapManager` retain high-cost visualization responsibilities.
- `UIHelpers` and `ModalDialog` provide shared presentation behavior.

The extraction will be integrated incrementally. Each module must be compared with its corresponding monolith methods for state, event, and message parity before duplicated methods are removed.

### DOM contract

Existing IDs remain stable where server state or handlers depend on them. Workspace selection and reusable components use semantic `data-workspace`, `data-action`, and `data-state` hooks. JavaScript must not depend on purely visual layout classes.

### State and data flow

1. `WebSocketService` receives and validates the message envelope as it does today.
2. `MessageRouter` forwards the message to one domain manager.
3. The manager updates shared state and its scoped DOM.
4. High-frequency audio and preview updates are throttled or scheduled through `requestAnimationFrame`.
5. User actions send the existing message types through `WebSocketService`.

The server remains authoritative for show state. A reconnect snapshot replaces stale state and refreshes all affected managers.

### Styling

`mcav-tokens.css` remains the canonical brand-token source and gains the missing spacing, typography, sizing, motion, elevation, and semantic-state tokens. Shared base and control styles stay separate from workspace layout rules. Superseded layout selectors are removed only after the new DOM is visually verified.

## Error Handling

- Connection and protocol failures surface through persistent status plus concise toasts when appropriate.
- Missing optional capabilities render an unavailable state rather than hiding controls without explanation.
- Preview initialization failure disables only the preview and leaves all control functions available.
- Invalid or incomplete server payloads are handled at the message boundary and must not partially mutate unrelated UI.
- Local-storage failures fall back to defaults without affecting show control.
- Destructive or networked actions report success, pending, and failure states explicitly.

## Accessibility

- Meet WCAG AA contrast for text and interactive states.
- Preserve correct tablist, tabpanel, button, slider, select, dialog, and live-region semantics.
- Provide accessible names for icon-only controls and values for live meters.
- Never use color as the only status indicator.
- Keep keyboard shortcuts inactive while typing in editable controls.
- Restore focus after modal closure and workspace transitions where appropriate.
- Verify reduced-motion behavior and visible focus at every responsive breakpoint.

## Performance Constraints

- No framework runtime or new render loop is introduced.
- Audio meters and Three.js updates remain animation-frame throttled.
- DOM queries are cached and event delegation is used for dynamic collections.
- Workspace switching uses visibility and lifecycle hooks; hidden expensive previews do not continue unnecessary work.
- No full workspace rerender occurs for an audio frame or a single status message.

## Verification Strategy

### Static and build checks

- Run `npm run build` from the repository root.
- Run a syntax check over every first-party ES module; use the Vite build to resolve the complete import graph.
- Run the existing `vj_server/tests/test_static_http.py` coverage for static path routing and serving behavior.

### Behavior checks

- Connect, reconnect, stale-state, and disabled-mutation behavior.
- Workspace navigation and restoration.
- Stage/zone selection, multi-zone selection, and pattern application.
- Scene and pattern launching, transitions, presets, effects, and particle triggers.
- Blackout, freeze, tap tempo, and keyboard shortcuts.
- Bitmap, zone, DJ, banner, voice-chat, synchronization, and parity controls.
- Confirmation, loading, unavailable, success, and failure states.

### Visual and accessibility checks

- Inspect the rendered interface at 1920×1080, 1440×900, 1280×800, and a narrow fallback.
- Confirm that Live has no vertical scroll at 1920×1080.
- Check clipping, overlap, focus order, labels, contrast, reduced motion, and non-color state cues.
- Capture final Live, Visuals, Zones, DJs, and System screenshots for comparison.

### Preservation audit

Before cleanup, build an inventory of current interactive IDs and message-producing actions. After the redesign, confirm that every item maps to the new DOM and domain manager. Only then remove duplicated monolith methods and superseded layout CSS.

## Cleanup Authorization

The user approved removing duplicated implementations from `admin-app.js` and superseded layout CSS after their extracted replacements are wired and verified. This authorization does not extend to removing any feature, control, protocol behavior, or unrelated file.

## Success Criteria

- The Live workspace fits within 1920×1080 without vertical scrolling.
- Preview, active output, launch controls, audio response, emergency controls, and core health state are simultaneously understandable.
- Every existing panel capability remains reachable and functional.
- The five-workspace hierarchy is implemented with stable global controls.
- The current WebSocket protocol and static VJ-server delivery remain compatible.
- Existing extracted modules are integrated; `AdminApp` is no longer the domain monolith.
- Duplicated code is removed only after parity is demonstrated.
- Build, static-serving tests, browser interaction checks, visual checks, and accessibility checks pass.
