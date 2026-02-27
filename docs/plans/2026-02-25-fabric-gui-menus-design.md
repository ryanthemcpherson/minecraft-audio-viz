# Fabric GUI Menu System Design

**Date**: 2026-02-25
**Status**: Approved
**Scope**: Port 7 missing SGUI menus from Paper plugin to Fabric mod

## Context

The Fabric mod has 4 existing SGUI menus (MainMenu, DJControlMenu, SettingsMenu, ZoneManagementMenu). The Paper plugin has 7 additional menus needed for admin workflow. This design ports them as a clean rewrite using SGUI patterns already established in the mod.

## Decisions

- **GUI library**: SGUI 1.12.0 (already in use)
- **Text input**: AnvilInputGui for all text input (names, search, config). No chat input.
- **Deferred features**: Zone boundary rendering, placement wizard, 3D stage preview — stubbed with TODO
- **Approach**: Clean rewrite around SGUI patterns, not 1:1 Paper port

## Base Class Enhancements

Add to `AudioVizGui`:

- `backButton(int slot, Runnable action)` — standard back arrow item
- `slot(int row, int col)` → `row * 9 + col`
- `promptTextInput(String title, String defaultText, Consumer<String> callback)` — opens AnvilInputGui, returns text, reopens current menu on cancel
- `paginate(List<T> items, int page, int perPage, int startSlot, int endSlot, BiConsumer<Integer, T> renderer)` — pagination helper

## Navigation Flow

```
MainMenu (add Stages button)
  ├── ZoneManagementMenu (existing)
  │   └── ZoneEditorMenu — size/rotation/pool controls
  │       └── ZoneTemplateMenu — preset sizes
  ├── StageListMenu — paginated, sort/filter/search
  │   ├── StageEditorMenu — spatial zone grid, move/rotate
  │   │   ├── StageDecoratorMenu — toggle/configure decorators
  │   │   └── StageVJMenu — live pattern/intensity/effects
  │   └── StageTemplateMenu — pick template on create
  ├── DJControlMenu (existing)
  └── SettingsMenu (existing)
```

## Menu Specifications

### StageListMenu (6 rows, 54 slots)

- **Row 0**: Back, Sort (cycle: name/template/status), Filter (cycle: all/active/inactive), Search (AnvilInputGui), page info
- **Rows 1-4**: Stage items (28/page). Name, template, zone count, active status. Left-click → editor, right-click → toggle active, shift-click → delete
- **Row 5**: Prev page, Create New (→ template menu), Next page, Refresh

### StageTemplateMenu (5 rows, 45 slots)

- **Row 0**: Back + header
- **Rows 1-2**: 4 templates (small/medium/large/custom) with zone roles and entity count
- Click → AnvilInputGui for name → create at player pos → open editor

### StageEditorMenu (6 rows, 54 slots)

- **Row 0**: Stage name/info, active toggle
- **Rows 1-3**: Spatial zone grid:
  - Row 1: LEFT_WING (10), MAIN_STAGE (13), RIGHT_WING (16)
  - Row 2: BACKSTAGE (10), RUNWAY (13), AUDIENCE (16)
  - Row 3: SKYBOX (10), BALCONY (16)
  - Empty = gray glass, populated = colored wool with zone info
  - Left-click → ZoneEditorMenu, right-click → assign/unassign role
- **Row 5**: Back, Move, Rotate (±15°), Decorators, VJ Control, Delete

### ZoneEditorMenu (4 rows, 36 slots)

- **Row 0**: Back + zone info (name, origin)
- **Row 1**: Size X/Y/Z with [-][value][+] controls (click=1, shift=5)
- **Row 2**: Rotation [-15°][+15°], Init Pool, Cleanup, Teleport
- **Row 3**: Back, Delete (shift-confirm)

### StageDecoratorMenu (5 rows, 45 slots)

- **Row 0**: Back + header
- **Rows 1-2**: 7 decorators (billboard, spotlights, floor tiles, crowd fx, beat text, banner, transition). Green/red wool for enabled/disabled. Left-click toggle, right-click → AnvilInputGui config
- **Row 3**: Enable All, Disable All
- **Row 4**: Back

### StageVJMenu (6 rows, 54 slots)

- **Row 0**: Header with stage name + zone count
- **Row 1**: Zone selectors (up to 8 + select-all)
- **Row 2**: Intensity slider (7 levels)
- **Row 3**: Pattern prev/display/next + render mode
- **Row 4**: 8 effect triggers (blackout, freeze, flash, strobe, pulse, wave, spiral, explode)
- **Row 5**: Back, 3 presets (chill/party/rave), Apply

### ZoneTemplateMenu (5 rows, 45 slots)

- **Row 0**: Back + header
- **Rows 1-2**: 6 presets (tiny 4³, small 8³, medium 16x12x16, large 24x16x24, flat 16x1x16, tower 4x24x4) + Custom
- Click → AnvilInputGui for name → create at player pos

## Implementation Order

1. Base class enhancements (layout helpers, AnvilInputGui, pagination)
2. ZoneTemplateMenu + ZoneEditorMenu
3. StageTemplateMenu
4. StageListMenu
5. StageEditorMenu
6. StageDecoratorMenu
7. StageVJMenu
8. Wire into MainMenu + commands
