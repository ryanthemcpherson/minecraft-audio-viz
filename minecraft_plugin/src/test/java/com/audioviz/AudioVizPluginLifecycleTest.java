package com.audioviz;

import com.audioviz.metrics.MetricsDisplay;
import com.audioviz.websocket.VizWebSocketServer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.logging.Logger;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class AudioVizPluginLifecycleTest {

    @Test
    @SuppressWarnings("unchecked")
    void disableStopsWebSocketStartupBeforeOtherSubsystemCleanup() throws Exception {
        AudioVizPlugin plugin = mock(AudioVizPlugin.class, CALLS_REAL_METHODS);
        WebSocketStartupManager<VizWebSocketServer> startupManager =
            mock(WebSocketStartupManager.class);
        MetricsDisplay metricsDisplay = mock(MetricsDisplay.class);
        doReturn(Logger.getLogger(getClass().getName())).when(plugin).getLogger();
        setField(plugin, "webSocketStartupManager", startupManager);
        setField(plugin, "metricsDisplay", metricsDisplay);

        plugin.onDisable();

        InOrder shutdownOrder = inOrder(startupManager, metricsDisplay);
        shutdownOrder.verify(startupManager).stop();
        shutdownOrder.verify(metricsDisplay).stop();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = AudioVizPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
