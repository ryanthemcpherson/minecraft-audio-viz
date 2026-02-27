package com.audioviz.map;

import net.minecraft.item.map.MapState;
import net.minecraft.network.packet.s2c.play.MapUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Collection;

/**
 * Sends MapUpdateS2CPacket with dirty-rect partial updates to players in range.
 * One packet per dirty tile replaces thousands of per-entity metadata packets.
 */
public class MapPacketSender {

    /**
     * Send a partial map update to all players within range of the display.
     *
     * @param mapId   Minecraft map ID (from MapState)
     * @param buffer  The map frame buffer with dirty tracking
     * @param players Players to send the update to
     */
    public static void sendDirtyUpdate(int mapId, MapFrameBuffer buffer,
                                        Collection<ServerPlayerEntity> players) {
        var dirtyOpt = buffer.getDirtyRect();
        if (dirtyOpt.isEmpty()) return;

        var dirty = dirtyOpt.get();
        byte[] data = buffer.extractDirtyData();

        MapState.UpdateData colorPatch = new MapState.UpdateData(
            dirty.x(), dirty.z(),
            dirty.width(), dirty.height(),
            data
        );

        MapUpdateS2CPacket packet = new MapUpdateS2CPacket(
            new net.minecraft.component.type.MapIdComponent(mapId),
            (byte) 0,
            false,
            java.util.Optional.empty(),
            java.util.Optional.of(colorPatch)
        );

        for (ServerPlayerEntity player : players) {
            player.networkHandler.sendPacket(packet);
        }

        buffer.clearDirty();
    }
}
