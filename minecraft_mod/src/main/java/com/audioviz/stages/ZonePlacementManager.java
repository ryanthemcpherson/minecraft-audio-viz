package com.audioviz.stages;

import com.audioviz.AudioVizMod;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active zone placement sessions per player.
 * Handles delegation from Fabric event callbacks to active sessions.
 *
 * <p>Ported from Paper: StageZonePlacementManager.
 * Bukkit events → Fabric callbacks (registered in AudioVizMod).
 */
public class ZonePlacementManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");
    private final AudioVizMod mod;
    private final Map<UUID, ZonePlacementSession> activeSessions = new ConcurrentHashMap<>();

    // Dedup flag: tracks players whose left-click was already handled by AttackBlockCallback
    // this tick, so the HandSwing mixin doesn't double-fire.
    private final Set<UUID> blockClickHandledThisTick = ConcurrentHashMap.newKeySet();

    public ZonePlacementManager(AudioVizMod mod) {
        this.mod = mod;
    }

    /**
     * Start a new zone placement session for a player.
     *
     * @param onComplete callback invoked when all zones are placed (typically opens StageEditorMenu)
     * @return the session, or null if creation failed
     */
    public ZonePlacementSession startSession(ServerPlayerEntity player, Stage stage, Runnable onComplete) {
        UUID uuid = player.getUuid();

        // Cancel any existing session
        ZonePlacementSession existing = activeSessions.get(uuid);
        if (existing != null) {
            existing.stop(true);
        }

        ZonePlacementSession session = new ZonePlacementSession(mod, player, stage, onComplete);
        activeSessions.put(uuid, session);
        session.start();

        LOGGER.info("Started zone placement session for {} (stage: {})", player.getName().getString(), stage.getName());
        return session;
    }

    /**
     * Remove a session (called by the session itself on stop).
     */
    public void removeSession(UUID playerUuid) {
        activeSessions.remove(playerUuid);
    }

    public boolean hasActiveSession(ServerPlayerEntity player) {
        return activeSessions.containsKey(player.getUuid());
    }

    public ZonePlacementSession getSession(ServerPlayerEntity player) {
        return activeSessions.get(player.getUuid());
    }

    // ==================== Dedup: Block vs Air Left-Click ====================

    /**
     * Mark that this player's left-click was handled by AttackBlockCallback this tick.
     * Prevents the HandSwing mixin from double-firing.
     */
    public void markBlockClickHandled(ServerPlayerEntity player) {
        blockClickHandledThisTick.add(player.getUuid());
    }

    /**
     * Check whether AttackBlockCallback already handled this player's left-click this tick.
     */
    public boolean wasBlockClickHandledThisTick(ServerPlayerEntity player) {
        return blockClickHandledThisTick.contains(player.getUuid());
    }

    /**
     * Clear dedup flags. Called at the start of each server tick.
     */
    public void clearBlockClickFlags() {
        blockClickHandledThisTick.clear();
    }

    // ==================== Fabric Callback Delegation ====================

    /**
     * Handle left-click (from AttackBlockCallback).
     * @return FAIL to cancel block breaking, PASS if no active session
     */
    public ActionResult handleLeftClick(ServerPlayerEntity player) {
        ZonePlacementSession session = activeSessions.get(player.getUuid());
        if (session == null || session.isStopped()) return ActionResult.PASS;

        boolean consumed = session.handleInteraction(true, player.isSneaking());
        return consumed ? ActionResult.FAIL : ActionResult.PASS;
    }

    /**
     * Handle right-click (from UseBlockCallback or UseItemCallback).
     * @return FAIL to cancel block use/placement, PASS if no active session
     */
    public ActionResult handleRightClick(ServerPlayerEntity player) {
        ZonePlacementSession session = activeSessions.get(player.getUuid());
        if (session == null || session.isStopped()) return ActionResult.PASS;

        boolean consumed = session.handleInteraction(false, player.isSneaking());
        return consumed ? ActionResult.FAIL : ActionResult.PASS;
    }

    // ==================== Tick ====================

    /**
     * Tick all active sessions (renders particles, updates action bars).
     * Called from AudioVizMod's tick loop.
     */
    public void tick() {
        for (var entry : activeSessions.entrySet()) {
            ZonePlacementSession session = entry.getValue();
            if (session.isStopped()) {
                activeSessions.remove(entry.getKey());
                continue;
            }
            session.tick();
        }
    }

    // ==================== Lifecycle ====================

    /**
     * Handle player disconnect.
     */
    public void handleDisconnect(UUID playerUuid) {
        ZonePlacementSession session = activeSessions.remove(playerUuid);
        if (session != null) {
            session.stop(true);
        }
    }

    /**
     * Cancel all active sessions (called on server shutdown).
     */
    public void cancelAll() {
        for (ZonePlacementSession session : activeSessions.values()) {
            session.stop(true);
        }
        activeSessions.clear();
    }
}
