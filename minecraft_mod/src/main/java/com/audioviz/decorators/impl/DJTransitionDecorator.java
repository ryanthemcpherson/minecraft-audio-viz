package com.audioviz.decorators.impl;

import com.audioviz.AudioVizMod;
import com.audioviz.decorators.*;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Dramatic transition effect when the active DJ switches.
 * Sequence: Blackout -> Flash -> Reveal with DJ name text.
 *
 * <p>Ported from Paper: Bukkit scheduler -> tick-based state machine,
 * Bukkit Particle -> MC ParticleTypes, Location -> Vec3d.
 */
public class DJTransitionDecorator extends StageDecorator {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");

    private enum Phase { IDLE, BLACKOUT, FLASH, REVEAL, CLEANUP }

    private Phase phase = Phase.IDLE;
    private long phaseStartTick = 0;
    private int blackoutTicks;
    private int flashTicks;
    private int revealTicks;
    private int particleCount;
    private DJInfo pendingNewDJ;

    private final Map<String, Integer> preBrightness = new HashMap<>();

    public DJTransitionDecorator(Stage stage, AudioVizMod mod) {
        super("transition", "DJ Transitions", stage, mod);
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
        if (phase != Phase.IDLE) {
            cleanupDecoratorZone();
            restoreBrightness();
            setAllZoneGlow(false);
            mod.getStageDecoratorManager().setTransitionInProgress(false);
        }
        phase = Phase.IDLE;
        preBrightness.clear();
    }

    @Override
    public void tick(AudioState audio, DJInfo djInfo) {
        if (phase == Phase.IDLE) return;

        long elapsed = tickCount - phaseStartTick;

        switch (phase) {
            case BLACKOUT:
                if (elapsed >= blackoutTicks) {
                    phase = Phase.FLASH;
                    phaseStartTick = tickCount;
                    doFlash();
                }
                break;

            case FLASH:
                if (elapsed >= flashTicks) {
                    phase = Phase.REVEAL;
                    phaseStartTick = tickCount;
                }
                break;

            case REVEAL:
                updateReveal(elapsed);
                if (elapsed >= revealTicks) {
                    phase = Phase.CLEANUP;
                    doCleanup();
                }
                break;

            case CLEANUP:
                phase = Phase.IDLE;
                break;

            default:
                break;
        }
    }

    /**
     * Trigger the transition sequence. Called by StageDecoratorManager
     * when a DJ change is detected.
     */
    public void triggerTransition(DJInfo oldDJ, DJInfo newDJ, StageDecoratorManager manager) {
        if (phase != Phase.IDLE) return;

        blackoutTicks = config.getInt("blackout_ticks", 20);
        flashTicks = config.getInt("flash_ticks", 5);
        revealTicks = config.getInt("reveal_ticks", 15);
        particleCount = config.getInt("particle_count", 100);
        pendingNewDJ = newDJ;

        manager.setTransitionInProgress(true);

        // Create transition text zone + entity
        Vec3d textOrigin = getStageRelativePosition(0, 6, 0);
        createDecoratorZone(textOrigin, new Vector3f(1, 1, 1));
        initTextPool(1);

        String textZone = getDecoratorZoneName();

        // Phase 1: BLACKOUT
        mod.getDecoratorEntityManager().updateTextContent(textZone, "text_0",
            "\u00A7c\u00A7l\u2726 DJ SWITCHING... \u2726");
        setAllZoneBrightness(0);

        mod.getDecoratorEntityManager().batchUpdate(textZone, List.of(
            DecoratorUpdate.builder("text_0")
                .scale(new Vector3f(0.5f, 0.5f, 0.5f))
                .brightness(15)
                .interpolationDuration(3)
                .build()
        ));

        phase = Phase.BLACKOUT;
        phaseStartTick = tickCount;
    }

    private void doFlash() {
        setAllZoneBrightness(15);
        setAllZoneGlow(true);

        String textZone = getDecoratorZoneName();
        String djName = pendingNewDJ != null && !pendingNewDJ.djName().isEmpty()
            ? pendingNewDJ.djName() : "New DJ";

        mod.getDecoratorEntityManager().updateTextContent(textZone, "text_0",
            "\u00A76\u00A7l\u2605 " + djName + " \u2605");

        mod.getDecoratorEntityManager().batchUpdate(textZone, List.of(
            DecoratorUpdate.builder("text_0")
                .scale(new Vector3f(2.0f, 2.0f, 2.0f))
                .brightness(15)
                .glow(true)
                .interpolationDuration(3)
                .build()
        ));

        // Particle burst
        Vec3d center = getStageRelativePosition(0, 3, 0);
        ServerWorld world = getStageWorld();
        if (world != null) {
            world.spawnParticles(ParticleTypes.FIREWORK,
                center.x, center.y, center.z, particleCount, 3, 2, 3, 0.1);
            world.spawnParticles(ParticleTypes.END_ROD,
                center.x, center.y, center.z, particleCount / 2, 2, 3, 2, 0.05);
        }
    }

    private void updateReveal(long elapsed) {
        int steps = 5;
        int ticksPerStep = Math.max(1, revealTicks / steps);
        int currentStep = (int) (elapsed / ticksPerStep);
        if (currentStep >= steps) currentStep = steps - 1;

        int brightness = (int) (15.0 * (steps - currentStep) / steps);
        setAllZoneBrightness(brightness);

        float textScale = 2.0f * (1.0f - (float) currentStep / steps);
        textScale = Math.max(0.1f, textScale);

        String textZone = getDecoratorZoneName();
        mod.getDecoratorEntityManager().batchUpdate(textZone, List.of(
            DecoratorUpdate.builder("text_0")
                .scale(new Vector3f(textScale, textScale, textScale))
                .interpolationDuration(3)
                .build()
        ));
    }

    private void doCleanup() {
        setAllZoneGlow(false);
        restoreBrightness();
        cleanupDecoratorZone();
        mod.getStageDecoratorManager().setTransitionInProgress(false);
        LOGGER.info("DJ transition complete: {}",
            pendingNewDJ != null ? pendingNewDJ.djName() : "unknown");
    }

    private void setAllZoneBrightness(int brightness) {
        for (String zoneName : stage.getZoneNames()) {
            if (!preBrightness.containsKey(zoneName)) {
                preBrightness.put(zoneName, 15);
            }
            mod.getDecoratorEntityManager().setZoneBrightness(zoneName, brightness);
        }
    }

    private void setAllZoneGlow(boolean glow) {
        for (String zoneName : stage.getZoneNames()) {
            mod.getDecoratorEntityManager().setZoneGlow(zoneName, glow);
        }
    }

    private void restoreBrightness() {
        for (Map.Entry<String, Integer> entry : preBrightness.entrySet()) {
            mod.getDecoratorEntityManager().setZoneBrightness(entry.getKey(), entry.getValue());
        }
        preBrightness.clear();
    }
}
