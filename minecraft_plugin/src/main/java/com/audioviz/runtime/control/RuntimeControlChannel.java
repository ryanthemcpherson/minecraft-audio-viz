package com.audioviz.runtime.control;

import com.audioviz.runtime.supervisor.RuntimeHealth;
import com.audioviz.runtime.supervisor.RuntimeReady;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

public final class RuntimeControlChannel {
    private final Consumer<RuntimeReady> readinessSink;
    private final Consumer<RuntimeHealth> healthSink;
    private final LongConsumer disconnectSink;

    private ExpectedRuntime expected;
    private Object connection;
    private Sender sender;
    private boolean readinessDelivered;
    private long lastHealthSequence = -1;

    public RuntimeControlChannel(
        Consumer<RuntimeReady> readinessSink,
        Consumer<RuntimeHealth> healthSink,
        LongConsumer disconnectSink
    ) {
        this.readinessSink = Objects.requireNonNull(readinessSink, "readinessSink");
        this.healthSink = Objects.requireNonNull(healthSink, "healthSink");
        this.disconnectSink = Objects.requireNonNull(disconnectSink, "disconnectSink");
    }

    public synchronized void expect(ExpectedRuntime next) {
        expected = Objects.requireNonNull(next, "next");
        connection = null;
        sender = null;
        readinessDelivered = false;
        lastHealthSequence = -1;
    }

    public synchronized void clear(long generation) {
        if (expected == null || expected.generation() != generation) {
            return;
        }
        expected = null;
        connection = null;
        sender = null;
        readinessDelivered = false;
        lastHealthSequence = -1;
    }

    boolean acceptReady(
        Object source,
        Sender outbound,
        RuntimeReady ready
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(outbound, "outbound");
        Objects.requireNonNull(ready, "ready");
        synchronized (this) {
            if (
                expected == null ||
                readinessDelivered ||
                (connection != null && connection != source) ||
                ready.generation() != expected.generation() ||
                ready.runtimeApi() != expected.runtimeApi() ||
                !ready.releaseVersion().equals(expected.releaseVersion()) ||
                !secretsEqual(ready.launchNonce(), expected.launchNonce())
            ) {
                return false;
            }
            connection = source;
            sender = outbound;
            readinessDelivered = true;
            lastHealthSequence = -1;
        }
        try {
            readinessSink.accept(ready);
            return true;
        } catch (RuntimeException error) {
            rollbackFailedReadiness(source, outbound, ready.generation());
            return false;
        }
    }

    boolean acceptHealth(Object source, RuntimeHealth health) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(health, "health");
        synchronized (this) {
            if (
                expected == null ||
                !readinessDelivered ||
                connection != source ||
                health.generation() != expected.generation() ||
                health.sequence() <= lastHealthSequence ||
                !secretsEqual(health.launchNonce(), expected.launchNonce())
            ) {
                return false;
            }
            lastHealthSequence = health.sequence();
        }
        try {
            healthSink.accept(health);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private void rollbackFailedReadiness(
        Object failedConnection,
        Sender failedSender,
        long generation
    ) {
        synchronized (this) {
            if (
                expected != null &&
                expected.generation() == generation &&
                connection == failedConnection &&
                sender == failedSender &&
                readinessDelivered
            ) {
                connection = null;
                sender = null;
                readinessDelivered = false;
                lastHealthSequence = -1;
            }
        }
    }

    public void disconnected(Object source) {
        long disconnectedGeneration;
        synchronized (this) {
            if (expected == null || connection != source) {
                return;
            }
            disconnectedGeneration = expected.generation();
            connection = null;
            sender = null;
            readinessDelivered = false;
            lastHealthSequence = -1;
        }
        notifyDisconnected(disconnectedGeneration);
    }

    public synchronized boolean connected() {
        return connection != null && readinessDelivered;
    }

    public boolean sendShutdown(long generation, String reasonCode) {
        String reason = reasonCode(reasonCode);
        JsonObject message = new JsonObject();
        message.addProperty("type", "runtime_shutdown");
        message.addProperty("generation", generation);
        message.addProperty("reason", reason);
        return send(generation, message.toString());
    }

    public boolean sendPerformance(RuntimePerformanceInstruction instruction) {
        RuntimePerformanceInstruction value = Objects.requireNonNull(
            instruction,
            "instruction"
        );
        JsonObject message = new JsonObject();
        message.addProperty("type", "runtime_performance");
        message.addProperty("generation", value.generation());
        message.addProperty("level", value.level().name());
        message.addProperty("target_fps", value.targetFps());
        message.addProperty("entity_budget", value.entityBudget());
        message.addProperty("particles_enabled", value.particlesEnabled());
        return send(value.generation(), message.toString());
    }

    private boolean send(long generation, String payload) {
        Sender currentSender;
        Object currentConnection;
        synchronized (this) {
            if (
                expected == null ||
                expected.generation() != generation ||
                connection == null ||
                !readinessDelivered ||
                sender == null
            ) {
                return false;
            }
            currentSender = sender;
            currentConnection = connection;
        }
        boolean sent;
        try {
            sent = currentSender.send(payload);
        } catch (RuntimeException error) {
            sent = false;
        }
        if (!sent) {
            sendFailed(generation, currentConnection, currentSender);
        }
        return sent;
    }

    private void sendFailed(
        long generation,
        Object failedConnection,
        Sender failedSender
    ) {
        boolean notify = false;
        synchronized (this) {
            if (
                expected != null &&
                expected.generation() == generation &&
                connection == failedConnection &&
                sender == failedSender
            ) {
                connection = null;
                sender = null;
                readinessDelivered = false;
                lastHealthSequence = -1;
                notify = true;
            }
        }
        if (notify) {
            notifyDisconnected(generation);
        }
    }

    private void notifyDisconnected(long generation) {
        try {
            disconnectSink.accept(generation);
        } catch (RuntimeException ignored) {
            // Lifecycle notification failures cannot escape the WebSocket selector.
        }
    }

    private static boolean secretsEqual(String left, String right) {
        return MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String reasonCode(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("invalid runtime control reason");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < 'A' || character > 'Z') && character != '_') {
                throw new IllegalArgumentException("invalid runtime control reason");
            }
        }
        return value;
    }

    @FunctionalInterface
    public interface Sender {
        boolean send(String payload);
    }

    public record ExpectedRuntime(
        long generation,
        String launchNonce,
        String releaseVersion,
        int runtimeApi
    ) {
        public ExpectedRuntime {
            if (
                generation <= 0 || runtimeApi <= 0 ||
                !RuntimeReady.isValidLaunchNonce(launchNonce) ||
                releaseVersion == null || releaseVersion.isBlank() ||
                releaseVersion.length() > 64
            ) {
                throw new IllegalArgumentException("invalid expected runtime");
            }
        }

        @Override
        public String toString() {
            return "ExpectedRuntime[generation=" + generation +
                ", releaseVersion=" + releaseVersion +
                ", runtimeApi=" + runtimeApi + "]";
        }
    }

    public enum RuntimePerformanceLevel {
        NORMAL,
        PREVIEW_REDUCED,
        FPS_REDUCED,
        SAFE
    }

    public record RuntimePerformanceInstruction(
        long generation,
        RuntimePerformanceLevel level,
        int targetFps,
        int entityBudget,
        boolean particlesEnabled
    ) {
        public RuntimePerformanceInstruction {
            Objects.requireNonNull(level, "level");
            if (
                generation <= 0 || targetFps <= 0 || targetFps > 240 ||
                entityBudget <= 0 || entityBudget > 10_000
            ) {
                throw new IllegalArgumentException("invalid runtime performance instruction");
            }
        }
    }
}
