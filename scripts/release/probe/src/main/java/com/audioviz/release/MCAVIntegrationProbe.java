package com.audioviz.release;

import com.audioviz.AudioVizPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable Paper integration probe. This class is never shipped in a release bundle. */
public final class MCAVIntegrationProbe extends JavaPlugin {
    private static final String PROBE_WORLD = "mcav_unload_probe";
    private static final String PROBE_ZONE = "mcav_unload_probe";

    @Override
    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] arguments) {
        if (arguments.length != 1) {
            return false;
        }

        if ("verify-main-batch".equals(arguments[0])) {
            Bukkit.getScheduler().runTaskLater(this, this::verifyMainBatch, 20L);
            return true;
        }
        if (!"unload-cycle".equals(arguments[0])) {
            return false;
        }

        AudioVizPlugin audioViz = AudioVizPlugin.getInstance();
        World world = new WorldCreator(PROBE_WORLD).createWorld();
        if (audioViz == null || world == null) {
            getLogger().severe("MCAV_PROBE_WORLD_UNLOAD_FAILED setup");
            return true;
        }

        if (!audioViz.getZoneManager().zoneExists(PROBE_ZONE)) {
            audioViz.getZoneManager().createZone(
                    PROBE_ZONE, new Location(world, 0.0, 80.0, 0.0));
        }
        audioViz.getEntityPoolManager().initializeBlockPool(
                PROBE_ZONE, 16, Material.GLOWSTONE);

        Bukkit.getScheduler().runTaskLater(this, () -> verifyWorldUnload(audioViz, world), 2L);
        return true;
    }

    private void verifyMainBatch() {
        int displayCount = 0;
        int transformedCount = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof BlockDisplay display) {
                    displayCount++;
                    if (display.getTransformation().getScale().x() > 0.0F) {
                        transformedCount++;
                    }
                }
            }
        }
        if (displayCount == 256 && transformedCount == 256) {
            getLogger().info("MCAV_PROBE_MAIN_BATCH_APPLIED");
            return;
        }
        getLogger().severe(
                "MCAV_PROBE_MAIN_BATCH_FAILED displays="
                        + displayCount
                        + " transformed="
                        + transformedCount);
    }

    private void verifyWorldUnload(AudioVizPlugin audioViz, World world) {
        int initializedCount = audioViz.getEntityPoolManager().getEntityCount(PROBE_ZONE);
        boolean unloaded = Bukkit.unloadWorld(world, false);
        int remainingCount = audioViz.getEntityPoolManager().getEntityCount(PROBE_ZONE);

        if (initializedCount == 16 && unloaded && remainingCount == 0) {
            getLogger().info("MCAV_PROBE_WORLD_UNLOAD_CLEAN");
            return;
        }
        getLogger().severe(
                "MCAV_PROBE_WORLD_UNLOAD_FAILED initialized="
                        + initializedCount
                        + " unloaded="
                        + unloaded
                        + " remaining="
                        + remainingCount);
    }
}
