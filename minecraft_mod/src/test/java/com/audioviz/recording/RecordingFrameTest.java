package com.audioviz.recording;

import org.junit.jupiter.api.*;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class RecordingFrameTest {

    @Test
    @DisplayName("round-trip serialization preserves all fields")
    void roundTrip() {
        double[] bands = {0.8, 0.6, 0.4, 0.2, 0.1};
        var frame = new RecordingFrame(bands, 0.75, true, 0.9, 0.85, 0.5, 42);

        byte[] bytes = frame.toBytes();
        assertEquals(RecordingFrame.BYTE_SIZE, bytes.length);

        RecordingFrame restored = RecordingFrame.fromBytes(bytes);
        assertArrayEquals(bands, restored.bands(), 0.001);
        assertEquals(0.75, restored.amplitude(), 0.001);
        assertTrue(restored.isBeat());
        assertEquals(0.9, restored.beatIntensity(), 0.001);
        assertEquals(0.85, restored.tempoConfidence(), 0.001);
        assertEquals(0.5, restored.beatPhase(), 0.001);
        assertEquals(42, restored.tickIndex());
    }

    @Test
    @DisplayName("silent frame serializes correctly")
    void silentFrame() {
        var frame = RecordingFrame.silent(0);
        byte[] bytes = frame.toBytes();
        RecordingFrame restored = RecordingFrame.fromBytes(bytes);
        assertEquals(0.0, restored.amplitude());
        assertFalse(restored.isBeat());
        assertEquals(0, restored.tickIndex());
    }

    @Test
    @DisplayName("BYTE_SIZE matches actual serialized size")
    void byteSizeConstant() {
        var frame = new RecordingFrame(new double[5], 0, false, 0, 0, 0, 0);
        assertEquals(RecordingFrame.BYTE_SIZE, frame.toBytes().length);
    }

    @Test
    @DisplayName("fromValues captures all fields")
    void fromValues() {
        double[] bands = {0.1, 0.2, 0.3, 0.4, 0.5};
        var frame = RecordingFrame.fromValues(bands, 0.6, true, 0.7, 0.8, 0.9, 100);
        assertEquals(0.6, frame.amplitude(), 0.001);
        assertTrue(frame.isBeat());
        assertEquals(100, frame.tickIndex());
    }
}
