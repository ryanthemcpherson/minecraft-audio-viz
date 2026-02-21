package com.audioviz.bitmap;

/*
 * Bitmap LED Wall Renderer — text display entities as individually-addressable pixels.
 *
 * The core pixel-grid technique (text display with space character + ARGB background
 * color, client-side interpolation smoothing) was pioneered by TheCymaera:
 *   https://github.com/TheCymaera/minecraft-text-display-experiments
 *   Video: https://youtu.be/uZmEYYs0ZKs
 *
 * MCAV adapted this approach for real-time audio visualization with a frame buffer
 * pipeline, dirty-pixel diffing, and VJ control protocol integration.
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renderer backend that creates a flat 2D grid of TextDisplay entities,
 * each acting as a single "pixel" via background color manipulation.
 *
 * <p>This is the core integration of TheCymaera's bitmap display technique
 * into MCAV's renderer architecture. Each TextDisplay:
 * <ul>
 *   <li>Has {@code text = " "} (space character) so only background shows</li>
 *   <li>Uses {@code backgroundColor} as the pixel color (ARGB)</li>
 *   <li>Has {@code interpolationDuration = 2-3} for client-side smooth blending</li>
 *   <li>Uses {@code Billboard.FIXED} to face the configured direction</li>
 * </ul>
 *
 * <p>The server pushes color updates at 20 TPS via {@link #applyFrame}, but the
 * client interpolates between states, making the visual appear ~60fps smooth.
 * This is the same trick that makes TheCymaera's Mandelbrot demo look fluid.
 *
 * <p>Lifecycle: {@link #initialize} spawns the grid → {@link #applyFrame} pushes
 * colors each tick → {@link #teardown} despawns.
 */
public class BitmapRendererBackend implements RendererBackend {

    private final AudioVizPlugin plugin;
    private final EntityPoolManager poolManager;

    /** Zone name → grid config for active bitmap zones. */
    private final Map<String, BitmapGridConfig> gridConfigs = new ConcurrentHashMap<>();

    /** Zone name → last frame buffer snapshot (for dirty-checking optimization). */
    private final Map<String, int[]> lastFramePixels = new ConcurrentHashMap<>();

    /** Default interpolation ticks for smooth color blending. */
    private static final int DEFAULT_INTERPOLATION_TICKS = 3;

    /** Maximum bitmap dimensions (prevents entity explosion). */
    private static final int MAX_BITMAP_WIDTH = 128;
    private static final int MAX_BITMAP_HEIGHT = 64;

    public BitmapRendererBackend(AudioVizPlugin plugin, EntityPoolManager poolManager) {
        this.plugin = plugin;
        this.poolManager = poolManager;
    }

    @Override
    public RendererBackendType getType() {
        return RendererBackendType.BITMAP;
    }

    /**
     * Initialize is called by the generic renderer contract with a block Material,
     * but bitmap mode doesn't use block displays. We store the count but defer
     * actual grid spawning to {@link #initializeBitmapGrid}.
     */
    @Override
    public void initialize(VisualizationZone zone, int count, Material material) {
        // For bitmap mode, count is width*height. We need explicit dimensions.
        // If called through the generic path, assume a square-ish grid.
        int side = (int) Math.ceil(Math.sqrt(count));
        initializeBitmapGrid(zone, side, side);
    }

    /**
     * Initialize a bitmap grid with explicit dimensions.
     * Returns the actual dimensions used as {width, height} (may be scaled down).
     */
    public int[] initializeBitmapGrid(VisualizationZone zone, int width, int height) {
        // Clamp dimensions
        width = Math.max(1, Math.min(MAX_BITMAP_WIDTH, width));
        height = Math.max(1, Math.min(MAX_BITMAP_HEIGHT, height));

        int maxEntities = plugin.getConfig().getInt("performance.max_entities_per_zone", 1000);
        if (width * height > maxEntities) {
            plugin.getLogger().warning("Bitmap " + width + "x" + height + " = " +
                (width * height) + " exceeds max_entities_per_zone (" + maxEntities +
                "). Scaling down.");
            double ratio = Math.sqrt((double) maxEntities / (width * height));
            width = Math.max(1, (int) (width * ratio));
            height = Math.max(1, (int) (height * ratio));
        }

        String zoneName = zone.getName();
        BitmapGridConfig config = new BitmapGridConfig(width, height, DEFAULT_INTERPOLATION_TICKS);
        gridConfigs.put(zoneName.toLowerCase(), config);

        int count = width * height;
        final int finalWidth = width;
        final int finalHeight = height;

        // Spawn TextDisplay entities as pixels
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Clean up any existing entities first
            poolManager.cleanupZone(zoneName);

            Location origin = zone.getOrigin().clone();

            // Calculate pixel spacing based on zone size
            double zoneWidth = zone.getSize().getX();
            double zoneHeight = zone.getSize().getY();
            float pixelScaleX = (float) (zoneWidth / finalWidth);
            float pixelScaleY = (float) (zoneHeight / finalHeight);
            float pixelScale = Math.min(pixelScaleX, pixelScaleY);

            // Spawn grid of TextDisplay "pixels"
            Map<String, Entity> pool = new LinkedHashMap<>();

            for (int py = 0; py < finalHeight; py++) {
                for (int px = 0; px < finalWidth; px++) {
                    int idx = py * finalWidth + px;
                    String entityId = "bmp_" + idx;

                    // Position: origin + grid offset
                    // X maps to columns, Y maps to rows (bottom-up for natural coords)
                    double worldX = origin.getX() + (px + 0.5) * pixelScale;
                    double worldY = origin.getY() + (finalHeight - 1 - py + 0.5) * pixelScale;
                    double worldZ = origin.getZ(); // Flat on Z plane

                    // Apply zone rotation
                    double radians = Math.toRadians(zone.getRotation());
                    double relX = worldX - origin.getX();
                    double relZ = worldZ - origin.getZ();
                    double rotX = relX * Math.cos(radians) - relZ * Math.sin(radians);
                    double rotZ = relX * Math.sin(radians) + relZ * Math.cos(radians);

                    Location pixelLoc = new Location(
                        origin.getWorld(),
                        origin.getX() + rotX,
                        worldY,
                        origin.getZ() + rotZ,
                        zone.getRotation(), 0
                    );

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
                        entity.setDefaultBackground(false);
                        entity.setLineWidth(200); // Wide enough that space doesn't wrap
                        entity.setPersistent(false);

                        // Scale to pixel size using transformation
                        entity.setTransformation(new Transformation(
                            new Vector3f(-scale / 2f, -scale / 2f, 0),
                            new AxisAngle4f(0, 0, 0, 1),
                            new Vector3f(scale, scale, scale),
                            new AxisAngle4f(0, 0, 0, 1)
                        ));
                    });

                    pool.put(entityId, display);
                }
            }

            // Register with pool manager for lifecycle tracking
            poolManager.registerExternalPool(zoneName, pool);

            plugin.getLogger().info("Bitmap grid initialized: " + finalWidth + "x" + finalHeight +
                " (" + count + " pixels) for zone '" + zoneName + "'");
        });

        return new int[]{width, height};
    }

    /**
     * Apply a frame buffer to the bitmap grid.
     * This is the hot path — called every tick (~20 TPS) by the pattern engine.
     *
     * <p>Uses dirty-checking: only sends color updates for pixels that changed
     * since the last frame, dramatically reducing entity update overhead.
     *
     * @param zoneName the zone to update
     * @param frame    the frame buffer to render
     */
    public void applyFrame(String zoneName, BitmapFrameBuffer frame) {
        String zoneKey = zoneName.toLowerCase();
        BitmapGridConfig config = gridConfigs.get(zoneKey);
        if (config == null) return;

        int[] currentPixels = frame.getRawPixels();
        int[] lastPixels = lastFramePixels.get(zoneKey);

        // Build color update map (only changed pixels)
        Map<String, Color> colorUpdates = new LinkedHashMap<>();
        boolean fullFrame = (lastPixels == null || lastPixels.length != currentPixels.length);

        for (int i = 0; i < currentPixels.length && i < config.width() * config.height(); i++) {
            int argb = currentPixels[i];
            if (fullFrame || argb != lastPixels[i]) {
                String entityId = "bmp_" + i;
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                colorUpdates.put(entityId, Color.fromARGB(a, r, g, b));
            }
        }

        if (!colorUpdates.isEmpty()) {
            poolManager.batchUpdateTextBackgrounds(zoneName, colorUpdates);
        }

        // Snapshot for next frame's dirty check
        int[] snapshot = new int[currentPixels.length];
        System.arraycopy(currentPixels, 0, snapshot, 0, currentPixels.length);
        lastFramePixels.put(zoneKey, snapshot);
    }

    /**
     * Apply a raw ARGB pixel array directly (from WebSocket bitmap_frame message).
     */
    public void applyRawFrame(String zoneName, int[] argbPixels) {
        String zoneKey = zoneName.toLowerCase();
        BitmapGridConfig config = gridConfigs.get(zoneKey);
        if (config == null) return;

        BitmapFrameBuffer temp = new BitmapFrameBuffer(config.width(), config.height());
        int count = Math.min(argbPixels.length, config.width() * config.height());
        System.arraycopy(argbPixels, 0, temp.getRawPixels(), 0, count);
        applyFrame(zoneName, temp);
    }

    @Override
    public void updateFrame(String zoneName, List<EntityUpdate> updates) {
        // Bitmap mode doesn't use the entity update path — it uses applyFrame().
        // But we support it as a fallback: treat each update's brightness as grayscale.
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
        // Audio state is consumed by BitmapPatterns writing to the frame buffer,
        // not by the backend directly.
    }

    @Override
    public void setVisible(String zoneName, boolean visible) {
        if (visible) {
            // Restore last frame
            int[] last = lastFramePixels.get(zoneName.toLowerCase());
            if (last != null) {
                applyRawFrame(zoneName, last);
            }
        } else {
            // Blackout: set all pixels to transparent
            BitmapGridConfig config = gridConfigs.get(zoneName.toLowerCase());
            if (config != null) {
                int[] black = new int[config.width() * config.height()];
                applyRawFrame(zoneName, black);
            }
        }
    }

    @Override
    public void teardown(String zoneName) {
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

    /**
     * Get the grid config for a zone (if it's in bitmap mode).
     */
    public BitmapGridConfig getGridConfig(String zoneName) {
        return gridConfigs.get(zoneName.toLowerCase());
    }

    /**
     * Check if a zone is configured for bitmap rendering.
     */
    public boolean isBitmapZone(String zoneName) {
        return gridConfigs.containsKey(zoneName.toLowerCase());
    }

    /**
     * Set the interpolation duration for smooth color blending.
     * Higher values = smoother but more latent. 2-3 ticks is the sweet spot.
     */
    public void setInterpolation(String zoneName, int ticks) {
        BitmapGridConfig config = gridConfigs.get(zoneName.toLowerCase());
        if (config == null) return;

        gridConfigs.put(zoneName.toLowerCase(),
            new BitmapGridConfig(config.width(), config.height(), ticks));
        poolManager.setZoneInterpolation(zoneName, ticks);
    }

    /**
     * Immutable grid configuration for a bitmap zone.
     */
    public record BitmapGridConfig(int width, int height, int interpolationTicks) {
        public int pixelCount() { return width * height; }
    }
}
