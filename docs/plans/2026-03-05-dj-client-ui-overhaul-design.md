# DJ Client UI Overhaul — Design Document

**Date:** 2026-03-05
**Status:** Approved

## Goal

Redesign the DJ client's connected dashboard for compact sidebar window usage, improve visual polish to match MCAV brand aesthetic, and extract components from the monolithic App.tsx.

## Constraints

- **Primary window size**: Small/compact sidebar (~300-400px wide)
- **Priority information**: Connection status + queue position > frequency meter > presets
- **App flow**: Keep existing two-state approach (disconnected form / connected dashboard)
- **Tech**: React + Tauri, single CSS file + shared tokens

## Approach: Compact-First Reflow

Single-column vertical stack optimized for compact windows, expanding to 2-column at >720px.

### Compact Layout (default, <720px)

```
┌─────────────────────────┐
│ DJ Name    LIVE     👤  │  Top bar (40px)
├─────────────────────────┤
│ Show: Friday Vibes      │  Status strip (28px x2)
│ Queue: 1/3 · 128 BPM   │
├─────────────────────────┤
│ ▊▊▊▊▊ ▊▊▊ ▊▊ ▊▊▊ ▊▊▊▊ │  Freq strip (48px)
├─────────────────────────┤
│ auto edm chill rock folk│  Presets (32px)
├─────────────────────────┤
│ DJ Queue                │  Queue panel (flex)
│  DJ_Ryan (LIVE)         │
│  DJ_Alex                │
├─────────────────────────┤
│ System Audio     🎤  ×  │  Bottom bar (44px)
└─────────────────────────┘
```

### Expanded Layout (>720px)

2-column grid: left = frequency meter (taller) + presets, right = status + queue.

## Visual Polish

1. **Typography**: Body → Inter (fix current Space Grotesk everywhere). Space Grotesk for headings only.
2. **Status strip**: Glassmorphism card, cyan glow when LIVE, animated connection pulse, BPM in JetBrains Mono.
3. **Frequency strip**: Gradient bars (cyan → indigo), glow behind active bars, beat = amber pulse.
4. **Preset chips**: Active chip gets glow shadow. Colored dot per genre.
5. **Beat indicator**: Larger amber ring with expanding ripple.
6. **Queue panel**: Active DJ gets left cyan accent border.
7. **Bottom bar**: Slightly darker background to anchor layout.

## Component Extraction

Break App.tsx (~840 lines) into:

- `StatusStrip.tsx` — connection status + show info + queue position
- `FrequencyStrip.tsx` — compact horizontal 5-band meter (new canvas renderer)
- `PresetBar.tsx` — preset chip row
- `QueuePanel.tsx` — DJ queue list
- `ConnectForm.tsx` — disconnected state form (connect code + direct connect + history)
- `TopBar.tsx` — DJ name + beat indicator + profile chip

App.tsx becomes a thin shell that manages state and renders the appropriate components.

## Non-Goals

- No new features or functionality changes
- No mobile/tablet support
- No changes to Rust backend or WebSocket protocol
- No changes to disconnected state layout (just component extraction)
