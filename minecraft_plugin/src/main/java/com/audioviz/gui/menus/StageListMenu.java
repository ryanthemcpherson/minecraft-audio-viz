package com.audioviz.gui.menus;

import com.audioviz.AudioVizPlugin;
import com.audioviz.gui.ChatInputManager;
import com.audioviz.gui.Menu;
import com.audioviz.gui.MenuManager;
import com.audioviz.gui.builder.ItemBuilder;
import com.audioviz.stages.Stage;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Paginated, sortable, filterable stage list with search and bulk operations.
 *
 * Layout:
 *   Row 0: Control strip (Back, Sort, Filter, Search, Page Info, Bulk Toggle, Create)
 *   Row 1-4: Stage items (28 per page)
 *   Row 5: Action bar (Bulk Activate/Deactivate, Prev/Next page, Refresh)
 */
public class StageListMenu implements Menu {

    private final AudioVizPlugin plugin;
    private final MenuManager menuManager;

    // Pagination
    private int currentPage = 0;
    private static final int STAGES_PER_PAGE = 28;
    private static final int[] STAGE_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    // Control strip (row 0)
    private static final int SLOT_BACK = 0;
    private static final int SLOT_SORT = 1;
    private static final int SLOT_FILTER = 2;
    private static final int SLOT_SEARCH = 3;
    private static final int SLOT_PAGE_INFO = 4;
    private static final int SLOT_BULK_TOGGLE = 6;
    private static final int SLOT_CREATE = 8;

    // Action bar (row 5)
    private static final int SLOT_BULK_ACTIVATE = 45;
    private static final int SLOT_BULK_DEACTIVATE = 46;
    private static final int SLOT_PREV_PAGE = 48;
    private static final int SLOT_NEXT_PAGE = 50;
    private static final int SLOT_REFRESH = 53;

    // ========== Sort/Filter/Search State ==========

    private SortMode sortMode = SortMode.NAME;
    private FilterMode filterMode = FilterMode.ALL;
    private String tagFilter = null;
    private String searchQuery = null;
    private boolean bulkSelectMode = false;
    private final Set<String> bulkSelected = new LinkedHashSet<>();

    enum SortMode {
        NAME("Name", Material.NAME_TAG),
        TEMPLATE("Template", Material.ANVIL),
        STATUS("Status", Material.LIME_DYE),
        RECENT("Recent Use", Material.CLOCK),
        ZONE_COUNT("Zone Count", Material.CHEST);

        final String display;
        final Material icon;
        SortMode(String display, Material icon) {
            this.display = display;
            this.icon = icon;
        }
    }

    enum FilterMode {
        ALL("All Stages", Material.PAPER),
        ACTIVE("Active Only", Material.LIME_DYE),
        INACTIVE("Inactive Only", Material.GRAY_DYE),
        PINNED("Pinned", Material.GOLD_INGOT);

        final String display;
        final Material icon;
        FilterMode(String display, Material icon) {
            this.display = display;
            this.icon = icon;
        }
    }

    // ========== Constructors ==========

    public StageListMenu(AudioVizPlugin plugin, MenuManager menuManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
    }

    public StageListMenu(AudioVizPlugin plugin, MenuManager menuManager, int page) {
        this(plugin, menuManager);
        this.currentPage = page;
    }

    @Override
    public String getTitle() {
        return "\u00A75Stage Management";
    }

    @Override
    public int getSize() {
        return 54;
    }

    // ========== Build ==========

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();

        // Fill borders (side columns for rows 1-4)
        ItemStack filler = ItemBuilder.glassPane(DyeColor.GRAY);
        inventory.setItem(9, filler);
        inventory.setItem(17, filler);
        inventory.setItem(18, filler);
        inventory.setItem(26, filler);
        inventory.setItem(27, filler);
        inventory.setItem(35, filler);
        inventory.setItem(36, filler);
        inventory.setItem(44, filler);

        // Fill unused control strip and action bar slots
        ItemStack controlFiller = ItemBuilder.glassPane(DyeColor.PURPLE);
        inventory.setItem(5, controlFiller);
        inventory.setItem(7, controlFiller);
        ItemStack actionFiller = ItemBuilder.glassPane(DyeColor.GRAY);
        inventory.setItem(47, actionFiller);
        inventory.setItem(49, actionFiller);
        inventory.setItem(51, actionFiller);
        inventory.setItem(52, actionFiller);

        // Get sorted & filtered stages
        List<Stage> stageList = getSortedFilteredStages();
        int totalPages = Math.max(1, (int) Math.ceil(stageList.size() / (double) STAGES_PER_PAGE));
        currentPage = Math.max(0, Math.min(currentPage, totalPages - 1));

        int startIndex = currentPage * STAGES_PER_PAGE;
        int endIndex = Math.min(startIndex + STAGES_PER_PAGE, stageList.size());

        // Place stage items
        for (int i = 0; i < STAGE_SLOTS.length; i++) {
            int stageIndex = startIndex + i;
            if (stageIndex < endIndex) {
                Stage stage = stageList.get(stageIndex);
                inventory.setItem(STAGE_SLOTS[i], createStageItem(stage));
            }
        }

        int totalStages = plugin.getStageManager().getStageCount();

        // ===== Control Strip (Row 0) =====

        // Back button
        inventory.setItem(SLOT_BACK, ItemBuilder.backButton());

        // Sort button
        List<String> sortLore = new ArrayList<>();
        sortLore.add("&7Click to cycle sort order");
        sortLore.add("");
        for (SortMode mode : SortMode.values()) {
            String prefix = mode == sortMode ? " &a\u25B6 " : " &7  ";
            sortLore.add(prefix + mode.display);
        }
        inventory.setItem(SLOT_SORT, new ItemBuilder(sortMode.icon)
            .name("&bSort: " + sortMode.display)
            .lore(sortLore)
            .build());

        // Filter button
        List<String> filterLore = new ArrayList<>();
        filterLore.add("&7Left-click to cycle filter");
        filterLore.add("&7Right-click to filter by tag");
        filterLore.add("");
        for (FilterMode mode : FilterMode.values()) {
            String prefix = mode == filterMode ? " &a\u25B6 " : " &7  ";
            filterLore.add(prefix + mode.display);
        }
        if (tagFilter != null) {
            filterLore.add("");
            filterLore.add("&6Tag filter: &f" + tagFilter);
        }
        inventory.setItem(SLOT_FILTER, new ItemBuilder(filterMode.icon)
            .name("&6Filter: " + filterMode.display)
            .lore(filterLore)
            .build());

        // Search button
        boolean hasSearch = searchQuery != null && !searchQuery.isEmpty();
        List<String> searchLore = new ArrayList<>();
        searchLore.add(hasSearch ? "&7Active: &f" + searchQuery : "&7No active search");
        searchLore.add("");
        searchLore.add("&eLeft-click to search");
        if (hasSearch) searchLore.add("&eRight-click to clear");
        inventory.setItem(SLOT_SEARCH, new ItemBuilder(hasSearch ? Material.WRITTEN_BOOK : Material.BOOK)
            .name("&dSearch" + (hasSearch ? ": &f" + searchQuery : ""))
            .lore(searchLore)
            .glow(hasSearch)
            .build());

        // Page info
        String filterInfo = stageList.size() < totalStages
            ? "&7Showing &f" + stageList.size() + "&7 of &f" + totalStages
            : "&7" + totalStages + " total stages";
        inventory.setItem(SLOT_PAGE_INFO, new ItemBuilder(Material.PAPER)
            .name("&fPage " + (currentPage + 1) + "/" + totalPages)
            .lore(filterInfo)
            .build());

        // Bulk select toggle
        inventory.setItem(SLOT_BULK_TOGGLE, new ItemBuilder(
                bulkSelectMode ? Material.CHEST_MINECART : Material.MINECART)
            .name(bulkSelectMode ? "&aBulk Select: ON" : "&7Bulk Select: OFF")
            .lore(
                "&7Select multiple stages for",
                "&7bulk activate/deactivate.",
                "",
                bulkSelectMode ? "&e" + bulkSelected.size() + " selected" : "&eClick to toggle"
            )
            .glow(bulkSelectMode)
            .build());

        // Create button
        inventory.setItem(SLOT_CREATE, new ItemBuilder(Material.EMERALD)
            .name("&aCreate New Stage")
            .lore(
                "&7Set up a new multi-zone",
                "&7stage from a template.",
                "",
                "&eClick to choose template"
            )
            .glow()
            .build());

        // ===== Action Bar (Row 5) =====

        if (bulkSelectMode) {
            inventory.setItem(SLOT_BULK_ACTIVATE, new ItemBuilder(Material.LIME_DYE)
                .name("&aBulk Activate")
                .lore(
                    "&7Activate all selected stages.",
                    "&e" + bulkSelected.size() + " selected"
                )
                .build());

            inventory.setItem(SLOT_BULK_DEACTIVATE, new ItemBuilder(Material.RED_DYE)
                .name("&cBulk Deactivate")
                .lore(
                    "&7Deactivate all selected stages.",
                    "&e" + bulkSelected.size() + " selected"
                )
                .build());
        } else {
            inventory.setItem(SLOT_BULK_ACTIVATE, actionFiller);
            inventory.setItem(SLOT_BULK_DEACTIVATE, actionFiller);
        }

        // Pagination
        if (currentPage > 0) {
            inventory.setItem(SLOT_PREV_PAGE, ItemBuilder.previousPage(currentPage + 1));
        } else {
            inventory.setItem(SLOT_PREV_PAGE, actionFiller);
        }

        if (currentPage < totalPages - 1) {
            inventory.setItem(SLOT_NEXT_PAGE, ItemBuilder.nextPage(currentPage + 1, totalPages));
        } else {
            inventory.setItem(SLOT_NEXT_PAGE, actionFiller);
        }

        // Refresh
        inventory.setItem(SLOT_REFRESH, new ItemBuilder(Material.SUNFLOWER)
            .name("&eRefresh")
            .lore("&7Reload stage list")
            .build());
    }

    // ========== Sorting & Filtering ==========

    private List<Stage> getSortedFilteredStages() {
        List<Stage> list = new ArrayList<>(plugin.getStageManager().getAllStages());

        // Apply filter mode
        list.removeIf(stage -> switch (filterMode) {
            case ACTIVE -> !stage.isActive();
            case INACTIVE -> stage.isActive();
            case PINNED -> !stage.isPinned();
            default -> false;
        });

        // Apply tag filter
        if (tagFilter != null && !tagFilter.isEmpty()) {
            list.removeIf(stage -> !stage.getTag().equalsIgnoreCase(tagFilter));
        }

        // Apply search
        if (searchQuery != null && !searchQuery.isEmpty()) {
            String query = searchQuery.toLowerCase();
            list.removeIf(stage ->
                !stage.getName().toLowerCase().contains(query)
                && !stage.getTemplateName().toLowerCase().contains(query)
                && !stage.getTag().toLowerCase().contains(query));
        }

        // Sort: pinned always first, then by sort mode
        Comparator<Stage> comparator = Comparator.comparing(Stage::isPinned).reversed();
        comparator = comparator.thenComparing(switch (sortMode) {
            case NAME -> Comparator.comparing(Stage::getName, String.CASE_INSENSITIVE_ORDER);
            case TEMPLATE -> Comparator.comparing(Stage::getTemplateName, String.CASE_INSENSITIVE_ORDER);
            case STATUS -> Comparator.comparing(Stage::isActive).reversed();
            case RECENT -> Comparator.comparingLong(Stage::getLastActivatedAt).reversed();
            case ZONE_COUNT -> Comparator.<Stage, Integer>comparing(s -> s.getRoleToZone().size()).reversed();
        });

        list.sort(comparator);
        return list;
    }

    // ========== Stage Item Rendering ==========

    private ItemStack createStageItem(Stage stage) {
        // Material: active=JUKEBOX, pinned+inactive=GOLD_BLOCK, inactive=NOTE_BLOCK
        Material material;
        if (stage.isActive()) {
            material = Material.JUKEBOX;
        } else if (stage.isPinned()) {
            material = Material.GOLD_BLOCK;
        } else {
            material = Material.NOTE_BLOCK;
        }

        // Name color: active=green, pinned=gold, default=yellow
        String nameColor = stage.isActive() ? "&a" : (stage.isPinned() ? "&6" : "&e");
        String status = stage.isActive() ? "&a&lACTIVE" : "&7Inactive";

        int totalEntities = 0;
        for (String zoneName : stage.getZoneNames()) {
            totalEntities += plugin.getEntityPoolManager().getEntityCount(zoneName);
        }

        List<String> lore = new ArrayList<>();
        lore.add("&7Template: &f" + stage.getTemplateName());
        lore.add("&7Status: " + status);

        if (stage.isPinned()) {
            lore.add("&6\u2605 Pinned");
        }
        if (!stage.getTag().isEmpty()) {
            lore.add("&7Tag: &f" + stage.getTag());
        }

        lore.add("");
        lore.add("&7Zones: &f" + stage.getRoleToZone().size());
        lore.add("&7Entities: &f" + totalEntities);
        lore.add("&7Rotation: &f" + (int) stage.getRotation() + "\u00B0");

        if (stage.getLastActivatedAt() > 0) {
            lore.add("&7Last used: &f" + formatTimeAgo(stage.getLastActivatedAt()));
        }

        lore.add("");
        if (bulkSelectMode) {
            boolean selected = bulkSelected.contains(stage.getName().toLowerCase());
            lore.add(selected ? "&a\u2714 Selected" : "&7Click to select");
        } else {
            lore.add("&eLeft-click to edit");
            lore.add("&eRight-click to pin/unpin");
            lore.add("&cShift-click to delete");
        }

        ItemBuilder builder = new ItemBuilder(material)
            .name(nameColor + stage.getName())
            .lore(lore);

        if (stage.isActive() || (bulkSelectMode && bulkSelected.contains(stage.getName().toLowerCase()))) {
            builder.glow();
        }

        return builder.build();
    }

    private String formatTimeAgo(long timestamp) {
        long seconds = (System.currentTimeMillis() - timestamp) / 1000;
        if (seconds < 60) return "just now";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        if (seconds < 86400) return (seconds / 3600) + "h ago";
        return (seconds / 86400) + "d ago";
    }

    // ========== Click Handling ==========

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        switch (slot) {
            case SLOT_BACK -> {
                playClickSound(player);
                menuManager.openMenu(player, new MainMenu(plugin, menuManager));
            }

            case SLOT_SORT -> {
                playClickSound(player);
                SortMode[] values = SortMode.values();
                sortMode = values[(sortMode.ordinal() + 1) % values.length];
                currentPage = 0;
                menuManager.refreshMenu(player);
            }

            case SLOT_FILTER -> {
                playClickSound(player);
                if (click.isRightClick()) {
                    cycleTagFilter(player);
                } else {
                    FilterMode[] values = FilterMode.values();
                    filterMode = values[(filterMode.ordinal() + 1) % values.length];
                    tagFilter = null; // Clear tag filter when cycling main filter
                    currentPage = 0;
                    menuManager.refreshMenu(player);
                }
            }

            case SLOT_SEARCH -> {
                if (click.isRightClick() && searchQuery != null) {
                    playClickSound(player);
                    searchQuery = null;
                    currentPage = 0;
                    menuManager.refreshMenu(player);
                } else {
                    playClickSound(player);
                    player.closeInventory();
                    requestSearch(player);
                }
            }

            case SLOT_BULK_TOGGLE -> {
                playClickSound(player);
                bulkSelectMode = !bulkSelectMode;
                if (!bulkSelectMode) bulkSelected.clear();
                menuManager.refreshMenu(player);
            }

            case SLOT_CREATE -> {
                playClickSound(player);
                menuManager.openMenu(player, new StageTemplateMenu(plugin, menuManager));
            }

            case SLOT_BULK_ACTIVATE -> {
                if (bulkSelectMode && !bulkSelected.isEmpty()) {
                    playClickSound(player);
                    int count = plugin.getStageManager().bulkActivate(bulkSelected);
                    bulkSelected.clear();
                    player.sendMessage(ChatColor.GREEN + "Activated " + count + " stage(s).");
                    menuManager.refreshMenu(player);
                }
            }

            case SLOT_BULK_DEACTIVATE -> {
                if (bulkSelectMode && !bulkSelected.isEmpty()) {
                    playClickSound(player);
                    int count = plugin.getStageManager().bulkDeactivate(bulkSelected);
                    bulkSelected.clear();
                    player.sendMessage(ChatColor.GREEN + "Deactivated " + count + " stage(s).");
                    menuManager.refreshMenu(player);
                }
            }

            case SLOT_PREV_PAGE -> {
                if (currentPage > 0) {
                    playClickSound(player);
                    currentPage--;
                    menuManager.refreshMenu(player);
                }
            }

            case SLOT_NEXT_PAGE -> {
                playClickSound(player);
                currentPage++;
                menuManager.refreshMenu(player);
            }

            case SLOT_REFRESH -> {
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
                menuManager.refreshMenu(player);
            }

            default -> {
                Stage stage = getStageAtSlot(slot);
                if (stage != null) {
                    handleStageClick(player, stage, click);
                }
            }
        }
    }

    private void handleStageClick(Player player, Stage stage, ClickType click) {
        if (bulkSelectMode) {
            // Toggle selection
            playClickSound(player);
            String key = stage.getName().toLowerCase();
            if (bulkSelected.contains(key)) {
                bulkSelected.remove(key);
            } else {
                bulkSelected.add(key);
            }
            menuManager.refreshMenu(player);
        } else if (click.isShiftClick()) {
            // Delete stage
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.5f, 1f);
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "Deleting stage: " + stage.getName());
            plugin.getStageManager().deleteStage(stage.getName());
            player.sendMessage(ChatColor.GREEN + "Stage and all its zones deleted.");
        } else if (click.isRightClick()) {
            // Toggle pin
            playClickSound(player);
            stage.setPinned(!stage.isPinned());
            plugin.getStageManager().saveStages();
            String msg = stage.isPinned()
                ? ChatColor.GOLD + "\u2605 " + ChatColor.GREEN + "Stage pinned!"
                : ChatColor.GRAY + "Stage unpinned.";
            player.sendMessage(msg);
            menuManager.refreshMenu(player);
        } else {
            // Edit
            playClickSound(player);
            menuManager.openMenu(player, new StageEditorMenu(plugin, menuManager, stage));
        }
    }

    // ========== Search ==========

    private void requestSearch(Player player) {
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "=== Stage Search ===");
        player.sendMessage(ChatColor.YELLOW + "Type a stage name (or part of it) to search.");
        player.sendMessage(ChatColor.GRAY + "Searches names, templates, and tags.");
        player.sendMessage(ChatColor.GRAY + "Type 'cancel' to cancel.");
        player.sendMessage("");

        plugin.getChatInputManager().requestInput(player, ChatInputManager.InputType.GENERAL, input -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (input != null) {
                    searchQuery = input.trim();
                    currentPage = 0;
                }
                menuManager.openMenu(player, this);
            });
        });
    }

    // ========== Tag Filter ==========

    private void cycleTagFilter(Player player) {
        Set<String> allTags = plugin.getStageManager().getAllTags();

        if (allTags.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "No stages have tags yet. Set tags in the stage editor.");
            return;
        }

        List<String> tagList = new ArrayList<>(allTags);
        if (tagFilter == null) {
            tagFilter = tagList.get(0);
        } else {
            int idx = tagList.indexOf(tagFilter);
            if (idx == -1 || idx == tagList.size() - 1) {
                tagFilter = null; // Cycle back to no filter
            } else {
                tagFilter = tagList.get(idx + 1);
            }
        }

        currentPage = 0;
        menuManager.refreshMenu(player);
    }

    // ========== Helpers ==========

    private Stage getStageAtSlot(int slot) {
        int slotIndex = -1;
        for (int i = 0; i < STAGE_SLOTS.length; i++) {
            if (STAGE_SLOTS[i] == slot) {
                slotIndex = i;
                break;
            }
        }
        if (slotIndex == -1) return null;

        // Must use the same sorted/filtered list as build()
        List<Stage> stageList = getSortedFilteredStages();
        int stageIndex = currentPage * STAGES_PER_PAGE + slotIndex;

        if (stageIndex >= 0 && stageIndex < stageList.size()) {
            return stageList.get(stageIndex);
        }
        return null;
    }

    private void playClickSound(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }
}
