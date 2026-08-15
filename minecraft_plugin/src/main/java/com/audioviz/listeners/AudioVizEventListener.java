package com.audioviz.listeners;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.bitmap.text.ChatWallPattern;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/** Core Bukkit events isolated from optional integration class signatures. */
public final class AudioVizEventListener implements Listener {

    private final AudioVizPlugin plugin;

    public AudioVizEventListener(AudioVizPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        String worldName = event.getWorld().getName();
        for (var zone : plugin.getZoneManager().getAllZones()) {
            if (zone.getWorld().getName().equals(worldName)) {
                plugin.getLogger().info(
                        "World '" + worldName + "' unloading, cleaning up zone '"
                                + zone.getName() + "'");
                plugin.getEntityPoolManager().cleanupZoneSync(zone.getName());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (plugin.getBitmapPatternManager() == null) return;
        BitmapPattern pattern = plugin.getBitmapPatternManager().getPattern("bmp_chat_wall");
        if (pattern instanceof ChatWallPattern chatWall) {
            chatWall.addMessage(event.getPlayer().getName(), event.getMessage());
        }
    }
}
