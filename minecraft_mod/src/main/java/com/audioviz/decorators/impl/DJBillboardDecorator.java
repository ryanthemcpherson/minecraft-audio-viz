package com.audioviz.decorators.impl;

import com.audioviz.AudioVizMod;
import com.audioviz.decorators.*;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.List;

/**
 * Floating TextDisplay above the main stage showing the active DJ name.
 * Pulses scale on beat, changes color with dominant frequency band.
 *
 * <p>Ported from Paper: Location -> Vec3d, EntityUpdate -> DecoratorUpdate,
 * Transformation -> direct scale/brightness, ChatColor -> section signs.
 */
public class DJBillboardDecorator extends StageDecorator {

    private static final String[] BAND_COLORS = {
        "\u00A7c", "\u00A76", "\u00A7e", "\u00A7a", "\u00A7b"
    };

    private String currentDJName = "";
    private float currentScale = 1.0f;
    private int beatDecayTicks = 0;

    public DJBillboardDecorator(Stage stage, AudioVizMod mod) {
        super("billboard", "DJ Billboard", stage, mod);
    }

    @Override
    public DecoratorConfig getDefaultConfig() {
        DecoratorConfig config = new DecoratorConfig();
        config.set("text_format", "\u25C6 %s \u25C6");
        config.set("height_offset", 5.0);
        config.set("pulse_scale", 1.3);
        config.set("color_mode", "frequency");
        return config;
    }

    @Override
    public void onActivate() {
        double heightOffset = config.getDouble("height_offset", 5.0);
        Vec3d origin = getStageRelativePosition(0, heightOffset, 0);
        createDecoratorZone(origin, new Vector3f(1, 1, 1));
        initTextPool(1);
    }

    @Override
    public void onDeactivate() {
        cleanupDecoratorZone();
    }

    @Override
    public void tick(AudioState audio, DJInfo djInfo) {
        String zoneName = getDecoratorZoneName();

        if (djInfo.isPresent() && !djInfo.djName().equals(currentDJName)) {
            currentDJName = djInfo.djName();
            updateDisplayText(zoneName, audio);
        }

        if (audio.isBeat()) {
            currentScale = config.getFloat("pulse_scale", 1.3f);
            beatDecayTicks = 4;
        } else if (beatDecayTicks > 0) {
            beatDecayTicks--;
            currentScale = (float) lerp(1.0, config.getFloat("pulse_scale", 1.3f),
                beatDecayTicks / 4.0);
        } else {
            currentScale = 1.0f;
        }

        int brightness = (int) lerp(8, 15, audio.getAmplitude());

        DecoratorUpdate update = DecoratorUpdate.builder("text_0")
            .scale(new Vector3f(currentScale, currentScale, currentScale))
            .brightness(brightness)
            .glow(audio.isBeat() || beatDecayTicks > 0)
            .interpolationDuration(2)
            .build();

        mod.getDecoratorEntityManager().batchUpdate(zoneName, List.of(update));

        if (tickCount % 5 == 0 && currentDJName != null && !currentDJName.isEmpty()) {
            updateDisplayText(zoneName, audio);
        }
    }

    private void updateDisplayText(String zoneName, AudioState audio) {
        String format = config.getString("text_format", "\u25C6 %s \u25C6");
        String colorMode = config.getString("color_mode", "frequency");
        String name = currentDJName.isEmpty() ? "No DJ" : currentDJName;

        String colorCode;
        if ("frequency".equals(colorMode)) {
            int dominant = getDominantBand(audio);
            colorCode = BAND_COLORS[dominant];
        } else if ("rainbow".equals(colorMode)) {
            int index = (int) ((tickCount / 5) % BAND_COLORS.length);
            colorCode = BAND_COLORS[index];
        } else {
            colorCode = "\u00A7f";
        }

        String text = colorCode + "\u00A7l" + String.format(format, name);
        mod.getDecoratorEntityManager().updateTextContent(zoneName, "text_0", text);
    }
}
