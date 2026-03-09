# Fabric GUI Menu System Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Port 7 missing SGUI menus to the Fabric mod so server admins can create/edit stages, zones, and decorators in-game.

**Architecture:** Extend the existing `AudioVizGui` base class with layout helpers and AnvilInputGui text input. Build 7 new menu classes following the established SGUI patterns (GuiElementBuilder, click callbacks, rebuild()). Wire into MainMenu and commands.

**Tech Stack:** Java 21, SGUI 1.12.0 (eu.pb4.sgui), Fabric 1.21.11

**Key files to reference:**
- Base class: `minecraft_mod/src/main/java/com/audioviz/gui/AudioVizGui.java`
- Existing menus: `minecraft_mod/src/main/java/com/audioviz/gui/menus/MainMenu.java` (pattern reference)
- Stage API: `minecraft_mod/src/main/java/com/audioviz/stages/Stage.java`, `StageManager.java`, `StageZoneRole.java`, `StageZoneConfig.java`
- Zone API: `minecraft_mod/src/main/java/com/audioviz/zones/VisualizationZone.java`, `ZoneManager.java`
- Decorator API: `minecraft_mod/src/main/java/com/audioviz/decorators/DecoratorConfig.java`
- Commands: `minecraft_mod/src/main/java/com/audioviz/commands/AudioVizCommand.java`

---

### Task 1: Base Class Enhancements

**Files:**
- Modify: `minecraft_mod/src/main/java/com/audioviz/gui/AudioVizGui.java`

**Step 1: Add layout and navigation helpers to AudioVizGui**

Add these methods after the existing `playClickSound()` method:

```java
/**
 * Convert row/col to slot index. Row 0 = top, Col 0 = left.
 */
protected static int slot(int row, int col) {
    return row * 9 + col;
}

/**
 * Add a standard back button at the given slot.
 */
protected void setBackButton(int slotIndex, Runnable action) {
    setSlot(slotIndex, new GuiElementBuilder(Items.ARROW)
        .setName(Text.literal("Back").formatted(net.minecraft.util.Formatting.WHITE))
        .setCallback((index, type, act) -> {
            playClickSound();
            action.run();
        }));
}

/**
 * Open an anvil text input GUI. On submit, calls onResult with the typed text.
 * On close without submitting, calls onCancel (which typically reopens this menu).
 */
protected void promptTextInput(String title, String defaultText,
                                java.util.function.Consumer<String> onResult,
                                Runnable onCancel) {
    var anvil = new eu.pb4.sgui.api.gui.AnvilInputGui(getPlayer(), false) {
        @Override
        public void onClose() {
            onCancel.run();
        }
    };
    anvil.setTitle(Text.literal(title));
    anvil.setDefaultInputValue(defaultText != null ? defaultText : "");

    // Confirm button in the output slot (slot 2 of anvil)
    anvil.setSlot(2, new GuiElementBuilder(Items.LIME_CONCRETE)
        .setName(Text.literal("Confirm").formatted(net.minecraft.util.Formatting.GREEN))
        .setCallback((index, type, action) -> {
            String input = anvil.getInput();
            anvil.close();
            onResult.accept(input != null ? input.trim() : "");
        }));

    menuManager.openMenu(getPlayer(), null); // clear tracking of old menu
    anvil.open();
}
```

Add this import at the top:
```java
import eu.pb4.sgui.api.gui.AnvilInputGui;
```

**Step 2: Build and verify**

Run: `cd minecraft_mod && ./gradlew build`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add minecraft_mod/src/main/java/com/audioviz/gui/AudioVizGui.java
git commit -m "feat(gui): add layout helpers and AnvilInputGui to base class"
```

---

### Task 2: ZoneTemplateMenu

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/gui/menus/ZoneTemplateMenu.java`

**Step 1: Create ZoneTemplateMenu**

5-row (45 slot) menu with 6 preset zone sizes + custom option. Each template is a named item that creates a zone at the player's position via AnvilInputGui for the name.

```java
package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import com.audioviz.zones.VisualizationZone;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

public class ZoneTemplateMenu extends AudioVizGui {

    private final AudioVizMod mod;
    private final Runnable onBack;

    /** Preset templates: name, sizeX, sizeY, sizeZ, entityCount, item */
    private static final Object[][] TEMPLATES = {
        {"Tiny (4x4x4)",       4, 4, 4,    16, Items.FLOWER_POT},
        {"Small (8x8x8)",      8, 8, 8,    32, Items.CHEST},
        {"Medium (16x12x16)", 16, 12, 16,  64, Items.CRAFTING_TABLE},
        {"Large (24x16x24)",  24, 16, 24, 128, Items.BEACON},
        {"Flat (16x1x16)",    16, 1, 16,   64, Items.WHITE_CARPET},
        {"Tower (4x24x4)",     4, 24, 4,   48, Items.LIGHTNING_ROD},
    };

    public ZoneTemplateMenu(ServerPlayerEntity player, MenuManager menuManager,
                            AudioVizMod mod, Runnable onBack) {
        super(ScreenHandlerType.GENERIC_9X5, player, menuManager);
        this.mod = mod;
        this.onBack = onBack;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("Zone Templates").formatted(Formatting.AQUA);
    }

    @Override
    protected void build() {
        // Header
        setSlot(slot(0, 4), new GuiElementBuilder(Items.ENDER_EYE)
            .setName(Text.literal("Choose Zone Size").formatted(Formatting.GOLD))
            .addLoreLine(Text.literal("Select a preset or custom size").formatted(Formatting.GRAY)));

        // Templates in rows 1-2 (slots 10,12,14 and 19,21,23)
        int[] slots = {slot(1, 1), slot(1, 3), slot(1, 5), slot(2, 1), slot(2, 3), slot(2, 5)};
        for (int i = 0; i < TEMPLATES.length; i++) {
            Object[] t = TEMPLATES[i];
            String name = (String) t[0];
            int sx = (int) t[1], sy = (int) t[2], sz = (int) t[3];
            int entities = (int) t[4];
            net.minecraft.item.Item icon = (net.minecraft.item.Item) t[5];

            setSlot(slots[i], new GuiElementBuilder(icon)
                .setName(Text.literal(name).formatted(Formatting.AQUA))
                .addLoreLine(Text.literal("Size: " + sx + "x" + sy + "x" + sz).formatted(Formatting.GRAY))
                .addLoreLine(Text.literal("Entities: " + entities).formatted(Formatting.GRAY))
                .addLoreLine(Text.empty())
                .addLoreLine(Text.literal("Click to create zone").formatted(Formatting.YELLOW))
                .setCallback((idx, type, action) -> {
                    playClickSound();
                    promptCreateZone(sx, sy, sz);
                }));
        }

        // Custom option
        setSlot(slot(3, 4), new GuiElementBuilder(Items.ANVIL)
            .setName(Text.literal("Custom Size").formatted(Formatting.LIGHT_PURPLE))
            .addLoreLine(Text.literal("Create with default 10x10x10").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Edit size in Zone Editor after").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to create").formatted(Formatting.YELLOW))
            .setCallback((idx, type, action) -> {
                playClickSound();
                promptCreateZone(10, 10, 10);
            }));

        // Back
        setBackButton(slot(4, 0), onBack);
        fillBackground();
    }

    private void promptCreateZone(int sizeX, int sizeY, int sizeZ) {
        promptTextInput("Zone Name", "", name -> {
            if (name.isEmpty()) {
                menuManager.openMenu(getPlayer(), new ZoneTemplateMenu(getPlayer(), menuManager, mod, onBack));
                return;
            }
            if (mod.getZoneManager().zoneExists(name)) {
                getPlayer().sendMessage(Text.literal("Zone '" + name + "' already exists!").formatted(Formatting.RED));
                menuManager.openMenu(getPlayer(), new ZoneTemplateMenu(getPlayer(), menuManager, mod, onBack));
                return;
            }
            BlockPos pos = getPlayer().getBlockPos();
            VisualizationZone zone = mod.getZoneManager().createZone(name, getPlayer().getEntityWorld(), pos);
            if (zone != null) {
                zone.setSize(sizeX, sizeY, sizeZ);
                mod.getZoneManager().saveZones();
                getPlayer().sendMessage(Text.literal("Created zone '" + name + "' (" + sizeX + "x" + sizeY + "x" + sizeZ + ")")
                    .formatted(Formatting.GREEN));
                // Open the zone editor for the new zone
                menuManager.openMenu(getPlayer(),
                    new ZoneEditorMenu(getPlayer(), menuManager, mod, name, onBack));
            }
        }, () -> menuManager.openMenu(getPlayer(), new ZoneTemplateMenu(getPlayer(), menuManager, mod, onBack)));
    }
}
```

**Step 2: Build and verify**

Run: `cd minecraft_mod && ./gradlew build`
Expected: BUILD SUCCESSFUL (ZoneEditorMenu doesn't exist yet — this will fail. Create a stub first, see Task 3.)

---

### Task 3: ZoneEditorMenu

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/gui/menus/ZoneEditorMenu.java`

**Step 1: Create ZoneEditorMenu**

4-row (36 slot) menu with size X/Y/Z +/- controls, rotation, pool management, teleport, delete.

```java
package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import com.audioviz.zones.VisualizationZone;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.joml.Vector3f;

import java.util.Set;

public class ZoneEditorMenu extends AudioVizGui {

    private final AudioVizMod mod;
    private final String zoneName;
    private final Runnable onBack;

    public ZoneEditorMenu(ServerPlayerEntity player, MenuManager menuManager,
                          AudioVizMod mod, String zoneName, Runnable onBack) {
        super(ScreenHandlerType.GENERIC_9X4, player, menuManager);
        this.mod = mod;
        this.zoneName = zoneName;
        this.onBack = onBack;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("Zone: " + zoneName).formatted(Formatting.AQUA);
    }

    @Override
    protected void build() {
        VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
        if (zone == null) {
            setSlot(slot(1, 4), new GuiElementBuilder(Items.BARRIER)
                .setName(Text.literal("Zone not found!").formatted(Formatting.RED)));
            setBackButton(slot(3, 0), onBack);
            fillBackground();
            return;
        }

        Vector3f size = zone.getSize();
        float rotation = zone.getRotation();

        // Row 0: Info header
        setSlot(slot(0, 4), new GuiElementBuilder(Items.ENDER_EYE)
            .setName(Text.literal(zoneName).formatted(Formatting.AQUA, Formatting.BOLD))
            .addLoreLine(Text.literal("Origin: " + zone.getOrigin().getX() + ", " +
                zone.getOrigin().getY() + ", " + zone.getOrigin().getZ()).formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Size: " + fmt(size.x) + "x" + fmt(size.y) + "x" + fmt(size.z)).formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Rotation: " + fmt(rotation) + "\u00B0").formatted(Formatting.GRAY)));

        // Row 1: Size X [-][val][+]  Size Y [-][val][+]  Size Z [-][val][+]
        buildSizeControl(1, 0, "X", size.x, Formatting.RED, (v) -> {
            zone.setSize(v, zone.getSize().y, zone.getSize().z);
            save();
        });
        buildSizeControl(1, 3, "Y", size.y, Formatting.GREEN, (v) -> {
            zone.setSize(zone.getSize().x, v, zone.getSize().z);
            save();
        });
        buildSizeControl(1, 6, "Z", size.z, Formatting.BLUE, (v) -> {
            zone.setSize(zone.getSize().x, zone.getSize().y, v);
            save();
        });

        // Row 2: Rotation [-][val][+], Init Pool, Cleanup, Teleport
        setSlot(slot(2, 0), new GuiElementBuilder(Items.RED_DYE)
            .setName(Text.literal("-15\u00B0").formatted(Formatting.RED))
            .addLoreLine(Text.literal("Shift: -45\u00B0").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                float step = type == ClickType.MOUSE_LEFT_SHIFT ? 45 : 15;
                zone.setRotation(zone.getRotation() - step);
                save();
            }));
        setSlot(slot(2, 1), new GuiElementBuilder(Items.COMPASS)
            .setName(Text.literal("Rotation: " + fmt(rotation) + "\u00B0").formatted(Formatting.YELLOW)));
        setSlot(slot(2, 2), new GuiElementBuilder(Items.LIME_DYE)
            .setName(Text.literal("+15\u00B0").formatted(Formatting.GREEN))
            .addLoreLine(Text.literal("Shift: +45\u00B0").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                float step = type == ClickType.MOUSE_LEFT_SHIFT ? 45 : 15;
                zone.setRotation(zone.getRotation() + step);
                save();
            }));

        // Init Pool
        setSlot(slot(2, 4), new GuiElementBuilder(Items.SPAWNER)
            .setName(Text.literal("Init Pool").formatted(Formatting.LIGHT_PURPLE))
            .addLoreLine(Text.literal("Left: 64 entities").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Right: 128 entities").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Shift-Left: 16 entities").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                int count = switch (type) {
                    case MOUSE_LEFT_SHIFT -> 16;
                    case MOUSE_RIGHT -> 128;
                    default -> 64;
                };
                mod.getVirtualRenderer().initializePool(zoneName, zone, count, zone.getWorld());
                getPlayer().sendMessage(Text.literal("Pool initialized: " + count + " entities").formatted(Formatting.GREEN));
                rebuild();
            }));

        // Cleanup
        setSlot(slot(2, 5), new GuiElementBuilder(Items.TNT)
            .setName(Text.literal("Cleanup").formatted(Formatting.RED))
            .addLoreLine(Text.literal("Destroy entity pool & map display").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                mod.getVirtualRenderer().destroyPool(zoneName);
                mod.getMapRenderer().destroyDisplay(zoneName);
                getPlayer().sendMessage(Text.literal("Zone cleaned up").formatted(Formatting.YELLOW));
                rebuild();
            }));

        // Teleport
        setSlot(slot(2, 7), new GuiElementBuilder(Items.ENDER_PEARL)
            .setName(Text.literal("Teleport").formatted(Formatting.AQUA))
            .setCallback((i, type, a) -> {
                playClickSound();
                close();
                getPlayer().teleport(zone.getWorld(),
                    zone.getOrigin().getX() + 0.5, (double) zone.getOrigin().getY(),
                    zone.getOrigin().getZ() + 0.5, Set.of(),
                    getPlayer().getYaw(), getPlayer().getPitch(), false);
            }));

        // Row 3: Back, Boundaries (stub), Delete
        setBackButton(slot(3, 0), onBack);

        setSlot(slot(3, 4), new GuiElementBuilder(Items.GLASS)
            .setName(Text.literal("Show Boundaries").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Coming soon").formatted(Formatting.DARK_GRAY)));

        setSlot(slot(3, 8), new GuiElementBuilder(Items.BARRIER)
            .setName(Text.literal("Delete Zone").formatted(Formatting.RED, Formatting.BOLD))
            .addLoreLine(Text.literal("Shift-click to confirm").formatted(Formatting.DARK_RED))
            .setCallback((i, type, a) -> {
                if (type == ClickType.MOUSE_LEFT_SHIFT || type == ClickType.MOUSE_RIGHT_SHIFT) {
                    playClickSound();
                    mod.getVirtualRenderer().destroyPool(zoneName);
                    mod.getMapRenderer().destroyDisplay(zoneName);
                    mod.getZoneManager().deleteZone(zoneName);
                    getPlayer().sendMessage(Text.literal("Deleted zone '" + zoneName + "'").formatted(Formatting.RED));
                    onBack.run();
                }
            }));

        fillBackground();
    }

    private void buildSizeControl(int row, int startCol, String axis, float current,
                                   Formatting color, java.util.function.Consumer<Float> setter) {
        setSlot(slot(row, startCol), new GuiElementBuilder(Items.RED_DYE)
            .setName(Text.literal("-").formatted(Formatting.RED))
            .addLoreLine(Text.literal("Click: -1 | Shift: -5").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                float step = (type == ClickType.MOUSE_LEFT_SHIFT) ? 5 : 1;
                setter.accept(Math.max(1, current - step));
                rebuild();
            }));
        setSlot(slot(row, startCol + 1), new GuiElementBuilder(Items.PAPER)
            .setName(Text.literal(axis + ": " + fmt(current)).formatted(color)));
        setSlot(slot(row, startCol + 2), new GuiElementBuilder(Items.LIME_DYE)
            .setName(Text.literal("+").formatted(Formatting.GREEN))
            .addLoreLine(Text.literal("Click: +1 | Shift: +5").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                float step = (type == ClickType.MOUSE_LEFT_SHIFT) ? 5 : 1;
                setter.accept(Math.min(100, current + step));
                rebuild();
            }));
    }

    private void save() {
        mod.getZoneManager().saveZones();
        rebuild();
    }

    private static String fmt(float v) {
        return v == (int) v ? String.valueOf((int) v) : String.format("%.1f", v);
    }
}
```

**Step 2: Build and verify**

Run: `cd minecraft_mod && ./gradlew build`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add minecraft_mod/src/main/java/com/audioviz/gui/menus/ZoneTemplateMenu.java \
        minecraft_mod/src/main/java/com/audioviz/gui/menus/ZoneEditorMenu.java
git commit -m "feat(gui): add ZoneTemplateMenu and ZoneEditorMenu"
```

---

### Task 4: StageTemplateMenu

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/gui/menus/StageTemplateMenu.java`

**Step 1: Create StageTemplateMenu**

5-row menu with 4 stage templates (small/medium/large/custom). On selection, prompts for stage name via AnvilInputGui, creates stage at player position, opens StageEditorMenu.

Templates use `StageManager.createStage()` which auto-creates zones for all `StageZoneRole` values. The template name is stored as metadata but currently all templates create the same zone set — the template differences are in the display description only.

```java
package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import com.audioviz.stages.Stage;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

public class StageTemplateMenu extends AudioVizGui {

    private final AudioVizMod mod;
    private final Runnable onBack;

    public StageTemplateMenu(ServerPlayerEntity player, MenuManager menuManager,
                             AudioVizMod mod, Runnable onBack) {
        super(ScreenHandlerType.GENERIC_9X5, player, menuManager);
        this.mod = mod;
        this.onBack = onBack;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("New Stage - Choose Template").formatted(Formatting.GOLD);
    }

    @Override
    protected void build() {
        setSlot(slot(0, 4), new GuiElementBuilder(Items.NETHER_STAR)
            .setName(Text.literal("Stage Templates").formatted(Formatting.GOLD, Formatting.BOLD))
            .addLoreLine(Text.literal("Choose a layout for your stage").formatted(Formatting.GRAY)));

        // Small stage
        setSlot(slot(1, 1), new GuiElementBuilder(Items.OAK_SIGN)
            .setName(Text.literal("Small Stage").formatted(Formatting.GREEN))
            .addLoreLine(Text.literal("8 zones, compact layout").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Good for small servers").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to create").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> { playClickSound(); promptCreate("small"); }));

        // Medium stage
        setSlot(slot(1, 3), new GuiElementBuilder(Items.OAK_HANGING_SIGN)
            .setName(Text.literal("Medium Stage").formatted(Formatting.AQUA))
            .addLoreLine(Text.literal("8 zones, standard layout").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Recommended for most setups").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to create").formatted(Formatting.YELLOW))
            .glow()
            .setCallback((i, t, a) -> { playClickSound(); promptCreate("medium"); }));

        // Large stage
        setSlot(slot(1, 5), new GuiElementBuilder(Items.BEACON)
            .setName(Text.literal("Large Stage").formatted(Formatting.LIGHT_PURPLE))
            .addLoreLine(Text.literal("8 zones, expanded layout").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("For large venues, high entity count").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to create").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> { playClickSound(); promptCreate("large"); }));

        // Custom
        setSlot(slot(1, 7), new GuiElementBuilder(Items.COMMAND_BLOCK)
            .setName(Text.literal("Custom").formatted(Formatting.GOLD))
            .addLoreLine(Text.literal("8 zones, default sizes").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Fully customize after creation").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to create").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> { playClickSound(); promptCreate("custom"); }));

        setBackButton(slot(4, 0), onBack);
        fillBackground();
    }

    private void promptCreate(String template) {
        promptTextInput("Stage Name", "", name -> {
            if (name.isEmpty()) {
                reopen();
                return;
            }
            if (mod.getStageManager().stageExists(name)) {
                getPlayer().sendMessage(Text.literal("Stage '" + name + "' already exists!").formatted(Formatting.RED));
                reopen();
                return;
            }
            BlockPos pos = getPlayer().getBlockPos();
            String worldName = getPlayer().getEntityWorld().getRegistryKey().getValue().toString();
            Stage stage = mod.getStageManager().createStage(name, pos, worldName, template);
            if (stage != null) {
                getPlayer().sendMessage(Text.literal("Created stage '" + name + "' with " +
                    stage.getRoleToZone().size() + " zones").formatted(Formatting.GREEN));
                menuManager.openMenu(getPlayer(),
                    new StageEditorMenu(getPlayer(), menuManager, mod, stage.getName(), onBack));
            } else {
                getPlayer().sendMessage(Text.literal("Failed to create stage").formatted(Formatting.RED));
                reopen();
            }
        }, this::reopen);
    }

    private void reopen() {
        menuManager.openMenu(getPlayer(), new StageTemplateMenu(getPlayer(), menuManager, mod, onBack));
    }
}
```

**Step 2: Build and verify** (StageEditorMenu doesn't exist yet — build will fail until Task 6)

---

### Task 5: StageListMenu

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/gui/menus/StageListMenu.java`

**Step 1: Create StageListMenu**

6-row paginated menu listing all stages with sort, filter, search. Left-click opens editor, right-click toggles active, shift-click deletes.

```java
package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import com.audioviz.stages.Stage;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class StageListMenu extends AudioVizGui {

    private final AudioVizMod mod;
    private final Runnable onBack;

    private int page = 0;
    private int sortMode = 0; // 0=name, 1=template, 2=status
    private int filterMode = 0; // 0=all, 1=active, 2=inactive
    private String searchQuery = "";

    private static final int ITEMS_PER_PAGE = 28; // rows 1-4, 7 per row
    private static final String[] SORT_NAMES = {"Name", "Template", "Status"};
    private static final String[] FILTER_NAMES = {"All", "Active", "Inactive"};

    public StageListMenu(ServerPlayerEntity player, MenuManager menuManager,
                         AudioVizMod mod, Runnable onBack) {
        super(ScreenHandlerType.GENERIC_9X6, player, menuManager);
        this.mod = mod;
        this.onBack = onBack;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("Stages").formatted(Formatting.GOLD);
    }

    @Override
    protected void build() {
        List<Stage> stages = getFilteredStages();
        int totalPages = Math.max(1, (stages.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        if (page >= totalPages) page = totalPages - 1;

        // Row 0: Controls
        setBackButton(slot(0, 0), onBack);

        setSlot(slot(0, 2), new GuiElementBuilder(Items.HOPPER)
            .setName(Text.literal("Sort: " + SORT_NAMES[sortMode]).formatted(Formatting.YELLOW))
            .addLoreLine(Text.literal("Click to cycle").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> { playClickSound(); sortMode = (sortMode + 1) % SORT_NAMES.length; rebuild(); }));

        setSlot(slot(0, 3), new GuiElementBuilder(Items.PAPER)
            .setName(Text.literal("Filter: " + FILTER_NAMES[filterMode]).formatted(Formatting.AQUA))
            .addLoreLine(Text.literal("Click to cycle").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> { playClickSound(); filterMode = (filterMode + 1) % FILTER_NAMES.length; rebuild(); }));

        setSlot(slot(0, 4), new GuiElementBuilder(Items.SPYGLASS)
            .setName(Text.literal("Search" + (searchQuery.isEmpty() ? "" : ": " + searchQuery)).formatted(Formatting.GREEN))
            .addLoreLine(Text.literal("Click to search by name").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Right-click to clear").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                if (type == ClickType.MOUSE_RIGHT) {
                    searchQuery = "";
                    page = 0;
                    rebuild();
                } else {
                    promptTextInput("Search Stages", searchQuery, query -> {
                        searchQuery = query;
                        page = 0;
                        menuManager.openMenu(getPlayer(), this);
                        rebuild();
                    }, () -> { menuManager.openMenu(getPlayer(), this); rebuild(); });
                }
            }));

        setSlot(slot(0, 8), new GuiElementBuilder(Items.CLOCK)
            .setName(Text.literal("Page " + (page + 1) + "/" + totalPages).formatted(Formatting.WHITE)));

        // Rows 1-4: Stage items
        int startIdx = page * ITEMS_PER_PAGE;
        int slotIdx = 0;
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                int stageIdx = startIdx + slotIdx;
                if (stageIdx < stages.size()) {
                    Stage stage = stages.get(stageIdx);
                    setSlot(slot(row, col), buildStageItem(stage));
                }
                slotIdx++;
            }
        }

        // Row 5: Navigation
        if (page > 0) {
            setSlot(slot(5, 1), new GuiElementBuilder(Items.ARROW)
                .setName(Text.literal("\u2190 Previous").formatted(Formatting.WHITE))
                .setCallback((i, t, a) -> { playClickSound(); page--; rebuild(); }));
        }

        setSlot(slot(5, 4), new GuiElementBuilder(Items.EMERALD)
            .setName(Text.literal("Create New Stage").formatted(Formatting.GREEN, Formatting.BOLD))
            .addLoreLine(Text.literal("Click to choose template").formatted(Formatting.YELLOW))
            .glow()
            .setCallback((i, t, a) -> {
                playClickSound();
                menuManager.openMenu(getPlayer(),
                    new StageTemplateMenu(getPlayer(), menuManager, mod, () ->
                        menuManager.openMenu(getPlayer(), new StageListMenu(getPlayer(), menuManager, mod, onBack))));
            }));

        if (page < totalPages - 1) {
            setSlot(slot(5, 7), new GuiElementBuilder(Items.ARROW)
                .setName(Text.literal("Next \u2192").formatted(Formatting.WHITE))
                .setCallback((i, t, a) -> { playClickSound(); page++; rebuild(); }));
        }

        setSlot(slot(5, 8), new GuiElementBuilder(Items.SUNFLOWER)
            .setName(Text.literal("Refresh").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> { playClickSound(); rebuild(); }));

        fillBackground();
    }

    private GuiElementBuilder buildStageItem(Stage stage) {
        boolean active = stage.isActive();
        return new GuiElementBuilder(active ? Items.LIME_WOOL : Items.RED_WOOL)
            .setName(Text.literal(stage.getName()).formatted(active ? Formatting.GREEN : Formatting.RED))
            .addLoreLine(Text.literal("Template: " + stage.getTemplateName()).formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Zones: " + stage.getRoleToZone().size()).formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Status: " + (active ? "Active" : "Inactive"))
                .formatted(active ? Formatting.GREEN : Formatting.RED))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Left: Edit | Right: Toggle active").formatted(Formatting.YELLOW))
            .addLoreLine(Text.literal("Shift-click: Delete").formatted(Formatting.DARK_RED))
            .setCallback((i, type, a) -> {
                playClickSound();
                if (type == ClickType.MOUSE_LEFT_SHIFT || type == ClickType.MOUSE_RIGHT_SHIFT) {
                    mod.getStageManager().deleteStage(stage.getName());
                    getPlayer().sendMessage(Text.literal("Deleted stage '" + stage.getName() + "'").formatted(Formatting.RED));
                    rebuild();
                } else if (type == ClickType.MOUSE_RIGHT) {
                    if (active) mod.getStageManager().deactivateStage(stage);
                    else mod.getStageManager().activateStage(stage);
                    rebuild();
                } else {
                    menuManager.openMenu(getPlayer(),
                        new StageEditorMenu(getPlayer(), menuManager, mod, stage.getName(), () ->
                            menuManager.openMenu(getPlayer(), new StageListMenu(getPlayer(), menuManager, mod, onBack))));
                }
            });
    }

    private List<Stage> getFilteredStages() {
        List<Stage> result = new ArrayList<>(mod.getStageManager().getAllStages());

        // Filter
        if (filterMode == 1) result.removeIf(s -> !s.isActive());
        else if (filterMode == 2) result.removeIf(Stage::isActive);

        // Search
        if (!searchQuery.isEmpty()) {
            String query = searchQuery.toLowerCase();
            result.removeIf(s -> !s.getName().toLowerCase().contains(query)
                && !s.getTemplateName().toLowerCase().contains(query));
        }

        // Sort
        switch (sortMode) {
            case 0 -> result.sort(Comparator.comparing(Stage::getName));
            case 1 -> result.sort(Comparator.comparing(Stage::getTemplateName));
            case 2 -> result.sort(Comparator.comparing(Stage::isActive).reversed()
                .thenComparing(Stage::getName));
        }

        return result;
    }
}
```

**Step 2: Build** — depends on StageEditorMenu from Task 6.

---

### Task 6: StageEditorMenu

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/gui/menus/StageEditorMenu.java`

**Step 1: Create StageEditorMenu**

6-row menu with spatial zone grid (rows 1-3), stage controls (row 5). The zone grid mimics the 2D stage layout: wings/main in row 1, backstage/runway/audience in row 2, skybox/balcony in row 3. Populated zones show colored wool, empty roles show gray glass.

```java
package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneRole;
import com.audioviz.zones.VisualizationZone;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

public class StageEditorMenu extends AudioVizGui {

    private final AudioVizMod mod;
    private final String stageName;
    private final Runnable onBack;

    // Spatial grid: map each role to a fixed slot
    private static final Map<StageZoneRole, Integer> ROLE_SLOTS = Map.of(
        StageZoneRole.LEFT_WING,  slot(1, 1),
        StageZoneRole.MAIN_STAGE, slot(1, 4),
        StageZoneRole.RIGHT_WING, slot(1, 7),
        StageZoneRole.BACKSTAGE,  slot(2, 1),
        StageZoneRole.RUNWAY,     slot(2, 4),
        StageZoneRole.AUDIENCE,   slot(2, 7),
        StageZoneRole.SKYBOX,     slot(3, 1),
        StageZoneRole.BALCONY,    slot(3, 7)
    );

    private static final Map<StageZoneRole, Formatting> ROLE_COLORS = Map.of(
        StageZoneRole.MAIN_STAGE, Formatting.GOLD,
        StageZoneRole.LEFT_WING,  Formatting.BLUE,
        StageZoneRole.RIGHT_WING, Formatting.BLUE,
        StageZoneRole.SKYBOX,     Formatting.AQUA,
        StageZoneRole.AUDIENCE,   Formatting.GREEN,
        StageZoneRole.BACKSTAGE,  Formatting.GRAY,
        StageZoneRole.RUNWAY,     Formatting.LIGHT_PURPLE,
        StageZoneRole.BALCONY,    Formatting.YELLOW
    );

    public StageEditorMenu(ServerPlayerEntity player, MenuManager menuManager,
                           AudioVizMod mod, String stageName, Runnable onBack) {
        super(ScreenHandlerType.GENERIC_9X6, player, menuManager);
        this.mod = mod;
        this.stageName = stageName;
        this.onBack = onBack;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("Stage: " + stageName).formatted(Formatting.GOLD);
    }

    @Override
    protected void build() {
        Stage stage = mod.getStageManager().getStage(stageName);
        if (stage == null) {
            setSlot(slot(2, 4), new GuiElementBuilder(Items.BARRIER)
                .setName(Text.literal("Stage not found!").formatted(Formatting.RED)));
            setBackButton(slot(5, 0), onBack);
            fillBackground();
            return;
        }

        // Row 0: Header
        boolean active = stage.isActive();
        setSlot(slot(0, 4), new GuiElementBuilder(active ? Items.NETHER_STAR : Items.COAL)
            .setName(Text.literal(stageName).formatted(Formatting.GOLD, Formatting.BOLD))
            .addLoreLine(Text.literal("Template: " + stage.getTemplateName()).formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Anchor: " + stage.getAnchor().getX() + ", " +
                stage.getAnchor().getY() + ", " + stage.getAnchor().getZ()).formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Rotation: " + (int) stage.getRotation() + "\u00B0").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Zones: " + stage.getRoleToZone().size() + "/8").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to toggle " + (active ? "OFF" : "ON"))
                .formatted(active ? Formatting.RED : Formatting.GREEN))
            .setCallback((i, t, a) -> {
                playClickSound();
                if (active) mod.getStageManager().deactivateStage(stage);
                else mod.getStageManager().activateStage(stage);
                rebuild();
            }));

        // Rows 1-3: Spatial zone grid
        for (StageZoneRole role : StageZoneRole.values()) {
            Integer slotPos = ROLE_SLOTS.get(role);
            if (slotPos == null) continue;

            String zoneName = stage.getZoneName(role);
            Formatting color = ROLE_COLORS.getOrDefault(role, Formatting.WHITE);

            if (zoneName != null) {
                VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
                var builder = new GuiElementBuilder(Items.LIME_WOOL)
                    .setName(Text.literal(role.getDisplayName()).formatted(color, Formatting.BOLD))
                    .addLoreLine(Text.literal("Zone: " + zoneName).formatted(Formatting.GRAY));

                if (zone != null) {
                    builder.addLoreLine(Text.literal("Size: " + (int) zone.getSize().x + "x" +
                        (int) zone.getSize().y + "x" + (int) zone.getSize().z).formatted(Formatting.GRAY));
                }
                builder.addLoreLine(Text.empty())
                    .addLoreLine(Text.literal("Left: Edit zone").formatted(Formatting.YELLOW))
                    .addLoreLine(Text.literal("Right: Unassign role").formatted(Formatting.RED));

                String finalZoneName = zoneName;
                builder.setCallback((i, type, act) -> {
                    playClickSound();
                    if (type == ClickType.MOUSE_RIGHT) {
                        stage.getRoleToZone().remove(role);
                        mod.getStageManager().saveStages();
                        rebuild();
                    } else {
                        menuManager.openMenu(getPlayer(),
                            new ZoneEditorMenu(getPlayer(), menuManager, mod, finalZoneName, () ->
                                menuManager.openMenu(getPlayer(),
                                    new StageEditorMenu(getPlayer(), menuManager, mod, stageName, onBack))));
                    }
                });
                setSlot(slotPos, builder);
            } else {
                setSlot(slotPos, new GuiElementBuilder(Items.GRAY_STAINED_GLASS_PANE)
                    .setName(Text.literal(role.getDisplayName()).formatted(Formatting.DARK_GRAY))
                    .addLoreLine(Text.literal("No zone assigned").formatted(Formatting.GRAY))
                    .addLoreLine(Text.literal("Click to assign").formatted(Formatting.YELLOW))
                    .setCallback((i, t, a) -> {
                        playClickSound();
                        // Auto-create a zone for this role
                        String autoName = stageName.toLowerCase() + "_" + role.name().toLowerCase();
                        if (!mod.getZoneManager().zoneExists(autoName)) {
                            var worldPos = stage.getWorldPositionForRole(role);
                            BlockPos zoneOrigin = new BlockPos((int) worldPos.x, (int) worldPos.y, (int) worldPos.z);
                            var world = findWorld(stage.getWorldName());
                            if (world != null) {
                                var zone = mod.getZoneManager().createZone(autoName, world, zoneOrigin);
                                if (zone != null) {
                                    var defaultSize = role.getDefaultSize();
                                    zone.setSize(defaultSize.x, defaultSize.y, defaultSize.z);
                                    mod.getZoneManager().saveZones();
                                }
                            }
                        }
                        stage.getRoleToZone().put(role, autoName);
                        mod.getStageManager().saveStages();
                        rebuild();
                    }));
            }
        }

        // Row 4: separator (just background)

        // Row 5: Actions
        setBackButton(slot(5, 0), onBack);

        // Move to player
        setSlot(slot(5, 1), new GuiElementBuilder(Items.COMPASS)
            .setName(Text.literal("Move Here").formatted(Formatting.AQUA))
            .addLoreLine(Text.literal("Set anchor to your position").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                stage.setAnchor(getPlayer().getBlockPos());
                stage.setWorldName(getPlayer().getEntityWorld().getRegistryKey().getValue().toString());
                mod.getStageManager().saveStages();
                getPlayer().sendMessage(Text.literal("Stage moved to your position").formatted(Formatting.GREEN));
                rebuild();
            }));

        // Rotate
        setSlot(slot(5, 2), new GuiElementBuilder(Items.RECOVERY_COMPASS)
            .setName(Text.literal("Rotate +15\u00B0").formatted(Formatting.YELLOW))
            .addLoreLine(Text.literal("Current: " + (int) stage.getRotation() + "\u00B0").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Shift: -15\u00B0").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                float step = (type == ClickType.MOUSE_LEFT_SHIFT) ? -15 : 15;
                stage.setRotation(stage.getRotation() + step);
                mod.getStageManager().saveStages();
                rebuild();
            }));

        // Decorators
        setSlot(slot(5, 4), new GuiElementBuilder(Items.FIREWORK_ROCKET)
            .setName(Text.literal("Decorators").formatted(Formatting.LIGHT_PURPLE))
            .addLoreLine(Text.literal("Billboard, spotlights, effects...").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                menuManager.openMenu(getPlayer(),
                    new StageDecoratorMenu(getPlayer(), menuManager, mod, stageName, () ->
                        menuManager.openMenu(getPlayer(),
                            new StageEditorMenu(getPlayer(), menuManager, mod, stageName, onBack))));
            }));

        // VJ Control
        setSlot(slot(5, 5), new GuiElementBuilder(Items.NOTE_BLOCK)
            .setName(Text.literal("VJ Control").formatted(Formatting.GOLD))
            .addLoreLine(Text.literal("Patterns, intensity, effects").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                menuManager.openMenu(getPlayer(),
                    new StageVJMenu(getPlayer(), menuManager, mod, stageName, () ->
                        menuManager.openMenu(getPlayer(),
                            new StageEditorMenu(getPlayer(), menuManager, mod, stageName, onBack))));
            }));

        // Delete
        setSlot(slot(5, 8), new GuiElementBuilder(Items.BARRIER)
            .setName(Text.literal("Delete Stage").formatted(Formatting.RED, Formatting.BOLD))
            .addLoreLine(Text.literal("Shift-click to confirm").formatted(Formatting.DARK_RED))
            .addLoreLine(Text.literal("Also deletes all zones!").formatted(Formatting.DARK_RED))
            .setCallback((i, type, a) -> {
                if (type == ClickType.MOUSE_LEFT_SHIFT || type == ClickType.MOUSE_RIGHT_SHIFT) {
                    playClickSound();
                    mod.getStageManager().deleteStage(stageName);
                    getPlayer().sendMessage(Text.literal("Deleted stage '" + stageName + "'").formatted(Formatting.RED));
                    onBack.run();
                }
            }));

        fillBackground();
    }

    private net.minecraft.server.world.ServerWorld findWorld(String worldName) {
        for (var w : mod.getServer().getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(worldName)) return w;
        }
        for (var w : mod.getServer().getWorlds()) return w;
        return null;
    }
}
```

**Step 2: Build and verify**

Run: `cd minecraft_mod && ./gradlew build`
Expected: Compile errors for StageDecoratorMenu and StageVJMenu (not yet created). These will be resolved in Tasks 7-8.

---

### Task 7: StageDecoratorMenu

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/gui/menus/StageDecoratorMenu.java`

**Step 1: Create StageDecoratorMenu**

5-row menu listing 7 decorator types. Each shows enabled/disabled state with green/red wool. Left-click toggles, right-click opens config editing via AnvilInputGui (key=value format).

```java
package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.decorators.DecoratorConfig;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import com.audioviz.stages.Stage;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class StageDecoratorMenu extends AudioVizGui {

    private final AudioVizMod mod;
    private final String stageName;
    private final Runnable onBack;

    // Decorator definitions: id, display name, icon, description
    private static final Object[][] DECORATORS = {
        {"billboard",   "DJ Billboard",      Items.OAK_SIGN,        "DJ name above stage"},
        {"spotlight",   "Spotlights",        Items.SEA_LANTERN,     "Sweeping light beams"},
        {"floor_tiles", "Floor Tiles",       Items.PURPLE_CONCRETE, "Reactive dance floor"},
        {"crowd_fx",    "Crowd FX",          Items.FIREWORK_ROCKET, "Audience particles"},
        {"beat_text",   "Beat Text FX",      Items.WRITABLE_BOOK,   "Hype text on beats"},
        {"banner",      "DJ Banner",         Items.WHITE_BANNER,    "DJ branding display"},
        {"transition",  "DJ Transitions",    Items.ENDER_EYE,       "Blackout/flash on DJ switch"},
    };

    public StageDecoratorMenu(ServerPlayerEntity player, MenuManager menuManager,
                              AudioVizMod mod, String stageName, Runnable onBack) {
        super(ScreenHandlerType.GENERIC_9X5, player, menuManager);
        this.mod = mod;
        this.stageName = stageName;
        this.onBack = onBack;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("Decorators: " + stageName).formatted(Formatting.LIGHT_PURPLE);
    }

    @Override
    protected void build() {
        Stage stage = mod.getStageManager().getStage(stageName);
        if (stage == null) {
            setSlot(slot(2, 4), new GuiElementBuilder(Items.BARRIER)
                .setName(Text.literal("Stage not found!").formatted(Formatting.RED)));
            setBackButton(slot(4, 0), onBack);
            fillBackground();
            return;
        }

        setSlot(slot(0, 4), new GuiElementBuilder(Items.FIREWORK_STAR)
            .setName(Text.literal("Stage Decorators").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD))
            .addLoreLine(Text.literal("Left: Toggle on/off").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Right: Edit config (key=value)").formatted(Formatting.GRAY)));

        // Row 1: decorators 0-3 (slots 10, 12, 14, 16)
        // Row 2: decorators 4-6 (slots 19, 21, 23)
        int[] slots = {slot(1, 1), slot(1, 3), slot(1, 5), slot(1, 7),
                       slot(2, 1), slot(2, 3), slot(2, 5)};

        for (int i = 0; i < DECORATORS.length; i++) {
            Object[] d = DECORATORS[i];
            String decoId = (String) d[0];
            String displayName = (String) d[1];
            Item icon = (Item) d[2];
            String desc = (String) d[3];

            DecoratorConfig config = stage.getDecoratorConfig(decoId);
            boolean enabled = config != null && config.isEnabled();

            var builder = new GuiElementBuilder(enabled ? icon : Items.GRAY_DYE)
                .setName(Text.literal(displayName).formatted(enabled ? Formatting.GREEN : Formatting.RED))
                .addLoreLine(Text.literal(enabled ? "Enabled" : "Disabled")
                    .formatted(enabled ? Formatting.GREEN : Formatting.RED))
                .addLoreLine(Text.literal(desc).formatted(Formatting.GRAY));

            if (config != null && !config.getSettings().isEmpty()) {
                builder.addLoreLine(Text.empty());
                for (var entry : config.getSettings().entrySet()) {
                    builder.addLoreLine(Text.literal("  " + entry.getKey() + "=" + entry.getValue())
                        .formatted(Formatting.DARK_GRAY));
                }
            }

            builder.addLoreLine(Text.empty())
                .addLoreLine(Text.literal("Left: Toggle | Right: Config").formatted(Formatting.YELLOW));

            if (enabled) builder.glow();

            builder.setCallback((idx, type, act) -> {
                playClickSound();
                if (type == ClickType.MOUSE_RIGHT) {
                    promptTextInput("Config (" + decoId + "): key=value", "", input -> {
                        if (input.contains("=")) {
                            String[] parts = input.split("=", 2);
                            DecoratorConfig cfg = stage.getDecoratorConfig(decoId);
                            if (cfg == null) { cfg = new DecoratorConfig(); stage.setDecoratorConfig(decoId, cfg); }
                            cfg.set(parts[0].trim(), parts[1].trim());
                            mod.getStageManager().saveStages();
                            getPlayer().sendMessage(Text.literal("Set " + parts[0].trim() + "=" + parts[1].trim()).formatted(Formatting.GREEN));
                        }
                        menuManager.openMenu(getPlayer(),
                            new StageDecoratorMenu(getPlayer(), menuManager, mod, stageName, onBack));
                    }, () -> menuManager.openMenu(getPlayer(),
                        new StageDecoratorMenu(getPlayer(), menuManager, mod, stageName, onBack)));
                } else {
                    DecoratorConfig cfg = stage.getDecoratorConfig(decoId);
                    if (cfg == null) { cfg = new DecoratorConfig(); stage.setDecoratorConfig(decoId, cfg); }
                    cfg.setEnabled(!enabled);
                    mod.getStageManager().saveStages();
                    rebuild();
                }
            });

            setSlot(slots[i], builder);
        }

        // Row 3: Bulk controls
        setSlot(slot(3, 2), new GuiElementBuilder(Items.LIME_DYE)
            .setName(Text.literal("Enable All").formatted(Formatting.GREEN))
            .setCallback((i, t, a) -> {
                playClickSound();
                for (Object[] d : DECORATORS) {
                    DecoratorConfig cfg = stage.getDecoratorConfig((String) d[0]);
                    if (cfg == null) { cfg = new DecoratorConfig(); stage.setDecoratorConfig((String) d[0], cfg); }
                    cfg.setEnabled(true);
                }
                mod.getStageManager().saveStages();
                rebuild();
            }));

        setSlot(slot(3, 6), new GuiElementBuilder(Items.RED_DYE)
            .setName(Text.literal("Disable All").formatted(Formatting.RED))
            .setCallback((i, t, a) -> {
                playClickSound();
                for (Object[] d : DECORATORS) {
                    DecoratorConfig cfg = stage.getDecoratorConfig((String) d[0]);
                    if (cfg != null) cfg.setEnabled(false);
                }
                mod.getStageManager().saveStages();
                rebuild();
            }));

        setBackButton(slot(4, 0), onBack);
        fillBackground();
    }
}
```

**Step 2: Build and verify**

---

### Task 8: StageVJMenu

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/gui/menus/StageVJMenu.java`

**Step 1: Create StageVJMenu**

6-row live performance control menu with zone selectors, intensity slider, pattern cycling, and effect triggers.

```java
package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneConfig;
import com.audioviz.stages.StageZoneRole;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

public class StageVJMenu extends AudioVizGui {

    private final AudioVizMod mod;
    private final String stageName;
    private final Runnable onBack;

    private final Set<StageZoneRole> selectedZones = new HashSet<>();
    private int intensity = 4; // 1-7
    private int patternIndex = 0;

    private static final String[] EFFECT_NAMES = {
        "Blackout", "Freeze", "Flash", "Strobe", "Pulse", "Wave", "Spiral", "Explode"
    };
    private static final net.minecraft.item.Item[] EFFECT_ICONS = {
        Items.BLACK_WOOL, Items.PACKED_ICE, Items.GLOWSTONE, Items.REDSTONE_LAMP,
        Items.HEART_OF_THE_SEA, Items.PRISMARINE_SHARD, Items.NAUTILUS_SHELL, Items.TNT
    };

    public StageVJMenu(ServerPlayerEntity player, MenuManager menuManager,
                       AudioVizMod mod, String stageName, Runnable onBack) {
        super(ScreenHandlerType.GENERIC_9X6, player, menuManager);
        this.mod = mod;
        this.stageName = stageName;
        this.onBack = onBack;
        // Select all zones by default
        Stage stage = mod.getStageManager().getStage(stageName);
        if (stage != null) selectedZones.addAll(stage.getActiveRoles());
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("VJ: " + stageName).formatted(Formatting.GOLD, Formatting.BOLD);
    }

    @Override
    protected void build() {
        Stage stage = mod.getStageManager().getStage(stageName);
        if (stage == null) {
            setSlot(slot(2, 4), new GuiElementBuilder(Items.BARRIER)
                .setName(Text.literal("Stage not found!").formatted(Formatting.RED)));
            setBackButton(slot(5, 0), onBack);
            fillBackground();
            return;
        }

        // Row 0: Header
        setSlot(slot(0, 4), new GuiElementBuilder(Items.NOTE_BLOCK)
            .setName(Text.literal("VJ Control Panel").formatted(Formatting.GOLD, Formatting.BOLD))
            .addLoreLine(Text.literal("Selected: " + selectedZones.size() + " zones").formatted(Formatting.GRAY)));

        // Row 1: Zone selectors
        List<StageZoneRole> roles = new ArrayList<>(stage.getActiveRoles());
        roles.sort(Comparator.comparingInt(Enum::ordinal));

        for (int i = 0; i < Math.min(roles.size(), 8); i++) {
            StageZoneRole role = roles.get(i);
            boolean selected = selectedZones.contains(role);
            setSlot(slot(1, i), new GuiElementBuilder(selected ? Items.LIME_DYE : Items.GRAY_DYE)
                .setName(Text.literal(role.getDisplayName()).formatted(selected ? Formatting.GREEN : Formatting.GRAY))
                .addLoreLine(Text.literal(selected ? "Selected" : "Unselected")
                    .formatted(selected ? Formatting.GREEN : Formatting.RED))
                .setCallback((idx, type, act) -> {
                    playClickSound();
                    if (selected) selectedZones.remove(role);
                    else selectedZones.add(role);
                    rebuild();
                }));
        }

        // Select All toggle
        boolean allSelected = selectedZones.containsAll(stage.getActiveRoles());
        setSlot(slot(1, 8), new GuiElementBuilder(allSelected ? Items.LIME_WOOL : Items.WHITE_WOOL)
            .setName(Text.literal(allSelected ? "Deselect All" : "Select All").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> {
                playClickSound();
                if (allSelected) selectedZones.clear();
                else selectedZones.addAll(stage.getActiveRoles());
                rebuild();
            }));

        // Row 2: Intensity slider (7 levels)
        net.minecraft.item.Item[] woolColors = {
            Items.BLACK_WOOL, Items.GRAY_WOOL, Items.LIGHT_GRAY_WOOL, Items.WHITE_WOOL,
            Items.YELLOW_WOOL, Items.ORANGE_WOOL, Items.RED_WOOL
        };
        for (int level = 1; level <= 7; level++) {
            boolean active = level <= intensity;
            int finalLevel = level;
            setSlot(slot(2, level), new GuiElementBuilder(active ? woolColors[level - 1] : Items.BLACK_STAINED_GLASS_PANE)
                .setName(Text.literal("Intensity " + level).formatted(active ? Formatting.YELLOW : Formatting.DARK_GRAY))
                .setCallback((i, t, a) -> {
                    playClickSound();
                    intensity = finalLevel;
                    applyIntensity(stage);
                    rebuild();
                }));
        }

        // Row 3: Pattern controls
        List<String> patterns = mod.getBitmapPatternManager() != null
            ? mod.getBitmapPatternManager().getPatternIds() : List.of();
        if (!patterns.isEmpty() && patternIndex >= patterns.size()) patternIndex = 0;
        String currentPattern = patterns.isEmpty() ? "none" : patterns.get(patternIndex);

        setSlot(slot(3, 2), new GuiElementBuilder(Items.ARROW)
            .setName(Text.literal("\u2190 Prev Pattern").formatted(Formatting.WHITE))
            .setCallback((i, t, a) -> {
                playClickSound();
                patternIndex = (patternIndex - 1 + patterns.size()) % Math.max(1, patterns.size());
                rebuild();
            }));

        setSlot(slot(3, 4), new GuiElementBuilder(Items.PAINTING)
            .setName(Text.literal("Pattern: " + currentPattern).formatted(Formatting.AQUA, Formatting.BOLD))
            .addLoreLine(Text.literal("Click to apply to selected zones").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> {
                playClickSound();
                applyPattern(stage, currentPattern);
                getPlayer().sendMessage(Text.literal("Applied '" + currentPattern + "' to " +
                    selectedZones.size() + " zones").formatted(Formatting.GREEN));
            }));

        setSlot(slot(3, 6), new GuiElementBuilder(Items.ARROW)
            .setName(Text.literal("Next Pattern \u2192").formatted(Formatting.WHITE))
            .setCallback((i, t, a) -> {
                playClickSound();
                patternIndex = (patternIndex + 1) % Math.max(1, patterns.size());
                rebuild();
            }));

        // Row 4: Effect triggers
        for (int i = 0; i < EFFECT_NAMES.length; i++) {
            String effectName = EFFECT_NAMES[i];
            setSlot(slot(4, i), new GuiElementBuilder(EFFECT_ICONS[i])
                .setName(Text.literal(effectName).formatted(Formatting.GOLD))
                .addLoreLine(Text.literal("Click to trigger").formatted(Formatting.YELLOW))
                .setCallback((idx, type, act) -> {
                    playClickSound();
                    triggerEffect(effectName.toLowerCase());
                }));
        }

        // Row 5: Back + presets
        setBackButton(slot(5, 0), onBack);

        setSlot(slot(5, 3), new GuiElementBuilder(Items.BLUE_DYE)
            .setName(Text.literal("Chill").formatted(Formatting.BLUE))
            .setCallback((i, t, a) -> { playClickSound(); intensity = 2; applyIntensity(stage); rebuild(); }));
        setSlot(slot(5, 4), new GuiElementBuilder(Items.YELLOW_DYE)
            .setName(Text.literal("Party").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> { playClickSound(); intensity = 5; applyIntensity(stage); rebuild(); }));
        setSlot(slot(5, 5), new GuiElementBuilder(Items.RED_DYE)
            .setName(Text.literal("Rave").formatted(Formatting.RED))
            .setCallback((i, t, a) -> { playClickSound(); intensity = 7; applyIntensity(stage); rebuild(); }));

        fillBackground();
    }

    private void applyIntensity(Stage stage) {
        float multiplier = intensity / 4.0f; // range 0.25 to 1.75
        for (StageZoneRole role : selectedZones) {
            StageZoneConfig config = stage.getOrCreateConfig(role);
            config.setIntensityMultiplier(multiplier);
        }
        mod.getStageManager().saveStages();
    }

    private void applyPattern(Stage stage, String patternId) {
        var bpm = mod.getBitmapPatternManager();
        for (StageZoneRole role : selectedZones) {
            String zoneName = stage.getZoneName(role);
            if (zoneName != null && bpm != null && bpm.isActive(zoneName)) {
                bpm.setPattern(zoneName, patternId);
            }
            StageZoneConfig config = stage.getOrCreateConfig(role);
            config.setPattern(patternId);
        }
        mod.getStageManager().saveStages();
    }

    private void triggerEffect(String effect) {
        var effects = mod.getBitmapPatternManager() != null
            ? mod.getBitmapPatternManager().getEffectsProcessor() : null;
        if (effects == null) return;

        switch (effect) {
            case "blackout" -> effects.blackout(!effects.getBrightness().equals(0.0));
            case "freeze" -> {
                if (effects.isFrozen()) effects.unfreeze();
                else {
                    var buf = mod.getBitmapPatternManager().getFrameBuffer("main");
                    if (buf != null) effects.freeze(buf);
                }
            }
            case "flash" -> effects.setBeatFlashEnabled(true);
            case "strobe" -> effects.setStrobeEnabled(!effects.isStrobeEnabled());
            default -> getPlayer().sendMessage(
                Text.literal("Effect '" + effect + "' triggered").formatted(Formatting.YELLOW));
        }
    }
}
```

**Step 2: Build and verify**

Run: `cd minecraft_mod && ./gradlew build`
Expected: BUILD SUCCESSFUL (all menus exist now)

**Step 3: Commit all menus**

```bash
git add minecraft_mod/src/main/java/com/audioviz/gui/menus/
git commit -m "feat(gui): add StageListMenu, StageEditorMenu, StageDecoratorMenu, StageVJMenu, StageTemplateMenu"
```

---

### Task 9: Wire Menus into MainMenu and Commands

**Files:**
- Modify: `minecraft_mod/src/main/java/com/audioviz/gui/menus/MainMenu.java`
- Modify: `minecraft_mod/src/main/java/com/audioviz/gui/menus/ZoneManagementMenu.java`
- Modify: `minecraft_mod/src/main/java/com/audioviz/commands/AudioVizCommand.java`

**Step 1: Add Stages button to MainMenu**

Add a new slot constant and button between DJ Panel and Settings:

```java
private static final int SLOT_STAGES = 11; // between zones(10) and dj_panel(12)
```

In `build()`, after the Zone Management block, add:

```java
// Stage Management
int stageCount = mod.getStageManager().getStageCount();
setSlot(SLOT_STAGES, new GuiElementBuilder(Items.NETHER_STAR)
    .setName(Text.literal("Stage Management").formatted(Formatting.LIGHT_PURPLE))
    .addLoreLine(Text.literal("Create and manage stages").formatted(Formatting.GRAY))
    .addLoreLine(Text.empty())
    .addLoreLine(Text.literal("Stages: " + stageCount).formatted(Formatting.YELLOW))
    .addLoreLine(Text.empty())
    .addLoreLine(Text.literal("Click to manage stages").formatted(Formatting.YELLOW))
    .glow()
    .setCallback((index, type, action) -> {
        playClickSound();
        menuManager.openMenu(getPlayer(),
            new StageListMenu(getPlayer(), menuManager, mod, () ->
                menuManager.openMenu(getPlayer(), new MainMenu(getPlayer(), menuManager, mod))));
    }));
```

**Step 2: Update ZoneManagementMenu to open ZoneEditorMenu on left-click**

Replace the teleport-only left-click callback with navigation to ZoneEditorMenu. The `MOUSE_LEFT` case becomes:

```java
if (type == eu.pb4.sgui.api.ClickType.MOUSE_LEFT) {
    menuManager.openMenu(getPlayer(),
        new ZoneEditorMenu(getPlayer(), menuManager, mod, zoneName, () ->
            menuManager.openMenu(getPlayer(), new ZoneManagementMenu(getPlayer(), menuManager, mod))));
}
```

Add a "Create Zone" button and a "Teleport" hint in lore (shift-click to teleport instead).

**Step 3: Add stage commands to AudioVizCommand**

Add these subcommands under `/audioviz`:

```java
.then(CommandManager.literal("stage")
    .then(CommandManager.literal("list")
        .executes(ctx -> listStages(ctx, mod)))
    .then(CommandManager.literal("create")
        .then(CommandManager.argument("name", StringArgumentType.word())
            .executes(ctx -> createStage(ctx, mod))))
    .then(CommandManager.literal("delete")
        .then(CommandManager.argument("name", StringArgumentType.word())
            .executes(ctx -> deleteStage(ctx, mod))))
    .then(CommandManager.literal("activate")
        .then(CommandManager.argument("name", StringArgumentType.word())
            .executes(ctx -> activateStage(ctx, mod))))
)
.then(CommandManager.literal("menu")
    .executes(ctx -> openMenu(ctx, mod)))
```

Implement the handler methods:

```java
private static int listStages(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
    var stages = mod.getStageManager().getAllStages();
    if (stages.isEmpty()) {
        ctx.getSource().sendFeedback(() -> Text.literal("No stages").formatted(Formatting.GRAY), false);
        return 1;
    }
    ctx.getSource().sendFeedback(() -> Text.literal("Stages (" + stages.size() + "):").formatted(Formatting.AQUA), false);
    for (var stage : stages) {
        ctx.getSource().sendFeedback(() -> Text.literal("  " + stage.toString()).formatted(Formatting.WHITE), false);
    }
    return 1;
}

private static int createStage(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
    ServerPlayerEntity player = ctx.getSource().getPlayer();
    if (player == null) { ctx.getSource().sendError(Text.literal("Players only")); return 0; }
    String name = StringArgumentType.getString(ctx, "name");
    BlockPos pos = player.getBlockPos();
    String worldName = player.getEntityWorld().getRegistryKey().getValue().toString();
    Stage stage = mod.getStageManager().createStage(name, pos, worldName, "custom");
    if (stage != null) {
        ctx.getSource().sendFeedback(() -> Text.literal("Created stage '" + name + "' with " +
            stage.getRoleToZone().size() + " zones").formatted(Formatting.GREEN), true);
        return 1;
    }
    ctx.getSource().sendError(Text.literal("Stage '" + name + "' already exists"));
    return 0;
}

private static int deleteStage(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
    String name = StringArgumentType.getString(ctx, "name");
    if (mod.getStageManager().deleteStage(name)) {
        ctx.getSource().sendFeedback(() -> Text.literal("Deleted stage '" + name + "'").formatted(Formatting.YELLOW), true);
        return 1;
    }
    ctx.getSource().sendError(Text.literal("Stage '" + name + "' not found"));
    return 0;
}

private static int activateStage(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
    String name = StringArgumentType.getString(ctx, "name");
    Stage stage = mod.getStageManager().getStage(name);
    if (stage == null) { ctx.getSource().sendError(Text.literal("Stage not found")); return 0; }
    if (stage.isActive()) mod.getStageManager().deactivateStage(stage);
    else mod.getStageManager().activateStage(stage);
    ctx.getSource().sendFeedback(() -> Text.literal("Stage '" + name + "' " +
        (stage.isActive() ? "activated" : "deactivated")).formatted(Formatting.GREEN), true);
    return 1;
}

private static int openMenu(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
    ServerPlayerEntity player = ctx.getSource().getPlayer();
    if (player == null) { ctx.getSource().sendError(Text.literal("Players only")); return 0; }
    mod.getMenuManager().openMenu(player, new MainMenu(player, mod.getMenuManager(), mod));
    return 1;
}
```

**Step 4: Build and verify**

Run: `cd minecraft_mod && ./gradlew build`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add minecraft_mod/src/main/java/com/audioviz/gui/menus/MainMenu.java \
        minecraft_mod/src/main/java/com/audioviz/gui/menus/ZoneManagementMenu.java \
        minecraft_mod/src/main/java/com/audioviz/commands/AudioVizCommand.java
git commit -m "feat(gui): wire stage menus into MainMenu and add stage/menu commands"
```

---

### Task 10: Final Build & Smoke Test

**Step 1: Full clean build**

Run: `cd minecraft_mod && ./gradlew clean build`
Expected: BUILD SUCCESSFUL

**Step 2: Verify file count**

Run: `find minecraft_mod/src/main/java/com/audioviz/gui -name "*.java" | wc -l`
Expected: 11 (AudioVizGui + MenuManager + 4 existing + 5 new menus)

Wait — we have 7 new menus (ZoneTemplateMenu, ZoneEditorMenu, StageTemplateMenu, StageListMenu, StageEditorMenu, StageDecoratorMenu, StageVJMenu). That's 9 total in `menus/` + AudioVizGui + MenuManager = 11.

**Step 3: Commit any remaining changes**

```bash
git add minecraft_mod/
git commit -m "feat(gui): complete Fabric GUI menu system port (7 new menus)"
```
