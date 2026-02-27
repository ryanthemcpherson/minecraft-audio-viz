package com.audioviz.websocket;

import com.audioviz.AudioVizMod;
import com.audioviz.protocol.MessageHandler;
import com.audioviz.protocol.MessageQueue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket server for receiving visualization commands.
 * Ported from Paper plugin — removed Bukkit scheduler deps.
 * Heartbeat and metrics are driven by tick() calls from AudioVizMod.
 */
public class VizWebSocketServer extends WebSocketServer {

    private final MessageHandler messageHandler;
    private final MessageQueue messageQueue;
    private final Gson gson = new Gson();
    private final ConcurrentHashMap<WebSocket, ClientInfo> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WebSocket, Long> lastPongTime = new ConcurrentHashMap<>();

    private boolean asyncEnabled = true;

    // Connection metrics
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong totalDisconnections = new AtomicLong(0);
    private final AtomicLong totalMessagesSent = new AtomicLong(0);
    private final AtomicLong totalMessagesReceived = new AtomicLong(0);
    private final AtomicLong totalSendFailures = new AtomicLong(0);

    // Heartbeat constants
    private static final long PONG_TIMEOUT_MS = 45000L;
    private static final int HEARTBEAT_INTERVAL_TICKS = 300; // 15 seconds
    private static final int METRICS_LOG_INTERVAL_TICKS = 6000; // 5 minutes

    // Tick counters (driven by AudioVizMod.tick())
    private int heartbeatTickCounter = 0;
    private int metricsTickCounter = 0;

    private static final int MAX_MESSAGE_SIZE = 262_144;

    public VizWebSocketServer(String address, int port, MessageHandler messageHandler, MessageQueue messageQueue) {
        super(new InetSocketAddress(address, port));
        this.messageHandler = messageHandler;
        this.messageQueue = messageQueue;

        setReuseAddr(true);
        setConnectionLostTimeout(0);
    }

    /**
     * Called every server tick from AudioVizMod.
     * Handles heartbeat and metrics logging that was previously BukkitTask-based.
     */
    public void tick() {
        heartbeatTickCounter++;
        metricsTickCounter++;

        if (heartbeatTickCounter >= HEARTBEAT_INTERVAL_TICKS) {
            heartbeatTickCounter = 0;
            sendHeartbeats();
        }

        if (metricsTickCounter >= METRICS_LOG_INTERVAL_TICKS) {
            metricsTickCounter = 0;
            logMetrics();
        }
    }

    private void sendHeartbeats() {
        long now = System.currentTimeMillis();
        String pingMessage = gson.toJson(createPingMessage());
        List<WebSocket> clientsToClose = new ArrayList<>();

        for (Map.Entry<WebSocket, Long> entry : lastPongTime.entrySet()) {
            WebSocket conn = entry.getKey();
            long lastPong = entry.getValue();

            if (now - lastPong > PONG_TIMEOUT_MS) {
                ClientInfo info = clients.get(conn);
                String address = info != null ? info.address : "unknown";
                AudioVizMod.LOGGER.warn("Client {} heartbeat timeout (no pong for {}s), closing",
                    address, (now - lastPong) / 1000);
                clientsToClose.add(conn);
            } else if (conn.isOpen()) {
                try {
                    conn.send(pingMessage);
                    totalMessagesSent.incrementAndGet();
                } catch (Exception e) {
                    AudioVizMod.LOGGER.warn("Failed to send ping: {}", e.getMessage());
                    clientsToClose.add(conn);
                }
            }
        }

        for (WebSocket conn : clientsToClose) {
            try {
                conn.close(1000, "Heartbeat timeout");
            } catch (Exception e) {
                AudioVizMod.LOGGER.warn("Error closing timed-out connection: {}", e.getMessage());
            }
        }
    }

    private void logMetrics() {
        AudioVizMod.LOGGER.info("WebSocket Metrics: connections={}, disconnections={}, active={}, sent={}, received={}, failures={}",
            totalConnections.get(), totalDisconnections.get(), clients.size(),
            totalMessagesSent.get(), totalMessagesReceived.get(), totalSendFailures.get());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        ClientInfo info = new ClientInfo(conn.getRemoteSocketAddress().toString());
        clients.put(conn, info);
        lastPongTime.put(conn, System.currentTimeMillis());
        totalConnections.incrementAndGet();

        AudioVizMod.LOGGER.info("WebSocket client connected: {}", info.address);

        JsonObject welcome = new JsonObject();
        welcome.addProperty("type", "connected");
        welcome.addProperty("message", "Connected to AudioViz server");
        welcome.addProperty("version", "1.0.0");
        conn.send(gson.toJson(welcome));
        totalMessagesSent.incrementAndGet();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ClientInfo info = clients.remove(conn);
        lastPongTime.remove(conn);
        totalDisconnections.incrementAndGet();

        String address = info != null ? info.address : "unknown";
        long duration = info != null ? System.currentTimeMillis() - info.connectedAt : 0;

        AudioVizMod.LOGGER.info("Client {} disconnected: code={}, reason={}, duration={}",
            address, code, (reason != null && !reason.isEmpty() ? reason : "none"),
            formatDuration(duration));
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        totalMessagesReceived.incrementAndGet();

        if (message.length() > MAX_MESSAGE_SIZE) {
            AudioVizMod.LOGGER.warn("Oversized message rejected: {} chars from {}",
                message.length(), conn.getRemoteSocketAddress());
            return;
        }

        // Handle pong responses
        if (message.contains("\"type\":\"pong\"") || message.contains("\"type\": \"pong\"")) {
            lastPongTime.put(conn, System.currentTimeMillis());
            return;
        }

        if (asyncEnabled && isHighFrequencyMessage(message)) {
            messageQueue.enqueueRaw(message);
        } else {
            try {
                JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                String type = json.has("type") ? json.get("type").getAsString() : "unknown";

                if ("get_ws_metrics".equals(type)) {
                    conn.send(gson.toJson(getMetrics()));
                    totalMessagesSent.incrementAndGet();
                    return;
                }

                JsonObject response = messageHandler.handleMessage(type, json);
                if (response != null) {
                    conn.send(gson.toJson(response));
                    totalMessagesSent.incrementAndGet();
                }
            } catch (Exception e) {
                AudioVizMod.LOGGER.warn("Error processing WebSocket message", e);
                JsonObject error = new JsonObject();
                error.addProperty("type", "error");
                error.addProperty("message", e.getMessage());
                conn.send(gson.toJson(error));
                totalMessagesSent.incrementAndGet();
            }
        }
    }

    private boolean isHighFrequencyMessage(String message) {
        int limit = Math.min(message.length(), 60);
        return containsWithin(message, "\"type\":\"batch_update\"", limit) ||
               containsWithin(message, "\"type\": \"batch_update\"", limit) ||
               containsWithin(message, "\"type\":\"bitmap_frame\"", limit) ||
               containsWithin(message, "\"type\": \"bitmap_frame\"", limit) ||
               containsWithin(message, "\"type\":\"audio_state\"", limit) ||
               containsWithin(message, "\"type\": \"audio_state\"", limit) ||
               containsWithin(message, "\"type\":\"voice_audio\"", limit) ||
               containsWithin(message, "\"type\": \"voice_audio\"", limit);
    }

    private static boolean containsWithin(String haystack, String needle, int limit) {
        int maxStart = limit - needle.length();
        if (maxStart < 0) return false;
        for (int i = 0; i <= maxStart; i++) {
            if (haystack.regionMatches(i, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        String address = "server";
        try {
            if (conn != null && conn.getRemoteSocketAddress() != null) {
                address = conn.getRemoteSocketAddress().toString();
            }
        } catch (Exception ignored) {}

        if (ex instanceof IllegalStateException && ex.getMessage() != null
                && ex.getMessage().contains("zip file closed")) {
            AudioVizMod.LOGGER.warn("WebSocket closed during reload (expected)");
            return;
        }

        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        if (ex instanceof java.io.IOException && (
                msg.contains("connection reset") ||
                msg.contains("broken pipe") ||
                msg.contains("end of stream") ||
                msg.contains("socket closed"))) {
            AudioVizMod.LOGGER.warn("WebSocket network error from {}: {}", address, ex.getMessage());
            return;
        }
        if (ex instanceof java.io.EOFException) {
            AudioVizMod.LOGGER.warn("WebSocket EOF from {}: {}", address, ex.getMessage());
            return;
        }

        AudioVizMod.LOGGER.error("WebSocket error from {}: {}", address, ex.getMessage(), ex);
    }

    @Override
    public void onStart() {
        AudioVizMod.LOGGER.info("WebSocket server started on port {}", getPort());
    }

    public int broadcast(JsonObject message) {
        String json = gson.toJson(message);
        int successCount = 0;
        List<WebSocket> failedClients = new ArrayList<>();

        for (WebSocket conn : clients.keySet()) {
            if (conn.isOpen()) {
                try {
                    conn.send(json);
                    successCount++;
                    totalMessagesSent.incrementAndGet();
                } catch (Exception e) {
                    totalSendFailures.incrementAndGet();
                    failedClients.add(conn);
                }
            }
        }

        for (WebSocket conn : failedClients) {
            try { conn.close(1000, "Send failure"); } catch (Exception ignored) {}
        }

        return successCount;
    }

    public int getConnectionCount() {
        return clients.size();
    }

    public void shutdown() {
        messageQueue.stop();

        for (WebSocket conn : new ArrayList<>(getConnections())) {
            try { conn.close(1001, "Server shutting down"); } catch (Exception ignored) {}
        }

        try {
            stop(3000);
        } catch (InterruptedException e) {
            AudioVizMod.LOGGER.warn("WebSocket server shutdown interrupted");
        }
    }

    public JsonObject getMetrics() {
        JsonObject metrics = new JsonObject();
        metrics.addProperty("type", "ws_metrics");
        metrics.addProperty("totalConnections", totalConnections.get());
        metrics.addProperty("totalDisconnections", totalDisconnections.get());
        metrics.addProperty("activeConnections", clients.size());
        metrics.addProperty("totalMessagesSent", totalMessagesSent.get());
        metrics.addProperty("totalMessagesReceived", totalMessagesReceived.get());
        metrics.addProperty("totalSendFailures", totalSendFailures.get());
        metrics.addProperty("timestamp", System.currentTimeMillis());
        return metrics;
    }

    private JsonObject createPingMessage() {
        JsonObject ping = new JsonObject();
        ping.addProperty("type", "ping");
        ping.addProperty("timestamp", System.currentTimeMillis());
        return ping;
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) return minutes + "m " + seconds + "s";
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m " + seconds + "s";
    }

    public String getQueueStats() {
        return messageQueue.getStats();
    }

    public void setAsyncEnabled(boolean enabled) {
        this.asyncEnabled = enabled;
    }

    private static class ClientInfo {
        final String address;
        final long connectedAt;

        ClientInfo(String address) {
            this.address = address;
            this.connectedAt = System.currentTimeMillis();
        }
    }
}
