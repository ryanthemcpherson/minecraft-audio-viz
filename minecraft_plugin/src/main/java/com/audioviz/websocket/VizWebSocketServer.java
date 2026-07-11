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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
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
    private final ConcurrentHashMap<WebSocket, Long> lastPongTime;
    private final WebSocketSecurityPolicy securityPolicy;
    private final Consumer<Runnable> authTimeoutScheduler;
    private final Object connectionLifecycleLock;
    private final Object messageIntakeLock;
    // Guarded by connectionLifecycleLock. Tracks clients whose connect callback
    // completed and whose matching disconnect callback is still outstanding.
    private int lifecycleActiveClients;

    // Enable async processing for high-frequency messages
    private volatile boolean asyncEnabled = true;
    private volatile boolean acceptingMessages = true;

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
    private static final long AUTH_TIMEOUT_TICKS = 100L; // 5 seconds

    public VizWebSocketServer(AudioVizPlugin plugin, int port) {
        this(plugin, port, new MessageHandler(plugin));
    }

    private VizWebSocketServer(AudioVizPlugin plugin, int port, MessageHandler messageHandler) {
        this(
            plugin,
            port,
            messageHandler,
            new MessageQueue(plugin, messageHandler),
            new WebSocketSecurityPolicy(plugin.getConfig().getString("ws-secret", "")),
            task -> plugin.getServer().getScheduler().runTaskLaterAsynchronously(
                plugin, task, AUTH_TIMEOUT_TICKS)
        );
        messageQueue.start();
    }

    VizWebSocketServer(
        AudioVizPlugin plugin,
        int port,
        MessageHandler messageHandler,
        MessageQueue messageQueue,
        WebSocketSecurityPolicy securityPolicy,
        Consumer<Runnable> authTimeoutScheduler
    ) {
        super(new InetSocketAddress(
            plugin.getConfig().getString("websocket.address", "127.0.0.1"), port));
        this.plugin = plugin;
        this.messageHandler = messageHandler;
        this.messageQueue = messageQueue;
        this.securityPolicy = securityPolicy;
        this.authTimeoutScheduler = authTimeoutScheduler;
        this.gson = new Gson();
        this.clients = new ConcurrentHashMap<>();
        this.lastPongTime = new ConcurrentHashMap<>();
        this.connectionLifecycleLock = new Object();
        this.messageIntakeLock = new Object();
        this.lifecycleActiveClients = 0;

        // Allow rebinding the port immediately after a server restart
        // (prevents "Address already in use" from zombie/lingering sockets)
        setReuseAddr(true);

        // Disable library-level connection-lost detection (protocol PING/PONG).
        // We use our own JSON heartbeat system instead (startHeartbeat).
        // websockets 16.x (Python) doesn't respond to java-websocket's protocol
        // pings, causing spurious 35s disconnects.
        setConnectionLostTimeout(0);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        if (handshake.hasFieldValue("Origin")) {
            conn.close(4003, "Browser clients are not allowed");
            return;
        }

        ClientInfo info;
        synchronized (messageIntakeLock) {
            if (!acceptingMessages) {
                info = null;
            } else {
                info = new ClientInfo(conn.getRemoteSocketAddress().toString());
                // Acquire the opening generation lease before publishing the
                // client. Shutdown drains the intake gate before snapshotting,
                // so every accepted onOpen is represented by an in-flight lease.
                info.beginOperationIfOpen();
                clients.put(conn, info);
            }
        }
        if (info == null) {
            conn.close(1001, "Server shutting down");
            return;
        }

        try {
            // Send welcome message
            JsonObject welcome = new JsonObject();
            welcome.addProperty("type", "connected");
            welcome.addProperty("message", "Connected to AudioViz server");
            welcome.addProperty("version", plugin.getDescription().getVersion());
            welcome.addProperty("server_type", "paper");
            welcome.addProperty("auth_required", securityPolicy.requiresAuthentication());
            conn.send(gson.toJson(welcome));
            totalMessagesSent.incrementAndGet();

            if (securityPolicy.requiresAuthentication()) {
                authTimeoutScheduler.accept(() -> {
                    ClientInfo pendingClient = clients.get(conn);
                    if (pendingClient != null && closeInactiveClient(conn, pendingClient)) {
                        plugin.getLogger().warning(
                            "Authentication timeout for " + pendingClient.address);
                        conn.close(4002, "Authentication timeout");
                    }
                });
                return;
            }

            if (beginAuthentication(conn, info)) {
                try {
                    admitClient(conn, info);
                } finally {
                    info.endOperation();
                }
            }
        } finally {
            info.endOperation();
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ClientInfo info = clients.get(conn);
        if (info == null) {
            lastPongTime.remove(conn);
            return;
        }

        ClientCloseResult closeResult;
        synchronized (info) {
            if (clients.get(conn) != info) {
                return;
            }

            if (info.isAdmitting()) {
                // Admission callbacks are serialized by the lifecycle lock. Keep
                // client -> lifecycle lock order so a completed connect callback
                // is always paired with this close.
                synchronized (connectionLifecycleLock) {
                    closeResult = info.closeAndAwaitDrained();
                    finishClientClose(conn, info, closeResult, code, reason);
                }
                return;
            }

            // Mark this generation closing before waiting. Object.wait releases
            // the client monitor, and no global lock is held while work drains.
            closeResult = info.closeAndAwaitDrained();
        }

        synchronized (connectionLifecycleLock) {
            finishClientClose(conn, info, closeResult, code, reason);
        }
    }

    private void finishClientClose(
        WebSocket conn,
        ClientInfo info,
        ClientCloseResult closeResult,
        int code,
        String reason
    ) {
        if (closeResult == null || !clients.remove(conn, info)) {
            return;
        }
        lastPongTime.remove(conn);
        if (!closeResult.wasActive()) {
            return;
        }

        totalDisconnections.incrementAndGet();
        lifecycleActiveClients = Math.max(0, lifecycleActiveClients - 1);

        long connectionDuration = System.currentTimeMillis() - info.connectedAt;
        String durationStr = formatDuration(connectionDuration);
        plugin.getLogger().info("Client " + info.address + " disconnected: code=" + code +
            ", reason=" + (reason != null && !reason.isEmpty() ? reason : "none") +
            ", duration=" + durationStr);

        var disconnectListener = plugin.getConnectionStateListener();
        if (disconnectListener != null && lifecycleActiveClients == 0) {
            try {
                disconnectListener.onDjDisconnect(
                    reason != null ? reason : "connection closed"
                );
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                    Level.WARNING,
                    "Connection listener failed during WebSocket close",
                    exception
                );
            }
        }
    }

    // Maximum incoming message size (256KB - generous for any valid message)
    private static final int MAX_MESSAGE_SIZE = 262_144;

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (!acceptingMessages) {
            return;
        }

        totalMessagesReceived.incrementAndGet();

        ClientInfo clientInfo = clients.get(conn);
        if (clientInfo != null && clientInfo.rejectsMessagesWithoutClosing()) {
            return;
        }
        if (message.length() > MAX_MESSAGE_SIZE) {
            if (clientInfo == null || !clientInfo.isActive()) {
                if (clientInfo == null || closeInactiveClient(conn, clientInfo)) {
                    conn.close(4001, "Authentication failed");
                }
            } else {
                plugin.getLogger().warning("Oversized message rejected: " + message.length() +
                    " chars from " + conn.getRemoteSocketAddress());
            }
            return;
        }

        if (clientInfo == null || !clientInfo.isActive()) {
            if (clientInfo != null && clientInfo.isPending() && authenticate(conn, clientInfo, message)) {
                return;
            }
            if (clientInfo == null || closeInactiveClient(conn, clientInfo)) {
                conn.close(4001, "Authentication failed");
            }
            return;
        }

        // Handle pong responses for heartbeat (check only prefix to avoid
        // crafted messages with "pong" buried in payload data)
        String prefix = message.substring(0, Math.min(64, message.length()));
        if (prefix.contains("\"type\":\"pong\"") || prefix.contains("\"type\": \"pong\"")) {
            updatePongForActiveClient(conn, clientInfo);
            return;
        }

        // High-frequency messages (batch_update, audio) go through async queue
        // Other messages are processed synchronously for immediate response
        if (asyncEnabled && isHighFrequencyMessage(message)) {
            try {
                enqueueForActiveClient(conn, clientInfo, message);
            } catch (RejectedExecutionException exception) {
                if (acceptingMessages) {
                    throw exception;
                }
            }
        } else {
            // Synchronous processing for commands that need immediate response
            try {
                JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                String type = json.has("type") ? json.get("type").getAsString() : "unknown";

                // Echo correlation ID (_seq) from request to response so the
                // VJ server can match responses to the correct caller.
                final int seq = json.has("_seq") ? json.get("_seq").getAsInt() : -1;

                // Handle metrics request
                if ("get_ws_metrics".equals(type)) {
                    JsonObject response = getMetrics();
                    if (seq >= 0) response.addProperty("_seq", seq);
                    sendToActiveClient(conn, clientInfo, gson.toJson(response));
                    return;
                }

                handleForActiveClient(conn, clientInfo, type, json, seq);

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error processing WebSocket message", e);

                // Send error response
                JsonObject error = new JsonObject();
                error.addProperty("type", "error");
                error.addProperty("message", e.getMessage());
                sendToActiveClient(conn, clientInfo, gson.toJson(error));
            }
        }
    }

    private boolean authenticate(WebSocket conn, ClientInfo info, String message) {
        if (!securityPolicy.requiresAuthentication()) {
            return false;
        }

        JsonObject authMessage;
        try {
            authMessage = JsonParser.parseString(message).getAsJsonObject();
            if (!authMessage.has("type") || !authMessage.has("token") ||
                    !"auth".equals(authMessage.get("type").getAsString()) ||
                    !securityPolicy.tokenMatches(authMessage.get("token").getAsString())) {
                return false;
            }
        } catch (RuntimeException exception) {
            return false;
        }

        if (!beginAuthentication(conn, info)) {
            return false;
        }

        try {
            conn.send("{\"type\":\"auth_ok\"}");
            totalMessagesSent.incrementAndGet();
        } catch (RuntimeException exception) {
            // A close helper must never wait on the operation lease owned by
            // this thread. Release it before discarding the failed generation.
            info.endOperation();
            discardUnauthenticatedClient(conn, info);
            throw exception;
        }

        try {
            admitClient(conn, info);
            return true;
        } finally {
            info.endOperation();
        }
    }

    private boolean beginAuthentication(WebSocket conn, ClientInfo info) {
        synchronized (info) {
            return acceptingMessages
                && clients.get(conn) == info
                && info.beginAuthenticationOperation();
        }
    }

    private boolean admitClient(WebSocket conn, ClientInfo info) {
        synchronized (info) {
            if (!acceptingMessages || clients.get(conn) != info
                    || !info.beginAdmission()) {
                return false;
            }
        }

        synchronized (connectionLifecycleLock) {
            if (!acceptingMessages || clients.get(conn) != info || !info.isAdmitting()) {
                return false;
            }

            notifyClientConnected(info);

            if (!acceptingMessages || clients.get(conn) != info
                    || !info.activateIfAdmitting()) {
                return false;
            }
            lastPongTime.put(conn, System.currentTimeMillis());
            totalConnections.incrementAndGet();
            lifecycleActiveClients++;
            return true;
        }
    }

    private void discardUnauthenticatedClient(WebSocket conn, ClientInfo info) {
        if (info.closeInactiveAndAwaitDrained() && clients.remove(conn, info)) {
            lastPongTime.remove(conn);
        }
    }

    private boolean closeInactiveClient(WebSocket conn, ClientInfo info) {
        if (clients.get(conn) != info || !info.closeInactiveAndAwaitDrained()) {
            return false;
        }
        boolean removed = clients.remove(conn, info);
        if (removed) {
            lastPongTime.remove(conn);
        }
        return removed;
    }

    private boolean enqueueForActiveClient(WebSocket conn, ClientInfo info, String message) {
        synchronized (messageIntakeLock) {
            if (!acceptingMessages) {
                return false;
            }
            if (!beginOperationForActiveClient(conn, info)) {
                return false;
            }
            try {
                MessageQueue.MessageGuard guard = operation ->
                    runForActiveClient(conn, info, operation);
                messageQueue.enqueueRaw(message, guard);
                return true;
            } finally {
                info.endOperation();
            }
        }
    }

    private void handleForActiveClient(
        WebSocket conn,
        ClientInfo info,
        String type,
        JsonObject message,
        int seq
    ) {
        if (!beginOperationForActiveClient(conn, info)) {
            return;
        }
        try {
            JsonObject response = messageHandler.handleMessage(type, message);
            if (response != null) {
                if (seq >= 0) response.addProperty("_seq", seq);
                sendToActiveClient(conn, info, gson.toJson(response));
            }
        } finally {
            info.endOperation();
        }
    }

    private boolean runForActiveClient(WebSocket conn, ClientInfo info, Runnable operation) {
        if (!beginOperationForActiveClient(conn, info)) {
            return false;
        }
        try {
            operation.run();
            return true;
        } finally {
            info.endOperation();
        }
    }

    private boolean sendToActiveClient(WebSocket conn, ClientInfo info, String message) {
        if (!beginOperationForActiveClient(conn, info)) {
            return false;
        }
        try {
            if (!conn.isOpen()) {
                return false;
            }
            conn.send(message);
            totalMessagesSent.incrementAndGet();
            return true;
        } finally {
            info.endOperation();
        }
    }

    private void updatePongForActiveClient(WebSocket conn, ClientInfo info) {
        if (!beginOperationForActiveClient(conn, info)) {
            return;
        }
        try {
            lastPongTime.replace(conn, System.currentTimeMillis());
        } finally {
            info.endOperation();
        }
    }

    private boolean beginOperationForActiveClient(WebSocket conn, ClientInfo info) {
        if (!acceptingMessages || clients.get(conn) != info || !info.isActive()) {
            return false;
        }
        synchronized (info) {
            return acceptingMessages
                && clients.get(conn) == info
                && info.beginOperationIfActive();
        }
    }

    private void notifyClientConnected(ClientInfo info) {
        plugin.getLogger().info("WebSocket client connected: " + info.address);
        var connectListener = plugin.getConnectionStateListener();
        if (connectListener != null) {
            connectListener.onDjConnect(info.address);
        }
    }

    /**
     * Check if a message is high-frequency and should be processed asynchronously.
     * Only checks the first 60 chars (where the "type" field is) to avoid
     * false-matching keywords in payload data.
     */
    private boolean isHighFrequencyMessage(String message) {
        // Check the prefix where the "type" field appears in serialized JSON.
        // Use indexOf with a limit to avoid allocating a substring every call.
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

    /**
     * Check if {@code needle} appears within the first {@code limit} characters of {@code haystack},
     * without allocating a substring.
     */
    private static boolean containsWithin(String haystack, String needle, int limit) {
        int maxStart = limit - needle.length();
        if (maxStart < 0) return false;
        // regionMatches does char-by-char comparison with no allocation
        for (int i = 0; i <= maxStart; i++) {
            if (haystack.regionMatches(i, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
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

        // Downgrade common network errors to WARNING (connection reset, broken pipe, EOF)
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        if (ex instanceof java.io.IOException && (
                msg.contains("connection reset") ||
                msg.contains("broken pipe") ||
                msg.contains("end of stream") ||
                msg.contains("socket closed"))) {
            plugin.getLogger().warning("WebSocket network error from " + address + ": " + ex.getMessage());
            return;
        }
        if (ex instanceof java.io.EOFException) {
            plugin.getLogger().warning("WebSocket EOF from " + address + ": " + ex.getMessage());
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
                ClientInfo info = clients.get(conn);

                if (info == null || !info.isActive()) {
                    lastPongTime.remove(conn, lastPong);
                    continue;
                }

                // Check if client has timed out
                if (now - lastPong > PONG_TIMEOUT_MS) {
                    plugin.getLogger().warning("Client " + info.address + " heartbeat timeout (no pong for " +
                        ((now - lastPong) / 1000) + "s), closing connection");
                    clientsToClose.add(conn);
                } else {
                    // Send ping to active clients
                    try {
                        sendToActiveClient(conn, info, pingMessage);
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
                ", active=" + activeClientCount() +
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

        for (Map.Entry<WebSocket, ClientInfo> entry : clients.entrySet()) {
            WebSocket conn = entry.getKey();
            try {
                if (sendToActiveClient(conn, entry.getValue(), json)) {
                    successCount++;
                }
            } catch (Exception e) {
                ClientInfo info = clients.get(conn);
                String address = info != null ? info.address : "unknown";
                plugin.getLogger().warning("Broadcast failed to client " + address + ": " + e.getMessage());
                totalSendFailures.incrementAndGet();
                failedClients.add(conn);
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
        return activeClientCount();
    }

    private int activeClientCount() {
        int count = 0;
        for (ClientInfo info : clients.values()) {
            if (info.isActive()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Shutdown the server and message queue.
     */
    public void shutdown() {
        synchronized (connectionLifecycleLock) {
            if (!acceptingMessages) {
                return;
            }
            acceptingMessages = false;
        }

        // Drain any submission that crossed the intake gate before shutdown.
        synchronized (messageIntakeLock) {
            // Intentionally empty.
        }

        List<WebSocket> connectionsToClose = new ArrayList<>(clients.keySet());
        for (Map.Entry<WebSocket, ClientInfo> entry : new ArrayList<>(clients.entrySet())) {
            WebSocket conn = entry.getKey();
            ClientInfo info = entry.getValue();
            // Join the same per-generation drain protocol used by onClose.
            // No global lifecycle lock is held while accepted work completes.
            ClientCloseResult closeResult = info.closeAndAwaitDrained();
            synchronized (connectionLifecycleLock) {
                finishClientClose(
                    conn,
                    info,
                    closeResult,
                    1001,
                    "Server shutting down"
                );
            }
        }
        clients.clear();
        lastPongTime.clear();

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

        // Close all active connections before stopping the server
        for (WebSocket conn : connectionsToClose) {
            try {
                conn.close(1001, "Server shutting down");
            } catch (Exception ignored) {}
        }

        try {
            stop(3000);  // Stop with 3 second timeout for clean thread shutdown
        } catch (InterruptedException e) {
            plugin.getLogger().warning("WebSocket server shutdown interrupted");
            Thread.currentThread().interrupt();
        } finally {
            messageQueue.stop();
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
        metrics.addProperty("activeConnections", activeClientCount());
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
        private volatile ClientState state;
        private int inFlightOperations;
        private boolean closeWasActive;

        ClientInfo(String address) {
            this.address = address;
            this.connectedAt = System.currentTimeMillis();
            this.state = ClientState.PENDING;
            this.inFlightOperations = 0;
            this.closeWasActive = false;
        }

        synchronized boolean beginOperationIfOpen() {
            if (state == ClientState.CLOSING || state == ClientState.CLOSED) {
                return false;
            }
            inFlightOperations++;
            return true;
        }

        synchronized boolean beginAuthenticationOperation() {
            if (state != ClientState.PENDING) {
                return false;
            }
            state = ClientState.AUTHENTICATING;
            inFlightOperations++;
            return true;
        }

        synchronized boolean beginAdmission() {
            if (state != ClientState.AUTHENTICATING) {
                return false;
            }
            state = ClientState.ADMITTING;
            return true;
        }

        /** Called only while holding the server's global lifecycle lock. */
        boolean activateIfAdmitting() {
            if (state != ClientState.ADMITTING) {
                return false;
            }
            state = ClientState.ACTIVE;
            return true;
        }

        synchronized boolean beginOperationIfActive() {
            if (state != ClientState.ACTIVE) {
                return false;
            }
            inFlightOperations++;
            return true;
        }

        synchronized void endOperation() {
            if (inFlightOperations <= 0) {
                throw new IllegalStateException("Client operation lease underflow");
            }
            inFlightOperations--;
            if (inFlightOperations == 0) {
                notifyAll();
            }
        }

        synchronized boolean closeInactiveAndAwaitDrained() {
            if (state != ClientState.PENDING && state != ClientState.AUTHENTICATING) {
                return false;
            }
            closeAndAwaitDrained();
            return true;
        }

        synchronized ClientCloseResult closeAndAwaitDrained() {
            if (state == ClientState.CLOSING) {
                boolean interrupted = false;
                while (state == ClientState.CLOSING) {
                    try {
                        wait();
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new ClientCloseResult(closeWasActive);
            }
            if (state == ClientState.CLOSED) {
                return new ClientCloseResult(closeWasActive);
            }

            boolean wasActive = state == ClientState.ACTIVE;
            closeWasActive = wasActive;
            state = ClientState.CLOSING;
            boolean interrupted = false;
            while (inFlightOperations > 0) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            state = ClientState.CLOSED;
            notifyAll();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return new ClientCloseResult(wasActive);
        }

        boolean isPending() {
            return state == ClientState.PENDING;
        }

        boolean isActive() {
            return state == ClientState.ACTIVE;
        }

        boolean isAdmitting() {
            return state == ClientState.ADMITTING;
        }

        boolean rejectsMessagesWithoutClosing() {
            return state == ClientState.ADMITTING
                || state == ClientState.CLOSING
                || state == ClientState.CLOSED;
        }
    }

    private record ClientCloseResult(boolean wasActive) { }

    private enum ClientState {
        PENDING,
        AUTHENTICATING,
        ADMITTING,
        ACTIVE,
        CLOSING,
        CLOSED
    }
}
