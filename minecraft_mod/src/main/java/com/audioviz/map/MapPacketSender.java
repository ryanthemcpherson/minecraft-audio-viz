package com.audioviz.map;

import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.map.MapState;
import net.minecraft.network.packet.s2c.play.MapUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Collection;
import java.util.Optional;

/**
 * Sends MapUpdateS2CPacket with full-frame updates to players in range.
 * Bypasses vanilla map tracking — we handle all updates ourselves.
 */
public class MapPacketSender {

    /**
     * Send a map update to all players. Always sends the full frame
     * for maximum reliability at 20 TPS.
     */
    public static void sendUpdate(int mapId, MapFrameBuffer buffer,
                                   Collection<ServerPlayerEntity> players) {
        if (players.isEmpty()) return;

        // Always send full 128×128 frame — reliable and only ~16KB per map
        byte[] columnMajor = buffer.extractFullFrame();

        MapState.UpdateData colorPatch = new MapState.UpdateData(
            0, 0, MapFrameBuffer.SIZE, MapFrameBuffer.SIZE, columnMajor
        );

        MapUpdateS2CPacket packet = new MapUpdateS2CPacket(
            new MapIdComponent(mapId),
            (byte) 0,
            false,
            Optional.empty(),
            Optional.of(colorPatch)
        );

        for (ServerPlayerEntity player : players) {
            player.networkHandler.sendPacket(packet);
        }
    }
}
