# Fabric Mod Stage Handler Parity Design

**Goal:** Port 5 missing stage message handlers from the Paper plugin to the Fabric mod so the control center (admin panel + preview tool) has full stage management and world scanning support.

## Missing Handlers

| Message | Purpose |
|-|-|
| `get_stage` | Return full stage details (zones, configs, decorators) |
| `update_stage` | Move anchor, rotate, add/remove zone roles |
| `set_stage_zone_config` | Update pattern/entity_count/render_mode per zone role |
| `get_stage_templates` | List available templates with roles and descriptions |
| `scan_stage_blocks` | Scan world blocks around stage zones for 3D preview |

## Files to Modify

- `minecraft_mod/src/main/java/com/audioviz/protocol/MessageHandler.java` — Add 5 case branches and handler methods
- `minecraft_mod/src/main/java/com/audioviz/stages/StageManager.java` — Add `addRoleToStage()` and `removeRoleFromStage()` methods

## Key Fabric API Adaptations

- **Block scanning**: `world.getBlockState(new BlockPos(x,y,z))` → `Registries.BLOCK.getId(state.getBlock()).toString()`
- **Air check**: `state.isAir()` (covers AIR, CAVE_AIR, VOID_AIR)
- **Thread safety**: `CompletableFuture` submitted via `mod.getServer().execute()` for main-thread block access
- **Zone data**: Mod's `VisualizationZone` uses `BlockPos` origin + `Vector3f` size — direct match

## Response Formats

All responses match the Paper plugin's format so admin panel and preview tool work without changes.

### `get_stage` response
```json
{"type": "stage", "stage": {"name": "...", "id": "...", "template": "...", "active": true, "rotation": 0, "world": "minecraft:overworld", "anchor": {"x": 0, "y": 64, "z": 0}, "zones": {"MAIN_STAGE": {"zone_name": "...", "config": {...}}}, "decorators": {...}}}
```

### `scan_stage_blocks` response
```json
{"type": "stage_blocks", "stage": "...", "palette": ["STONE", "GRASS_BLOCK"], "blocks": [[x,y,z,paletteIdx], ...], "bounds": {"minX": 0, "minY": 60, "minZ": 0, "maxX": 50, "maxY": 80, "maxZ": 50}}
```
