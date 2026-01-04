package com.audioviz.websocket;

import com.audioviz.AudioVizPlugin;
import com.audioviz.protocol.MessageHandler;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * WebSocket server for receiving visualization commands from Python.
 */
public class VizWebSocketServer extends WebSocketServer {

    private final AudioVizPlugin plugin;
    private final MessageHandler messageHandler;
    private final Gson gson;
    private final ConcurrentHashMap<WebSocket, ClientInfo> clients;

    public VizWebSocketServer(AudioVizPlugin plugin, int port) {
        super(new InetSocketAddress("0.0.0.0", port));  // Bind to all interfaces
        this.plugin = plugin;
        this.messageHandler = new MessageHandler(plugin);
        this.gson = new Gson();
        this.clients = new ConcurrentHashMap<>();

        // Set connection timeout
        setConnectionLostTimeout(30);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        ClientInfo info = new ClientInfo(conn.getRemoteSocketAddress().toString());
        clients.put(conn, info);

        plugin.getLogger().info("WebSocket client connected: " + info.address);

        // Send welcome message
        JsonObject welcome = new JsonObject();
        welcome.addProperty("type", "connected");
        welcome.addProperty("message", "Connected to AudioViz server");
        welcome.addProperty("version", plugin.getDescription().getVersion());
        conn.send(gson.toJson(welcome));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ClientInfo info = clients.remove(conn);
        String address = info != null ? info.address : "unknown";
        plugin.getLogger().info("WebSocket client disconnected: " + address + " (code: " + code + ")");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : "unknown";

            // Process message
            JsonObject response = messageHandler.handleMessage(type, json);

            // Send response if any
            if (response != null) {
                conn.send(gson.toJson(response));
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error processing WebSocket message", e);

            // Send error response
            JsonObject error = new JsonObject();
            error.addProperty("type", "error");
            error.addProperty("message", e.getMessage());
            conn.send(gson.toJson(error));
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        String address = conn != null ? conn.getRemoteSocketAddress().toString() : "server";
        plugin.getLogger().log(Level.WARNING, "WebSocket error from " + address, ex);
    }

    @Override
    public void onStart() {
        plugin.getLogger().info("WebSocket server started successfully");
    }

    /**
     * Broadcast a message to all connected clients.
     */
    public void broadcast(JsonObject message) {
        String json = gson.toJson(message);
        for (WebSocket conn : clients.keySet()) {
            if (conn.isOpen()) {
                conn.send(json);
            }
        }
    }

    /**
     * Get number of connected clients.
     */
    public int getConnectionCount() {
        return clients.size();
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
