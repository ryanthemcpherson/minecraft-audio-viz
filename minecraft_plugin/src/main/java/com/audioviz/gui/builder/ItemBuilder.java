package com.audioviz.gui.builder;

import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fluent builder for creating ItemStacks for menus.
 * Provides a clean API for setting names, lore, and other properties.
 */
public class ItemBuilder {

    private final ItemStack item;
    private final ItemMeta meta;

    public ItemBuilder(Material material) {
        this.item = new ItemStack(material);
        this.meta = item.getItemMeta();
    }

    public ItemBuilder(Material material, int amount) {
        this.item = new ItemStack(material, amount);
        this.meta = item.getItemMeta();
    }

    /**
     * Set the display name with color code translation.
     */
    public ItemBuilder name(String name) {
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        return this;
    }

    /**
     * Set the lore (description) with color code translation.
     */
    public ItemBuilder lore(String... lines) {
        List<String> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }
        meta.setLore(lore);
        return this;
    }

    /**
     * Set the lore from a list.
     */
    public ItemBuilder lore(List<String> lines) {
        List<String> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }
        meta.setLore(lore);
        return this;
    }

    /**
     * Add a line to existing lore.
     */
    public ItemBuilder addLore(String line) {
        List<String> lore = meta.getLore();
        if (lore == null) lore = new ArrayList<>();
        lore.add(ChatColor.translateAlternateColorCodes('&', line));
        meta.setLore(lore);
        return this;
    }

    /**
     * Add a glowing enchantment effect (visual only).
     */
    public ItemBuilder glow() {
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        return this;
    }

    /**
     * Conditionally add a glowing enchantment effect.
     */
    public ItemBuilder glow(boolean condition) {
        if (condition) glow();
        return this;
    }

    /**
     * Set the item amount.
     */
    public ItemBuilder amount(int amount) {
        item.setAmount(amount);
        return this;
    }

    /**
     * Hide all item flags (enchants, attributes, etc.).
     */
    public ItemBuilder hideFlags() {
        meta.addItemFlags(ItemFlag.values());
        return this;
    }

    /**
     * Make the item unbreakable.
     */
    public ItemBuilder unbreakable() {
        meta.setUnbreakable(true);
        return this;
    }

    /**
     * Set a custom model data value (for resource packs).
     */
    public ItemBuilder customModelData(int data) {
        meta.setCustomModelData(data);
        return this;
    }

    /**
     * Build and return the final ItemStack.
     */
    public ItemStack build() {
        item.setItemMeta(meta);
        return item;
    }

    // ========== Static Factory Methods ==========

    /**
     * Create a simple named item.
     */
    public static ItemStack create(Material material, String name) {
        return new ItemBuilder(material).name(name).build();
    }

    /**
     * Create a named item with lore.
     */
    public static ItemStack create(Material material, String name, String... lore) {
        return new ItemBuilder(material).name(name).lore(lore).build();
    }

    /**
     * Create a glass pane filler (typically used for borders).
     */
    public static ItemStack filler(Material material) {
        return new ItemBuilder(material).name(" ").build();
    }

    /**
     * Create a glass pane with a specific color.
     */
    public static ItemStack glassPane(DyeColor color) {
        Material material = switch (color) {
            case WHITE -> Material.WHITE_STAINED_GLASS_PANE;
            case ORANGE -> Material.ORANGE_STAINED_GLASS_PANE;
            case MAGENTA -> Material.MAGENTA_STAINED_GLASS_PANE;
            case LIGHT_BLUE -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            case YELLOW -> Material.YELLOW_STAINED_GLASS_PANE;
            case LIME -> Material.LIME_STAINED_GLASS_PANE;
            case PINK -> Material.PINK_STAINED_GLASS_PANE;
            case GRAY -> Material.GRAY_STAINED_GLASS_PANE;
            case LIGHT_GRAY -> Material.LIGHT_GRAY_STAINED_GLASS_PANE;
            case CYAN -> Material.CYAN_STAINED_GLASS_PANE;
            case PURPLE -> Material.PURPLE_STAINED_GLASS_PANE;
            case BLUE -> Material.BLUE_STAINED_GLASS_PANE;
            case BROWN -> Material.BROWN_STAINED_GLASS_PANE;
            case GREEN -> Material.GREEN_STAINED_GLASS_PANE;
            case RED -> Material.RED_STAINED_GLASS_PANE;
            case BLACK -> Material.BLACK_STAINED_GLASS_PANE;
        };
        return new ItemBuilder(material).name(" ").build();
    }

    /**
     * Create a back button item.
     */
    public static ItemStack backButton() {
        return new ItemBuilder(Material.ARROW)
            .name("&c< Back")
            .lore("&7Return to previous menu")
            .build();
    }

    /**
     * Create a close button item.
     */
    public static ItemStack closeButton() {
        return new ItemBuilder(Material.BARRIER)
            .name("&c Close")
            .lore("&7Close this menu")
            .build();
    }

    /**
     * Create a previous page button.
     */
    public static ItemStack previousPage(int currentPage) {
        return new ItemBuilder(Material.ARROW)
            .name("&e< Previous Page")
            .lore("&7Page " + currentPage)
            .build();
    }

    /**
     * Create a next page button.
     */
    public static ItemStack nextPage(int currentPage, int totalPages) {
        return new ItemBuilder(Material.ARROW)
            .name("&eNext Page >")
            .lore("&7Page " + currentPage + "/" + totalPages)
            .build();
    }

    /**
     * Create a toggle button that shows on/off state.
     */
    public static ItemStack toggle(String name, boolean enabled, String... description) {
        Material material = enabled ? Material.LIME_DYE : Material.GRAY_DYE;
        String state = enabled ? "&aEnabled" : "&cDisabled";

        List<String> lore = new ArrayList<>();
        lore.addAll(Arrays.asList(description));
        lore.add("");
        lore.add("&7Status: " + state);
        lore.add("&eClick to toggle");

        return new ItemBuilder(material)
            .name(name)
            .lore(lore)
            .build();
    }

    /**
     * Create a slider representation (1-10 scale) using colored wool/concrete.
     */
    public static ItemStack slider(String name, int value, int max, String description) {
        // Use dye color based on value percentage
        float percentage = (float) value / max;
        Material material;
        if (percentage >= 0.8) material = Material.LIME_CONCRETE;
        else if (percentage >= 0.6) material = Material.YELLOW_CONCRETE;
        else if (percentage >= 0.4) material = Material.ORANGE_CONCRETE;
        else material = Material.RED_CONCRETE;

        return new ItemBuilder(material)
            .name(name)
            .lore(
                description,
                "",
                "&7Value: &f" + value + "/" + max,
                "&eLeft-click to increase",
                "&eRight-click to decrease"
            )
            .amount(Math.max(1, Math.min(64, value)))
            .build();
    }
}
