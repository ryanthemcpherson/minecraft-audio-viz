package com.audioviz.decorators.impl;

import com.audioviz.AudioVizPlugin;
import com.audioviz.decorators.BannerConfig;
import com.audioviz.decorators.DJInfo;
import com.audioviz.decorators.DecoratorConfig;
import com.audioviz.decorators.StageDecorator;
import com.audioviz.entities.EntityUpdate;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * Background LED wall decorator that renders behind the stage.
 * Supports two modes:
 * - Text mode: DJ name with configurable font styles and reactive colors
 * - Image mode: PNG logo converted to a pixel grid of colored TextDisplay entities
 *
 * Banner configuration is received from the VJ server via BannerConfig objects
 * and automatically applies when a DJ goes live.
 */
public class DJBannerDecorator extends StageDecorator {

    // Minecraft color codes for frequency bands
    private static final String[] BAND_COLORS = {
        "\u00A7c", // Bass - Red
        "\u00A76", // Low-mid - Gold
        "\u00A7e", // Mid - Yellow
        "\u00A7a", // High-mid - Green
        "\u00A7b"  // High - Aqua
    };

    // Block letter font: 5 rows per character, using █ (full block) and space
    private static final Map<Character, String[]> BLOCK_FONT = buildBlockFont();

    // Current state
    private BannerConfig bannerConfig = BannerConfig.empty();
    private String currentDJName = "";
    private String currentMode = "text";
    private int currentEntityCount = 0;
    private float currentPulseScale = 1.0f;
    private int beatDecayTicks = 0;
    private boolean pixelColorsApplied = false;

    public DJBannerDecorator(Stage stage, AudioVizPlugin plugin) {
        super("banner", "DJ Banner", stage, plugin);
    }

    @Override
    public DecoratorConfig getDefaultConfig() {
        DecoratorConfig config = new DecoratorConfig();
        config.set("wall_width", 20);
        config.set("wall_height", 10);
        config.set("depth_offset", -8.0);
        config.set("height_offset", 2.0);
        config.set("pulse_intensity", 0.3);
        config.set("brightness_react", true);
        config.set("glow_on_beat", true);
        return config;
    }

    @Override
    public void onActivate() {
        initBannerZone();
        initEntitiesForMode();
    }

    @Override
    public void onDeactivate() {
        cleanupDecoratorZone();
        currentEntityCount = 0;
        pixelColorsApplied = false;
    }

    @Override
    public void tick(AudioState audio, DJInfo djInfo) {
        // Update DJ name if changed
        if (djInfo.isPresent() && !djInfo.djName().equals(currentDJName)) {
            currentDJName = djInfo.djName();
            if (bannerConfig.isTextMode()) {
                updateTextDisplay(audio);
            }
        }

        // Check if we have a banner config from the manager
        BannerConfig managerConfig = plugin.getDecoratorManager().getCurrentBannerConfig();
        if (managerConfig != null && managerConfig != bannerConfig) {
            onBannerConfigChanged(managerConfig);
        }

        if (bannerConfig.isTextMode()) {
            tickTextMode(audio, djInfo);
        } else if (bannerConfig.isImageMode()) {
            tickImageMode(audio, djInfo);
        }
    }

    /**
     * Called when the VJ server sends a new banner configuration.
     * May require re-initializing the entity pool if the mode or grid size changed.
     */
    public void onBannerConfigChanged(BannerConfig newConfig) {
        boolean modeChanged = !newConfig.getMode().equals(bannerConfig.getMode());
        boolean gridChanged = newConfig.isImageMode() && (
            newConfig.getGridWidth() != bannerConfig.getGridWidth() ||
            newConfig.getGridHeight() != bannerConfig.getGridHeight()
        );

        this.bannerConfig = newConfig;
        this.pixelColorsApplied = false;

        if (modeChanged || gridChanged) {
            // Need to rebuild entity pool
            cleanupDecoratorZone();
            currentEntityCount = 0;
            initBannerZone();
            initEntitiesForMode();
        }

        // Force text update on next tick
        if (newConfig.isTextMode()) {
            currentDJName = ""; // Reset to trigger update
        }
    }

    // ========== Zone & Pool Setup ==========

    private void initBannerZone() {
        double depthOffset = config.getDouble("depth_offset", -8.0);
        double heightOffset = config.getDouble("height_offset", 2.0);
        int wallWidth = config.getInt("wall_width", 20);
        int wallHeight = config.getInt("wall_height", 10);

        Location origin = getStageRelativeLocation(0, heightOffset, depthOffset);
        createDecoratorZone(origin, new Vector(wallWidth, wallHeight, 1));
    }

    private void initEntitiesForMode() {
        if (bannerConfig.isImageMode()) {
            int count = bannerConfig.getGridWidth() * bannerConfig.getGridHeight();
            initTextPool(count, Display.Billboard.FIXED);
            currentEntityCount = count;
            currentMode = "image";
        } else {
            // Text mode: 3 entities (main text + potential multi-line)
            initTextPool(3, Display.Billboard.FIXED);
            currentEntityCount = 3;
            currentMode = "text";
        }
    }

    /**
     * Initialize text pool with a specific billboard mode.
     * Uses the new EntityPoolManager overload for FIXED billboard.
     */
    private void initTextPool(int count, Display.Billboard billboardMode) {
        plugin.getEntityPoolManager().initializeTextPool(
            getDecoratorZoneName(), count, billboardMode
        );
    }

    // ========== Text Mode ==========

    private void tickTextMode(AudioState audio, DJInfo djInfo) {
        String zoneName = getDecoratorZoneName();

        // Beat pulse
        float pulseIntensity = config.getFloat("pulse_intensity", 0.3f);
        if (audio.isBeat()) {
            currentPulseScale = 1.0f + pulseIntensity;
            beatDecayTicks = 4;
        } else if (beatDecayTicks > 0) {
            beatDecayTicks--;
            currentPulseScale = (float) lerp(1.0, 1.0 + pulseIntensity, beatDecayTicks / 4.0);
        } else {
            currentPulseScale = 1.0f;
        }

        // Brightness
        int brightness;
        if (config.getBoolean("brightness_react", true)) {
            brightness = (int) lerp(6, 15, audio.getAmplitude());
        } else {
            brightness = 15;
        }

        // Glow
        boolean glow = config.getBoolean("glow_on_beat", true) && (audio.isBeat() || beatDecayTicks > 0);

        // Neon style always glows
        if ("neon".equals(bannerConfig.getTextStyle())) {
            glow = true;
        }

        // Build entity updates for text entities
        // Main text entity at center
        float textScale = 3.0f * currentPulseScale; // Large text
        Transformation transform = new Transformation(
            new Vector3f(0, 0, 0),
            new AxisAngle4f(0, 0, 0, 1),
            new Vector3f(textScale, textScale, textScale),
            new AxisAngle4f(0, 0, 0, 1)
        );

        List<EntityUpdate> updates = new ArrayList<>();
        updates.add(EntityUpdate.builder("text_0")
            .transformation(transform)
            .brightness(brightness)
            .glow(glow)
            .interpolationDuration(2)
            .build());

        // Position secondary entities (for block font multi-line)
        if ("block".equals(bannerConfig.getTextStyle()) && currentEntityCount >= 3) {
            for (int i = 1; i < 3; i++) {
                float offsetY = -1.5f * i;
                Transformation lineTransform = new Transformation(
                    new Vector3f(0, offsetY, 0),
                    new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f(textScale * 0.8f, textScale * 0.8f, textScale * 0.8f),
                    new AxisAngle4f(0, 0, 0, 1)
                );
                updates.add(EntityUpdate.builder("text_" + i)
                    .transformation(lineTransform)
                    .brightness(brightness)
                    .glow(glow)
                    .interpolationDuration(2)
                    .build());
            }
        }

        plugin.getEntityPoolManager().batchUpdateEntities(zoneName, updates);

        // Update text content periodically (every 5 ticks for color cycling)
        if (tickCount % 5 == 0 && !currentDJName.isEmpty()) {
            updateTextDisplay(audio);
        }
    }

    private void updateTextDisplay(AudioState audio) {
        String zoneName = getDecoratorZoneName();
        String name = currentDJName.isEmpty() ? "LIVE" : currentDJName;
        String format = bannerConfig.getTextFormat();
        String formatted = String.format(format, name);
        String style = bannerConfig.getTextStyle();
        String colorCode = getColorCode(audio);

        switch (style) {
            case "block" -> updateBlockLetters(zoneName, formatted, colorCode);
            case "neon" -> updateNeonText(zoneName, formatted, colorCode);
            default -> updateBoldText(zoneName, formatted, colorCode);
        }
    }

    private void updateBoldText(String zoneName, String text, String colorCode) {
        String display = colorCode + "\u00A7l\u25C6 " + text.toUpperCase() + " \u25C6";
        plugin.getEntityPoolManager().updateTextContent(zoneName, "text_0", display);
        // Clear secondary entities
        plugin.getEntityPoolManager().updateTextContent(zoneName, "text_1", "");
        plugin.getEntityPoolManager().updateTextContent(zoneName, "text_2", "");
    }

    private void updateNeonText(String zoneName, String text, String colorCode) {
        // Neon: cycling colors, extra formatting
        int colorIndex = (int) ((tickCount / 3) % BAND_COLORS.length);
        String neonColor = BAND_COLORS[colorIndex];
        String display = neonColor + "\u00A7l\u2726 " + text.toUpperCase() + " \u2726";
        plugin.getEntityPoolManager().updateTextContent(zoneName, "text_0", display);
        // Accent line below
        String accent = neonColor + "\u2501".repeat(Math.min(text.length() + 4, 30));
        plugin.getEntityPoolManager().updateTextContent(zoneName, "text_1", accent);
        plugin.getEntityPoolManager().updateTextContent(zoneName, "text_2", "");
    }

    private void updateBlockLetters(String zoneName, String text, String colorCode) {
        String upper = text.toUpperCase();
        String[] lines = renderBlockText(upper);

        // Distribute across up to 3 text entities (top, mid, bottom rows)
        // Each entity gets a portion of the block letter rows
        StringBuilder top = new StringBuilder();
        StringBuilder mid = new StringBuilder();
        StringBuilder bot = new StringBuilder();

        for (int row = 0; row < lines.length; row++) {
            String line = colorCode + "\u00A7l" + lines[row];
            if (row < 2) top.append(line).append("\n");
            else if (row < 4) mid.append(line).append("\n");
            else bot.append(line).append("\n");
        }

        plugin.getEntityPoolManager().updateTextContent(zoneName, "text_0", top.toString().trim());
        plugin.getEntityPoolManager().updateTextContent(zoneName, "text_1", mid.toString().trim());
        plugin.getEntityPoolManager().updateTextContent(zoneName, "text_2", bot.toString().trim());
    }

    private String getColorCode(AudioState audio) {
        String mode = bannerConfig.getTextColorMode();
        return switch (mode) {
            case "rainbow" -> {
                int index = (int) ((tickCount / 5) % BAND_COLORS.length);
                yield BAND_COLORS[index];
            }
            case "fixed" -> "\u00A7" + bannerConfig.getTextFixedColor();
            default -> { // "frequency"
                int dominant = getDominantBand(audio);
                yield BAND_COLORS[dominant];
            }
        };
    }

    // ========== Image Mode ==========

    private void tickImageMode(AudioState audio, DJInfo djInfo) {
        String zoneName = getDecoratorZoneName();
        int gridW = bannerConfig.getGridWidth();
        int gridH = bannerConfig.getGridHeight();

        // Apply pixel colors once when config changes
        if (!pixelColorsApplied && bannerConfig.hasImagePixels()) {
            applyPixelColors(zoneName, gridW, gridH);
            positionPixelGrid(zoneName, gridW, gridH);
            pixelColorsApplied = true;
        }

        // Audio-reactive brightness and glow per tick
        float pulseIntensity = config.getFloat("pulse_intensity", 0.3f);
        boolean glowOnBeat = config.getBoolean("glow_on_beat", true);
        boolean brightnessReact = config.getBoolean("brightness_react", true);

        // Beat pulse for image mode
        if (audio.isBeat()) {
            beatDecayTicks = 4;
        } else if (beatDecayTicks > 0) {
            beatDecayTicks--;
        }

        boolean glow = glowOnBeat && (audio.isBeat() || beatDecayTicks > 0);

        // Map frequency bands to column ranges for per-pixel brightness
        double[] bands = audio.getBands();
        int totalEntities = gridW * gridH;
        List<EntityUpdate> updates = new ArrayList<>(totalEntities);

        for (int row = 0; row < gridH; row++) {
            for (int col = 0; col < gridW; col++) {
                int idx = row * gridW + col;
                String entityId = "text_" + idx;

                // Map column to frequency band (left=bass, right=high)
                int bandIdx = Math.min(4, (col * 5) / gridW);
                double bandVal = bands[bandIdx];

                int brightness;
                if (brightnessReact) {
                    // Base brightness + audio boost
                    brightness = (int) lerp(5, 15, 0.3 + bandVal * 0.7);
                } else {
                    brightness = 15;
                }

                // Scale pulse on beat
                float scale = 0.4f; // Base pixel scale
                if (beatDecayTicks > 0) {
                    scale += (float) (pulseIntensity * 0.1 * (beatDecayTicks / 4.0));
                }

                Transformation transform = new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f(0, 0, 0, 1)
                );

                updates.add(EntityUpdate.builder(entityId)
                    .transformation(transform)
                    .brightness(brightness)
                    .glow(glow && bandVal > 0.5)
                    .interpolationDuration(2)
                    .build());
            }
        }

        plugin.getEntityPoolManager().batchUpdateEntities(zoneName, updates);
    }

    private void applyPixelColors(String zoneName, int gridW, int gridH) {
        int[] pixels = bannerConfig.getImagePixels();
        if (pixels == null) return;

        Map<String, Color> colorMap = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(pixels.length, gridW * gridH); i++) {
            int argb = pixels[i];
            int a = (argb >> 24) & 0xFF;
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            colorMap.put("text_" + i, Color.fromARGB(a, r, g, b));
        }

        plugin.getEntityPoolManager().batchUpdateTextBackgrounds(zoneName, colorMap);
    }

    private void positionPixelGrid(String zoneName, int gridW, int gridH) {
        int wallWidth = config.getInt("wall_width", 20);
        int wallHeight = config.getInt("wall_height", 10);

        // Calculate spacing between pixels
        float spacingX = (float) wallWidth / gridW;
        float spacingY = (float) wallHeight / gridH;
        float startX = -wallWidth / 2.0f;
        float startY = wallHeight; // Top of wall

        List<EntityUpdate> positions = new ArrayList<>();
        for (int row = 0; row < gridH; row++) {
            for (int col = 0; col < gridW; col++) {
                int idx = row * gridW + col;
                String entityId = "text_" + idx;

                float x = startX + col * spacingX + spacingX / 2;
                float y = startY - row * spacingY - spacingY / 2;

                Location origin = getStageRelativeLocation(
                    x,
                    config.getDouble("height_offset", 2.0) + y,
                    config.getDouble("depth_offset", -8.0)
                );

                positions.add(EntityUpdate.builder(entityId)
                    .location(origin)
                    .build());
            }
        }

        plugin.getEntityPoolManager().batchUpdateEntities(zoneName, positions);
    }

    // ========== Block Font ==========

    /**
     * Render text using the block font. Returns 5 lines of characters.
     */
    private String[] renderBlockText(String text) {
        String[] result = new String[5];
        Arrays.fill(result, "");

        for (char c : text.toCharArray()) {
            String[] charLines = BLOCK_FONT.getOrDefault(c, BLOCK_FONT.get(' '));
            if (charLines == null) charLines = BLOCK_FONT.get(' ');
            for (int row = 0; row < 5; row++) {
                result[row] += (row < charLines.length ? charLines[row] : "   ") + " ";
            }
        }

        return result;
    }

    /**
     * Build the block letter font map. Each character is 5 rows of fixed-width strings
     * using \u2588 (full block) and space.
     */
    private static Map<Character, String[]> buildBlockFont() {
        Map<Character, String[]> font = new HashMap<>();

        font.put('A', new String[]{ "\u2588\u2588\u2588", "\u2588 \u2588", "\u2588\u2588\u2588", "\u2588 \u2588", "\u2588 \u2588" });
        font.put('B', new String[]{ "\u2588\u2588 ", "\u2588 \u2588", "\u2588\u2588 ", "\u2588 \u2588", "\u2588\u2588 " });
        font.put('C', new String[]{ "\u2588\u2588\u2588", "\u2588  ", "\u2588  ", "\u2588  ", "\u2588\u2588\u2588" });
        font.put('D', new String[]{ "\u2588\u2588 ", "\u2588 \u2588", "\u2588 \u2588", "\u2588 \u2588", "\u2588\u2588 " });
        font.put('E', new String[]{ "\u2588\u2588\u2588", "\u2588  ", "\u2588\u2588 ", "\u2588  ", "\u2588\u2588\u2588" });
        font.put('F', new String[]{ "\u2588\u2588\u2588", "\u2588  ", "\u2588\u2588 ", "\u2588  ", "\u2588  " });
        font.put('G', new String[]{ "\u2588\u2588\u2588", "\u2588  ", "\u2588 \u2588", "\u2588 \u2588", "\u2588\u2588\u2588" });
        font.put('H', new String[]{ "\u2588 \u2588", "\u2588 \u2588", "\u2588\u2588\u2588", "\u2588 \u2588", "\u2588 \u2588" });
        font.put('I', new String[]{ "\u2588\u2588\u2588", " \u2588 ", " \u2588 ", " \u2588 ", "\u2588\u2588\u2588" });
        font.put('J', new String[]{ "\u2588\u2588\u2588", "  \u2588", "  \u2588", "\u2588 \u2588", "\u2588\u2588\u2588" });
        font.put('K', new String[]{ "\u2588 \u2588", "\u2588\u2588 ", "\u2588  ", "\u2588\u2588 ", "\u2588 \u2588" });
        font.put('L', new String[]{ "\u2588  ", "\u2588  ", "\u2588  ", "\u2588  ", "\u2588\u2588\u2588" });
        font.put('M', new String[]{ "\u2588 \u2588", "\u2588\u2588\u2588", "\u2588\u2588\u2588", "\u2588 \u2588", "\u2588 \u2588" });
        font.put('N', new String[]{ "\u2588 \u2588", "\u2588\u2588\u2588", "\u2588\u2588\u2588", "\u2588 \u2588", "\u2588 \u2588" });
        font.put('O', new String[]{ "\u2588\u2588\u2588", "\u2588 \u2588", "\u2588 \u2588", "\u2588 \u2588", "\u2588\u2588\u2588" });
        font.put('P', new String[]{ "\u2588\u2588\u2588", "\u2588 \u2588", "\u2588\u2588\u2588", "\u2588  ", "\u2588  " });
        font.put('Q', new String[]{ "\u2588\u2588\u2588", "\u2588 \u2588", "\u2588 \u2588", "\u2588\u2588 ", " \u2588\u2588" });
        font.put('R', new String[]{ "\u2588\u2588\u2588", "\u2588 \u2588", "\u2588\u2588 ", "\u2588 \u2588", "\u2588 \u2588" });
        font.put('S', new String[]{ "\u2588\u2588\u2588", "\u2588  ", "\u2588\u2588\u2588", "  \u2588", "\u2588\u2588\u2588" });
        font.put('T', new String[]{ "\u2588\u2588\u2588", " \u2588 ", " \u2588 ", " \u2588 ", " \u2588 " });
        font.put('U', new String[]{ "\u2588 \u2588", "\u2588 \u2588", "\u2588 \u2588", "\u2588 \u2588", "\u2588\u2588\u2588" });
        font.put('V', new String[]{ "\u2588 \u2588", "\u2588 \u2588", "\u2588 \u2588", "\u2588 \u2588", " \u2588 " });
        font.put('W', new String[]{ "\u2588 \u2588", "\u2588 \u2588", "\u2588\u2588\u2588", "\u2588\u2588\u2588", "\u2588 \u2588" });
        font.put('X', new String[]{ "\u2588 \u2588", "\u2588 \u2588", " \u2588 ", "\u2588 \u2588", "\u2588 \u2588" });
        font.put('Y', new String[]{ "\u2588 \u2588", "\u2588 \u2588", " \u2588 ", " \u2588 ", " \u2588 " });
        font.put('Z', new String[]{ "\u2588\u2588\u2588", "  \u2588", " \u2588 ", "\u2588  ", "\u2588\u2588\u2588" });

        font.put('0', new String[]{ "\u2588\u2588\u2588", "\u2588 \u2588", "\u2588 \u2588", "\u2588 \u2588", "\u2588\u2588\u2588" });
        font.put('1', new String[]{ " \u2588 ", "\u2588\u2588 ", " \u2588 ", " \u2588 ", "\u2588\u2588\u2588" });
        font.put('2', new String[]{ "\u2588\u2588\u2588", "  \u2588", "\u2588\u2588\u2588", "\u2588  ", "\u2588\u2588\u2588" });
        font.put('3', new String[]{ "\u2588\u2588\u2588", "  \u2588", "\u2588\u2588\u2588", "  \u2588", "\u2588\u2588\u2588" });
        font.put('4', new String[]{ "\u2588 \u2588", "\u2588 \u2588", "\u2588\u2588\u2588", "  \u2588", "  \u2588" });
        font.put('5', new String[]{ "\u2588\u2588\u2588", "\u2588  ", "\u2588\u2588\u2588", "  \u2588", "\u2588\u2588\u2588" });
        font.put('6', new String[]{ "\u2588\u2588\u2588", "\u2588  ", "\u2588\u2588\u2588", "\u2588 \u2588", "\u2588\u2588\u2588" });
        font.put('7', new String[]{ "\u2588\u2588\u2588", "  \u2588", "  \u2588", "  \u2588", "  \u2588" });
        font.put('8', new String[]{ "\u2588\u2588\u2588", "\u2588 \u2588", "\u2588\u2588\u2588", "\u2588 \u2588", "\u2588\u2588\u2588" });
        font.put('9', new String[]{ "\u2588\u2588\u2588", "\u2588 \u2588", "\u2588\u2588\u2588", "  \u2588", "\u2588\u2588\u2588" });

        font.put(' ', new String[]{ "   ", "   ", "   ", "   ", "   " });
        font.put('-', new String[]{ "   ", "   ", "\u2588\u2588\u2588", "   ", "   " });
        font.put('.', new String[]{ "   ", "   ", "   ", "   ", " \u2588 " });
        font.put('!', new String[]{ " \u2588 ", " \u2588 ", " \u2588 ", "   ", " \u2588 " });

        return Collections.unmodifiableMap(font);
    }
}
