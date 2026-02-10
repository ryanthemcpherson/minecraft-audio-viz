package com.audioviz.websocket;

import com.audioviz.AudioVizPlugin;
import com.audioviz.protocol.MessageHandler;
import com.audioviz.protocol.MessageQueue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.scheduler.BukkitTask;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * WebSocket server for receiving visualization commands from Python.
 *
 * Performance optimizations:
 * - Async JSON parsing via MessageQueue
 * - Tick-based batch processing
 * - Non-blocking message handling
 */
public class VizWebSocketServer extends WebSocketServer {

    private final AudioVizPlugin plugin;
    private final MessageHandler messageHandler;
    private final MessageQueue messageQueue;
    private final Gson gson;
    private final ConcurrentHashMap<WebSocket, ClientInfo> clients;
    private final Map<WebSocket, Long> lastPongTime;

    // Enable async processing for high-frequency messages
    private boolean asyncEnabled = true;

    // Heartbeat task for connection health monitoring
    private BukkitTask heartbeatTask;
    private BukkitTask metricsLogTask;

    // Connection metrics
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong totalDisconnections = new AtomicLong(0);
    private final AtomicLong totalMessagesSent = new AtomicLong(0);
    private final AtomicLong totalMessagesReceived = new AtomicLong(0);
    private final AtomicLong totalSendFailures = new AtomicLong(0);

    // Heartbeat constants
    private static final long HEARTBEAT_INTERVAL_TICKS = 300L; // 15 seconds
    private static final long PONG_TIMEOUT_MS = 45000L; // 45 seconds
    private static final long METRICS_LOG_INTERVAL_TICKS = 6000L; // 5 minutes

    public VizWebSocketServer(AudioVizPlugin plugin, int port) {
        super(new InetSocketAddress("0.0.0.0", port));  // Bind to all interfaces
        this.plugin = plugin;
        this.messageHandler = new MessageHandler(plugin);
        this.messageQueue = new MessageQueue(plugin, messageHandler);
        this.gson = new Gson();
        this.clients = new ConcurrentHashMap<>();
        this.lastPongTime = new ConcurrentHashMap<>();

        // Set connection timeout
        setConnectionLostTimeout(30);

        // Start the message queue processor
        messageQueue.start();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        ClientInfo info = new ClientInfo(conn.getRemoteSocketAddress().toString());
        clients.put(conn, info);
        lastPongTime.put(conn, System.currentTimeMillis());
        totalConnections.incrementAndGet();

        plugin.getLogger().info("WebSocket client connected: " + info.address);

        // Send welcome message
        JsonObject welcome = new JsonObject();
        welcome.addProperty("type", "connected");
        welcome.addProperty("message", "Connected to AudioViz server");
        welcome.addProperty("version", plugin.getDescription().getVersion());
        conn.send(gson.toJson(welcome));
        totalMessagesSent.incrementAndGet();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ClientInfo info = clients.remove(conn);
        lastPongTime.remove(conn);
        totalDisconnections.incrementAndGet();

        String address = info != null ? info.address : "unknown";
        long connectionDuration = info != null ? System.currentTimeMillis() - info.connectedAt : 0;
        String durationStr = formatDuration(connectionDuration);

        plugin.getLogger().info("Client " + address + " disconnected: code=" + code +
            ", reason=" + (reason != null && !reason.isEmpty() ? reason : "none") +
            ", duration=" + durationStr);
    }

    // Maximum incoming message size (256KB - generous for any valid message)
    private static final int MAX_MESSAGE_SIZE = 262_144;

    @Override
    public void onMessage(WebSocket conn, String message) {
        totalMessagesReceived.incrementAndGet();

        // Reject oversized messages to prevent memory exhaustion
        if (message.length() > MAX_MESSAGE_SIZE) {
            plugin.getLogger().warning("Oversized message rejected: " + message.length() + " chars from " +
                conn.getRemoteSocketAddress());
            return;
        }

        // Handle pong responses for heartbeat
        if (message.contains("\"type\":\"pong\"") || message.contains("\"type\": \"pong\"")) {
            lastPongTime.put(conn, System.currentTimeMillis());
            return;
        }

        // High-frequency messages (batch_update, audio) go through async queue
        // Other messages are processed synchronously for immediate response
        if (asyncEnabled && isHighFrequencyMessage(message)) {
            // Async processing - non-blocking
            messageQueue.enqueueRaw(message);
        } else {
            // Synchronous processing for commands that need immediate response
            try {
                JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                String type = json.has("type") ? json.get("type").getAsString() : "unknown";

                // Handle metrics request
                if ("get_ws_metrics".equals(type)) {
                    JsonObject response = getMetrics();
                    conn.send(gson.toJson(response));
                    totalMessagesSent.incrementAndGet();
                    return;
                }

                // Process message
                JsonObject response = messageHandler.handleMessage(type, json);

                // Send response if any
                if (response != null) {
                    conn.send(gson.toJson(response));
                    totalMessagesSent.incrementAndGet();
                }

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error processing WebSocket message", e);

                // Send error response
                JsonObject error = new JsonObject();
                error.addProperty("type", "error");
                error.addProperty("message", e.getMessage());
                conn.send(gson.toJson(error));
                totalMessagesSent.incrementAndGet();
            }
        }
    }

    /**
     * Check if a message is high-frequency and should be processed asynchronously.
     * Only checks the first 60 chars (where the "type" field is) to avoid
     * false-matching keywords in payload data.
     */
    private boolean isHighFrequencyMessage(String message) {
        // Check the prefix where the "type" field appears in serialized JSON
        String prefix = message.substring(0, Math.min(message.length(), 60));
        return prefix.contains("\"type\":\"batch_update\"") ||
               prefix.contains("\"type\": \"batch_update\"") ||
               prefix.contains("\"type\":\"audio_state\"") ||
               prefix.contains("\"type\": \"audio_state\"");
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        // Guard against null address during shutdown/reload
        String address = "server";
        try {
            if (conn != null && conn.getRemoteSocketAddress() != null) {
                address = conn.getRemoteSocketAddress().toString();
            }
        } catch (Exception ignored) {}

        // Suppress "zip file closed" errors during plugin reload - these are expected
        if (ex instanceof IllegalStateException && ex.getMessage() != null
                && ex.getMessage().contains("zip file closed")) {
            plugin.getLogger().warning("WebSocket closed during plugin reload (expected)");
            return;
        }

        plugin.getLogger().log(Level.SEVERE, "WebSocket error from " + address + ": " + ex.getMessage(), ex);
    }

    @Override
    public void onStart() {
        plugin.getLogger().info("WebSocket server started successfully");
        startHeartbeat();
        startMetricsLogging();
    }

    /**
     * Start the heartbeat task to monitor client connections.
     * Sends ping every 15 seconds and closes connections that haven't responded in 45 seconds.
     */
    private void startHeartbeat() {
        heartbeatTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            String pingMessage = gson.toJson(createPingMessage());

            List<WebSocket> clientsToClose = new ArrayList<>();

            for (Map.Entry<WebSocket, Long> entry : lastPongTime.entrySet()) {
                WebSocket conn = entry.getKey();
                long lastPong = entry.getValue();

                // Check if client has timed out
                if (now - lastPong > PONG_TIMEOUT_MS) {
                    ClientInfo info = clients.get(conn);
                    String address = info != null ? info.address : "unknown";
                    plugin.getLogger().warning("Client " + address + " heartbeat timeout (no pong for " +
                        ((now - lastPong) / 1000) + "s), closing connection");
                    clientsToClose.add(conn);
                } else if (conn.isOpen()) {
                    // Send ping to active clients
                    try {
                        conn.send(pingMessage);
                        totalMessagesSent.incrementAndGet();
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to send ping to client: " + e.getMessage());
                        clientsToClose.add(conn);
                    }
                }
            }

            // Close timed-out connections
            for (WebSocket conn : clientsToClose) {
                try {
                    conn.close(1000, "Heartbeat timeout");
                } catch (Exception e) {
                    plugin.getLogger().warning("Error closing timed-out connection: " + e.getMessage());
                }
            }
        }, HEARTBEAT_INTERVAL_TICKS, HEARTBEAT_INTERVAL_TICKS);
    }

    /**
     * Start periodic metrics logging (every 5 minutes).
     */
    private void startMetricsLogging() {
        metricsLogTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            plugin.getLogger().info("WebSocket Metrics: connections=" + totalConnections.get() +
                ", disconnections=" + totalDisconnections.get() +
                ", active=" + clients.size() +
                ", sent=" + totalMessagesSent.get() +
                ", received=" + totalMessagesReceived.get() +
                ", sendFailures=" + totalSendFailures.get());
        }, METRICS_LOG_INTERVAL_TICKS, METRICS_LOG_INTERVAL_TICKS);
    }

    private JsonObject createPingMessage() {
        JsonObject ping = new JsonObject();
        ping.addProperty("type", "ping");
        ping.addProperty("timestamp", System.currentTimeMillis());
        return ping;
    }

    /**
     * Broadcast a message to all connected clients.
     * Returns the count of successful sends.
     */
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
                    ClientInfo info = clients.get(conn);
                    String address = info != null ? info.address : "unknown";
                    plugin.getLogger().warning("Broadcast failed to client " + address + ": " + e.getMessage());
                    totalSendFailures.incrementAndGet();
                    failedClients.add(conn);
                }
            }
        }

        // Remove clients that failed to receive broadcast
        for (WebSocket conn : failedClients) {
            ClientInfo info = clients.get(conn);
            String address = info != null ? info.address : "unknown";
            plugin.getLogger().info("Removing client " + address + " due to send failure");
            try {
                conn.close(1000, "Send failure");
            } catch (Exception e) {
                // Ignore close errors
            }
        }

        return successCount;
    }

    /**
     * Get number of connected clients.
     */
    public int getConnectionCount() {
        return clients.size();
    }

    /**
     * Shutdown the server and message queue.
     */
    public void shutdown() {
        // Cancel heartbeat task
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }

        // Cancel metrics logging task
        if (metricsLogTask != null) {
            metricsLogTask.cancel();
            metricsLogTask = null;
        }

        messageQueue.stop();

        // Close all active connections before stopping the server
        for (org.java_websocket.WebSocket conn : new java.util.ArrayList<>(getConnections())) {
            try {
                conn.close(1001, "Server shutting down");
            } catch (Exception ignored) {}
        }

        try {
            stop(3000);  // Stop with 3 second timeout for clean thread shutdown
        } catch (InterruptedException e) {
            plugin.getLogger().warning("WebSocket server shutdown interrupted");
        }
    }

    /**
     * Get connection metrics as a JsonObject.
     */
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

    /**
     * Format duration in human-readable format.
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) {
            return minutes + "m " + seconds + "s";
        }
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m " + seconds + "s";
    }

    /**
     * Get message queue statistics.
     */
    public String getQueueStats() {
        return messageQueue.getStats();
    }

    /**
     * Enable or disable async message processing.
     */
    public void setAsyncEnabled(boolean enabled) {
        this.asyncEnabled = enabled;
    }

    /**
     * Client connection info.
     */
    private static class ClientInfo {
        final String address;
        final long connectedAt;

        ClientInfo(String address) {
            this.address = address;
            this.connectedAt = System.currentTimeMillis();
        }
    }
}
