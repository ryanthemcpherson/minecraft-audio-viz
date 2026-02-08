package com.audioviz.decorators.impl;

import com.audioviz.AudioVizPlugin;
import com.audioviz.decorators.DJInfo;
import com.audioviz.decorators.DecoratorConfig;
import com.audioviz.decorators.StageDecorator;
import com.audioviz.entities.EntityUpdate;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Floating TextDisplay above the main stage showing the active DJ name.
 * Pulses scale on beat, changes color with dominant frequency band.
 */
public class DJBillboardDecorator extends StageDecorator {

    private static final String[] BAND_COLORS = {
        "\u00A7c", // Bass - Red
        "\u00A76", // Low-mid - Gold
        "\u00A7e", // Mid - Yellow
        "\u00A7a", // High-mid - Green
        "\u00A7b"  // High - Aqua
    };

    private String currentDJName = "";
    private float currentScale = 1.0f;
    private int beatDecayTicks = 0;

    public DJBillboardDecorator(Stage stage, AudioVizPlugin plugin) {
        super("billboard", "DJ Billboard", stage, plugin);
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
        Location origin = getStageRelativeLocation(0, heightOffset, 0);
        createDecoratorZone(origin, new Vector(1, 1, 1));
        initTextPool(1);
    }

    @Override
    public void onDeactivate() {
        cleanupDecoratorZone();
    }

    @Override
    public void tick(AudioState audio, DJInfo djInfo) {
        String zoneName = getDecoratorZoneName();

        // Update text content if DJ changed
        if (djInfo.isPresent() && !djInfo.djName().equals(currentDJName)) {
            currentDJName = djInfo.djName();
            updateDisplayText(zoneName, audio);
        }

        // Beat pulse
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

        // Update transformation (scale) and brightness
        int brightness = (int) lerp(8, 15, audio.getAmplitude());

        Transformation transform = new Transformation(
            new Vector3f(0, 0, 0),
            new AxisAngle4f(0, 0, 0, 1),
            new Vector3f(currentScale, currentScale, currentScale),
            new AxisAngle4f(0, 0, 0, 1)
        );

        EntityUpdate update = EntityUpdate.builder("text_0")
            .transformation(transform)
            .brightness(brightness)
            .glow(audio.isBeat() || beatDecayTicks > 0)
            .interpolationDuration(2)
            .build();

        plugin.getEntityPoolManager().batchUpdateEntities(zoneName, List.of(update));

        // Update text color periodically (every 5 ticks)
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
            colorCode = "\u00A7f"; // White for "fixed"
        }

        String text = colorCode + "\u00A7l" + String.format(format, name);
        plugin.getEntityPoolManager().updateTextContent(zoneName, "text_0", text);
    }
}
