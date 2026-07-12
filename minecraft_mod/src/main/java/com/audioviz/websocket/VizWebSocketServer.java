package com.audioviz.websocket;

import com.audioviz.AudioVizMod;
import com.audioviz.connection.ConnectionStateListener;
import com.audioviz.protocol.MessageHandler;
import com.audioviz.protocol.MessageQueue;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket server for receiving visualization commands.
 * Ported from Paper plugin — removed Bukkit scheduler deps.
 * Heartbeat and metrics are driven by tick() calls from AudioVizMod.
 */
public class VizWebSocketServer extends WebSocketServer {

    private final MessageHandler messageHandler;
    private final MessageQueue messageQueue;
    private final Executor serverExecutor;
    private final Gson gson = new Gson();
    private final ConcurrentHashMap<WebSocket, ClientInfo> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WebSocket, Long> lastPongTime = new ConcurrentHashMap<>();
    private final WebSocketSecurityPolicy securityPolicy;
    private final ConnectionStateListener connectionStateListener;
    private final Object connectionLifecycleLock = new Object();
    private final Object messageIntakeLock = new Object();

    private volatile boolean asyncEnabled = true;
    private volatile boolean acceptingMessages = true;

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
    private static final long AUTH_TIMEOUT_TICKS = 100L; // 5 seconds
    private static final int MAX_AUTH_TOKEN_LENGTH = 1_024;

    // Tick counters (driven by AudioVizMod.tick())
    private int heartbeatTickCounter = 0;
    private int metricsTickCounter = 0;
    private volatile long authTickCounter = 0;

    private static final int MAX_MESSAGE_SIZE = 262_144;

    public VizWebSocketServer(
        String address,
        int port,
        String secret,
        MessageHandler messageHandler,
        MessageQueue messageQueue,
        Executor serverExecutor,
        ConnectionStateListener connectionStateListener
    ) {
        super(new InetSocketAddress(requireSafeBindAddress(address), port), transportDrafts());
        this.messageHandler = messageHandler;
        this.messageQueue = messageQueue;
        this.serverExecutor = serverExecutor;
        this.securityPolicy = new WebSocketSecurityPolicy(secret);
        this.connectionStateListener = connectionStateListener;

        setReuseAddr(true);
        setConnectionLostTimeout(0);
    }

    private static String requireSafeBindAddress(String bindAddress) {
        if (!WebSocketSecurityPolicy.isSafeConfiguration(bindAddress, "")) {
            throw new IllegalArgumentException(
                "Minecraft renderer WebSocket must bind to an explicit loopback address"
            );
        }
        return bindAddress.strip();
    }

    /**
     * Called every server tick from AudioVizMod.
     * Handles heartbeat and metrics logging that was previously BukkitTask-based.
     */
    public void tick() {
        if (!acceptingMessages) {
            return;
        }

        authTickCounter++;
        heartbeatTickCounter++;
        metricsTickCounter++;

        closeTimedOutClients();

        if (heartbeatTickCounter >= HEARTBEAT_INTERVAL_TICKS) {
            heartbeatTickCounter = 0;
            sendHeartbeats();
        }

        if (metricsTickCounter >= METRICS_LOG_INTERVAL_TICKS) {
            metricsTickCounter = 0;
            logMetrics();
        }
    }

    private void closeTimedOutClients() {
        long currentTick = authTickCounter;
        for (Map.Entry<WebSocket, ClientInfo> entry : clients.entrySet()) {
            WebSocket conn = entry.getKey();
            ClientInfo info = entry.getValue();
            if (currentTick >= info.authDeadlineTick && closeInactiveClient(conn, info)) {
                AudioVizMod.LOGGER.warn("Authentication timeout for {}", info.address);
                conn.close(4002, "Authentication timeout");
            }
        }
    }

    private void sendHeartbeats() {
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

            if (now - lastPong > PONG_TIMEOUT_MS) {
                AudioVizMod.LOGGER.warn("Client {} heartbeat timeout (no pong for {}s), closing",
                    info.address, (now - lastPong) / 1000);
                clientsToClose.add(conn);
            } else {
                try {
                    sendToActiveClient(conn, info, pingMessage);
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
            totalConnections.get(), totalDisconnections.get(), activeClientCount(),
            totalMessagesSent.get(), totalMessagesReceived.get(), totalSendFailures.get());
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
                info = new ClientInfo(
                    conn.getRemoteSocketAddress().toString(),
                    authTickCounter + AUTH_TIMEOUT_TICKS
                );
                clients.put(conn, info);
            }
        }
        if (info == null) {
            conn.close(1001, "Server shutting down");
            return;
        }

        JsonObject welcome = new JsonObject();
        welcome.addProperty("type", "connected");
        welcome.addProperty("message", "Connected to AudioViz server");
        welcome.addProperty("version", "1.0.0");
        welcome.addProperty("server_type", "fabric");
        welcome.addProperty("auth_required", securityPolicy.requiresAuthentication());
        conn.send(gson.toJson(welcome));
        totalMessagesSent.incrementAndGet();

        if (securityPolicy.requiresAuthentication()) {
            return;
        }

        if (beginAuthentication(conn, info)) {
            admitClient(conn, info);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ClientInfo info = clients.get(conn);
        if (info == null) {
            lastPongTime.remove(conn);
            return;
        }

        // Lock order is always client -> global lifecycle. Waiting for
        // in-flight work on this client never blocks unrelated lifecycle events.
        synchronized (info) {
            synchronized (connectionLifecycleLock) {
                if (!clients.remove(conn, info)) {
                    return;
                }
                lastPongTime.remove(conn);

                if (!info.closeAndWasActive()) {
                    return;
                }

                totalDisconnections.incrementAndGet();

                long duration = System.currentTimeMillis() - info.connectedAt;
                String safeCause = safeCloseCause(remote, code);
                AudioVizMod.LOGGER.info(
                    "Client {} disconnected: code={}, cause={}, duration={}",
                    info.address,
                    code,
                    safeCause,
                    formatDuration(duration)
                );

                if (connectionStateListener != null && activeClientCount() == 0) {
                    connectionStateListener.onDjDisconnect(safeCause);
                }
            }
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (!acceptingMessages) {
            return;
        }

        totalMessagesReceived.incrementAndGet();

        ClientInfo clientInfo = clients.get(conn);
        if (message.length() > MAX_MESSAGE_SIZE) {
            if (clientInfo == null || !clientInfo.isActive()) {
                if (clientInfo != null) {
                    closeInactiveClient(conn, clientInfo);
                }
                conn.close(4001, "Authentication failed");
            } else {
                AudioVizMod.LOGGER.warn("Oversized message rejected: {} chars from {}",
                    message.length(), conn.getRemoteSocketAddress());
            }
            return;
        }

        if (clientInfo == null || !clientInfo.isActive()) {
            if (clientInfo != null && clientInfo.isPending()
                    && authenticate(conn, clientInfo, message)) {
                return;
            }
            if (!acceptingMessages) {
                return;
            }
            if (clientInfo != null) {
                closeInactiveClient(conn, clientInfo);
            }
            conn.close(4001, "Authentication failed");
            return;
        }

        try {
            enqueueForActiveClient(conn, clientInfo, message);
        } catch (RejectedExecutionException exception) {
            if (acceptingMessages) {
                throw exception;
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
            if (!isValidAuthenticationMessage(authMessage)) {
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
            discardUnauthenticatedClient(conn, info);
            throw exception;
        }

        admitClient(conn, info);
        return true;
    }

    private boolean isValidAuthenticationMessage(JsonObject authMessage) {
        int propertyCount = authMessage.size();
        if (!authMessage.has("type") || !authMessage.has("token")
                || (propertyCount != 2 && propertyCount != 3)
                || (propertyCount == 3 && !authMessage.has("v"))) {
            return false;
        }

        JsonElement type = authMessage.get("type");
        JsonElement tokenElement = authMessage.get("token");
        JsonElement version = authMessage.get("v");
        if (!isJsonString(type) || !"auth".equals(type.getAsString())
                || !isJsonString(tokenElement)
                || (version != null && !isJsonString(version))) {
            return false;
        }

        String token = tokenElement.getAsString();
        int tokenLength = token.codePointCount(0, token.length());
        return tokenLength >= 1
            && tokenLength <= MAX_AUTH_TOKEN_LENGTH
            && securityPolicy.tokenMatches(token);
    }

    private static boolean isJsonString(JsonElement element) {
        return element != null
            && element.isJsonPrimitive()
            && element.getAsJsonPrimitive().isString();
    }

    private static List<Draft> transportDrafts() {
        return List.of(new Draft_6455(List.of(), MAX_MESSAGE_SIZE));
    }

    private static String safeCloseCause(boolean remote, int code) {
        return (remote ? "remote" : "local") + " close (code " + code + ")";
    }

    private boolean beginAuthentication(WebSocket conn, ClientInfo info) {
        synchronized (info) {
            return acceptingMessages
                && clients.get(conn) == info
                && info.beginAuthentication();
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

            // Keep the client non-active until the globally serialized
            // connection callback has completed. No client lock is held here.
            AudioVizMod.LOGGER.info("WebSocket client connected: {}", info.address);
            if (connectionStateListener != null) {
                connectionStateListener.onDjConnect(info.address);
            }

            if (!acceptingMessages || clients.get(conn) != info
                    || !info.activateIfAdmitting()) {
                return false;
            }
            lastPongTime.put(conn, System.currentTimeMillis());
            totalConnections.incrementAndGet();
            return true;
        }
    }

    private boolean closeInactiveClient(WebSocket conn, ClientInfo info) {
        synchronized (info) {
            synchronized (connectionLifecycleLock) {
                return clients.get(conn) == info && info.closeIfInactive();
            }
        }
    }

    private void discardUnauthenticatedClient(WebSocket conn, ClientInfo info) {
        synchronized (info) {
            synchronized (connectionLifecycleLock) {
                clients.remove(conn, info);
                lastPongTime.remove(conn);
                info.closeIfInactive();
            }
        }
    }

    private boolean enqueueForActiveClient(WebSocket conn, ClientInfo info, String message) {
        synchronized (messageIntakeLock) {
            if (!acceptingMessages) {
                return false;
            }
            synchronized (info) {
                if (clients.get(conn) != info || !info.isActive()) {
                    return false;
                }
                MessageQueue.MessageGuard guard = operation ->
                    runForActiveClient(conn, info, operation);
                messageQueue.parseAndDispatch(
                    message,
                    guard,
                    (parsed, parsedGuard) -> dispatchParsedMessage(
                        conn,
                        info,
                        parsed,
                        parsedGuard
                    ),
                    exception -> handleMessageFailure(conn, info, exception)
                );
                return true;
            }
        }
    }

    private void dispatchParsedMessage(
        WebSocket conn,
        ClientInfo info,
        JsonObject message,
        MessageQueue.MessageGuard guard
    ) {
        try {
            String type = topLevelType(message);
            if ("pong".equals(type)) {
                updatePongForActiveClient(conn, info);
                return;
            }

            if (requiresBoundedQueue(type) || (asyncEnabled && isLegacyAsyncType(type))) {
                messageQueue.enqueue(message, guard);
                return;
            }

            int seq = message.has("_seq") ? message.get("_seq").getAsInt() : -1;
            if ("get_ws_metrics".equals(type)) {
                JsonObject response = getMetrics();
                if (seq >= 0) {
                    response.addProperty("_seq", seq);
                }
                sendToActiveClient(conn, info, gson.toJson(response));
                return;
            }

            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            boolean submitted = submitForActiveClient(
                conn,
                info,
                () -> executeHandlerForActiveClient(conn, info, type, message, future)
            );
            if (!submitted) {
                return;
            }

            future.whenComplete((result, exception) -> {
                try {
                    if (exception != null) {
                        JsonObject error = new JsonObject();
                        error.addProperty("type", "error");
                        error.addProperty("message", exception.getMessage());
                        if (seq >= 0) {
                            error.addProperty("_seq", seq);
                        }
                        sendToActiveClient(conn, info, gson.toJson(error));
                    } else if (result != null) {
                        if (seq >= 0) {
                            result.addProperty("_seq", seq);
                        }
                        sendToActiveClient(conn, info, gson.toJson(result));
                    }
                } catch (RuntimeException sendException) {
                    AudioVizMod.LOGGER.warn(
                        "Failed to send response: {}",
                        sendException.getMessage()
                    );
                    totalSendFailures.incrementAndGet();
                }
            });
        } catch (RuntimeException exception) {
            handleMessageFailure(conn, info, exception);
        }
    }

    private void handleMessageFailure(
        WebSocket conn,
        ClientInfo info,
        RuntimeException exception
    ) {
        AudioVizMod.LOGGER.warn("Error processing WebSocket message", exception);
        try {
            JsonObject error = new JsonObject();
            error.addProperty("type", "error");
            error.addProperty("message", exception.getMessage());
            sendToActiveClient(conn, info, gson.toJson(error));
        } catch (RuntimeException sendException) {
            AudioVizMod.LOGGER.warn(
                "Failed to send error response: {}",
                sendException.getMessage()
            );
            totalSendFailures.incrementAndGet();
        }
    }

    private boolean submitForActiveClient(WebSocket conn, ClientInfo info, Runnable task) {
        synchronized (messageIntakeLock) {
            if (!acceptingMessages) {
                return false;
            }
            synchronized (info) {
                if (clients.get(conn) != info || !info.isActive()) {
                    return false;
                }
                serverExecutor.execute(task);
                return true;
            }
        }
    }

    private void executeHandlerForActiveClient(
        WebSocket conn,
        ClientInfo info,
        String type,
        JsonObject message,
        CompletableFuture<JsonObject> future
    ) {
        JsonObject result;
        try {
            synchronized (info) {
                if (!acceptingMessages || clients.get(conn) != info || !info.isActive()) {
                    future.complete(null);
                    return;
                }
                result = messageHandler.handleMessage(type, message);
            }
            future.complete(result);
        } catch (Exception exception) {
            future.completeExceptionally(exception);
        }
    }

    private boolean runForActiveClient(WebSocket conn, ClientInfo info, Runnable operation) {
        synchronized (info) {
            if (!acceptingMessages || clients.get(conn) != info || !info.isActive()) {
                return false;
            }
            operation.run();
            return true;
        }
    }

    private boolean sendToActiveClient(WebSocket conn, ClientInfo info, String message) {
        synchronized (info) {
            if (!acceptingMessages || clients.get(conn) != info
                    || !info.isActive() || !conn.isOpen()) {
                return false;
            }
            conn.send(message);
            totalMessagesSent.incrementAndGet();
            return true;
        }
    }

    private void updatePongForActiveClient(WebSocket conn, ClientInfo info) {
        synchronized (info) {
            if (acceptingMessages && clients.get(conn) == info && info.isActive()) {
                lastPongTime.replace(conn, System.currentTimeMillis());
            }
        }
    }

    private static String topLevelType(JsonObject message) {
        JsonElement type = message.get("type");
        return isJsonString(type) ? type.getAsString() : "unknown";
    }

    private static boolean requiresBoundedQueue(String type) {
        return switch (type) {
            case "batch_update", "audio", "dj_audio_frame", "audio_frame",
                "bitmap_frame", "audio_state", "voice_audio" -> true;
            default -> false;
        };
    }

    private static boolean isLegacyAsyncType(String type) {
        return switch (type) {
            case "bitmap_frame", "audio_state", "voice_audio" -> true;
            default -> false;
        };
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

        for (Map.Entry<WebSocket, ClientInfo> entry : clients.entrySet()) {
            WebSocket conn = entry.getKey();
            try {
                if (sendToActiveClient(conn, entry.getValue(), json)) {
                    successCount++;
                }
            } catch (Exception e) {
                totalSendFailures.incrementAndGet();
                failedClients.add(conn);
            }
        }

        for (WebSocket conn : failedClients) {
            try { conn.close(1000, "Send failure"); } catch (Exception ignored) {}
        }

        return successCount;
    }

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

        for (WebSocket conn : new ArrayList<>(clients.keySet())) {
            try { conn.close(1001, "Server shutting down"); } catch (Exception ignored) {}
        }

        try {
            stop(3000);
        } catch (InterruptedException e) {
            AudioVizMod.LOGGER.warn("WebSocket server shutdown interrupted");
            Thread.currentThread().interrupt();
        } finally {
            messageQueue.stop();
        }
    }

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
        final long authDeadlineTick;
        private volatile ClientState state;

        ClientInfo(String address, long authDeadlineTick) {
            this.address = address;
            this.connectedAt = System.currentTimeMillis();
            this.authDeadlineTick = authDeadlineTick;
            this.state = ClientState.PENDING;
        }

        synchronized boolean beginAuthentication() {
            if (state != ClientState.PENDING) {
                return false;
            }
            state = ClientState.AUTHENTICATING;
            return true;
        }

        synchronized boolean beginAdmission() {
            if (state != ClientState.AUTHENTICATING) {
                return false;
            }
            state = ClientState.ADMITTING;
            return true;
        }

        /**
         * Called only while holding the global lifecycle lock. Once a client is
         * ADMITTING, every competing close transition also requires that lock.
         */
        boolean activateIfAdmitting() {
            if (state != ClientState.ADMITTING) {
                return false;
            }
            state = ClientState.ACTIVE;
            return true;
        }

        synchronized boolean closeIfInactive() {
            if (state == ClientState.ACTIVE || state == ClientState.CLOSED) {
                return false;
            }
            state = ClientState.CLOSED;
            return true;
        }

        synchronized boolean closeAndWasActive() {
            boolean wasActive = state == ClientState.ACTIVE;
            state = ClientState.CLOSED;
            return wasActive;
        }

        boolean isPending() {
            return state == ClientState.PENDING;
        }

        boolean isAdmitting() {
            return state == ClientState.ADMITTING;
        }

        boolean isActive() {
            return state == ClientState.ACTIVE;
        }
    }

    private enum ClientState {
        PENDING,
        AUTHENTICATING,
        ADMITTING,
        ACTIVE,
        CLOSED
    }
}
