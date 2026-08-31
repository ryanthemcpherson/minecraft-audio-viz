package com.audioviz.runtime.control;

import static com.audioviz.runtime.control.RuntimeControlMessageHandler.HandleResult.ACCEPTED;
import static com.audioviz.runtime.control.RuntimeControlMessageHandler.HandleResult.NOT_CONTROL;
import static com.audioviz.runtime.control.RuntimeControlMessageHandler.HandleResult.REJECTED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.audioviz.runtime.control.RuntimeControlChannel.ExpectedRuntime;
import com.audioviz.runtime.control.RuntimeControlChannel.RuntimePerformanceInstruction;
import com.audioviz.runtime.control.RuntimeControlChannel.RuntimePerformanceLevel;
import com.audioviz.runtime.supervisor.RuntimeHealth;
import com.audioviz.runtime.supervisor.RuntimeReady;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuntimeControlMessageHandlerTest {
    private static final String NONCE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final long GENERATION = 42;
    private final Object connection = new Object();
    private final Object otherConnection = new Object();
    private final List<RuntimeReady> readySignals = new ArrayList<>();
    private final List<RuntimeHealth> healthSignals = new ArrayList<>();
    private final List<Long> disconnects = new ArrayList<>();
    private final List<String> sent = new ArrayList<>();
    private RuntimeControlChannel channel;
    private RuntimeControlMessageHandler handler;

    @BeforeEach
    void setUp() {
        channel = new RuntimeControlChannel(
            readySignals::add,
            healthSignals::add,
            disconnects::add
        );
        channel.expect(new ExpectedRuntime(GENERATION, NONCE, "1.2.0", 1));
        handler = new RuntimeControlMessageHandler(channel);
    }

    @Test
    void validReadyBindsConnectionAndRoutesHealth() {
        assertEquals(ACCEPTED, handle(connection, readyJson()));
        assertEquals(ACCEPTED, handle(connection, healthJson(1)));

        assertEquals(1, readySignals.size());
        assertEquals(1, healthSignals.size());
        assertEquals(GENERATION, readySignals.getFirst().generation());
        assertEquals(1, healthSignals.getFirst().sequence());
        assertTrue(channel.connected());
    }

    @Test
    void unauthenticatedAndBrowserOriginMessagesNeverReachSupervisor() {
        assertEquals(REJECTED, handler.handle(connection, false, false, readyJson(), this::send));
        assertEquals(REJECTED, handler.handle(connection, true, true, readyJson(), this::send));

        assertTrue(readySignals.isEmpty());
        assertFalse(channel.connected());
    }

    @Test
    void readinessMustMatchExpectedNonceGenerationApiAndVersion() {
        assertEquals(REJECTED, handle(connection, readyJson().replace(NONCE, "B".repeat(43))));
        assertEquals(REJECTED, handle(connection, readyJson().replace(":42", ":41")));
        assertEquals(REJECTED, handle(connection, readyJson().replace("\"runtime_api\":1", "\"runtime_api\":2")));
        assertEquals(REJECTED, handle(connection, readyJson().replace("1.2.0", "1.1.0")));

        assertTrue(readySignals.isEmpty());
    }

    @Test
    void onlyBoundConnectionCanSendHealthOrRepeatReadiness() {
        assertEquals(ACCEPTED, handle(connection, readyJson()));
        assertEquals(REJECTED, handle(otherConnection, healthJson(1)));
        assertEquals(REJECTED, handle(otherConnection, readyJson()));
        assertEquals(REJECTED, handle(connection, readyJson()));

        assertTrue(healthSignals.isEmpty());
        assertEquals(1, readySignals.size());
    }

    @Test
    void duplicateUnknownMissingAndInvalidFieldsAreRejectedStrictly() {
        List<String> invalid = List.of(
            readyJson().replace("\"pid\":1234", "\"pid\":1234,\"pid\":1234"),
            readyJson().replace("\"pid\":1234", "\"pid\":1234,\"surprise\":true"),
            readyJson().replace(",\"pid\":1234", ""),
            readyJson().replace("\"pid\":1234", "\"pid\":1.5"),
            readyJson().replace("unified-ingress.v1", "invalid capability"),
            readyJson().replace("\"v\":\"1.0.0\"", "\"v\":\"2.0.0\""),
            readyJson() + "{}"
        );

        for (String message : invalid) {
            assertEquals(REJECTED, handle(connection, message));
        }
        assertTrue(readySignals.isEmpty());
    }

    @Test
    void oversizedControlMessageIsRejectedBeforeParsing() {
        String oversized = readyJson().replace(
            "unified-ingress.v1",
            "x".repeat(RuntimeControlMessageHandler.MAX_CONTROL_MESSAGE_CHARS)
        );

        assertEquals(REJECTED, handle(connection, oversized));
        assertTrue(readySignals.isEmpty());
    }

    @Test
    void replayedAndInvalidHealthNeverReachSupervisor() {
        assertEquals(ACCEPTED, handle(connection, readyJson()));
        assertEquals(ACCEPTED, handle(connection, healthJson(5)));
        assertEquals(REJECTED, handle(connection, healthJson(5)));
        assertEquals(REJECTED, handle(connection, healthJson(4)));
        assertEquals(REJECTED, handle(connection, healthJson(6).replace(NONCE, "B".repeat(43))));
        assertEquals(REJECTED, handle(connection, healthJson(6).replace("\"ingress_queue_depth\":0", "\"ingress_queue_depth\":1000001")));

        assertEquals(1, healthSignals.size());
        assertEquals(5, healthSignals.getFirst().sequence());
    }

    @Test
    void nonControlMessagesRemainAvailableToNormalRouting() {
        assertEquals(
            NOT_CONTROL,
            handler.handle(
                connection,
                true,
                false,
                "{\"type\":\"batch_update\",\"entities\":[]}",
                this::send
            )
        );
        assertEquals(
            NOT_CONTROL,
            handler.handle(
                connection,
                true,
                false,
                "{\"metadata\":{\"type\":\"runtime_ready\"}," +
                    "\"type\":\"batch_update\",\"entities\":[]}",
                this::send
            )
        );
    }

    @Test
    void outboundShutdownAndPerformanceUseOnlyCurrentAuthenticatedConnection() {
        assertFalse(channel.sendShutdown(GENERATION, "PLUGIN_DISABLE"));
        assertEquals(ACCEPTED, handle(connection, readyJson()));

        assertTrue(channel.sendShutdown(GENERATION, "PLUGIN_DISABLE"));
        assertTrue(channel.sendPerformance(new RuntimePerformanceInstruction(
            GENERATION,
            RuntimePerformanceLevel.SAFE,
            10,
            80,
            false
        )));

        JsonObject shutdown = JsonParser.parseString(sent.get(0)).getAsJsonObject();
        assertEquals("runtime_shutdown", shutdown.get("type").getAsString());
        assertEquals(3, shutdown.size());
        assertEquals("PLUGIN_DISABLE", shutdown.get("reason").getAsString());
        JsonObject performance = JsonParser.parseString(sent.get(1)).getAsJsonObject();
        assertEquals("runtime_performance", performance.get("type").getAsString());
        assertEquals("SAFE", performance.get("level").getAsString());
        assertEquals(10, performance.get("target_fps").getAsInt());
        assertEquals(80, performance.get("entity_budget").getAsInt());
        assertFalse(performance.get("particles_enabled").getAsBoolean());
    }

    @Test
    void disconnectAndSendFailureNotifySupervisorOnlyOnce() {
        assertEquals(ACCEPTED, handle(connection, readyJson()));
        channel.disconnected(otherConnection);
        assertTrue(disconnects.isEmpty());
        channel.disconnected(connection);
        channel.disconnected(connection);
        assertEquals(List.of(GENERATION), disconnects);

        channel.expect(new ExpectedRuntime(GENERATION + 1, NONCE, "1.2.0", 1));
        String nextReady = readyJson().replace(":42", ":43");
        assertEquals(
            ACCEPTED,
            handler.handle(otherConnection, true, false, nextReady, message -> {
                throw new IllegalStateException("send failed with secret " + NONCE);
            })
        );
        assertDoesNotThrow(() -> channel.sendShutdown(GENERATION + 1, "PLUGIN_DISABLE"));
        assertEquals(List.of(GENERATION, GENERATION + 1), disconnects);
    }

    @Test
    void replacingExpectationInvalidatesOldConnectionAndSignals() {
        assertEquals(ACCEPTED, handle(connection, readyJson()));
        channel.expect(new ExpectedRuntime(GENERATION + 1, "B".repeat(43), "1.2.1", 1));

        assertFalse(channel.connected());
        assertEquals(REJECTED, handle(connection, healthJson(2)));
        assertFalse(channel.sendShutdown(GENERATION, "PLUGIN_DISABLE"));
    }

    @Test
    void launchNonceContractIsExactAndSecretSafe() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExpectedRuntime(GENERATION, "A".repeat(42), "1.2.0", 1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExpectedRuntime(GENERATION, "A".repeat(44), "1.2.0", 1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExpectedRuntime(GENERATION, "A".repeat(42) + "=", "1.2.0", 1)
        );
        assertFalse(channel.toString().contains(NONCE));
        assertFalse(new ExpectedRuntime(GENERATION, NONCE, "1.2.0", 1).toString()
            .contains(NONCE));
    }

    private RuntimeControlMessageHandler.HandleResult handle(Object source, String message) {
        return handler.handle(source, true, false, message, this::send);
    }

    private boolean send(String message) {
        sent.add(message);
        return true;
    }

    private static String readyJson() {
        return "{" +
            "\"type\":\"runtime_ready\"," +
            "\"v\":\"1.0.0\"," +
            "\"release_version\":\"1.2.0\"," +
            "\"runtime_api\":1," +
            "\"generation\":42," +
            "\"launch_nonce\":\"" + NONCE + "\"," +
            "\"pid\":1234," +
            "\"capabilities\":[\"unified-ingress.v1\",\"sbe.v1\"]" +
            "}";
    }

    private static String healthJson(long sequence) {
        return "{" +
            "\"type\":\"runtime_health\"," +
            "\"v\":\"1.0.0\"," +
            "\"generation\":42," +
            "\"launch_nonce\":\"" + NONCE + "\"," +
            "\"sequence\":" + sequence + "," +
            "\"process_alive\":true," +
            "\"renderer_connected\":true," +
            "\"ingress_healthy\":true," +
            "\"event_loop_healthy\":true," +
            "\"render_loop_healthy\":true," +
            "\"last_render_age_ms\":10," +
            "\"ingress_queue_depth\":0," +
            "\"render_queue_depth\":0" +
            "}";
    }
}
