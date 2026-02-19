package com.audioviz.decorators.impl;

import com.audioviz.AudioVizPlugin;
import com.audioviz.decorators.DJInfo;
import com.audioviz.decorators.DecoratorConfig;
import com.audioviz.decorators.StageDecorator;
import com.audioviz.decorators.StageDecoratorManager;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * Dramatic transition effect when the active DJ switches.
 * Sequence: Blackout -> Flash -> Reveal with DJ name text.
 * Triggered externally by StageDecoratorManager, not by the normal tick loop.
 */
public class DJTransitionDecorator extends StageDecorator {

    private boolean transitionActive = false;

    // Store pre-transition brightness per zone for restoration
    private final Map<String, Integer> preBrightness = new HashMap<>();

    public DJTransitionDecorator(Stage stage, AudioVizPlugin plugin) {
        super("transition", "DJ Transitions", stage, plugin);
    }

    @Override
    public DecoratorConfig getDefaultConfig() {
        DecoratorConfig config = new DecoratorConfig();
        config.set("blackout_ticks", 20);
        config.set("flash_ticks", 5);
        config.set("reveal_ticks", 15);
        config.set("particle_count", 100);
        return config;
    }

    @Override
    public void onActivate() {
        // No persistent entities — transition text is spawned on-demand
    }

    @Override
    public void onDeactivate() {
        if (transitionActive) {
            cleanupDecoratorZone();
            restoreBrightness();
            setAllZoneGlow(false);
        }
        transitionActive = false;
        preBrightness.clear();
    }

    @Override
    public void tick(AudioState audio, DJInfo djInfo) {
        // This decorator doesn't use the normal tick loop.
        // It's triggered externally via triggerTransition().
    }

    /**
     * Trigger the transition sequence. Called by StageDecoratorManager
     * when a DJ change is detected.
     */
    public void triggerTransition(DJInfo oldDJ, DJInfo newDJ, StageDecoratorManager manager) {
        if (transitionActive) return; // Don't overlap transitions
        transitionActive = true;

        int blackoutTicks = config.getInt("blackout_ticks", 20);
        int flashTicks = config.getInt("flash_ticks", 5);
        int revealTicks = config.getInt("reveal_ticks", 15);
        int particleCount = config.getInt("particle_count", 100);

        // Suppress other decorators during transition
        manager.setTransitionInProgress(true);

        // Create transition text zone + entity
        Location textOrigin = getStageRelativeLocation(0, 6, 0);
        createDecoratorZone(textOrigin, new Vector(1, 1, 1));
        initTextPool(1);

        String textZone = getDecoratorZoneName();

        // Phase 1: BLACKOUT
        plugin.getEntityPoolManager().updateTextContent(textZone, "text_0",
            "\u00A7c\u00A7l\u2726 DJ SWITCHING... \u2726");
        setAllZoneBrightness(0);
        setScaleForText(textZone, 0.5f);

        // Phase 2: FLASH (after blackout)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!transitionActive) return;

            setAllZoneBrightness(15);
            setAllZoneGlow(true);

            // Update text to new DJ name
            String djName = newDJ.djName().isEmpty() ? "New DJ" : newDJ.djName();
            plugin.getEntityPoolManager().updateTextContent(textZone, "text_0",
                "\u00A76\u00A7l\u2605 " + djName + " \u2605");
            setScaleForText(textZone, 2.0f);

            // Particle burst at stage center
            Location center = getStageRelativeLocation(0, 3, 0);
            if (center.getWorld() != null) {
                center.getWorld().spawnParticle(Particle.FIREWORK, center,
                    particleCount, 3, 2, 3, 0.1);
                center.getWorld().spawnParticle(Particle.END_ROD, center,
                    particleCount / 2, 2, 3, 2, 0.05);
            }
        }, blackoutTicks);

        // Phase 3: REVEAL (brightness ramp back, text fades)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!transitionActive) return;

            // Ramp brightness back with several steps
            int steps = 5;
            int ticksPerStep = revealTicks / steps;
            for (int step = 0; step < steps; step++) {
                final int brightness = (int) (15.0 * (steps - step) / steps);
                final int s = step;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!transitionActive) return;
                    if (s < steps - 1) {
                        setAllZoneBrightness(brightness);
                    }
                    // Scale text down progressively
                    float textScale = 2.0f * (1.0f - (float) s / steps);
                    setScaleForText(textZone, Math.max(0.1f, textScale));
                }, (long) step * ticksPerStep);
            }
        }, blackoutTicks + flashTicks);

        // Phase 4: CLEANUP
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Restore normal state
            setAllZoneGlow(false);
            restoreBrightness();

            // Cleanup transition text zone
            cleanupDecoratorZone();

            transitionActive = false;
            manager.setTransitionInProgress(false);

            plugin.getLogger().info("DJ transition complete: " + newDJ.djName());
        }, blackoutTicks + flashTicks + revealTicks);
    }

    /**
     * Set brightness for all pattern entity pools in this stage.
     */
    private void setAllZoneBrightness(int brightness) {
        for (String zoneName : stage.getZoneNames()) {
            // Store pre-transition brightness on first call
            if (!preBrightness.containsKey(zoneName)) {
                preBrightness.put(zoneName, 15); // Default to full brightness
            }
            plugin.getEntityPoolManager().setZoneBrightness(zoneName, brightness);
        }
    }

    /**
     * Set glow for all pattern entity pools in this stage.
     */
    private void setAllZoneGlow(boolean glow) {
        for (String zoneName : stage.getZoneNames()) {
            plugin.getEntityPoolManager().setZoneGlow(zoneName, glow);
        }
    }

    /**
     * Restore pre-transition brightness.
     */
    private void restoreBrightness() {
        for (Map.Entry<String, Integer> entry : preBrightness.entrySet()) {
            plugin.getEntityPoolManager().setZoneBrightness(entry.getKey(), entry.getValue());
        }
        preBrightness.clear();
    }

    /**
     * Set scale for the transition text entity.
     */
    private void setScaleForText(String zoneName, float scale) {
        Transformation transform = new Transformation(
            new Vector3f(0, 0, 0),
            new AxisAngle4f(0, 0, 0, 1),
            new Vector3f(scale, scale, scale),
            new AxisAngle4f(0, 0, 0, 1)
        );

        com.audioviz.entities.EntityUpdate update = com.audioviz.entities.EntityUpdate.builder("text_0")
            .transformation(transform)
            .interpolationDuration(3)
            .build();

        plugin.getEntityPoolManager().batchUpdateEntities(zoneName, List.of(update));
    }
}
