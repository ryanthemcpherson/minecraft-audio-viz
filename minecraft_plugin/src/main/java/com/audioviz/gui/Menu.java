package com.audioviz.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

/**
 * Base interface for all menu screens.
 * Implementations define the layout, items, and click behavior.
 */
public interface Menu {

    /**
     * Get the title shown at the top of the inventory.
     */
    String getTitle();

    /**
     * Get the size of the inventory (must be multiple of 9, max 54).
     */
    int getSize();

    /**
     * Build the menu contents into the given inventory.
     * Called when the menu is opened or needs to be refreshed.
     *
     * @param inventory The inventory to populate
     * @param viewer The player viewing the menu
     */
    void build(Inventory inventory, Player viewer);

    /**
     * Handle a click event in the menu.
     *
     * @param player The player who clicked
     * @param slot The slot that was clicked
     * @param click The type of click (left, right, shift, etc.)
     */
    void onClick(Player player, int slot, ClickType click);

    /**
     * Called when the menu is closed.
     * Override to perform cleanup or save state.
     *
     * @param player The player who closed the menu
     */
    default void onClose(Player player) {
        // Default: no action
    }

    /**
     * Called when the menu is opened.
     * Override to perform initialization.
     *
     * @param player The player opening the menu
     */
    default void onOpen(Player player) {
        // Default: no action
    }

    /**
     * Whether to allow the player to take items from this menu.
     * Default is false (items cannot be taken).
     */
    default boolean allowItemTake() {
        return false;
    }

    /**
     * Whether to allow the player to put items into this menu.
     * Default is false (items cannot be placed).
     */
    default boolean allowItemPlace() {
        return false;
    }
}
