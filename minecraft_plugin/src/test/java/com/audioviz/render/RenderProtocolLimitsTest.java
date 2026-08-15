package com.audioviz.render;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RenderProtocolLimitsTest {

    @Test
    void unitDomainsAndResourceLimitsComeFromOneAuthority() {
        FileConfiguration config = new YamlConfiguration();
        config.set("performance.max_entities_per_zone", 256);
        config.set("performance.max_zones", 32);
        config.set("performance.max_particles_per_tick", 2_000);
        config.set("performance.render_decoder_threads", 2);

        RenderProtocolLimits limits = RenderProtocolLimits.from(config);

        assertEquals(5, RenderProtocolLimits.BAND_COUNT);
        assertEquals(4, limits.snapshotSlotCount());
        assertEquals(256, limits.maxEntitiesPerZone());
        assertEquals(32, limits.maxZones());
        assertEquals(2_000, limits.maxParticlesPerTick());
    }

    @Test
    void resourceLimitsMustBePositive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RenderProtocolLimits(0, 256, 2_000, 2));
    }
}
