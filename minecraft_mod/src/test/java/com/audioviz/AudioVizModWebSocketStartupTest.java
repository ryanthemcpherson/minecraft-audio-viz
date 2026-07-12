package com.audioviz;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioVizModWebSocketStartupTest {

    @Test
    void unsafeRemoteSecretlessConfigSkipsListenerWithoutAbortingInitialization() {
        AtomicInteger listenerStarts = new AtomicInteger();
        AtomicInteger unsafeConfigReports = new AtomicInteger();
        AtomicBoolean initializationContinued = new AtomicBoolean();

        assertDoesNotThrow(() -> {
            boolean listenerStarted = AudioVizMod.startWebSocketListenerIfSafe(
                "0.0.0.0",
                "  \t ",
                listenerStarts::incrementAndGet,
                unsafeConfigReports::incrementAndGet
            );
            assertFalse(listenerStarted);
            initializationContinued.set(true);
        });

        assertEquals(0, listenerStarts.get());
        assertEquals(1, unsafeConfigReports.get());
        assertTrue(initializationContinued.get());
    }
}
