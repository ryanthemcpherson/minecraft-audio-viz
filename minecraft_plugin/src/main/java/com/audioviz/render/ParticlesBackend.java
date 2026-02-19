package com.audioviz.render;

import com.audioviz.entities.EntityUpdate;
import com.audioviz.particles.ParticleVisualizationManager;
import com.audioviz.patterns.AudioState;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Material;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Renderer backend that uses Minecraft particles for visualization.
 *
 * <p>Particles are compatible with Bedrock edition (via Geyser) where
 * Display Entities are not supported. This backend renders frequency
 * bands as particle columns that react to audio amplitude and beats.
 *
 * <p>Unlike display entities, particles are ephemeral — they don't have
 * persistent entity IDs. The backend translates entity updates into
 * particle spawn parameters and relies on the particle visualization
 * manager's render tick for actual spawning.
 *
 * <p>Wraps {@link ParticleVisualizationManager} to implement the
 * {@link RendererBackend} contract.
 */
public class ParticlesBackend implements RendererBackend {

    private final ParticleVisualizationManager particleVizManager;

    public ParticlesBackend(ParticleVisualizationManager particleVizManager) {
        this.particleVizManager = particleVizManager;
    }

    @Override
    public RendererBackendType getType() {
        return RendererBackendType.PARTICLES;
    }

    @Override
    public void initialize(VisualizationZone zone, int count, Material material) {
        // Particles don't need pre-allocated pools.
        // Ensure config exists for the zone.
        particleVizManager.getOrCreateConfig(zone.getName());
        particleVizManager.setRenderMode(zone.getName(), "particles");
    }

    @Override
    public void updateFrame(String zoneName, List<EntityUpdate> updates) {
        // Particle visualization is driven by audio state, not entity updates.
        // The render tick in ParticleVisualizationManager handles spawning.
        // Entity updates are ignored in pure particle mode.
    }

    @Override
    public void updateAudioState(AudioState audioState) {
        particleVizManager.updateAudioState(audioState);
    }

    @Override
    public void setVisible(String zoneName, boolean visible) {
        if (visible) {
            particleVizManager.setRenderMode(zoneName, "particles");
        } else {
            // Temporarily switch to entities mode (no particles) to hide
            particleVizManager.setRenderMode(zoneName, "entities");
        }
    }

    @Override
    public void teardown(String zoneName) {
        particleVizManager.removeZoneConfig(zoneName);
    }

    @Override
    public int getElementCount(String zoneName) {
        // Particles are ephemeral — no persistent element count
        return 0;
    }

    @Override
    public Set<String> getElementIds(String zoneName) {
        // Particles don't have persistent IDs
        return Collections.emptySet();
    }

    /**
     * Access the underlying particle visualization manager for
     * particle-specific configuration (density, color mode, etc.)
     */
    public ParticleVisualizationManager getParticleVizManager() {
        return particleVizManager;
    }
}
