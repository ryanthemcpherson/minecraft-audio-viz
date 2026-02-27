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
        return Text.literal("Stages").formatted(Formatting.DARK_BLUE, Formatting.BOLD);
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
                        var newMenu = new StageListMenu(getPlayer(), menuManager, mod, onBack);
                        newMenu.searchQuery = searchQuery;
                        newMenu.sortMode = sortMode;
                        newMenu.filterMode = filterMode;
                        menuManager.openMenu(getPlayer(), newMenu);
                    }, () -> {
                        var newMenu = new StageListMenu(getPlayer(), menuManager, mod, onBack);
                        newMenu.searchQuery = searchQuery;
                        newMenu.sortMode = sortMode;
                        newMenu.filterMode = filterMode;
                        menuManager.openMenu(getPlayer(), newMenu);
                    });
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
            .addLoreLine(Text.literal("Zones: " + stage.getRoleToZone().size() + "/" + com.audioviz.stages.StageZoneRole.values().length).formatted(Formatting.GRAY))
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
