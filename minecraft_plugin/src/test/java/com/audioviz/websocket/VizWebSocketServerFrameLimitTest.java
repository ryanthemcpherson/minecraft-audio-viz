package com.audioviz.websocket;

import com.audioviz.AudioVizPlugin;
import com.audioviz.protocol.MessageHandler;
import com.audioviz.protocol.MessageQueue;
import org.bukkit.configuration.file.FileConfiguration;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.Opcode;
import org.java_websocket.enums.Role;
import org.java_websocket.exceptions.LimitExceededException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VizWebSocketServerFrameLimitTest {

    private static final int MAX_INCOMING_MESSAGE_BYTES = 262_144;

    @Test
    void configuresOnlyDraft6455WithTheTransportLimit() {
        ProbeServer server = newProbeServer();

        List<Draft> drafts = server.getDraft();

        assertEquals(1, drafts.size());
        Draft_6455 draft = assertInstanceOf(Draft_6455.class, drafts.getFirst());
        assertEquals(MAX_INCOMING_MESSAGE_BYTES, draft.getMaxFrameSize());
    }

    @ParameterizedTest
    @EnumSource(value = Opcode.class, names = {"TEXT", "BINARY"})
    void configuredDraftRejectsOversizedPayloadHeaderWith1009(Opcode opcode) {
        Draft_6455 draft = configuredDraft(newProbeServer());
        draft.setParseMode(Role.SERVER);

        LimitExceededException exception = assertThrows(
            LimitExceededException.class,
            () -> draft.translateFrame(oversizedClientFrameHeader(opcode))
        );

        assertEquals(CloseFrame.TOOBIG, exception.getCloseCode());
        assertEquals(MAX_INCOMING_MESSAGE_BYTES, exception.getLimit());
    }

    @ParameterizedTest
    @EnumSource(PayloadShape.class)
    void liveTransportClosesOversizedMessagesBeforeApplicationCallback(PayloadShape payloadShape)
        throws Exception {
        ProbeServer server = newProbeServer();
        ProbeClient client = null;
        server.start();

        try {
            assertTrue(server.started.await(5, TimeUnit.SECONDS));
            client = new ProbeClient(new URI("ws://127.0.0.1:" + server.getPort()));
            assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));

            payloadShape.send(client);

            assertTrue(server.closed.await(5, TimeUnit.SECONDS));
            assertEquals(CloseFrame.TOOBIG, server.closeCode.get());
            assertTrue(client.closed.await(5, TimeUnit.SECONDS));
            int clientCloseCode = client.closeCode.get();
            assertTrue(
                clientCloseCode == CloseFrame.TOOBIG
                    || clientCloseCode == CloseFrame.ABNORMAL_CLOSE,
                "Client must observe the 1009 close frame or the equivalent TCP-reset 1006"
            );
            assertEquals(0, server.textCallbacks.get());
            assertEquals(0, server.binaryCallbacks.get());
        } finally {
            if (client != null && !client.isClosed()) {
                client.closeBlocking();
            }
            server.stop(1_000);
        }
    }

    private static Draft_6455 configuredDraft(ProbeServer server) {
        return assertInstanceOf(Draft_6455.class, server.getDraft().getFirst());
    }

    private static ByteBuffer oversizedClientFrameHeader(Opcode opcode) {
        int wireOpcode = opcode == Opcode.TEXT ? 0x1 : 0x2;
        ByteBuffer header = ByteBuffer.allocate(10);
        header.put((byte) (0x80 | wireOpcode));
        header.put((byte) (0x80 | 0x7f));
        header.putLong(MAX_INCOMING_MESSAGE_BYTES + 1L);
        header.flip();
        return header;
    }

    private static ProbeServer newProbeServer() {
        AudioVizPlugin plugin = mock(AudioVizPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getString("websocket.address", "127.0.0.1"))
            .thenReturn("127.0.0.1");
        return new ProbeServer(plugin, mock(MessageHandler.class), mock(MessageQueue.class));
    }

    private enum PayloadShape {
        OVERSIZED_TEXT {
            @Override
            void send(WebSocketClient client) {
                client.send("x".repeat(MAX_INCOMING_MESSAGE_BYTES + 1));
            }
        },
        OVERSIZED_BINARY {
            @Override
            void send(WebSocketClient client) {
                client.send(new byte[MAX_INCOMING_MESSAGE_BYTES + 1]);
            }
        },
        FRAGMENTED_TEXT {
            @Override
            void send(WebSocketClient client) {
                sendFragments(client, Opcode.TEXT);
            }
        },
        FRAGMENTED_BINARY {
            @Override
            void send(WebSocketClient client) {
                sendFragments(client, Opcode.BINARY);
            }
        };

        abstract void send(WebSocketClient client);

        private static void sendFragments(WebSocketClient client, Opcode opcode) {
            int firstFragmentSize = MAX_INCOMING_MESSAGE_BYTES / 2;
            client.sendFragmentedFrame(
                opcode,
                ByteBuffer.wrap(new byte[firstFragmentSize]),
                false
            );
            client.sendFragmentedFrame(
                opcode,
                ByteBuffer.wrap(new byte[MAX_INCOMING_MESSAGE_BYTES - firstFragmentSize + 1]),
                true
            );
        }
    }

    private static final class ProbeServer extends VizWebSocketServer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closeCode = new AtomicInteger(Integer.MIN_VALUE);
        private final AtomicInteger textCallbacks = new AtomicInteger();
        private final AtomicInteger binaryCallbacks = new AtomicInteger();

        private ProbeServer(
            AudioVizPlugin plugin,
            MessageHandler messageHandler,
            MessageQueue messageQueue
        ) {
            super(
                plugin,
                "127.0.0.1",
                0,
                messageHandler,
                messageQueue,
                new WebSocketSecurityPolicy(""),
                ignored -> { }
            );
        }

        @Override
        public void onStart() {
            started.countDown();
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) { }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) {
            closeCode.set(code);
            closed.countDown();
        }

        @Override
        public void onMessage(WebSocket connection, String message) {
            textCallbacks.incrementAndGet();
        }

        @Override
        public void onMessage(WebSocket connection, ByteBuffer message) {
            binaryCallbacks.incrementAndGet();
        }

        @Override
        public void onError(WebSocket connection, Exception exception) { }
    }

    private static final class ProbeClient extends WebSocketClient {
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closeCode = new AtomicInteger(Integer.MIN_VALUE);

        private ProbeClient(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) { }

        @Override
        public void onMessage(String message) { }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closeCode.set(code);
            closed.countDown();
        }

        @Override
        public void onError(Exception exception) { }
    }
}
