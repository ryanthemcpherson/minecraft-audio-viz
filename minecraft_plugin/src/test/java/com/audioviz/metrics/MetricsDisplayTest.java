package com.audioviz.metrics;

import com.audioviz.AudioVizPlugin;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.zones.VisualizationZone;
import com.audioviz.zones.ZoneManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetricsDisplayTest {

    @Test
    @DisplayName("entity count uses the canonical per-zone limit")
    void entityCountUsesCanonicalPerZoneLimit() {
        AudioVizPlugin plugin = mock(AudioVizPlugin.class);
        EntityPoolManager entityPoolManager = mock(EntityPoolManager.class);
        ZoneManager zoneManager = mock(ZoneManager.class);
        VisualizationZone zone = mock(VisualizationZone.class);
        FileConfiguration config = mock(FileConfiguration.class);

        when(plugin.getEntityPoolManager()).thenReturn(entityPoolManager);
        when(plugin.getZoneManager()).thenReturn(zoneManager);
        when(plugin.getConfig()).thenReturn(config);
        when(zoneManager.getAllZones()).thenReturn(List.of(zone));
        when(zone.getName()).thenReturn("main");
        when(entityPoolManager.getEntityCount("main")).thenReturn(12);
        when(config.getInt("performance.max_entities_per_zone", 256)).thenReturn(256);

        assertEquals("12/256", new MetricsDisplay(plugin).collectEntityCount());
        verify(config).getInt("performance.max_entities_per_zone", 256);
    }

    @Nested
    @DisplayName("Metric Formatting")
    class Formatting {

        @Test
        @DisplayName("formatRenderTime rounds to 1 decimal")
        void formatRenderTime() {
            assertEquals("4.2ms", MetricsDisplay.formatRenderTime(4.23456));
        }

        @Test
        @DisplayName("formatRenderTime handles zero")
        void formatRenderTimeZero() {
            assertEquals("0.0ms", MetricsDisplay.formatRenderTime(0.0));
        }

        @Test
        @DisplayName("formatEntityCount shows used/total")
        void formatEntityCount() {
            assertEquals("312/500", MetricsDisplay.formatEntityCount(312, 500));
        }

        @Test
        @DisplayName("formatBpm shows integer when confident")
        void formatBpmConfident() {
            assertEquals("128 BPM", MetricsDisplay.formatBpm(128.4, 0.8));
        }

        @Test
        @DisplayName("formatBpm shows dash when not confident")
        void formatBpmNotConfident() {
            assertEquals("-- BPM", MetricsDisplay.formatBpm(128.4, 0.3));
        }

        @Test
        @DisplayName("formatDjStatus connected")
        void formatDjConnected() {
            assertEquals("Connected (128 BPM)",
                MetricsDisplay.formatDjStatus(true, false, 128.0, 0.9));
        }

        @Test
        @DisplayName("formatDjStatus disconnected")
        void formatDjDisconnected() {
            assertEquals("Disconnected",
                MetricsDisplay.formatDjStatus(false, false, 0, 0));
        }

        @Test
        @DisplayName("formatDjStatus stale")
        void formatDjStale() {
            assertEquals("Signal Lost",
                MetricsDisplay.formatDjStatus(true, true, 128.0, 0.9));
        }
    }
}
