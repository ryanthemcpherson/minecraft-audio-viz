package com.audioviz.net;

import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Wraps multiple packets in a Bundle for atomic client-side processing.
 * All packets in a bundle are applied on the same client tick — no visual tearing.
 *
 * Max 4096 packets per bundle (Minecraft protocol limit).
 */
public class BundlePacketSender {
    private static final int MAX_BUNDLE_SIZE = 4096;

    /**
     * Send a list of packets as a bundle to all specified players.
     * Automatically splits into multiple bundles if > 4096 packets.
     */
    @SuppressWarnings("unchecked")
    public static void sendBundled(List<? extends Packet<? super ClientPlayPacketListener>> packets,
                                    Collection<ServerPlayerEntity> players) {
        if (packets.isEmpty() || players.isEmpty()) return;

        for (int i = 0; i < packets.size(); i += MAX_BUNDLE_SIZE) {
            int end = Math.min(i + MAX_BUNDLE_SIZE, packets.size());
            var chunk = packets.subList(i, end);
            List<Packet<? super ClientPlayPacketListener>> bundleList = new ArrayList<>(chunk);
            BundleS2CPacket bundle = new BundleS2CPacket(bundleList);

            for (ServerPlayerEntity player : players) {
                player.networkHandler.sendPacket(bundle);
            }
        }
    }
}
