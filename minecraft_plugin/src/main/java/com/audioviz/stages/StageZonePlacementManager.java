package com.audioviz.stages;

import com.audioviz.AudioVizPlugin;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active zone placement sessions per player.
 * Only one placement session can be active per player at a time.
 */
public class StageZonePlacementManager {

    private final AudioVizPlugin plugin;
    private final Map<UUID, StageZonePlacementSession> activeSessions = new ConcurrentHashMap<>();

    public StageZonePlacementManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Start a new zone placement session for a player.
     * Checks for conflicts with ZoneEditor sessions.
     *
     * @return the session, or null if a conflicting session exists
     */
    public StageZonePlacementSession startSession(Player player, Stage stage) {
        UUID uuid = player.getUniqueId();

        // Check for active ZoneEditor session
        if (plugin.getZoneEditor().isEditing(player)) {
            player.sendMessage(org.bukkit.ChatColor.RED +
                "You are currently editing a zone. Exit the zone editor first.");
            return null;
        }

        // Check for existing placement session
        StageZonePlacementSession existing = activeSessions.get(uuid);
        if (existing != null) {
            existing.stop(true);
        }

        StageZonePlacementSession session = new StageZonePlacementSession(plugin, player, stage);
        activeSessions.put(uuid, session);
        session.start();
        return session;
    }

    /**
     * Remove a session (called by the session itself on stop).
     */
    public void removeSession(UUID playerUuid) {
        activeSessions.remove(playerUuid);
    }

    /**
     * Check if a player has an active placement session.
     */
    public boolean hasActiveSession(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    /**
     * Get the active session for a player, or null.
     */
    public StageZonePlacementSession getSession(Player player) {
        return activeSessions.get(player.getUniqueId());
    }

    /**
     * Cancel all active sessions (called on plugin disable).
     */
    public void cancelAll() {
        for (StageZonePlacementSession session : activeSessions.values()) {
            session.stop(true);
        }
        activeSessions.clear();
    }
}
