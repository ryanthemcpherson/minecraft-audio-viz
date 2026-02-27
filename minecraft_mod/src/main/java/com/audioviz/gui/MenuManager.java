package com.audioviz.gui;

import com.audioviz.AudioVizMod;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.*;

/**
 * Central manager for all GUI menus.
 *
 * <p>Ported from Paper: Bukkit Inventory events → SGUI callback-based.
 * SGUI handles click/close/drag events internally via SimpleGui callbacks,
 * so no event listener registration is needed (unlike Paper's InventoryClickEvent).
 */
public class MenuManager {

    private final AudioVizMod mod;

    /** Track which menu each player has open (for programmatic refresh). */
    private final Map<UUID, AudioVizGui> activeSessions = new HashMap<>();

    public MenuManager(AudioVizMod mod) {
        this.mod = mod;
    }

    /**
     * Open a menu for a player.
     */
    public void openMenu(ServerPlayerEntity player, AudioVizGui gui) {
        UUID playerId = player.getUuid();

        // Close any existing menu
        AudioVizGui existing = activeSessions.get(playerId);
        if (existing != null && existing.isOpen()) {
            existing.close();
        }

        activeSessions.put(playerId, gui);
        gui.init();
        gui.open();
    }

    /**
     * Close the current menu for a player.
     */
    public void closeMenu(ServerPlayerEntity player) {
        AudioVizGui gui = activeSessions.remove(player.getUuid());
        if (gui != null && gui.isOpen()) {
            gui.close();
        }
    }

    /**
     * Get the current menu a player has open.
     */
    public AudioVizGui getCurrentMenu(ServerPlayerEntity player) {
        return activeSessions.get(player.getUuid());
    }

    /**
     * Check if a player has a menu open.
     */
    public boolean hasMenuOpen(ServerPlayerEntity player) {
        AudioVizGui gui = activeSessions.get(player.getUuid());
        return gui != null && gui.isOpen();
    }

    /**
     * Remove a player's session (called on disconnect).
     */
    public void removeSession(UUID playerId) {
        activeSessions.remove(playerId);
    }

    /**
     * Clear all sessions (called on shutdown).
     */
    public void clearAllSessions() {
        activeSessions.clear();
    }

    public AudioVizMod getMod() {
        return mod;
    }
}
