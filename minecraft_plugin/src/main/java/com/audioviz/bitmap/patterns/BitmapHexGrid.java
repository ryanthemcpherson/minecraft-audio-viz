package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Hexagonal grid where cells light up in expanding waves.
 *
 * <p>Renders a hexagonal tiling over the display where activation waves
 * propagate outward from beat-triggered epicenters. Each hex cell lights up
 * as the wave front passes through it, then fades. Multiple overlapping
 * waves create complex interference patterns.
 *
 * <p>Audio reactivity:
 * <ul>
 *   <li>Beat spawns a new activation wave from a cycling position</li>
 *   <li>Bass controls wave expansion speed</li>
 *   <li>Frequency bands color different sectors of the hex grid</li>
 *   <li>Amplitude modulates the wave brightness and fade rate</li>
 *   <li>High frequencies add neon edge shimmer</li>
 * </ul>
 *
 * <p>The hex grid uses axial coordinates with offset rows for proper
 * hexagonal tiling. Neon outlines on a dark background give a cyberpunk feel.
 */
public class BitmapHexGrid extends BitmapPattern {

    private static final int MAX_WAVES = 8;
    private static final double HEX_SIZE = 3.5;
    private static final double SQRT_3 = Math.sqrt(3.0);
    private static final double HEX_INTERIOR_THRESHOLD_SQ = (HEX_SIZE * 0.65) * (HEX_SIZE * 0.65);
    private static final double HEX_EDGE_OUTER_THRESHOLD_SQ = (HEX_SIZE * 0.9) * (HEX_SIZE * 0.9);

    /** Active wave epicenters and radii. */
    private final double[] waveX = new double[MAX_WAVES];
    private final double[] waveY = new double[MAX_WAVES];
    private final double[] waveRadius = new double[MAX_WAVES];
    private final double[] waveBrightness = new double[MAX_WAVES];
    private final float[] waveHue = new float[MAX_WAVES];
    private int nextWaveSlot = 0;

    /** Position cycling for wave spawns. */
    private int spawnIndex = 0;

    /** Smooth beat pulse. */
    private double beatPulse = 0.0;

    public BitmapHexGrid() {
        super("bmp_hexgrid", "Hex Grid",
              "Hexagonal tiling with audio-reactive activation waves");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();

        double bass = audio.getBass();
        double mid = audio.getMid();
        double high = audio.getHigh();
        double amplitude = audio.getAmplitude();
        double[] bands = audio.getBands();

        // Beat pulse
        if (audio.isBeat()) {
            beatPulse = Math.max(beatPulse, audio.getBeatIntensity());
        } else {
            beatPulse *= 0.85;
        }

        // --- Spawn new wave on beat ---
        if (audio.isBeat()) {
            // Cycle spawn position around the display
            double angle = spawnIndex * Math.PI * 2.0 / 5.0;
            double spawnRadius = Math.min(w, h) * 0.3;
            waveX[nextWaveSlot] = w * 0.5 + Math.cos(angle) * spawnRadius;
            waveY[nextWaveSlot] = h * 0.5 + Math.sin(angle) * spawnRadius;
            waveRadius[nextWaveSlot] = 0;
            waveBrightness[nextWaveSlot] = 0.8 + audio.getBeatIntensity() * 0.2;

            // Hue based on dominant band
            int dominantBand = 0;
            double maxVal = 0;
            for (int i = 0; i < bands.length; i++) {
                if (bands[i] > maxVal) {
                    maxVal = bands[i];
                    dominantBand = i;
                }
            }
            waveHue[nextWaveSlot] = dominantBand * 72.0f; // 0, 72, 144, 216, 288

            nextWaveSlot = (nextWaveSlot + 1) % MAX_WAVES;
            spawnIndex = (spawnIndex + 1) % 5;
        }

        // --- Update waves ---
        double waveSpeed = 1.5 + bass * 3.0;
        for (int i = 0; i < MAX_WAVES; i++) {
            if (waveBrightness[i] > 0.01) {
                waveRadius[i] += waveSpeed;
                waveBrightness[i] *= (0.94 + amplitude * 0.04); // Fade slower when loud
            }
        }

        // --- Render hex grid ---
        buffer.clear(); // Transparent background

        double hexW = HEX_SIZE * 2.0;
        double hexH = HEX_SIZE * SQRT_3;

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                // Convert pixel to hex axial coordinates
                int hexRow = (int) Math.floor(py / hexH);
                double offsetX = (hexRow % 2 == 0) ? 0 : hexW * 0.5;
                int hexCol = (int) Math.floor((px - offsetX) / hexW);

                // Hex cell center in pixel space
                double cellCenterX = hexCol * hexW + offsetX + HEX_SIZE;
                double cellCenterY = hexRow * hexH + hexH * 0.5;

                double distToCenterSq = (px - cellCenterX) * (px - cellCenterX) + (py - cellCenterY) * (py - cellCenterY);
                boolean isInterior = distToCenterSq <= HEX_INTERIOR_THRESHOLD_SQ;
                boolean isEdge = !isInterior && distToCenterSq < HEX_EDGE_OUTER_THRESHOLD_SQ;

                if (!isEdge && !isInterior) continue; // Gap between hexes

                // Calculate wave activation for this hex cell
                double totalActivation = 0;
                float blendedHue = 0;
                double hueWeight = 0;

                double waveThickness = 3.0 + amplitude * 2.0;

                for (int i = 0; i < MAX_WAVES; i++) {
                    if (waveBrightness[i] < 0.01) continue;

                    double dxW = cellCenterX - waveX[i];
                    double dyW = cellCenterY - waveY[i];
                    double distToWaveSq = dxW * dxW + dyW * dyW;
                    double maxDist = waveRadius[i] + waveThickness;
                    if (distToWaveSq > maxDist * maxDist) continue;
                    double distToWave = Math.sqrt(distToWaveSq);

                    double ringDist = Math.abs(distToWave - waveRadius[i]);

                    if (ringDist < waveThickness) {
                        double activation = (1.0 - ringDist / waveThickness) * waveBrightness[i];
                        totalActivation += activation;
                        blendedHue += waveHue[i] * activation;
                        hueWeight += activation;
                    }
                }

                totalActivation = Math.min(1.0, totalActivation);

                if (totalActivation > 0.02) {
                    float hue = hueWeight > 0 ? blendedHue / (float) hueWeight : 180f;
                    hue = (hue + (float) (time * 10)) % 360f;

                    if (isEdge) {
                        // Neon edge: brighter, more saturated
                        float edgeBri = (float) Math.min(1.0, totalActivation * 1.2 + high * 0.2);
                        int edgeColor = BitmapFrameBuffer.fromHSB(hue, 0.9f, edgeBri);
                        buffer.setPixel(px, py, edgeColor);
                    } else {
                        // Interior: dimmer fill
                        float fillBri = (float) (totalActivation * 0.4);
                        int fillColor = BitmapFrameBuffer.fromHSB(hue, 0.7f, fillBri);
                        buffer.setPixel(px, py, fillColor);
                    }
                } else if (isEdge) {
                    // Dim grid outline even when inactive
                    int dimEdge = BitmapFrameBuffer.rgb(15, 15, 30);
                    buffer.setPixel(px, py, dimEdge);
                }
            }
        }
    }

    @Override
    public void reset() {
        for (int i = 0; i < MAX_WAVES; i++) {
            waveBrightness[i] = 0;
            waveRadius[i] = 0;
        }
        nextWaveSlot = 0;
        spawnIndex = 0;
        beatPulse = 0.0;
    }
}
