package com.audioviz;

import com.audioviz.metrics.MetricsDisplay;
import com.audioviz.sidecar.VjSidecarLaunchPlan;
import com.audioviz.sidecar.VjSidecarManager;
import com.audioviz.websocket.VizWebSocketServer;
import com.audioviz.websocket.WebSocketSecretManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AudioVizPluginLifecycleTest {

    @Test
    void persistedSecretStartsSidecarManager() throws Exception {
        AudioVizPlugin plugin = mock(AudioVizPlugin.class, CALLS_REAL_METHODS);
        VjSidecarLaunchPlan plan = mock(VjSidecarLaunchPlan.class);
        VjSidecarManager manager = mock(VjSidecarManager.class);
        WebSocketSecretManager.SecretResolution resolution =
            new WebSocketSecretManager.SecretResolution(
                "plugin-managed-secret-0123456789-abcdefgh",
                false
            );
        doReturn(Path.of("plugins", "AudioViz").toFile()).when(plugin).getDataFolder();
        doReturn(plan).when(plugin).createVjSidecarLaunchPlan(
            org.mockito.ArgumentMatchers.any(Path.class),
            anyString(),
            anyMap(),
            anyString()
        );
        doReturn(manager).when(plugin).createVjSidecarManager(plan);

        plugin.startVjSidecar(resolution, Map.of("MCAV_PUBLIC_HOST", "8.8.8.8"), "amd64");

        verify(manager).start();
    }

    @Test
    void generatedSecretIsPersistedBeforeWebSocketStartup() {
        FileConfiguration config = mock(FileConfiguration.class);
        Logger logger = mock(Logger.class);
        AudioVizPlugin plugin = webSocketLifecyclePlugin(config, logger);
        when(config.getString("ws-secret", "")).thenReturn("");
        when(config.getString("websocket.address", "127.0.0.1")).thenReturn("127.0.0.1");
        when(config.getInt("websocket.port", 8765)).thenReturn(8765);

        WebSocketSecretManager.SecretResolution resolution = plugin.prepareWebSocketSecret();
        plugin.startWebSocketListener(resolution);

        assertNotNull(resolution);
        assertTrue(resolution.generated());
        ArgumentCaptor<Object> persistedSecret = ArgumentCaptor.forClass(Object.class);
        InOrder startupOrder = inOrder(config, plugin);
        startupOrder.verify(config).set(org.mockito.ArgumentMatchers.eq("ws-secret"), persistedSecret.capture());
        startupOrder.verify(plugin).saveConfig();
        startupOrder.verify(plugin).startWebSocketWithRetry("127.0.0.1", 8765, 5, 2000);
        assertTrue(persistedSecret.getValue().toString().matches("[A-Za-z0-9_-]{43}"));
    }

    @Test
    void stableExistingSecretIsNotRewritten() {
        FileConfiguration config = mock(FileConfiguration.class);
        Logger logger = mock(Logger.class);
        AudioVizPlugin plugin = webSocketLifecyclePlugin(config, logger);
        when(config.getString("ws-secret", "")).thenReturn("  stable-secret  ");
        when(config.getString("websocket.address", "127.0.0.1")).thenReturn("localhost");
        when(config.getInt("websocket.port", 8765)).thenReturn(9000);

        WebSocketSecretManager.SecretResolution resolution = plugin.prepareWebSocketSecret();
        plugin.startWebSocketListener(resolution);

        assertNotNull(resolution);
        assertFalse(resolution.generated());
        verify(config, never()).set(anyString(), org.mockito.ArgumentMatchers.any());
        verify(plugin, never()).saveConfig();
        verify(plugin).startWebSocketWithRetry("localhost", 9000, 5, 2000);
    }

    @Test
    void secretPersistenceFailureLeavesWebSocketOfflineWithoutThrowing() {
        FileConfiguration config = mock(FileConfiguration.class);
        Logger logger = mock(Logger.class);
        AudioVizPlugin plugin = webSocketLifecyclePlugin(config, logger);
        when(config.getString("ws-secret", "")).thenReturn("");
        doThrow(new IllegalStateException("do-not-log-this-value")).when(plugin).saveConfig();

        WebSocketSecretManager.SecretResolution resolution = assertDoesNotThrow(
            plugin::prepareWebSocketSecret
        );
        assertDoesNotThrow(() -> plugin.startWebSocketListener(resolution));

        assertNull(resolution);
        verify(plugin, never()).startWebSocketWithRetry(
            anyString(), anyInt(), anyInt(), anyLong()
        );
        verify(logger).severe(
            "Unable to persist the WebSocket pairing secret; the WebSocket listener will remain offline."
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void disableStopsWebSocketStartupBeforeOtherSubsystemCleanup() throws Exception {
        AudioVizPlugin plugin = mock(AudioVizPlugin.class, CALLS_REAL_METHODS);
        WebSocketStartupManager<VizWebSocketServer> startupManager =
            mock(WebSocketStartupManager.class);
        VjSidecarManager sidecarManager = mock(VjSidecarManager.class);
        MetricsDisplay metricsDisplay = mock(MetricsDisplay.class);
        doReturn(Logger.getLogger(getClass().getName())).when(plugin).getLogger();
        setField(plugin, "webSocketStartupManager", startupManager);
        setField(plugin, "vjSidecarManager", sidecarManager);
        setField(plugin, "metricsDisplay", metricsDisplay);

        plugin.onDisable();

        InOrder shutdownOrder = inOrder(sidecarManager, startupManager, metricsDisplay);
        shutdownOrder.verify(sidecarManager).stop();
        shutdownOrder.verify(startupManager).stop();
        shutdownOrder.verify(metricsDisplay).stop();
    }

    private static AudioVizPlugin webSocketLifecyclePlugin(
        FileConfiguration config,
        Logger logger
    ) {
        AudioVizPlugin plugin = mock(AudioVizPlugin.class, CALLS_REAL_METHODS);
        doReturn(config).when(plugin).getConfig();
        doReturn(logger).when(plugin).getLogger();
        doNothing().when(plugin).saveConfig();
        doNothing().when(plugin).startWebSocketWithRetry(
            anyString(), anyInt(), anyInt(), anyLong()
        );
        return plugin;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = AudioVizPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
