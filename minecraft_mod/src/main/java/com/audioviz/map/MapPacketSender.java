package com.audioviz.map;

import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.map.MapState;
import net.minecraft.network.packet.s2c.play.MapUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Sends MapUpdateS2CPacket with partial (dirty-rect) updates to players in range.
 *
 * Also syncs dirty pixels to the server-side MapState.colors so that vanilla's
 * built-in map tracking (which sends full-frame data when a client first sees
 * a map in an item frame) transmits our actual pixel data instead of a blank map.
 */
public class MapPacketSender {

    /**
     * Send a map update to all players, but only if the tile has changed.
     * Uses dirty-rect tracking to send minimal data.
     * Also syncs the data to the server-side MapState for vanilla compatibility.
     */
    public static void sendUpdate(int mapId, MapFrameBuffer buffer,
                                   Collection<ServerPlayerEntity> players) {
        if (players.isEmpty()) return;

        var dirtyOpt = buffer.getDirtyRect();
        if (dirtyOpt.isEmpty()) return; // tile unchanged, skip

        MapFrameBuffer.DirtyRect rect = dirtyOpt.get();
        byte[] data = buffer.extractDirtyData();
        buffer.clearDirty();

        // Snapshot the player list to avoid concurrent modification
        List<ServerPlayerEntity> playerList = new ArrayList<>(players);
        if (playerList.isEmpty()) return;

        // Sync dirty pixels to server-side MapState so vanilla tracking
        // sends correct data when client first loads the chunk/item frame.
        ServerWorld world = (ServerWorld) playerList.get(0).getEntityWorld();
        MapState mapState = world.getMapState(new MapIdComponent(mapId));
        if (mapState != null) {
            byte[] serverColors = mapState.colors; // access-widened
            for (int z = 0; z < rect.height(); z++) {
                System.arraycopy(
                    data, z * rect.width(),
                    serverColors, (rect.z() + z) * 128 + rect.x(),
                    rect.width()
                );
            }
        }

        MapState.UpdateData colorPatch = new MapState.UpdateData(
            rect.x(), rect.z(), rect.width(), rect.height(), data
        );

        MapUpdateS2CPacket packet = new MapUpdateS2CPacket(
            new MapIdComponent(mapId),
            (byte) 0,
            false,
            Optional.empty(),
            Optional.of(colorPatch)
        );

        for (ServerPlayerEntity player : playerList) {
            player.networkHandler.sendPacket(packet);
        }
    }
}
