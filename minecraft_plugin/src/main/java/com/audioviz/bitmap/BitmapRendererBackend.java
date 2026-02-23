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
 *
 * v2 (adaptive): Uses half-block characters (▄) for 2 pixels per entity and greedy
 * rectangle merging for uniform regions, dramatically reducing entity count.
 */

import com.audioviz.AudioVizPlugin;
import com.audioviz.bitmap.adaptive.*;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.entities.EntityUpdate;
import com.audioviz.patterns.AudioState;
import com.audioviz.render.RendererBackend;
import com.audioviz.render.RendererBackendType;
import com.audioviz.zones.VisualizationZone;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
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
 * each acting as one or more "pixels" via background color and half-block
 * character manipulation.
 *
 * <p>v2 uses the adaptive pipeline: half-block characters (▄) encode two
 * vertical pixels per entity (top = background color, bottom = text color),
 * and greedy rectangle merging collapses uniform regions into fewer entities.
 * Dual dirty tracking (geometry + color) ensures minimal Bukkit API calls.
 *
 * <p>Lifecycle: {@link #initialize} spawns the pool → {@link #applyFrame}
 * pushes colors each tick → {@link #teardown} despawns.
 */
public class BitmapRendererBackend implements RendererBackend {

    private final AudioVizPlugin plugin;
    private final EntityPoolManager poolManager;

    /** Zone name → grid config for active bitmap zones. */
    private final Map<String, BitmapGridConfig> gridConfigs = new ConcurrentHashMap<>();

    /** Zone name → adaptive entity assigner (dirty tracking). */
    private final Map<String, AdaptiveEntityAssigner> assigners = new ConcurrentHashMap<>();

    /** Default interpolation ticks for smooth color blending. */
    private static final int DEFAULT_INTERPOLATION_TICKS = 0;

    /** Maximum bitmap dimensions (prevents entity explosion). */
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
     * Auto-size a bitmap grid from zone geometry.
     * Uses bitmap.pixels_per_block and bitmap.max_pixels_per_zone from config.
     * Returns the actual dimensions used as {width, height}.
     */
    public int[] initializeBitmapGrid(VisualizationZone zone) {
        int pixelsPerBlock = plugin.getConfig().getInt("bitmap.pixels_per_block", 3);
        int maxPixels = plugin.getConfig().getInt("bitmap.max_pixels_per_zone", 500);

        double zoneW = zone.getSize().getX();
        double zoneH = zone.getSize().getY();

        int targetW = Math.max(1, (int) Math.round(zoneW * pixelsPerBlock));
        // Double vertical resolution: half-block gives 2 pixels per entity row
        int targetH = Math.max(1, (int) Math.round(zoneH * pixelsPerBlock) * 2);

        // Scale down proportionally if over budget
        if (targetW * targetH > maxPixels) {
            double ratio = Math.sqrt((double) maxPixels / (targetW * targetH));
            targetW = Math.max(1, (int) (targetW * ratio));
            targetH = Math.max(1, (int) (targetH * ratio));
        }

        plugin.getLogger().info("Auto-sized bitmap for zone '" + zone.getName() +
            "': zone=" + zoneW + "x" + zoneH + " → " + targetW + "x" + targetH +
            " (" + (targetW * targetH) + " pixels)");

        return initializeBitmapGrid(zone, targetW, targetH);
    }

    /**
     * Initialize a bitmap grid with explicit dimensions.
     * Width and height are logical pixel dimensions. Height can be up to 128;
     * half-block encoding means cellHeight = ceil(height/2) entity rows.
     * Returns the actual dimensions used as {width, height} (may be scaled down).
     */
    public int[] initializeBitmapGrid(VisualizationZone zone, int width, int height) {
        // Clamp dimensions
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
        int cellWidth = width;
        int cellHeight = CellGridMerger.cellGridHeight(height);
        int maxEntities = cellWidth * cellHeight;

        // Calculate pixel scale based on zone size / cell dimensions
        double zoneWidth = zone.getSize().getX();
        double zoneHeight = zone.getSize().getY();
        float pixelScaleX = (float) (zoneWidth / cellWidth);
        float pixelScaleY = (float) (zoneHeight / cellHeight);
        float pixelScale = Math.min(pixelScaleX, pixelScaleY);

        BitmapGridConfig config = new BitmapGridConfig(
            width, height, cellWidth, cellHeight,
            maxEntities, DEFAULT_INTERPOLATION_TICKS, pixelScale
        );
        gridConfigs.put(zoneName.toLowerCase(), config);

        // Create adaptive entity assigner for this zone
        assigners.put(zoneName.toLowerCase(), new AdaptiveEntityAssigner(maxEntities));

        final int finalWidth = width;
        final int finalHeight = height;
        final int finalMaxEntities = maxEntities;
        final float finalPixelScale = pixelScale;

        // Spawn TextDisplay entities as a pool, all starting invisible
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Clean up any existing entities first
            poolManager.cleanupZone(zoneName);

            Location center = zone.getCenter();
            center.setYaw(zone.getRotation() + 180);
            center.setPitch(0);

            // Spawn pool of entities at zone center (they get repositioned by applyFrame)
            Map<String, Entity> pool = new LinkedHashMap<>();

            for (int i = 0; i < finalMaxEntities; i++) {
                String entityId = "bmp_" + i;

                TextDisplay display = center.getWorld().spawn(center, TextDisplay.class, entity -> {
                    // Default text is the half-block character
                    entity.text(Component.text(CellGridMerger.HALF_BLOCK));
                    // Start invisible: transparent background, zero scale
                    entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    entity.setBillboard(Display.Billboard.FIXED);
                    entity.setBrightness(new Display.Brightness(15, 15));
                    entity.setInterpolationDuration(DEFAULT_INTERPOLATION_TICKS);
                    entity.setInterpolationDelay(0);
                    entity.setTeleportDuration(0);
                    entity.setSeeThrough(false);
                    entity.setShadowed(false);
                    entity.setTextOpacity((byte) 0xFF);
                    entity.setDefaultBackground(false);
                    entity.setLineWidth(200); // Wide enough that text doesn't wrap
                    entity.setPersistent(false);

                    // Start at zero scale (invisible until first frame assigns them)
                    entity.setTransformation(new Transformation(
                        new Vector3f(0, 0, 0),
                        new AxisAngle4f(0, 0, 0, 1),
                        new Vector3f(0, 0, 0),
                        new AxisAngle4f(0, 0, 0, 1)
                    ));
                });

                pool.put(entityId, display);
            }

            // Register with pool manager for lifecycle tracking
            poolManager.registerExternalPool(zoneName, pool);

            plugin.getLogger().info("Adaptive bitmap grid initialized: " + finalWidth + "x" + finalHeight +
                " (" + finalMaxEntities + " entities, pixelScale=" +
                String.format("%.3f", finalPixelScale) + ") for zone '" + zoneName + "'");
        });

        return new int[]{width, height};
    }

    /**
     * Apply a frame buffer to the bitmap grid using the adaptive pipeline.
     * This is the hot path — called every tick (~20 TPS) by the pattern engine.
     *
     * <p>Pipeline: pixels → cell grid → greedy merge → assign to slots → diff → batch update.
     *
     * @param zoneName the zone to update
     * @param frame    the frame buffer to render
     */
    public void applyFrame(String zoneName, BitmapFrameBuffer frame) {
        applyFrame(zoneName, frame, null);
    }

    /**
     * Apply a frame buffer with optional per-pixel brightness.
     *
     * @param zoneName   the zone to update
     * @param frame      the frame buffer to render
     * @param brightness optional per-pixel brightness (0-15), indexed by pixel row*width+col; null = default (15)
     */
    public void applyFrame(String zoneName, BitmapFrameBuffer frame, int[] brightness) {
        String zoneKey = zoneName.toLowerCase();
        BitmapGridConfig config = gridConfigs.get(zoneKey);
        if (config == null) return;

        AdaptiveEntityAssigner assigner = assigners.get(zoneKey);
        if (assigner == null) return;

        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) return;

        int[] pixels = frame.getRawPixels();
        int logicalWidth = config.logicalWidth();
        int logicalHeight = config.logicalHeight();
        float pixelScale = config.pixelScale();

        // 1. Build rect list: either adaptive merged rectangles or 1x1 cell rects.
        // Default is non-merged for visual stability/fidelity.
        boolean adaptiveMerge = plugin.getConfig().getBoolean("bitmap.adaptive_merge", false);
        List<MergedRect> rects = adaptiveMerge
            ? CellGridMerger.merge(pixels, logicalWidth, logicalHeight)
            : CellGridMerger.asCellRects(pixels, logicalWidth, logicalHeight);

        // 2. Assign: rects → pool slots with dirty tracking
        AdaptiveEntityAssigner.FrameDiff diff = assigner.assign(rects, pixelScale);

        // 3. Early exit if nothing changed
        List<AdaptiveEntityAssigner.GeometryUpdate> geoUpdates = diff.geometryUpdates();
        List<AdaptiveEntityAssigner.BackgroundUpdate> bgUpdates = diff.backgroundUpdates();
        List<AdaptiveEntityAssigner.TextUpdate> txtUpdates = diff.textUpdates();
        int hideStart = diff.hideStart();
        int hideCount = diff.hideCount();

        if (geoUpdates.isEmpty() && bgUpdates.isEmpty() && txtUpdates.isEmpty() && hideCount == 0) {
            return;
        }

        // 4. Build arrays for batchUpdateAdaptive

        // Zone geometry for world-space positioning
        double zoneWidth = zone.getSize().getX();
        double zoneHeight = zone.getSize().getY();
        int cellWidth = config.cellWidth();
        int cellHeight = config.cellHeight();

        // Center the cell grid within the zone face
        double gridWorldWidth = cellWidth * pixelScale;
        double gridWorldHeight = cellHeight * pixelScale;
        double offsetX = (zoneWidth - gridWorldWidth) / 2.0 / zoneWidth;
        double offsetY = (zoneHeight - gridWorldHeight) / 2.0 / zoneHeight;

        // --- Geometry: Transformation + teleport location ---
        int geoCount = geoUpdates.size();
        String[] geoIds = new String[geoCount];
        Transformation[] geoTransforms = new Transformation[geoCount];
        Location[] geoLocations = new Location[geoCount];

        for (int i = 0; i < geoCount; i++) {
            AdaptiveEntityAssigner.GeometryUpdate geo = geoUpdates.get(i);
            geoIds[i] = geo.entityId();

            float scaleX = geo.scaleX();
            float scaleY = geo.scaleY();

            // TheCymaera scaling: space bg is ~1/8 wide, ~1/4 tall
            // With half-block (▄), the character itself is ~1/4 tall, bg is ~1/4 tall
            // scaleX/scaleY are in world-space units (pixelScale * cellCount)
            geoTransforms[i] = new Transformation(
                new Vector3f(
                    (-0.1f + 0.5f) * scaleX,  // X recenter: 0.4 * scaleX
                    (-0.5f + 0.5f) * scaleY,  // Y recenter: 0
                    0f
                ),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(scaleX * 8.0f, scaleY * 4.0f, 1f),
                new AxisAngle4f(0, 0, 0, 1)
            );

            // World-space position: convert cell (x, y) to normalized zone coords
            // geo.x() and geo.y() are in world-space units from the assigner (cellX * pixelScale)
            // Convert back to cell coords for positioning
            float cellX = geo.x() / pixelScale;
            float cellY = geo.y() / pixelScale;

            // Flip X so cell 0 is viewer's left (entities face audience at rotation+180)
            // Center the rect within its span (add half of rect width/height)
            double localX = offsetX + ((cellWidth - 1 - cellX - (geo.scaleX() / pixelScale - 1) / 2.0) + 0.5) * pixelScale / zoneWidth;
            double localY = offsetY + ((cellHeight - 1 - cellY - (geo.scaleY() / pixelScale - 1) / 2.0) + 0.5) * pixelScale / zoneHeight;
            double localZ = 0.5; // Center on the Z face of the zone

            Location loc = zone.localToWorld(localX, localY, localZ);
            loc.setYaw(zone.getRotation() + 180);
            loc.setPitch(0);
            geoLocations[i] = loc;
        }

        // --- Background + optional brightness ---
        // If per-pixel brightness is provided, push bg+brightness for all assigned slots
        // to avoid stale brightness state when adaptive slot assignment reshuffles rects.
        int assignCount = Math.min(rects.size(), config.entityCount());
        int bgCount = (brightness != null) ? assignCount : bgUpdates.size();
        String[] bgIds = new String[bgCount];
        int[] bgArgb = new int[bgCount];
        int[] bgBrightness = (brightness != null) ? new int[bgCount] : null;

        if (brightness != null) {
            for (int i = 0; i < bgCount; i++) {
                MergedRect rect = rects.get(i);
                bgIds[i] = "bmp_" + i;
                bgArgb[i] = rect.topARGB();

                // Cell (x, y) -> top pixel index = (y * 2) * logicalWidth + x
                int pixIdx = rect.y() * 2 * logicalWidth + rect.x();
                if (pixIdx >= 0 && pixIdx < brightness.length) {
                    bgBrightness[i] = brightness[pixIdx];
                } else {
                    bgBrightness[i] = 15;
                }
            }
        } else {
            for (int i = 0; i < bgCount; i++) {
                AdaptiveEntityAssigner.BackgroundUpdate bg = bgUpdates.get(i);
                bgIds[i] = bg.entityId();
                bgArgb[i] = bg.argb();
            }
        }

        // --- Text: Component with half-block color, or space for uniform ---
        int txtCount = txtUpdates.size();
        String[] txtIds = new String[txtCount];
        Component[] txtComponents = new Component[txtCount];

        for (int i = 0; i < txtCount; i++) {
            AdaptiveEntityAssigner.TextUpdate txt = txtUpdates.get(i);
            txtIds[i] = txt.entityId();

            if (txt.argb() == -1) {
                // Uniform cell: use space (only background shows)
                txtComponents[i] = Component.text(" ");
            } else {
                // Half-block with bottom pixel color as text color
                int argb = txt.argb();
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                txtComponents[i] = Component.text(CellGridMerger.HALF_BLOCK,
                    TextColor.color(r, g, b));
            }
        }

        // --- Hide: entity IDs from hideStart..hideStart+hideCount ---
        String[] hideIds = new String[hideCount];
        for (int i = 0; i < hideCount; i++) {
            hideIds[i] = "bmp_" + (hideStart + i);
        }

        // 5. Batch update (includes teleport for geometry changes)
        poolManager.batchUpdateAdaptive(zoneName,
            geoIds, geoTransforms, geoLocations, geoCount,
            bgIds, bgArgb, bgBrightness, bgCount,
            txtIds, txtComponents, txtCount,
            hideIds, hideCount);

        if (diff.poolExhausted()) {
            plugin.getLogger().warning("Adaptive pool exhausted for zone '" + zoneName +
                "': " + rects.size() + " rects > " + config.entityCount() + " entities");
        }
    }

    /**
     * Apply a raw ARGB pixel array directly (from WebSocket bitmap_frame message).
     */
    public void applyRawFrame(String zoneName, int[] argbPixels) {
        applyRawFrame(zoneName, argbPixels, null);
    }

    /**
     * Apply a raw ARGB pixel array with optional per-pixel brightness.
     *
     * @param brightness optional int[] of brightness values (0-15), parallel to argbPixels; null = default (15)
     */
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
        if (!visible) {
            // Blackout: set all pixels to transparent
            BitmapGridConfig config = gridConfigs.get(zoneName.toLowerCase());
            if (config != null) {
                int[] black = new int[config.width() * config.height()];
                applyRawFrame(zoneName, black);
            }
        }
        // For visible=true, the next applyFrame call will restore the display
    }

    @Override
    public void teardown(String zoneName) {
        plugin.getAmbientLightManager().teardownZone(zoneName);
        gridConfigs.remove(zoneName.toLowerCase());
        assigners.remove(zoneName.toLowerCase());
        poolManager.cleanupZone(zoneName);
    }

    @Override
    public int getElementCount(String zoneName) {
        BitmapGridConfig config = gridConfigs.get(zoneName.toLowerCase());
        return config != null ? config.entityCount() : 0;
    }

    @Override
    public Set<String> getElementIds(String zoneName) {
        BitmapGridConfig config = gridConfigs.get(zoneName.toLowerCase());
        if (config == null) return Collections.emptySet();

        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < config.entityCount(); i++) {
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
            new BitmapGridConfig(config.logicalWidth(), config.logicalHeight(),
                config.cellWidth(), config.cellHeight(),
                config.entityCount(), ticks, config.pixelScale()));
        poolManager.setZoneInterpolation(zoneName, ticks);
    }

    /**
     * Immutable grid configuration for a bitmap zone.
     *
     * @param logicalWidth      pixel width of the frame buffer
     * @param logicalHeight     pixel height of the frame buffer (can be up to 128)
     * @param cellWidth         cell grid width (same as logicalWidth)
     * @param cellHeight        cell grid height (ceil(logicalHeight / 2))
     * @param entityCount       max entities in the pool (cellWidth * cellHeight)
     * @param interpolationTicks client-side interpolation duration
     * @param pixelScale        world-space size of one cell
     */
    public record BitmapGridConfig(
        int logicalWidth, int logicalHeight,
        int cellWidth, int cellHeight,
        int entityCount, int interpolationTicks,
        float pixelScale
    ) {
        public int pixelCount() { return logicalWidth * logicalHeight; }
        public int width() { return logicalWidth; }
        public int height() { return logicalHeight; }
    }
}
