package com.audioviz.patterns;

/**
 * Represents the current audio state from the Python processor.
 * Contains frequency band levels, beat detection, and timing info.
 *
 * 5-band system for ultra-low-latency mode:
 * - Band 0: Bass (40-250Hz) - includes kick drums
 * - Band 1: Low-mid (250-500Hz)
 * - Band 2: Mid (500-2000Hz)
 * - Band 3: High-mid (2-6kHz)
 * - Band 4: High/Air (6-20kHz)
 */
public class AudioState {
    private final double[] bands;
    private final double amplitude;
    private final boolean isBeat;
    private final double beatIntensity;
    private final long frame;

    public AudioState(double[] bands, double amplitude, boolean isBeat, double beatIntensity, long frame) {
        this.bands = bands != null ? bands : new double[5];
        this.amplitude = amplitude;
        this.isBeat = isBeat;
        this.beatIntensity = beatIntensity;
        this.frame = frame;
    }

    public double getBand(int index) {
        if (index < 0 || index >= bands.length) return 0.0;
        return bands[index];
    }

    public double[] getBands() {
        return bands;
    }

    public double getAmplitude() {
        return amplitude;
    }

    public boolean isBeat() {
        return isBeat;
    }

    public double getBeatIntensity() {
        return beatIntensity;
    }

    public long getFrame() {
        return frame;
    }

    /**
     * Get bass level (band 0, 40-250Hz) - includes kick drums.
     */
    public double getBass() {
        return getBand(0);
    }

    /**
     * Get sub-bass level - now returns bass (sub-bass removed for low latency).
     * @deprecated Use getBass() instead
     */
    @Deprecated
    public double getSubBass() {
        return getBand(0);
    }

    /**
     * Get high frequency level (band 3, 2-6kHz).
     */
    public double getHigh() {
        return getBand(3);
    }

    /**
     * Get mid frequency level (band 2, 500-2000Hz).
     */
    public double getMid() {
        return getBand(2);
    }

    /**
     * Create a silent audio state (all zeros).
     */
    public static AudioState silent() {
        return new AudioState(new double[5], 0.0, false, 0.0, 0);
    }

    /**
     * Create a test audio state with simulated values.
     */
    public static AudioState forTest(long frame, boolean isBeat) {
        double[] bands = new double[5];
        double time = frame * 0.05;
        for (int i = 0; i < 5; i++) {
            bands[i] = 0.3 + 0.4 * Math.sin(time + i * 0.5);
        }
        double amplitude = 0.5 + 0.3 * Math.sin(time);
        double beatIntensity = isBeat ? 0.8 : 0.0;
        return new AudioState(bands, amplitude, isBeat, beatIntensity, frame);
    }
}
