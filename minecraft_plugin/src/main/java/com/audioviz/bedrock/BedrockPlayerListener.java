package com.audioviz.bedrock;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listens for player join/quit events to track Bedrock players.
 */
public class BedrockPlayerListener implements Listener {

    private final BedrockSupport bedrockSupport;

    public BedrockPlayerListener(BedrockSupport bedrockSupport) {
        this.bedrockSupport = bedrockSupport;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        bedrockSupport.onPlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        bedrockSupport.onPlayerQuit(event.getPlayer().getUniqueId());
    }
}
