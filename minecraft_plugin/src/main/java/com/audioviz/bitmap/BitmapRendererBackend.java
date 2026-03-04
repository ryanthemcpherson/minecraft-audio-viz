package com.audioviz.bitmap;

/*
 * Bitmap LED Wall Renderer — text display entities as individually-addressable pixels.
 *
 * This implementation uses a classic 1-entity-per-pixel grid for maximum visual
 * fidelity and predictable behavior under live DJ workloads.
 */

import com.audioviz.AudioVizPlugin;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.entities.EntityUpdate;
import com.audioviz.patterns.AudioState;
import com.audioviz.render.RendererBackend;
import com.audioviz.render.RendererBackendType;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BitmapRendererBackend implements RendererBackend {

    private final AudioVizPlugin plugin;
    private final EntityPoolManager poolManager;

    /** Zone name -> grid config for active bitmap zones. */
    private final Map<String, BitmapGridConfig> gridConfigs = new ConcurrentHashMap<>();

    /** Zone name -> last frame buffer snapshot for dirty-checking. */
    private final Map<String, int[]> lastFramePixels = new ConcurrentHashMap<>();

    private static final int DEFAULT_INTERPOLATION_TICKS = 2;
    private static final int MAX_BITMAP_WIDTH = 128;
    private static final int MAX_BITMAP_HEIGHT = 128;

    public BitmapRendererBackend(AudioVizPlugin plugin, EntityPoolManager poolManager) {
        this.plugin = plugin;
        this.poolManager = poolManager;
    }

    @Override
    public RendererBackendType getType() {
        return RendererBackendType.BITMAP;
    }

    @Override
    public void initialize(VisualizationZone zone, int count, Material material) {
        int side = (int) Math.ceil(Math.sqrt(count));
        initializeBitmapGrid(zone, side, side);
    }

    public int[] initializeBitmapGrid(VisualizationZone zone) {
        int pixelsPerBlock = plugin.getConfig().getInt("bitmap.pixels_per_block", 3);
        int maxPixels = plugin.getConfig().getInt("bitmap.max_pixels_per_zone", 500);

        double zoneW = zone.getSize().getX();
        double zoneH = zone.getSize().getY();

        int targetW = Math.max(1, (int) Math.round(zoneW * pixelsPerBlock));
        int targetH = Math.max(1, (int) Math.round(zoneH * pixelsPerBlock));

        if (targetW * targetH > maxPixels) {
            double ratio = Math.sqrt((double) maxPixels / (targetW * targetH));
            targetW = Math.max(1, (int) (targetW * ratio));
            targetH = Math.max(1, (int) (targetH * ratio));
        }

        plugin.getLogger().info("Auto-sized bitmap for zone '" + zone.getName() +
            "': zone=" + zoneW + "x" + zoneH + " -> " + targetW + "x" + targetH +
            " (" + (targetW * targetH) + " pixels)");

        return initializeBitmapGrid(zone, targetW, targetH);
    }

    public int[] initializeBitmapGrid(VisualizationZone zone, int width, int height) {
        width = Math.max(1, Math.min(MAX_BITMAP_WIDTH, width));
        height = Math.max(1, Math.min(MAX_BITMAP_HEIGHT, height));

        int maxPixels = plugin.getConfig().getInt("bitmap.max_pixels_per_zone", 500);
        if (width * height > maxPixels) {
            plugin.getLogger().warning("Bitmap " + width + "x" + height + " = " +
                (width * height) + " exceeds bitmap.max_pixels_per_zone (" + maxPixels +
                "). Scaling down.");
            double ratio = Math.sqrt((double) maxPixels / (width * height));
            width = Math.max(1, (int) (width * ratio));
            height = Math.max(1, (int) (height * ratio));
        }

        String zoneName = zone.getName();
        BitmapGridConfig config = new BitmapGridConfig(width, height, DEFAULT_INTERPOLATION_TICKS);
        gridConfigs.put(zoneName.toLowerCase(), config);

        int count = width * height;
        final int finalWidth = width;
        final int finalHeight = height;

        Bukkit.getScheduler().runTask(plugin, () -> {
            poolManager.cleanupZone(zoneName);

            double zoneWidth = zone.getSize().getX();
            double zoneHeight = zone.getSize().getY();
            float pixelScaleX = (float) (zoneWidth / finalWidth);
            float pixelScaleY = (float) (zoneHeight / finalHeight);
            float pixelScale = Math.min(pixelScaleX, pixelScaleY);

            double gridWorldWidth = finalWidth * pixelScale;
            double gridWorldHeight = finalHeight * pixelScale;
            double offsetX = (zoneWidth - gridWorldWidth) / 2.0 / zoneWidth;
            double offsetY = (zoneHeight - gridWorldHeight) / 2.0 / zoneHeight;

            Map<String, Entity> pool = new LinkedHashMap<>();

            for (int py = 0; py < finalHeight; py++) {
                for (int px = 0; px < finalWidth; px++) {
                    int idx = py * finalWidth + px;
                    String entityId = "bmp_" + idx;

                    double localX = offsetX + ((finalWidth - 1 - px + 0.5) * pixelScale) / zoneWidth;
                    double localY = offsetY + ((finalHeight - 1 - py + 0.5) * pixelScale) / zoneHeight;
                    double localZ = 0.5;

                    Location pixelLoc = zone.localToWorld(localX, localY, localZ);
                    pixelLoc.setYaw(zone.getRotation() + 180);
                    pixelLoc.setPitch(0);

                    final float scale = pixelScale;
                    TextDisplay display = pixelLoc.getWorld().spawn(pixelLoc, TextDisplay.class, entity -> {
                        entity.setText(" ");
                        entity.setBackgroundColor(Color.fromARGB(255, 0, 0, 0));
                        entity.setBillboard(Display.Billboard.FIXED);
                        entity.setBrightness(new Display.Brightness(15, 15));
                        entity.setInterpolationDuration(DEFAULT_INTERPOLATION_TICKS);
                        entity.setInterpolationDelay(0);
                        entity.setTeleportDuration(0);
                        entity.setSeeThrough(false);
                        entity.setShadowed(false);
                        entity.setTextOpacity((byte) 0xFF);
                        entity.setDefaultBackground(false);
                        entity.setLineWidth(200);
                        entity.setPersistent(false);

                        entity.setTransformation(new Transformation(
                            new Vector3f(0.1f * scale, 0f, 0f),
                            new AxisAngle4f(0, 0, 0, 1),
                            new Vector3f(scale * 8.0f, scale * 4.0f, 1f),
                            new AxisAngle4f(0, 0, 0, 1)
                        ));
                    });

                    pool.put(entityId, display);
                }
            }

            poolManager.registerExternalPool(zoneName, pool);

            plugin.getLogger().info("Bitmap grid initialized: " + finalWidth + "x" + finalHeight +
                " (" + count + " pixels) for zone '" + zoneName + "'");
        });

        return new int[]{width, height};
    }

    public void applyFrame(String zoneName, BitmapFrameBuffer frame) {
        applyFrame(zoneName, frame, null);
    }

    /**
     * Brightness array is accepted for protocol compatibility, but pixel-grid mode
     * currently ignores it and uses ARGB colors directly.
     */
    public void applyFrame(String zoneName, BitmapFrameBuffer frame, int[] brightness) {
        String zoneKey = zoneName.toLowerCase();
        BitmapGridConfig config = gridConfigs.get(zoneKey);
        if (config == null) return;

        int[] currentPixels = frame.getRawPixels();
        int[] lastPixels = lastFramePixels.get(zoneKey);

        boolean fullFrame = (lastPixels == null || lastPixels.length != currentPixels.length);
        int totalPixels = Math.min(currentPixels.length, config.width() * config.height());

        String[] dirtyIds = getDirtyIdsScratch(totalPixels);
        int[] dirtyArgb = getDirtyArgbScratch(totalPixels);
        int dirtyCount = 0;

        for (int i = 0; i < totalPixels; i++) {
            int argb = currentPixels[i];
            if (fullFrame || argb != lastPixels[i]) {
                dirtyIds[dirtyCount] = getBmpEntityId(i);
                dirtyArgb[dirtyCount] = argb;
                dirtyCount++;
            }
        }

        if (dirtyCount == 0) return;

        boolean applied = poolManager.batchUpdateTextBackgroundsRaw(zoneName, dirtyIds, dirtyArgb, dirtyCount);
        if (applied) {
            int[] snapshot = new int[currentPixels.length];
            System.arraycopy(currentPixels, 0, snapshot, 0, currentPixels.length);
            lastFramePixels.put(zoneKey, snapshot);
        }
    }

    private String[] dirtyIdsScratch = new String[0];
    private int[] dirtyArgbScratch = new int[0];

    private String[] getDirtyIdsScratch(int minCapacity) {
        if (dirtyIdsScratch.length < minCapacity) {
            dirtyIdsScratch = new String[minCapacity];
        }
        return dirtyIdsScratch;
    }

    private int[] getDirtyArgbScratch(int minCapacity) {
        if (dirtyArgbScratch.length < minCapacity) {
            dirtyArgbScratch = new int[minCapacity];
        }
        return dirtyArgbScratch;
    }

    private String[] bmpEntityIdCache = new String[0];

    private String getBmpEntityId(int index) {
        if (index >= bmpEntityIdCache.length) {
            int newLen = Math.max(index + 1, bmpEntityIdCache.length * 2);
            String[] expanded = new String[newLen];
            System.arraycopy(bmpEntityIdCache, 0, expanded, 0, bmpEntityIdCache.length);
            for (int i = bmpEntityIdCache.length; i < newLen; i++) {
                expanded[i] = "bmp_" + i;
            }
            bmpEntityIdCache = expanded;
        }
        return bmpEntityIdCache[index];
    }

    public void applyRawFrame(String zoneName, int[] argbPixels) {
        applyRawFrame(zoneName, argbPixels, null);
    }

    public void applyRawFrame(String zoneName, int[] argbPixels, int[] brightness) {
        String zoneKey = zoneName.toLowerCase();
        BitmapGridConfig config = gridConfigs.get(zoneKey);
        if (config == null) return;

        BitmapFrameBuffer temp = new BitmapFrameBuffer(config.width(), config.height());
        int count = Math.min(argbPixels.length, config.width() * config.height());
        System.arraycopy(argbPixels, 0, temp.getRawPixels(), 0, count);
        applyFrame(zoneName, temp, brightness);
    }

    @Override
    public void updateFrame(String zoneName, List<EntityUpdate> updates) {
        if (updates == null || updates.isEmpty()) return;

        Map<String, Color> colorUpdates = new LinkedHashMap<>();
        for (EntityUpdate update : updates) {
            if (update.hasBrightness()) {
                int gray = (int) (update.brightness() * 255.0 / 15.0);
                colorUpdates.put(update.entityId(), Color.fromARGB(255, gray, gray, gray));
            }
        }

        if (!colorUpdates.isEmpty()) {
            poolManager.batchUpdateTextBackgrounds(zoneName, colorUpdates);
        }
    }

    @Override
    public void updateAudioState(AudioState audioState) {
        // No-op: audio is consumed by patterns, not backend.
    }

    @Override
    public void setVisible(String zoneName, boolean visible) {
        if (visible) {
            int[] last = lastFramePixels.get(zoneName.toLowerCase());
            if (last != null) {
                applyRawFrame(zoneName, last);
            }
        } else {
            BitmapGridConfig config = gridConfigs.get(zoneName.toLowerCase());
            if (config != null) {
                int[] black = new int[config.width() * config.height()];
                applyRawFrame(zoneName, black);
            }
        }
    }

    @Override
    public void teardown(String zoneName) {
        plugin.getAmbientLightManager().teardownZone(zoneName);
        gridConfigs.remove(zoneName.toLowerCase());
        lastFramePixels.remove(zoneName.toLowerCase());
        poolManager.cleanupZone(zoneName);
    }

    @Override
    public int getElementCount(String zoneName) {
        BitmapGridConfig config = gridConfigs.get(zoneName.toLowerCase());
        return config != null ? config.width() * config.height() : 0;
    }

    @Override
    public Set<String> getElementIds(String zoneName) {
        BitmapGridConfig config = gridConfigs.get(zoneName.toLowerCase());
        if (config == null) return Collections.emptySet();

        Set<String> ids = new LinkedHashSet<>();
        int count = config.width() * config.height();
        for (int i = 0; i < count; i++) {
            ids.add("bmp_" + i);
        }
        return ids;
    }

    public BitmapGridConfig getGridConfig(String zoneName) {
        return gridConfigs.get(zoneName.toLowerCase());
    }

    public boolean isBitmapZone(String zoneName) {
        return gridConfigs.containsKey(zoneName.toLowerCase());
    }

    public void setInterpolation(String zoneName, int ticks) {
        BitmapGridConfig config = gridConfigs.get(zoneName.toLowerCase());
        if (config == null) return;

        gridConfigs.put(zoneName.toLowerCase(), new BitmapGridConfig(config.width(), config.height(), ticks));
        poolManager.setZoneInterpolation(zoneName, ticks);
    }

    public record BitmapGridConfig(int width, int height, int interpolationTicks) {
        public int pixelCount() { return width * height; }
    }
}
