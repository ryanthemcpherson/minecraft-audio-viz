package com.audioviz.connection;

import com.audioviz.AudioVizPlugin;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.stages.StageManager;
import com.audioviz.zones.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionStateListenerTest {

    @Test
    void disconnectCleanupRemovesEntitiesWithoutDeletingSavedState() {
        AudioVizPlugin plugin = mock(AudioVizPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        EntityPoolManager entityPoolManager = mock(EntityPoolManager.class);
        ZoneManager zoneManager = mock(ZoneManager.class);
        StageManager stageManager = mock(StageManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask tickTask = mock(BukkitTask.class);
        BukkitTask cleanupTask = mock(BukkitTask.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(getClass().getName()));
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getEntityPoolManager()).thenReturn(entityPoolManager);
        when(plugin.getZoneManager()).thenReturn(zoneManager);
        when(plugin.getStageManager()).thenReturn(stageManager);
        when(config.getInt("connection.disconnect_grace_ticks", 100)).thenReturn(100);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(10L)))
            .thenReturn(tickTask);
        ArgumentCaptor<Runnable> cleanup = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.runTaskLater(eq(plugin), cleanup.capture(), eq(100L)))
            .thenReturn(cleanupTask);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            ConnectionStateListener listener = new ConnectionStateListener(plugin);

            listener.start();
            listener.onDjDisconnect("remote close");
            cleanup.getValue().run();

            verify(entityPoolManager).cleanupAllSync();
            verify(zoneManager, never()).saveZones();
            verify(stageManager, never()).saveStages();
            listener.stop();
        }
    }

    @Test
    void disconnectGraceIsClampedToReleaseBounds() {
        assertEquals(0, ConnectionStateListener.clampDisconnectGraceTicks(-1));
        assertEquals(0, ConnectionStateListener.clampDisconnectGraceTicks(0));
        assertEquals(100, ConnectionStateListener.clampDisconnectGraceTicks(100));
        assertEquals(1200, ConnectionStateListener.clampDisconnectGraceTicks(1200));
        assertEquals(1200, ConnectionStateListener.clampDisconnectGraceTicks(1201));
    }

    @Nested
    @DisplayName("Staleness Detection")
    class StalenessDetection {

        @Test
        @DisplayName("not stale when frame received recently")
        void notStaleWhenRecent() {
            long now = System.currentTimeMillis();
            assertFalse(ConnectionStateListener.isStale(now - 1000, now, 3000));
        }

        @Test
        @DisplayName("stale when no frame for longer than threshold")
        void staleAfterThreshold() {
            long now = System.currentTimeMillis();
            assertTrue(ConnectionStateListener.isStale(now - 4000, now, 3000));
        }

        @Test
        @DisplayName("not stale at exact threshold boundary")
        void notStaleAtExactBoundary() {
            long now = System.currentTimeMillis();
            assertFalse(ConnectionStateListener.isStale(now - 3000, now, 3000));
        }

        @Test
        @DisplayName("stale when lastFrameMs is 0 (never received)")
        void staleWhenNeverReceived() {
            long now = System.currentTimeMillis();
            assertTrue(ConnectionStateListener.isStale(0, now, 3000));
        }
    }

    @Nested
    @DisplayName("Brightness Ramp")
    class BrightnessRamp {

        @Test
        @DisplayName("ramp computes intermediate values")
        void rampIntermediate() {
            double result = ConnectionStateListener.computeRampedBrightness(
                1.0, 0.3, 10, 5);
            assertEquals(0.65, result, 0.001);
        }

        @Test
        @DisplayName("ramp at start returns current")
        void rampAtStart() {
            double result = ConnectionStateListener.computeRampedBrightness(
                1.0, 0.3, 20, 0);
            assertEquals(1.0, result, 0.001);
        }

        @Test
        @DisplayName("ramp at end returns target")
        void rampAtEnd() {
            double result = ConnectionStateListener.computeRampedBrightness(
                1.0, 0.3, 20, 20);
            assertEquals(0.3, result, 0.001);
        }

        @Test
        @DisplayName("ramp past end clamps to target")
        void rampPastEnd() {
            double result = ConnectionStateListener.computeRampedBrightness(
                1.0, 0.3, 20, 25);
            assertEquals(0.3, result, 0.001);
        }
    }
}
