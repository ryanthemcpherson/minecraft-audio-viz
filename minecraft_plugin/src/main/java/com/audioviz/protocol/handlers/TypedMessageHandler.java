package com.audioviz.protocol.handlers;

import com.google.gson.JsonObject;

/**
 * Interface for typed WebSocket message handlers.
 * Each implementation handles a group of related message types.
 */
public interface TypedMessageHandler {

    /**
     * Handle an incoming WebSocket message.
     *
     * @param type    the message type string (already extracted by the router)
     * @param message the parsed JSON message (may or may not contain a "type" field)
     * @return a JSON response to send back to the client
     */
    JsonObject handle(String type, JsonObject message);

    /**
     * Return the message type strings this handler supports.
     * Used by the router to build the dispatch map.
     *
     * @return array of message type strings (e.g., "ping", "get_zones")
     */
    String[] getMessageTypes();
}
