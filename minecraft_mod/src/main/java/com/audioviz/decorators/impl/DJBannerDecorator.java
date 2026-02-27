package com.audioviz.decorators.impl;

import com.audioviz.AudioVizMod;
import com.audioviz.decorators.*;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.*;

/**
 * Background LED wall decorator that renders behind the stage.
 * Supports two modes:
 * - Text mode: DJ name with configurable font styles and reactive colors
 * - Image mode: PNG logo converted to a pixel grid of colored TextDisplay entities
 *
 * <p>Ported from Paper: EntityUpdate -> DecoratorUpdate, Transformation -> scale,
 * Display.Billboard -> DisplayEntity.BillboardMode, Color -> packed ARGB int.
 */
public class DJBannerDecorator extends StageDecorator {

    private static final String[] BAND_COLORS = {
        "\u00A7c", "\u00A76", "\u00A7e", "\u00A7a", "\u00A7b"
    };

    private static final Map<Character, String[]> BLOCK_FONT = buildBlockFont();

    private BannerConfig bannerConfig = BannerConfig.empty();
    private String currentDJName = "";
    private String currentMode = "text";
    private int currentEntityCount = 0;
    private float currentPulseScale = 1.0f;
    private int beatDecayTicks = 0;
    private boolean pixelColorsApplied = false;

    public DJBannerDecorator(Stage stage, AudioVizMod mod) {
        super("banner", "DJ Banner", stage, mod);
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
        if (djInfo.isPresent() && !djInfo.djName().equals(currentDJName)) {
            currentDJName = djInfo.djName();
            if (bannerConfig.isTextMode()) {
                updateTextDisplay(audio);
            }
        }

        BannerConfig managerConfig = mod.getStageDecoratorManager().getCurrentBannerConfig();
        if (managerConfig != null && managerConfig != bannerConfig) {
            onBannerConfigChanged(managerConfig);
        }

        if (bannerConfig.isTextMode()) {
            tickTextMode(audio);
        } else if (bannerConfig.isImageMode()) {
            tickImageMode(audio);
        }
    }

    public void onBannerConfigChanged(BannerConfig newConfig) {
        boolean modeChanged = !newConfig.getMode().equals(bannerConfig.getMode());
        boolean gridChanged = newConfig.isImageMode() && (
            newConfig.getGridWidth() != bannerConfig.getGridWidth() ||
            newConfig.getGridHeight() != bannerConfig.getGridHeight()
        );

        this.bannerConfig = newConfig;
        this.pixelColorsApplied = false;

        if (modeChanged || gridChanged) {
            cleanupDecoratorZone();
            currentEntityCount = 0;
            initBannerZone();
            initEntitiesForMode();
        }

        if (newConfig.isTextMode()) {
            currentDJName = "";
        }
    }

    // ========== Zone & Pool Setup ==========

    private void initBannerZone() {
        double depthOffset = config.getDouble("depth_offset", -8.0);
        double heightOffset = config.getDouble("height_offset", 2.0);
        int wallWidth = config.getInt("wall_width", 20);
        int wallHeight = config.getInt("wall_height", 10);

        Vec3d origin = getStageRelativePosition(0, heightOffset, depthOffset);
        createDecoratorZone(origin, new Vector3f(wallWidth, wallHeight, 1));
    }

    private void initEntitiesForMode() {
        if (bannerConfig.isImageMode()) {
            int count = bannerConfig.getGridWidth() * bannerConfig.getGridHeight();
            initTextPool(count, DisplayEntity.BillboardMode.FIXED);
            currentEntityCount = count;
            currentMode = "image";
        } else {
            initTextPool(3, DisplayEntity.BillboardMode.FIXED);
            currentEntityCount = 3;
            currentMode = "text";
        }
    }

    // ========== Text Mode ==========

    private void tickTextMode(AudioState audio) {
        String zoneName = getDecoratorZoneName();

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

        int brightness;
        if (config.getBoolean("brightness_react", true)) {
            brightness = (int) lerp(6, 15, audio.getAmplitude());
        } else {
            brightness = 15;
        }

        boolean glow = config.getBoolean("glow_on_beat", true) && (audio.isBeat() || beatDecayTicks > 0);
        if ("neon".equals(bannerConfig.getTextStyle())) {
            glow = true;
        }

        float textScale = 3.0f * currentPulseScale;

        List<DecoratorUpdate> updates = new ArrayList<>();
        updates.add(DecoratorUpdate.builder("text_0")
            .scale(new Vector3f(textScale, textScale, textScale))
            .brightness(brightness)
            .glow(glow)
            .interpolationDuration(2)
            .build());

        if ("block".equals(bannerConfig.getTextStyle()) && currentEntityCount >= 3) {
            for (int i = 1; i < 3; i++) {
                float lineScale = textScale * 0.8f;
                updates.add(DecoratorUpdate.builder("text_" + i)
                    .translation(new Vector3f(0, -1.5f * i, 0))
                    .scale(new Vector3f(lineScale, lineScale, lineScale))
                    .brightness(brightness)
                    .glow(glow)
                    .interpolationDuration(2)
                    .build());
            }
        }

        mod.getDecoratorEntityManager().batchUpdate(zoneName, updates);

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
        mod.getDecoratorEntityManager().updateTextContent(zoneName, "text_0", display);
        mod.getDecoratorEntityManager().updateTextContent(zoneName, "text_1", "");
        mod.getDecoratorEntityManager().updateTextContent(zoneName, "text_2", "");
    }

    private void updateNeonText(String zoneName, String text, String colorCode) {
        int colorIndex = (int) ((tickCount / 3) % BAND_COLORS.length);
        String neonColor = BAND_COLORS[colorIndex];
        String display = neonColor + "\u00A7l\u2726 " + text.toUpperCase() + " \u2726";
        mod.getDecoratorEntityManager().updateTextContent(zoneName, "text_0", display);
        String accent = neonColor + "\u2501".repeat(Math.min(text.length() + 4, 30));
        mod.getDecoratorEntityManager().updateTextContent(zoneName, "text_1", accent);
        mod.getDecoratorEntityManager().updateTextContent(zoneName, "text_2", "");
    }

    private void updateBlockLetters(String zoneName, String text, String colorCode) {
        String upper = text.toUpperCase();
        String[] lines = renderBlockText(upper);

        StringBuilder top = new StringBuilder();
        StringBuilder mid = new StringBuilder();
        StringBuilder bot = new StringBuilder();

        for (int row = 0; row < lines.length; row++) {
            String line = colorCode + "\u00A7l" + lines[row];
            if (row < 2) top.append(line).append("\n");
            else if (row < 4) mid.append(line).append("\n");
            else bot.append(line).append("\n");
        }

        mod.getDecoratorEntityManager().updateTextContent(zoneName, "text_0", top.toString().trim());
        mod.getDecoratorEntityManager().updateTextContent(zoneName, "text_1", mid.toString().trim());
        mod.getDecoratorEntityManager().updateTextContent(zoneName, "text_2", bot.toString().trim());
    }

    private String getColorCode(AudioState audio) {
        String mode = bannerConfig.getTextColorMode();
        return switch (mode) {
            case "rainbow" -> {
                int index = (int) ((tickCount / 5) % BAND_COLORS.length);
                yield BAND_COLORS[index];
            }
            case "fixed" -> "\u00A7" + bannerConfig.getTextFixedColor();
            default -> {
                int dominant = getDominantBand(audio);
                yield BAND_COLORS[dominant];
            }
        };
    }

    // ========== Image Mode ==========

    private void tickImageMode(AudioState audio) {
        String zoneName = getDecoratorZoneName();
        int gridW = bannerConfig.getGridWidth();
        int gridH = bannerConfig.getGridHeight();

        if (!pixelColorsApplied && bannerConfig.hasImagePixels()) {
            applyPixelColors(zoneName, gridW, gridH);
            positionPixelGrid(zoneName, gridW, gridH);
            pixelColorsApplied = true;
        }

        float pulseIntensity = config.getFloat("pulse_intensity", 0.3f);
        boolean glowOnBeat = config.getBoolean("glow_on_beat", true);
        boolean brightnessReact = config.getBoolean("brightness_react", true);

        if (audio.isBeat()) {
            beatDecayTicks = 4;
        } else if (beatDecayTicks > 0) {
            beatDecayTicks--;
        }

        boolean glow = glowOnBeat && (audio.isBeat() || beatDecayTicks > 0);

        double[] bands = audio.getBands();
        List<DecoratorUpdate> updates = new ArrayList<>(gridW * gridH);

        for (int row = 0; row < gridH; row++) {
            for (int col = 0; col < gridW; col++) {
                int idx = row * gridW + col;
                String entityId = "text_" + idx;

                int bandIdx = Math.min(4, (col * 5) / gridW);
                double bandVal = bands[bandIdx];

                int brightness;
                if (brightnessReact) {
                    brightness = (int) lerp(5, 15, 0.3 + bandVal * 0.7);
                } else {
                    brightness = 15;
                }

                float scale = 0.4f;
                if (beatDecayTicks > 0) {
                    scale += (float) (pulseIntensity * 0.1 * (beatDecayTicks / 4.0));
                }

                updates.add(DecoratorUpdate.builder(entityId)
                    .scale(new Vector3f(scale, scale, scale))
                    .brightness(brightness)
                    .glow(glow && bandVal > 0.5)
                    .interpolationDuration(2)
                    .build());
            }
        }

        mod.getDecoratorEntityManager().batchUpdate(zoneName, updates);
    }

    private void applyPixelColors(String zoneName, int gridW, int gridH) {
        int[] pixels = bannerConfig.getImagePixels();
        if (pixels == null) return;

        Map<String, Integer> colorMap = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(pixels.length, gridW * gridH); i++) {
            colorMap.put("text_" + i, pixels[i]);
        }

        mod.getDecoratorEntityManager().batchUpdateBackgrounds(zoneName, colorMap);
    }

    private void positionPixelGrid(String zoneName, int gridW, int gridH) {
        int wallWidth = config.getInt("wall_width", 20);
        int wallHeight = config.getInt("wall_height", 10);

        float spacingX = (float) wallWidth / gridW;
        float spacingY = (float) wallHeight / gridH;
        float startX = -wallWidth / 2.0f;
        float startY = wallHeight;

        List<DecoratorUpdate> positions = new ArrayList<>();
        for (int row = 0; row < gridH; row++) {
            for (int col = 0; col < gridW; col++) {
                int idx = row * gridW + col;
                String entityId = "text_" + idx;

                float x = startX + col * spacingX + spacingX / 2;
                float y = startY - row * spacingY - spacingY / 2;

                Vec3d pos = getStageRelativePosition(
                    x,
                    config.getDouble("height_offset", 2.0) + y,
                    config.getDouble("depth_offset", -8.0)
                );

                positions.add(DecoratorUpdate.builder(entityId)
                    .position(pos)
                    .build());
            }
        }

        mod.getDecoratorEntityManager().batchUpdate(zoneName, positions);
    }

    // ========== Block Font ==========

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
