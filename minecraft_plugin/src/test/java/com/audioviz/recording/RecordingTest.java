package com.audioviz.recording;

import org.junit.jupiter.api.*;
import java.io.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RecordingTest {

    @Test
    @DisplayName("empty recording has zero duration")
    void emptyDuration() {
        var rec = new Recording("test", Map.of("zone1", "bmp_fire"), 20);
        assertEquals(0, rec.getDurationTicks());
        assertEquals(0.0, rec.getDurationSeconds(), 0.001);
    }

    @Test
    @DisplayName("duration calculated from frame count and tick rate")
    void duration() {
        var rec = new Recording("test", Map.of(), 20);
        for (int i = 0; i < 100; i++) {
            rec.addFrame(RecordingFrame.silent(i));
        }
        assertEquals(100, rec.getDurationTicks());
        assertEquals(5.0, rec.getDurationSeconds(), 0.001);
    }

    @Test
    @DisplayName("binary round-trip preserves all data")
    void binaryRoundTrip() throws IOException {
        var rec = new Recording("my_recording", Map.of("zone1", "bmp_plasma"), 20);
        rec.addFrame(new RecordingFrame(
            new double[]{0.8, 0.6, 0.4, 0.2, 0.1}, 0.75, true, 0.9, 0.85, 0.5, 0));
        rec.addFrame(RecordingFrame.silent(1));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rec.writeTo(out);

        Recording restored = Recording.readFrom(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("my_recording", restored.getName());
        assertEquals(20, restored.getTickRate());
        assertEquals(2, restored.getDurationTicks());
        assertEquals("bmp_plasma", restored.getZonePatterns().get("zone1"));

        var frame0 = restored.getFrame(0);
        assertEquals(0.8, frame0.bands()[0], 0.001);
        assertTrue(frame0.isBeat());
    }

    @Test
    @DisplayName("getFrame returns correct frame by index")
    void getFrame() {
        var rec = new Recording("test", Map.of(), 20);
        rec.addFrame(RecordingFrame.silent(0));
        rec.addFrame(new RecordingFrame(new double[5], 0.5, false, 0, 0, 0, 1));
        assertEquals(0.5, rec.getFrame(1).amplitude(), 0.001);
    }

    @Test
    @DisplayName("getFrame out of bounds returns silent")
    void getFrameOutOfBounds() {
        var rec = new Recording("test", Map.of(), 20);
        var frame = rec.getFrame(999);
        assertEquals(0.0, frame.amplitude());
    }
}
