package com.audioviz.runtime.control;

import com.audioviz.runtime.supervisor.RuntimeHealth;
import com.audioviz.runtime.supervisor.RuntimeReady;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class RuntimeControlMessageHandler {
    public static final int MAX_CONTROL_MESSAGE_CHARS = 16_384;
    private static final String PROTOCOL_VERSION = "1.0.0";
    private static final int MAX_CAPABILITIES = 32;
    private static final int MAX_CAPABILITY_CHARS = 64;
    private static final Pattern INTEGER = Pattern.compile("0|[1-9][0-9]*");
    private static final Set<String> READY_FIELDS = Set.of(
        "type",
        "v",
        "release_version",
        "runtime_api",
        "generation",
        "launch_nonce",
        "pid",
        "capabilities"
    );
    private static final Set<String> READY_REQUIRED = Set.of(
        "type",
        "release_version",
        "runtime_api",
        "generation",
        "launch_nonce",
        "pid",
        "capabilities"
    );
    private static final Set<String> HEALTH_FIELDS = Set.of(
        "type",
        "v",
        "generation",
        "launch_nonce",
        "sequence",
        "process_alive",
        "renderer_connected",
        "ingress_healthy",
        "event_loop_healthy",
        "render_loop_healthy",
        "last_render_age_ms",
        "ingress_queue_depth",
        "render_queue_depth"
    );
    private static final Set<String> HEALTH_REQUIRED = Set.of(
        "type",
        "generation",
        "launch_nonce",
        "sequence",
        "process_alive",
        "renderer_connected",
        "ingress_healthy",
        "event_loop_healthy",
        "render_loop_healthy",
        "last_render_age_ms",
        "ingress_queue_depth",
        "render_queue_depth"
    );

    private final RuntimeControlChannel channel;

    public RuntimeControlMessageHandler(RuntimeControlChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    public HandleResult handle(
        Object connection,
        boolean authenticated,
        boolean browserOrigin,
        String message,
        RuntimeControlChannel.Sender sender
    ) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(sender, "sender");
        if (message.length() > MAX_CONTROL_MESSAGE_CHARS) {
            return containsControlType(message) ? HandleResult.REJECTED : HandleResult.NOT_CONTROL;
        }
        String type = detectedType(message);
        if (!isRuntimeControlType(type)) {
            return type == null && containsControlType(message)
                ? HandleResult.REJECTED
                : HandleResult.NOT_CONTROL;
        }
        if (!authenticated || browserOrigin) {
            return HandleResult.REJECTED;
        }
        try {
            boolean accepted = switch (type) {
                case "runtime_ready" -> channel.acceptReady(
                    connection,
                    sender,
                    readReady(message)
                );
                case "runtime_health" -> channel.acceptHealth(
                    connection,
                    readHealth(message)
                );
                default -> false;
            };
            return accepted ? HandleResult.ACCEPTED : HandleResult.REJECTED;
        } catch (IOException | RuntimeException error) {
            return HandleResult.REJECTED;
        }
    }

    public void disconnected(Object connection) {
        channel.disconnected(connection);
    }

    public static boolean isRuntimeControlType(String type) {
        return "runtime_ready".equals(type) || "runtime_health".equals(type);
    }

    public static boolean isRuntimeControlMessage(String message) {
        return message != null &&
            message.length() <= MAX_CONTROL_MESSAGE_CHARS &&
            isRuntimeControlType(detectedType(message));
    }

    private static boolean containsControlType(String message) {
        return message.contains("\"runtime_ready\"") ||
            message.contains("\"runtime_health\"");
    }

    private static String detectedType(String message) {
        try {
            JsonElement parsed = JsonParser.parseString(message);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonElement type = parsed.getAsJsonObject().get("type");
            if (
                type == null ||
                !type.isJsonPrimitive() ||
                !type.getAsJsonPrimitive().isString()
            ) {
                return null;
            }
            return type.getAsString();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static RuntimeReady readReady(String message) throws IOException {
        try (JsonReader reader = reader(message)) {
            reader.beginObject();
            Set<String> seen = new LinkedHashSet<>();
            String type = null;
            String version = null;
            String releaseVersion = null;
            int runtimeApi = -1;
            long generation = -1;
            String nonce = null;
            long pid = -1;
            Set<String> capabilities = null;
            while (reader.hasNext()) {
                String field = nextField(reader, seen, READY_FIELDS);
                switch (field) {
                    case "type" -> type = string(reader, 32);
                    case "v" -> version = string(reader, 16);
                    case "release_version" -> releaseVersion = string(reader, 64);
                    case "runtime_api" -> runtimeApi = exactInt(reader, 1, 1_000_000);
                    case "generation" -> generation = exactLong(reader, 1, Long.MAX_VALUE);
                    case "launch_nonce" -> nonce = string(reader, 128);
                    case "pid" -> pid = exactLong(reader, 1, Long.MAX_VALUE);
                    case "capabilities" -> capabilities = capabilities(reader);
                    default -> throw new IllegalArgumentException("unknown readiness field");
                }
            }
            reader.endObject();
            requireEnd(reader);
            requireFields(seen, READY_REQUIRED);
            requireTypeAndVersion(type, "runtime_ready", version);
            return new RuntimeReady(
                releaseVersion,
                runtimeApi,
                generation,
                nonce,
                pid,
                capabilities
            );
        }
    }

    private static RuntimeHealth readHealth(String message) throws IOException {
        try (JsonReader reader = reader(message)) {
            reader.beginObject();
            Set<String> seen = new LinkedHashSet<>();
            String type = null;
            String version = null;
            long generation = -1;
            String nonce = null;
            long sequence = -1;
            boolean processAlive = false;
            boolean rendererConnected = false;
            boolean ingressHealthy = false;
            boolean eventLoopHealthy = false;
            boolean renderLoopHealthy = false;
            long lastRenderAge = -1;
            int ingressQueue = -1;
            int renderQueue = -1;
            while (reader.hasNext()) {
                String field = nextField(reader, seen, HEALTH_FIELDS);
                switch (field) {
                    case "type" -> type = string(reader, 32);
                    case "v" -> version = string(reader, 16);
                    case "generation" -> generation = exactLong(reader, 1, Long.MAX_VALUE);
                    case "launch_nonce" -> nonce = string(reader, 128);
                    case "sequence" -> sequence = exactLong(reader, 0, Long.MAX_VALUE);
                    case "process_alive" -> processAlive = bool(reader);
                    case "renderer_connected" -> rendererConnected = bool(reader);
                    case "ingress_healthy" -> ingressHealthy = bool(reader);
                    case "event_loop_healthy" -> eventLoopHealthy = bool(reader);
                    case "render_loop_healthy" -> renderLoopHealthy = bool(reader);
                    case "last_render_age_ms" -> lastRenderAge = exactLong(reader, 0, 60_000);
                    case "ingress_queue_depth" -> ingressQueue = exactInt(reader, 0, 1_000_000);
                    case "render_queue_depth" -> renderQueue = exactInt(reader, 0, 1_000_000);
                    default -> throw new IllegalArgumentException("unknown health field");
                }
            }
            reader.endObject();
            requireEnd(reader);
            requireFields(seen, HEALTH_REQUIRED);
            requireTypeAndVersion(type, "runtime_health", version);
            return new RuntimeHealth(
                generation,
                nonce,
                sequence,
                processAlive,
                rendererConnected,
                ingressHealthy,
                eventLoopHealthy,
                renderLoopHealthy,
                lastRenderAge,
                ingressQueue,
                renderQueue
            );
        }
    }

    private static JsonReader reader(String message) {
        JsonReader reader = new JsonReader(new StringReader(message));
        reader.setStrictness(Strictness.STRICT);
        return reader;
    }

    private static String nextField(
        JsonReader reader,
        Set<String> seen,
        Set<String> allowed
    ) throws IOException {
        String field = reader.nextName();
        if (!seen.add(field) || !allowed.contains(field)) {
            throw new IllegalArgumentException("invalid runtime control field");
        }
        return field;
    }

    private static void requireFields(Set<String> seen, Set<String> required) {
        if (!seen.containsAll(required)) {
            throw new IllegalArgumentException("missing runtime control field");
        }
    }

    private static void requireTypeAndVersion(
        String actualType,
        String expectedType,
        String version
    ) {
        if (!expectedType.equals(actualType)) {
            throw new IllegalArgumentException("invalid runtime control type");
        }
        if (version != null && !PROTOCOL_VERSION.equals(version)) {
            throw new IllegalArgumentException("unsupported runtime control version");
        }
    }

    private static Set<String> capabilities(JsonReader reader) throws IOException {
        requireToken(reader, JsonToken.BEGIN_ARRAY);
        reader.beginArray();
        Set<String> values = new LinkedHashSet<>();
        int count = 0;
        while (reader.hasNext()) {
            if (++count > MAX_CAPABILITIES) {
                throw new IllegalArgumentException("too many runtime capabilities");
            }
            String capability = string(reader, MAX_CAPABILITY_CHARS);
            if (!values.add(capability)) {
                throw new IllegalArgumentException("duplicate runtime capability");
            }
        }
        reader.endArray();
        return Set.copyOf(values);
    }

    private static String string(JsonReader reader, int maximum) throws IOException {
        requireToken(reader, JsonToken.STRING);
        String value = reader.nextString();
        if (value.isEmpty() || value.length() > maximum) {
            throw new IllegalArgumentException("runtime control string out of bounds");
        }
        return value;
    }

    private static boolean bool(JsonReader reader) throws IOException {
        requireToken(reader, JsonToken.BOOLEAN);
        return reader.nextBoolean();
    }

    private static int exactInt(JsonReader reader, int minimum, int maximum)
        throws IOException {
        return Math.toIntExact(exactLong(reader, minimum, maximum));
    }

    private static long exactLong(JsonReader reader, long minimum, long maximum)
        throws IOException {
        requireToken(reader, JsonToken.NUMBER);
        String raw = reader.nextString();
        if (!INTEGER.matcher(raw).matches()) {
            throw new IllegalArgumentException("runtime control integer required");
        }
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("runtime control integer out of bounds", error);
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("runtime control integer out of bounds");
        }
        return value;
    }

    private static void requireToken(JsonReader reader, JsonToken token) throws IOException {
        if (reader.peek() != token) {
            throw new IllegalArgumentException("invalid runtime control JSON type");
        }
    }

    private static void requireEnd(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new IllegalArgumentException("trailing runtime control data");
        }
    }

    public enum HandleResult {
        NOT_CONTROL,
        ACCEPTED,
        REJECTED
    }
}
