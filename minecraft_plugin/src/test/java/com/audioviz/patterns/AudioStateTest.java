package com.audioviz.patterns;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AudioState - pure data class, no Bukkit dependencies.
 */
class AudioStateTest {

    @Test
    @DisplayName("silent() creates zero-valued state")
    void silentCreatesZeroState() {
        AudioState state = AudioState.silent();

        assertEquals(0.0, state.getAmplitude());
        assertFalse(state.isBeat());
        assertEquals(0.0, state.getBeatIntensity());
        assertEquals(0, state.getFrame());
        assertEquals(5, state.getBands().length);

        for (int i = 0; i < 5; i++) {
            assertEquals(0.0, state.getBand(i), "Band " + i + " should be 0");
        }
    }

    @Test
    @DisplayName("forTest() creates state with non-zero bands")
    void forTestCreatesNonZeroState() {
        AudioState state = AudioState.forTest(100, true);

        assertEquals(100, state.getFrame());
        assertTrue(state.isBeat());
        assertEquals(0.8, state.getBeatIntensity());
        assertEquals(5, state.getBands().length);

        // At least some bands should be non-zero
        boolean anyNonZero = false;
        for (int i = 0; i < 5; i++) {
            if (state.getBand(i) != 0.0) anyNonZero = true;
        }
        assertTrue(anyNonZero, "At least one band should be non-zero in test state");
    }

    @Test
    @DisplayName("forTest() without beat has zero intensity")
    void forTestNoBeatZeroIntensity() {
        AudioState state = AudioState.forTest(50, false);

        assertFalse(state.isBeat());
        assertEquals(0.0, state.getBeatIntensity());
    }

    @Test
    @DisplayName("Band accessors return correct indices")
    void bandAccessorsCorrectIndices() {
        double[] bands = {0.1, 0.2, 0.3, 0.4, 0.5};
        AudioState state = new AudioState(bands, 0.5, false, 0.0, 0);

        assertEquals(0.1, state.getBass(), 1e-10);
        assertEquals(0.3, state.getMid(), 1e-10);
        assertEquals(0.4, state.getHighMid(), 1e-10);
        assertEquals(0.5, state.getHigh(), 1e-10);
    }

    @Test
    @DisplayName("getSubBass() delegates to getBass() (deprecated)")
    @SuppressWarnings("deprecation")
    void subBassDelegatesToBass() {
        double[] bands = {0.77, 0.2, 0.3, 0.4, 0.5};
        AudioState state = new AudioState(bands, 0.5, false, 0.0, 0);

        assertEquals(state.getBass(), state.getSubBass());
    }

    @Test
    @DisplayName("getBand() returns 0 for out-of-bounds indices")
    void getBandOutOfBoundsReturnsZero() {
        AudioState state = AudioState.silent();

        assertEquals(0.0, state.getBand(-1));
        assertEquals(0.0, state.getBand(5));
        assertEquals(0.0, state.getBand(100));
    }

    @Test
    @DisplayName("Constructor handles null bands array")
    void constructorHandlesNullBands() {
        AudioState state = new AudioState(null, 0.5, true, 0.8, 42);

        assertEquals(5, state.getBands().length);
        assertEquals(0.5, state.getAmplitude());
        assertTrue(state.isBeat());
        assertEquals(0.8, state.getBeatIntensity());
        assertEquals(42, state.getFrame());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    @DisplayName("Each band index returns correct value")
    void eachBandReturnsCorrectValue(int bandIndex) {
        double[] bands = {0.1, 0.2, 0.3, 0.4, 0.5};
        AudioState state = new AudioState(bands, 0.5, false, 0.0, 0);

        assertEquals(bands[bandIndex], state.getBand(bandIndex), 1e-10);
    }

    @Test
    @DisplayName("5-band system (no sub-bass)")
    void fiveBandSystem() {
        AudioState state = AudioState.silent();
        assertEquals(5, state.getBands().length,
            "AudioState should use 5-band system (bass, low-mid, mid, high-mid, high)");
    }

    @Test
    @DisplayName("Amplitude stored correctly")
    void amplitudeStoredCorrectly() {
        AudioState state = new AudioState(new double[5], 0.95, false, 0.0, 0);
        assertEquals(0.95, state.getAmplitude(), 1e-10);
    }

    @Test
    @DisplayName("Frame number preserved")
    void frameNumberPreserved() {
        AudioState state = new AudioState(new double[5], 0.0, false, 0.0, 123456789L);
        assertEquals(123456789L, state.getFrame());
    }
}
