# Psychedelic Bitmap Pattern Pack — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add 6 new psychedelic/rock bitmap patterns (metaballs, reaction-diffusion, fluid, voronoi shatter, sacred spiral, feedback loop) plus a trail/persistence post-processing effect.

**Architecture:** Each pattern is a self-contained `BitmapPattern` subclass in `minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/`. They render into `BitmapFrameBuffer` using ARGB pixel arrays. The trail effect is added to `EffectsProcessor`. All patterns are registered in `BitmapPatternManager.registerBuiltInPatterns()`.

**Tech Stack:** Java 21, Paper API, existing `BitmapFrameBuffer` / `BitmapPattern` / `EffectsProcessor` framework

**Reference files:**
- Pattern base class: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPattern.java`
- Frame buffer: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapFrameBuffer.java`
- Effects processor: `minecraft_plugin/src/main/java/com/audioviz/bitmap/effects/EffectsProcessor.java`
- Pattern manager/registry: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java`
- Audio state: `minecraft_plugin/src/main/java/com/audioviz/patterns/AudioState.java`
- Example pattern (plasma): `minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapPlasma.java`
- Example pattern (kaleidoscope): `minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapKaleidoscope.java`

**AudioState API:**
- `audio.getBass()` — bass band (0-1)
- `audio.getMid()` — mid band (0-1)
- `audio.getHigh()` — high band (0-1)
- `audio.getHighMid()` — high-mid band (0-1)
- `audio.getAmplitude()` — overall amplitude (0-1)
- `audio.isBeat()` — true on beat frames
- `audio.getBeatIntensity()` — beat strength (0-1)
- `audio.getFrame()` — frame counter (long)
- `audio.getBands()` — double[5] array (bass, low-mid, mid, high-mid, high)

**BitmapFrameBuffer API:**
- `buffer.getWidth()` / `buffer.getHeight()` — dimensions
- `buffer.getRawPixels()` — int[] ARGB array (row-major, direct reference)
- `buffer.setPixel(x, y, argb)` — set one pixel (bounds-checked)
- `buffer.getPixel(x, y)` — get one pixel
- `buffer.clear()` — clear to transparent black
- `buffer.clear(argb)` — clear to color
- `BitmapFrameBuffer.fromHSB(hue, sat, bri)` — HSB to ARGB (hue 0-360, sat/bri 0-1)
- `BitmapFrameBuffer.packARGB(a, r, g, b)` — pack components
- `BitmapFrameBuffer.lerpColor(c1, c2, t)` — linear interpolate colors
- `BitmapFrameBuffer.heatMapColor(intensity)` — intensity to heat map

**Pattern contract:**
```java
public class MyPattern extends BitmapPattern {
    public MyPattern() {
        super("bmp_myid", "Display Name", "Description");
    }
    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        // Write pixels into buffer
    }
    @Override
    public void reset() {
        // Reset state for pattern switch
    }
}
```

---

### Task 1: BitmapMetaballs — Lava Lamp Blobs

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapMetaballs.java`

**Step 1: Create the pattern**

```java
package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Lava lamp / mercury metaball effect.
 *
 * <p>Multiple blobs drift and merge organically using distance-field
 * iso-surface thresholding. Audio drives blob size, drift speed, and
 * color cycling. Beats spawn temporary extra blobs.
 */
public class BitmapMetaballs extends BitmapPattern {

    private static final int BASE_BLOB_COUNT = 7;
    private static final int MAX_EXTRA_BLOBS = 4;
    private static final double THRESHOLD = 1.0;

    // Blob state: [x, y, vx, vy, radius, bandIndex, life]
    // life: > 0 for temporary blobs (counts down), -1 for permanent
    private double[][] blobs;
    private int totalSlots;
    private double beatPulse = 0;

    public BitmapMetaballs() {
        super("bmp_metaballs", "Bitmap Metaballs",
              "Lava lamp blobs that merge and split with audio");
        initBlobs();
    }

    private void initBlobs() {
        totalSlots = BASE_BLOB_COUNT + MAX_EXTRA_BLOBS;
        blobs = new double[totalSlots][7];
        for (int i = 0; i < BASE_BLOB_COUNT; i++) {
            blobs[i][0] = 0.2 + Math.random() * 0.6; // x
            blobs[i][1] = 0.2 + Math.random() * 0.6; // y
            blobs[i][2] = (Math.random() - 0.5) * 0.3; // vx
            blobs[i][3] = (Math.random() - 0.5) * 0.3; // vy
            blobs[i][4] = 0.08 + Math.random() * 0.06; // radius
            blobs[i][5] = i % 5; // band index
            blobs[i][6] = -1; // permanent
        }
        for (int i = BASE_BLOB_COUNT; i < totalSlots; i++) {
            blobs[i][6] = 0; // inactive
        }
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        int[] pixels = buffer.getRawPixels();

        double bass = audio.getBass();
        double mid = audio.getMid();
        double high = audio.getHigh();
        double amplitude = audio.getAmplitude();
        double[] bands = audio.getBands();

        if (audio.isBeat()) {
            beatPulse = 1.0;
            // Spawn a temporary blob
            for (int i = BASE_BLOB_COUNT; i < totalSlots; i++) {
                if (blobs[i][6] <= 0) {
                    blobs[i][0] = 0.3 + Math.random() * 0.4;
                    blobs[i][1] = 0.3 + Math.random() * 0.4;
                    blobs[i][2] = (Math.random() - 0.5) * 0.5;
                    blobs[i][3] = (Math.random() - 0.5) * 0.5;
                    blobs[i][4] = 0.06 + audio.getBeatIntensity() * 0.04;
                    blobs[i][5] = (int) (Math.random() * 5);
                    blobs[i][6] = 1.0; // life
                    break;
                }
            }
        }
        beatPulse *= 0.9;

        // Update blob positions
        double speedMul = 0.02 + bass * 0.04;
        for (int i = 0; i < totalSlots; i++) {
            if (i >= BASE_BLOB_COUNT && blobs[i][6] <= 0) continue;

            blobs[i][0] += blobs[i][2] * speedMul;
            blobs[i][1] += blobs[i][3] * speedMul;

            // Bounce off edges
            if (blobs[i][0] < 0.05 || blobs[i][0] > 0.95) blobs[i][2] = -blobs[i][2];
            if (blobs[i][1] < 0.05 || blobs[i][1] > 0.95) blobs[i][3] = -blobs[i][3];
            blobs[i][0] = Math.max(0.02, Math.min(0.98, blobs[i][0]));
            blobs[i][1] = Math.max(0.02, Math.min(0.98, blobs[i][1]));

            // Decay temporary blobs
            if (blobs[i][6] > 0) {
                blobs[i][6] -= 0.015;
            }
        }

        // Render distance field
        double hueBase = time * 15;
        for (int py = 0; py < h; py++) {
            double ny = (double) py / h;
            for (int px = 0; px < w; px++) {
                double nx = (double) px / w;

                double field = 0;
                double weightedBand = 0;
                double totalWeight = 0;

                for (int i = 0; i < totalSlots; i++) {
                    if (i >= BASE_BLOB_COUNT && blobs[i][6] <= 0) continue;

                    double dx = nx - blobs[i][0];
                    double dy = ny - blobs[i][1];
                    double dist2 = dx * dx + dy * dy;

                    int bandIdx = (int) blobs[i][5];
                    double bandVal = bands[Math.min(bandIdx, 4)];
                    double r = blobs[i][4] * (1.0 + bandVal * 0.5 + beatPulse * 0.3);

                    // Life fading for temporary blobs
                    if (blobs[i][6] > 0) r *= blobs[i][6];

                    double contribution = (r * r) / (dist2 + 0.001);
                    field += contribution;
                    weightedBand += bandIdx * contribution;
                    totalWeight += contribution;
                }

                if (field > THRESHOLD) {
                    // Inside the blob surface
                    double excess = Math.min(3.0, (field - THRESHOLD) / THRESHOLD);
                    double dominantBand = totalWeight > 0 ? weightedBand / totalWeight : 0;

                    // Iridescent color: hue from band + position + time
                    float hue = (float) ((dominantBand * 60 + hueBase + excess * 30) % 360);
                    float sat = (float) (0.7 + high * 0.3);
                    float bri = (float) Math.min(1.0, 0.5 + excess * 0.3 + amplitude * 0.2);

                    pixels[py * w + px] = BitmapFrameBuffer.fromHSB(hue, sat, bri);
                } else if (field > THRESHOLD * 0.7) {
                    // Edge glow
                    double edge = (field - THRESHOLD * 0.7) / (THRESHOLD * 0.3);
                    float hue = (float) ((hueBase + 180) % 360);
                    float bri = (float) (edge * 0.4);
                    pixels[py * w + px] = BitmapFrameBuffer.fromHSB(hue, 0.5f, bri);
                } else {
                    pixels[py * w + px] = 0xFF000000; // Black background
                }
            }
        }
    }

    @Override
    public void reset() {
        beatPulse = 0;
        initBlobs();
    }
}
```

**Step 2: Verify it compiles**

Run: `cd minecraft_plugin && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapMetaballs.java
git commit -m "feat(bitmap): add BitmapMetaballs pattern — lava lamp blobs"
```

---

### Task 2: BitmapReactionDiffusion — Gray-Scott Cellular Automata

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapReactionDiffusion.java`

**Step 1: Create the pattern**

```java
package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.Random;

/**
 * Gray-Scott reaction-diffusion cellular automata.
 *
 * <p>Two chemicals react and diffuse across a grid, creating organic
 * coral/brain/maze textures. Audio modulates feed and kill rates to
 * morph between pattern types. Beats inject chemical at random points.
 *
 * <p>Runs at half internal resolution and upscales for performance.
 */
public class BitmapReactionDiffusion extends BitmapPattern {

    private double[] gridA, gridB;
    private double[] nextA, nextB;
    private int simW, simH;
    private int cachedW = -1, cachedH = -1;
    private final Random rng = new Random(42);
    private double beatPulse = 0;

    // Gray-Scott defaults (coral pattern)
    private static final double DA = 1.0;   // Diffusion rate A
    private static final double DB = 0.5;   // Diffusion rate B
    private static final double BASE_FEED = 0.055;
    private static final double BASE_KILL = 0.062;

    public BitmapReactionDiffusion() {
        super("bmp_reaction_diffusion", "Bitmap Reaction Diffusion",
              "Organic coral/maze textures from Gray-Scott automata");
    }

    private void ensureGrids(int outW, int outH) {
        if (outW == cachedW && outH == cachedH) return;
        cachedW = outW;
        cachedH = outH;
        // Half resolution for simulation
        simW = Math.max(4, outW / 2);
        simH = Math.max(4, outH / 2);
        int size = simW * simH;
        gridA = new double[size];
        gridB = new double[size];
        nextA = new double[size];
        nextB = new double[size];
        // Initialize: A=1 everywhere, B=0 except some seed spots
        java.util.Arrays.fill(gridA, 1.0);
        java.util.Arrays.fill(gridB, 0.0);
        seedInitialPatterns();
    }

    private void seedInitialPatterns() {
        // Seed several small squares of chemical B
        for (int s = 0; s < 5; s++) {
            int cx = simW / 4 + rng.nextInt(simW / 2);
            int cy = simH / 4 + rng.nextInt(simH / 2);
            int r = 2 + rng.nextInt(2);
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int x = (cx + dx + simW) % simW;
                    int y = (cy + dy + simH) % simH;
                    gridB[y * simW + x] = 1.0;
                }
            }
        }
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int outW = buffer.getWidth();
        int outH = buffer.getHeight();
        ensureGrids(outW, outH);

        double bass = audio.getBass();
        double mid = audio.getMid();
        double amplitude = audio.getAmplitude();

        if (audio.isBeat()) {
            beatPulse = 1.0;
            // Inject chemical B at a random spot
            int cx = rng.nextInt(simW);
            int cy = rng.nextInt(simH);
            int r = 2 + rng.nextInt(3);
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int x = (cx + dx + simW) % simW;
                    int y = (cy + dy + simH) % simH;
                    gridB[y * simW + x] = Math.min(1.0, gridB[y * simW + x] + 0.8);
                }
            }
        }
        beatPulse *= 0.9;

        // Audio-modulated parameters
        double feed = BASE_FEED + bass * 0.015 - mid * 0.005;
        double kill = BASE_KILL + mid * 0.008 - bass * 0.003;
        feed = Math.max(0.01, Math.min(0.1, feed));
        kill = Math.max(0.04, Math.min(0.08, kill));

        // Run 2-4 simulation steps per frame for responsiveness
        int steps = 2 + (int) (amplitude * 2);
        for (int step = 0; step < steps; step++) {
            simulationStep(feed, kill);
        }

        // Render to output buffer (nearest-neighbor upscale from sim grid)
        int[] pixels = buffer.getRawPixels();
        double hueShift = time * 10;

        for (int py = 0; py < outH; py++) {
            int sy = py * simH / outH;
            for (int px = 0; px < outW; px++) {
                int sx = px * simW / outW;
                int si = sy * simW + sx;

                double a = gridA[si];
                double b = gridB[si];

                // Color from chemical concentrations
                double intensity = b * 2.0;
                intensity = Math.max(0, Math.min(1, intensity));

                float hue = (float) ((intensity * 120 + hueShift + a * 60) % 360);
                float sat = (float) (0.6 + intensity * 0.4);
                float bri = (float) (intensity * 0.8 + amplitude * 0.15 + beatPulse * 0.1);
                bri = Math.min(1.0f, bri);

                if (bri < 0.02) {
                    pixels[py * outW + px] = 0xFF000000;
                } else {
                    pixels[py * outW + px] = BitmapFrameBuffer.fromHSB(hue, sat, bri);
                }
            }
        }
    }

    private void simulationStep(double feed, double kill) {
        for (int y = 0; y < simH; y++) {
            for (int x = 0; x < simW; x++) {
                int i = y * simW + x;
                double a = gridA[i];
                double b = gridB[i];

                // 5-point Laplacian with wrapping
                double lapA = laplacian(gridA, x, y);
                double lapB = laplacian(gridB, x, y);

                double abb = a * b * b;
                nextA[i] = a + (DA * lapA - abb + feed * (1.0 - a));
                nextB[i] = b + (DB * lapB + abb - (kill + feed) * b);

                nextA[i] = Math.max(0, Math.min(1, nextA[i]));
                nextB[i] = Math.max(0, Math.min(1, nextB[i]));
            }
        }
        // Swap buffers
        double[] tmpA = gridA; gridA = nextA; nextA = tmpA;
        double[] tmpB = gridB; gridB = nextB; nextB = tmpB;
    }

    private double laplacian(double[] grid, int x, int y) {
        int xm = (x - 1 + simW) % simW;
        int xp = (x + 1) % simW;
        int ym = (y - 1 + simH) % simH;
        int yp = (y + 1) % simH;
        int i = y * simW + x;
        return grid[ym * simW + x] + grid[yp * simW + x]
             + grid[y * simW + xm] + grid[y * simW + xp]
             - 4.0 * grid[i];
    }

    @Override
    public void reset() {
        beatPulse = 0;
        cachedW = -1;
        cachedH = -1;
    }
}
```

**Step 2: Verify it compiles**

Run: `cd minecraft_plugin && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapReactionDiffusion.java
git commit -m "feat(bitmap): add BitmapReactionDiffusion pattern — Gray-Scott automata"
```

---

### Task 3: BitmapFluid — Simplified Navier-Stokes Smoke

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapFluid.java`

**Step 1: Create the pattern**

```java
package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Simplified fluid/smoke simulation based on Jos Stam's "Stable Fluids".
 *
 * <p>Audio injects velocity and colored dye into the simulation.
 * Bass = large bursts, mids = swirling forces, highs = fine turbulence.
 * Runs at reduced internal resolution for performance.
 */
public class BitmapFluid extends BitmapPattern {

    private double[] vx, vy;     // Velocity field
    private double[] vx0, vy0;   // Previous velocity
    private double[] density;    // Dye density (R channel for simplicity)
    private double[] density0;   // Previous density
    private double[] densityG, densityG0; // Green channel
    private double[] densityB, densityB0; // Blue channel
    private int simW, simH, simSize;
    private int cachedW = -1, cachedH = -1;
    private double beatPulse = 0;
    private int beatCount = 0;

    private static final double VISCOSITY = 0.0001;
    private static final double DIFFUSION = 0.0001;
    private static final int SOLVER_ITERATIONS = 8;

    public BitmapFluid() {
        super("bmp_fluid", "Bitmap Fluid",
              "Audio-reactive fluid/smoke simulation");
    }

    private void ensureFields(int outW, int outH) {
        if (outW == cachedW && outH == cachedH) return;
        cachedW = outW;
        cachedH = outH;
        // 1/3 resolution for simulation (N+2 for boundaries)
        simW = Math.max(6, outW / 3 + 2);
        simH = Math.max(6, outH / 3 + 2);
        simSize = simW * simH;
        vx = new double[simSize]; vy = new double[simSize];
        vx0 = new double[simSize]; vy0 = new double[simSize];
        density = new double[simSize]; density0 = new double[simSize];
        densityG = new double[simSize]; densityG0 = new double[simSize];
        densityB = new double[simSize]; densityB0 = new double[simSize];
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int outW = buffer.getWidth();
        int outH = buffer.getHeight();
        ensureFields(outW, outH);

        double bass = audio.getBass();
        double mid = audio.getMid();
        double high = audio.getHigh();
        double amplitude = audio.getAmplitude();

        if (audio.isBeat()) {
            beatPulse = 1.0;
            beatCount++;
        }
        beatPulse *= 0.85;

        double dt = 0.05;

        // Clear source arrays
        java.util.Arrays.fill(vx0, 0);
        java.util.Arrays.fill(vy0, 0);
        java.util.Arrays.fill(density0, 0);
        java.util.Arrays.fill(densityG0, 0);
        java.util.Arrays.fill(densityB0, 0);

        int cx = simW / 2;
        int cy = simH / 2;

        // Bass: central upward burst
        if (bass > 0.2) {
            double force = bass * 80;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int idx = (cy + dy) * simW + (cx + dx);
                    if (idx > 0 && idx < simSize) {
                        vy0[idx] -= force;
                        // Dye color cycles with beat count
                        double hueRad = (beatCount * 0.8 + time * 0.3) % (2 * Math.PI);
                        density0[idx] += bass * 3 * Math.max(0, Math.cos(hueRad));
                        densityG0[idx] += bass * 3 * Math.max(0, Math.cos(hueRad + 2.094));
                        densityB0[idx] += bass * 3 * Math.max(0, Math.cos(hueRad + 4.189));
                    }
                }
            }
        }

        // Mids: swirling side forces
        if (mid > 0.15) {
            double angle = time * 2;
            int sx = cx + (int) (Math.cos(angle) * simW * 0.2);
            int sy = cy + (int) (Math.sin(angle) * simH * 0.2);
            sx = Math.max(1, Math.min(simW - 2, sx));
            sy = Math.max(1, Math.min(simH - 2, sy));
            int idx = sy * simW + sx;
            vx0[idx] += Math.cos(angle + 1.57) * mid * 60;
            vy0[idx] += Math.sin(angle + 1.57) * mid * 60;
            densityG0[idx] += mid * 2;
        }

        // Beat: radial burst
        if (beatPulse > 0.8) {
            double burstForce = 120 * audio.getBeatIntensity();
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    int idx = (cy + dy) * simW + (cx + dx);
                    if (idx > 0 && idx < simSize) {
                        double dist = Math.sqrt(dx * dx + dy * dy);
                        if (dist > 0) {
                            vx0[idx] += (dx / dist) * burstForce;
                            vy0[idx] += (dy / dist) * burstForce;
                        }
                        density0[idx] += 5;
                        densityG0[idx] += 5;
                        densityB0[idx] += 5;
                    }
                }
            }
        }

        // Simulate
        velocityStep(dt);
        densityStep(density, density0, dt);
        densityStep(densityG, densityG0, dt);
        densityStep(densityB, densityB0, dt);

        // Decay density
        for (int i = 0; i < simSize; i++) {
            density[i] *= 0.98;
            densityG[i] *= 0.98;
            densityB[i] *= 0.98;
        }

        // Render to output (bilinear upscale)
        int[] pixels = buffer.getRawPixels();
        int innerW = simW - 2;
        int innerH = simH - 2;

        for (int py = 0; py < outH; py++) {
            double sy = 1.0 + (double) py / outH * innerH;
            int sy0 = (int) sy;
            int sy1 = Math.min(sy0 + 1, simH - 1);
            double fy = sy - sy0;
            for (int px = 0; px < outW; px++) {
                double sx = 1.0 + (double) px / outW * innerW;
                int sx0 = (int) sx;
                int sx1 = Math.min(sx0 + 1, simW - 1);
                double fx = sx - sx0;

                // Bilinear sample each channel
                double r = bilerp(density, sx0, sy0, sx1, sy1, fx, fy);
                double g = bilerp(densityG, sx0, sy0, sx1, sy1, fx, fy);
                double b = bilerp(densityB, sx0, sy0, sx1, sy1, fx, fy);

                int ri = Math.min(255, (int) (r * 80));
                int gi = Math.min(255, (int) (g * 80));
                int bi = Math.min(255, (int) (b * 80));

                pixels[py * outW + px] = BitmapFrameBuffer.packARGB(255, ri, gi, bi);
            }
        }
    }

    private double bilerp(double[] field, int x0, int y0, int x1, int y1, double fx, double fy) {
        double v00 = field[y0 * simW + x0];
        double v10 = field[y0 * simW + x1];
        double v01 = field[y1 * simW + x0];
        double v11 = field[y1 * simW + x1];
        return (v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy;
    }

    // ========== Stam Fluid Solver ==========

    private void velocityStep(double dt) {
        addSource(vx, vx0, dt);
        addSource(vy, vy0, dt);
        diffuse(1, vx0, vx, VISCOSITY, dt);
        diffuse(2, vy0, vy, VISCOSITY, dt);
        project(vx0, vy0);
        advect(1, vx, vx0, vx0, vy0, dt);
        advect(2, vy, vy0, vx0, vy0, dt);
        project(vx, vy);
    }

    private void densityStep(double[] d, double[] d0, double dt) {
        addSource(d, d0, dt);
        diffuse(0, d0, d, DIFFUSION, dt);
        advect(0, d, d0, vx, vy, dt);
    }

    private void addSource(double[] target, double[] source, double dt) {
        for (int i = 0; i < simSize; i++) {
            target[i] += dt * source[i];
        }
    }

    private void diffuse(int b, double[] x, double[] x0, double diff, double dt) {
        double a = dt * diff * (simW - 2) * (simH - 2);
        double denom = 1 + 4 * a;
        for (int k = 0; k < SOLVER_ITERATIONS; k++) {
            for (int j = 1; j < simH - 1; j++) {
                for (int i = 1; i < simW - 1; i++) {
                    int idx = j * simW + i;
                    x[idx] = (x0[idx] + a * (x[idx - 1] + x[idx + 1]
                        + x[idx - simW] + x[idx + simW])) / denom;
                }
            }
            setBoundary(b, x);
        }
    }

    private void advect(int b, double[] d, double[] d0, double[] u, double[] v, double dt) {
        double dtx = dt * (simW - 2);
        double dty = dt * (simH - 2);
        for (int j = 1; j < simH - 1; j++) {
            for (int i = 1; i < simW - 1; i++) {
                int idx = j * simW + i;
                double x = i - dtx * u[idx];
                double y = j - dty * v[idx];
                x = Math.max(0.5, Math.min(simW - 1.5, x));
                y = Math.max(0.5, Math.min(simH - 1.5, y));
                int i0 = (int) x, j0 = (int) y;
                int i1 = i0 + 1, j1 = j0 + 1;
                double s1 = x - i0, s0 = 1 - s1;
                double t1 = y - j0, t0 = 1 - t1;
                d[idx] = s0 * (t0 * d0[j0 * simW + i0] + t1 * d0[j1 * simW + i0])
                       + s1 * (t0 * d0[j0 * simW + i1] + t1 * d0[j1 * simW + i1]);
            }
        }
        setBoundary(b, d);
    }

    private void project(double[] u, double[] v) {
        double[] div = density0; // Reuse temporarily
        double[] p = densityG0; // Reuse temporarily
        double h = 1.0 / Math.max(simW - 2, simH - 2);

        for (int j = 1; j < simH - 1; j++) {
            for (int i = 1; i < simW - 1; i++) {
                int idx = j * simW + i;
                div[idx] = -0.5 * h * (u[idx + 1] - u[idx - 1] + v[idx + simW] - v[idx - simW]);
                p[idx] = 0;
            }
        }
        setBoundary(0, div);
        setBoundary(0, p);

        for (int k = 0; k < SOLVER_ITERATIONS; k++) {
            for (int j = 1; j < simH - 1; j++) {
                for (int i = 1; i < simW - 1; i++) {
                    int idx = j * simW + i;
                    p[idx] = (div[idx] + p[idx - 1] + p[idx + 1]
                        + p[idx - simW] + p[idx + simW]) / 4.0;
                }
            }
            setBoundary(0, p);
        }

        for (int j = 1; j < simH - 1; j++) {
            for (int i = 1; i < simW - 1; i++) {
                int idx = j * simW + i;
                u[idx] -= 0.5 * (p[idx + 1] - p[idx - 1]) / h;
                v[idx] -= 0.5 * (p[idx + simW] - p[idx - simW]) / h;
            }
        }
        setBoundary(1, u);
        setBoundary(2, v);
    }

    private void setBoundary(int b, double[] x) {
        for (int i = 1; i < simW - 1; i++) {
            x[i] = (b == 2) ? -x[simW + i] : x[simW + i];
            x[(simH - 1) * simW + i] = (b == 2) ? -x[(simH - 2) * simW + i] : x[(simH - 2) * simW + i];
        }
        for (int j = 1; j < simH - 1; j++) {
            x[j * simW] = (b == 1) ? -x[j * simW + 1] : x[j * simW + 1];
            x[j * simW + simW - 1] = (b == 1) ? -x[j * simW + simW - 2] : x[j * simW + simW - 2];
        }
        // Corners
        x[0] = 0.5 * (x[1] + x[simW]);
        x[simW - 1] = 0.5 * (x[simW - 2] + x[2 * simW - 1]);
        x[(simH - 1) * simW] = 0.5 * (x[(simH - 2) * simW] + x[(simH - 1) * simW + 1]);
        x[simSize - 1] = 0.5 * (x[simSize - 2] + x[simSize - 1 - simW]);
    }

    @Override
    public void reset() {
        beatPulse = 0;
        beatCount = 0;
        cachedW = -1;
        cachedH = -1;
    }
}
```

**Step 2: Verify it compiles**

Run: `cd minecraft_plugin && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapFluid.java
git commit -m "feat(bitmap): add BitmapFluid pattern — Stam fluid simulation"
```

---

### Task 4: BitmapVoronoiShatter — Stained Glass That Breathes

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapVoronoiShatter.java`

**Step 1: Create the pattern**

```java
package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.Random;

/**
 * Voronoi diagram rendered as stained glass that breathes with audio.
 *
 * <p>Seeds drift slowly, cells pulse with their mapped frequency band,
 * and beats cause cells to "shatter" — seeds temporarily split and
 * recombine. Dark borders glow with amplitude.
 */
public class BitmapVoronoiShatter extends BitmapPattern {

    private static final int BASE_SEEDS = 25;
    private static final int MAX_SHATTER_SEEDS = 20;

    // Seed state: [x, y, vx, vy, bandIndex, hueOffset, life]
    // life: -1 = permanent, >0 = temporary (shatter fragment)
    private double[][] seeds;
    private int totalSlots;
    private double beatPulse = 0;
    private double shatterIntensity = 0;
    private final Random rng = new Random(123);

    public BitmapVoronoiShatter() {
        super("bmp_voronoi", "Bitmap Voronoi Shatter",
              "Stained glass cells that breathe and shatter on beats");
        initSeeds();
    }

    private void initSeeds() {
        totalSlots = BASE_SEEDS + MAX_SHATTER_SEEDS;
        seeds = new double[totalSlots][7];
        for (int i = 0; i < BASE_SEEDS; i++) {
            seeds[i][0] = Math.random();       // x
            seeds[i][1] = Math.random();       // y
            seeds[i][2] = (Math.random() - 0.5) * 0.2; // vx
            seeds[i][3] = (Math.random() - 0.5) * 0.2; // vy
            seeds[i][4] = i % 5;               // band index
            seeds[i][5] = i * (360.0 / BASE_SEEDS); // hue offset
            seeds[i][6] = -1;                  // permanent
        }
        for (int i = BASE_SEEDS; i < totalSlots; i++) {
            seeds[i][6] = 0; // inactive
        }
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        int[] pixels = buffer.getRawPixels();

        double bass = audio.getBass();
        double mid = audio.getMid();
        double amplitude = audio.getAmplitude();
        double[] bands = audio.getBands();

        if (audio.isBeat()) {
            beatPulse = 1.0;
            shatterIntensity = Math.min(1.0, shatterIntensity + 0.4);

            // Spawn shatter fragments near random existing seeds
            int fragmentsToSpawn = 2 + (int) (audio.getBeatIntensity() * 3);
            for (int f = 0; f < fragmentsToSpawn; f++) {
                int parentIdx = rng.nextInt(BASE_SEEDS);
                for (int i = BASE_SEEDS; i < totalSlots; i++) {
                    if (seeds[i][6] <= 0) {
                        seeds[i][0] = seeds[parentIdx][0] + (rng.nextDouble() - 0.5) * 0.08;
                        seeds[i][1] = seeds[parentIdx][1] + (rng.nextDouble() - 0.5) * 0.08;
                        seeds[i][2] = (rng.nextDouble() - 0.5) * 0.4;
                        seeds[i][3] = (rng.nextDouble() - 0.5) * 0.4;
                        seeds[i][4] = seeds[parentIdx][4];
                        seeds[i][5] = seeds[parentIdx][5] + rng.nextDouble() * 30;
                        seeds[i][6] = 1.0; // life
                        break;
                    }
                }
            }
        }
        beatPulse *= 0.88;
        shatterIntensity *= 0.97;

        // Update seed positions
        double speed = 0.01 + bass * 0.02;
        for (int i = 0; i < totalSlots; i++) {
            if (i >= BASE_SEEDS && seeds[i][6] <= 0) continue;

            seeds[i][0] += seeds[i][2] * speed;
            seeds[i][1] += seeds[i][3] * speed;

            // Wrap around
            seeds[i][0] = ((seeds[i][0] % 1.0) + 1.0) % 1.0;
            seeds[i][1] = ((seeds[i][1] % 1.0) + 1.0) % 1.0;

            // Decay shatter fragments
            if (seeds[i][6] > 0) seeds[i][6] -= 0.008;
        }

        // Render Voronoi
        double hueBase = time * 8;

        for (int py = 0; py < h; py++) {
            double ny = (double) py / h;
            for (int px = 0; px < w; px++) {
                double nx = (double) px / w;

                // Find closest and second-closest seed
                double minDist = Double.MAX_VALUE;
                double secondDist = Double.MAX_VALUE;
                int closestIdx = 0;

                for (int i = 0; i < totalSlots; i++) {
                    if (i >= BASE_SEEDS && seeds[i][6] <= 0) continue;

                    double dx = nx - seeds[i][0];
                    double dy = ny - seeds[i][1];
                    // Handle wrapping for toroidal distance
                    if (dx > 0.5) dx -= 1.0; else if (dx < -0.5) dx += 1.0;
                    if (dy > 0.5) dy -= 1.0; else if (dy < -0.5) dy += 1.0;
                    double dist = dx * dx + dy * dy;

                    if (dist < minDist) {
                        secondDist = minDist;
                        minDist = dist;
                        closestIdx = i;
                    } else if (dist < secondDist) {
                        secondDist = dist;
                    }
                }

                double edgeDist = Math.sqrt(secondDist) - Math.sqrt(minDist);

                int bandIdx = (int) seeds[closestIdx][4];
                double bandVal = bands[Math.min(bandIdx, 4)];
                double seedHue = seeds[closestIdx][5];

                // Life multiplier for shatter fragments
                double lifeMul = seeds[closestIdx][6] > 0 ? seeds[closestIdx][6] : 1.0;

                if (edgeDist < 0.015 + amplitude * 0.01) {
                    // Border: dark with glow
                    float bri = (float) (amplitude * 0.5 + beatPulse * 0.4);
                    float hue = (float) ((hueBase + seedHue) % 360);
                    pixels[py * w + px] = BitmapFrameBuffer.fromHSB(hue, 0.3f, bri * (float) lifeMul);
                } else {
                    // Cell interior
                    float hue = (float) ((seedHue + hueBase + bandVal * 30) % 360);
                    float sat = (float) (0.6 + bandVal * 0.3);
                    float bri = (float) (0.3 + bandVal * 0.5 + beatPulse * 0.15);
                    bri = Math.min(1.0f, bri) * (float) lifeMul;
                    pixels[py * w + px] = BitmapFrameBuffer.fromHSB(hue, sat, bri);
                }
            }
        }
    }

    @Override
    public void reset() {
        beatPulse = 0;
        shatterIntensity = 0;
        initSeeds();
    }
}
```

**Step 2: Verify it compiles**

Run: `cd minecraft_plugin && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapVoronoiShatter.java
git commit -m "feat(bitmap): add BitmapVoronoiShatter pattern — stained glass cells"
```

---

### Task 5: BitmapSacredSpiral — Fibonacci Spiral Hallucination

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapSacredSpiral.java`

**Step 1: Create the pattern**

```java
package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Sacred geometry spiral pattern with golden ratio arms.
 *
 * <p>Five interlocking golden spiral arms, each mapped to a frequency
 * band. Background concentric rings breathe with bass. Rotation tracks
 * audio energy. Alex Grey / DMT visual territory.
 */
public class BitmapSacredSpiral extends BitmapPattern {

    private float[] distTable;
    private float[] angleTable;
    private int cachedW = -1, cachedH = -1;
    private double rotation = 0;
    private double beatPulse = 0;
    private double ringPhase = 0;

    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0)); // ~137.5 degrees
    private static final double LOG_GOLDEN = Math.log(1.618033988749895);
    private static final int NUM_ARMS = 5;

    public BitmapSacredSpiral() {
        super("bmp_sacred_spiral", "Bitmap Sacred Spiral",
              "Golden ratio spiral arms with breathing concentric rings");
    }

    private void ensureTables(int w, int h) {
        if (w == cachedW && h == cachedH) return;
        cachedW = w;
        cachedH = h;
        int size = w * h;
        distTable = new float[size];
        angleTable = new float[size];
        double cx = w / 2.0;
        double cy = h / 2.0;
        double scale = Math.max(cx, cy);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = (x - cx) / scale;
                double dy = (y - cy) / scale;
                int idx = y * w + x;
                distTable[idx] = (float) Math.sqrt(dx * dx + dy * dy);
                angleTable[idx] = (float) Math.atan2(dy, dx);
            }
        }
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        ensureTables(w, h);
        int[] pixels = buffer.getRawPixels();

        double bass = audio.getBass();
        double mid = audio.getMid();
        double high = audio.getHigh();
        double amplitude = audio.getAmplitude();
        double[] bands = audio.getBands();

        if (audio.isBeat()) {
            beatPulse = 1.0;
        }
        beatPulse *= 0.88;

        rotation += 0.015 + amplitude * 0.03 + beatPulse * 0.06;
        ringPhase += 0.02 + bass * 0.05;

        double hueBase = time * 12;

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int idx = py * w + px;
                double dist = distTable[idx];
                double angle = angleTable[idx] + rotation;

                // Background: concentric rings breathing with bass
                double ringVal = Math.sin(dist * 20 - ringPhase) * 0.5 + 0.5;
                ringVal *= (0.2 + bass * 0.3);
                double bgBri = ringVal * 0.25;

                // Spiral arms
                double maxArmIntensity = 0;
                int dominantArm = 0;

                for (int arm = 0; arm < NUM_ARMS; arm++) {
                    // Golden spiral: r = a * e^(b*theta)
                    // Invert to find expected angle for this distance on this arm
                    double armOffset = arm * (2 * Math.PI / NUM_ARMS);
                    double spiralAngle = (angle - armOffset);

                    // How close is this pixel to the spiral arm?
                    // For a log spiral: theta = ln(r/a) / b
                    // We check how far the pixel's angle is from the expected angle
                    double expectedAngle = 0;
                    if (dist > 0.01) {
                        expectedAngle = Math.log(dist * 5 + 0.1) / LOG_GOLDEN;
                    }

                    double angleDiff = spiralAngle - expectedAngle;
                    // Normalize to [-pi, pi]
                    angleDiff = angleDiff % (2 * Math.PI);
                    if (angleDiff > Math.PI) angleDiff -= 2 * Math.PI;
                    if (angleDiff < -Math.PI) angleDiff += 2 * Math.PI;

                    // Arm width (narrower at center, wider at edges)
                    double armWidth = 0.3 + dist * 0.2 + bands[arm] * 0.2 + beatPulse * 0.15;
                    double armIntensity = Math.exp(-(angleDiff * angleDiff) / (armWidth * armWidth));

                    // Scale by band
                    armIntensity *= (0.3 + bands[arm] * 0.7);

                    if (armIntensity > maxArmIntensity) {
                        maxArmIntensity = armIntensity;
                        dominantArm = arm;
                    }
                }

                // Composite: background + arms
                double totalBri = bgBri + maxArmIntensity * 0.8 + amplitude * 0.05;
                totalBri = Math.min(1.0, totalBri);

                // Edge fade
                if (dist > 0.85) {
                    totalBri *= Math.max(0, (1.0 - dist) / 0.15);
                }

                float hue = (float) ((dominantArm * 72 + hueBase + dist * 60 + mid * 40) % 360);
                float sat = (float) (0.6 + maxArmIntensity * 0.35 + high * 0.05);
                sat = Math.min(1.0f, sat);

                if (totalBri < 0.01) {
                    pixels[idx] = 0xFF000000;
                } else {
                    pixels[idx] = BitmapFrameBuffer.fromHSB(hue, sat, (float) totalBri);
                }
            }
        }
    }

    @Override
    public void reset() {
        rotation = 0;
        beatPulse = 0;
        ringPhase = 0;
    }
}
```

**Step 2: Verify it compiles**

Run: `cd minecraft_plugin && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapSacredSpiral.java
git commit -m "feat(bitmap): add BitmapSacredSpiral pattern — golden ratio spirals"
```

---

### Task 6: BitmapFeedbackLoop — Infinite Tunnel / Mirror Hall

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapFeedbackLoop.java`

**Step 1: Create the pattern**

```java
package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Frame feedback loop with zoom and rotation.
 *
 * <p>Reads previous frame, applies zoom toward center + rotation + hue shift,
 * then overlays new audio-reactive content. Creates infinite recursive tunnel
 * and mirror-hall effects entirely procedurally.
 */
public class BitmapFeedbackLoop extends BitmapPattern {

    private int[] previousFrame;
    private int cachedW = -1, cachedH = -1;
    private double rotation = 0;
    private double beatPulse = 0;
    private int beatCount = 0;

    public BitmapFeedbackLoop() {
        super("bmp_feedback", "Bitmap Feedback Loop",
              "Infinite tunnel from frame feedback with zoom and rotation");
    }

    private void ensurePreviousFrame(int w, int h) {
        if (w == cachedW && h == cachedH) return;
        cachedW = w;
        cachedH = h;
        previousFrame = new int[w * h];
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        ensurePreviousFrame(w, h);
        int[] pixels = buffer.getRawPixels();

        double bass = audio.getBass();
        double mid = audio.getMid();
        double high = audio.getHigh();
        double amplitude = audio.getAmplitude();

        if (audio.isBeat()) {
            beatPulse = 1.0;
            beatCount++;
        }
        beatPulse *= 0.88;

        // Feedback parameters
        double zoomFactor = 0.96 - bass * 0.03 - beatPulse * 0.04; // <1 = zoom in
        double rotAngle = mid * 0.04 + beatPulse * 0.02;
        rotation += rotAngle;
        double hueShiftPerFrame = high * 8 + 1.5;

        double cx = w / 2.0;
        double cy = h / 2.0;
        double cosR = Math.cos(rotation * 0.1);
        double sinR = Math.sin(rotation * 0.1);

        // Step 1: Sample previous frame with zoom + rotation → current frame
        for (int py = 0; py < h; py++) {
            double dy = (py - cy) / cy;
            for (int px = 0; px < w; px++) {
                double dx = (px - cx) / cx;

                // Apply zoom and rotation
                double rx = (dx * cosR - dy * sinR) / zoomFactor;
                double ry = (dx * sinR + dy * cosR) / zoomFactor;

                // Map back to pixel coordinates
                int sx = (int) (rx * cx + cx);
                int sy = (int) (ry * cy + cy);

                int sampled;
                if (sx >= 0 && sx < w && sy >= 0 && sy < h) {
                    sampled = previousFrame[sy * w + sx];
                } else {
                    sampled = 0xFF000000;
                }

                // Apply hue shift to sampled pixel
                sampled = shiftHue(sampled, hueShiftPerFrame);

                // Slight brightness decay to prevent blowout
                sampled = decayBrightness(sampled, 0.95);

                pixels[py * w + px] = sampled;
            }
        }

        // Step 2: Overlay new audio-reactive content
        // Pulsing central shape that changes with beats
        int shape = beatCount % 4;
        double contentRadius = 0.05 + amplitude * 0.08 + beatPulse * 0.06;

        float contentHue = (float) ((time * 40 + beatCount * 45) % 360);
        int contentColor = BitmapFrameBuffer.fromHSB(contentHue, 0.9f,
            (float) (0.6 + amplitude * 0.4));

        for (int py = 0; py < h; py++) {
            double ny = (double) py / h - 0.5;
            for (int px = 0; px < w; px++) {
                double nx = (double) px / w - 0.5;
                boolean inShape = false;

                switch (shape) {
                    case 0: // Circle
                        inShape = nx * nx + ny * ny < contentRadius * contentRadius;
                        break;
                    case 1: // Diamond
                        inShape = Math.abs(nx) + Math.abs(ny) < contentRadius;
                        break;
                    case 2: // Cross
                        inShape = (Math.abs(nx) < contentRadius * 0.3 && Math.abs(ny) < contentRadius)
                               || (Math.abs(ny) < contentRadius * 0.3 && Math.abs(nx) < contentRadius);
                        break;
                    case 3: // Ring
                        double dist = Math.sqrt(nx * nx + ny * ny);
                        inShape = Math.abs(dist - contentRadius) < contentRadius * 0.3;
                        break;
                }

                if (inShape) {
                    int idx = py * w + px;
                    // Additive blend
                    int existing = pixels[idx];
                    int er = (existing >> 16) & 0xFF, eg = (existing >> 8) & 0xFF, eb = existing & 0xFF;
                    int cr = (contentColor >> 16) & 0xFF, cg = (contentColor >> 8) & 0xFF, cb = contentColor & 0xFF;
                    pixels[idx] = BitmapFrameBuffer.packARGB(255,
                        Math.min(255, er + cr), Math.min(255, eg + cg), Math.min(255, eb + cb));
                }
            }
        }

        // Step 3: Save current frame for next iteration
        System.arraycopy(pixels, 0, previousFrame, 0, pixels.length);
    }

    private static int shiftHue(int argb, double degrees) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (r == 0 && g == 0 && b == 0) return argb;

        float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
        hsb[0] = (float) ((hsb[0] + degrees / 360.0) % 1.0);
        if (hsb[0] < 0) hsb[0] += 1.0f;
        int rgb = java.awt.Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    private static int decayBrightness(int argb, double factor) {
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return BitmapFrameBuffer.packARGB(255, r, g, b);
    }

    @Override
    public void reset() {
        beatPulse = 0;
        beatCount = 0;
        rotation = 0;
        cachedW = -1;
        cachedH = -1;
    }
}
```

**Step 2: Verify it compiles**

Run: `cd minecraft_plugin && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/BitmapFeedbackLoop.java
git commit -m "feat(bitmap): add BitmapFeedbackLoop pattern — infinite tunnel feedback"
```

---

### Task 7: Trail/Persistence Effect in EffectsProcessor

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/effects/EffectsProcessor.java`

**Step 1: Add trail fields and methods**

Add these fields after the existing `edgeFlashWidth` field (around line 59):

```java
// ========== Trail / Persistence ==========
private boolean trailEnabled = false;
private double trailDecay = 0.85;          // 0 = no trail, 0.99 = long trails
private int[] trailPreviousFrame = new int[0];
```

Add trail processing as step 0, right at the start of the `process()` method, before the beat tracking block (insert before line 70 `if (audio != null && audio.isBeat())`):

```java
// 0. Trail persistence: blend previous frame into current
if (trailEnabled && trailPreviousFrame.length == buffer.getRawPixels().length) {
    int[] pixels = buffer.getRawPixels();
    float decay = (float) trailDecay;
    float fresh = 1.0f - decay;
    for (int i = 0; i < pixels.length; i++) {
        // Blend: output = max(current, previous * decay)
        // Using LIGHTEN blend so trails don't dim the active pattern
        int prev = trailPreviousFrame[i];
        int pr = (int) (((prev >> 16) & 0xFF) * decay);
        int pg = (int) (((prev >> 8) & 0xFF) * decay);
        int pb = (int) ((prev & 0xFF) * decay);
        int cr = (pixels[i] >> 16) & 0xFF;
        int cg = (pixels[i] >> 8) & 0xFF;
        int cb = pixels[i] & 0xFF;
        pixels[i] = BitmapFrameBuffer.packARGB(255,
            Math.max(cr, pr), Math.max(cg, pg), Math.max(cb, pb));
    }
}
```

At the end of the `process()` method, after step 9 (global brightness), add:

```java
// Snapshot for trail persistence (after all effects)
if (trailEnabled) {
    int[] pixels = buffer.getRawPixels();
    if (trailPreviousFrame.length != pixels.length) {
        trailPreviousFrame = new int[pixels.length];
    }
    System.arraycopy(pixels, 0, trailPreviousFrame, 0, pixels.length);
}
```

Add control methods after the existing edge flash controls section:

```java
// ========== Trail Controls ==========

public void setTrailEnabled(boolean enabled) { this.trailEnabled = enabled; }
public boolean isTrailEnabled() { return trailEnabled; }
public void setTrailDecay(double decay) { this.trailDecay = Math.max(0, Math.min(0.99, decay)); }
public double getTrailDecay() { return trailDecay; }
```

Add reset for trail in the `reset()` method:

```java
trailEnabled = false;
trailDecay = 0.85;
trailPreviousFrame = new int[0];
```

**Step 2: Verify it compiles**

Run: `cd minecraft_plugin && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/effects/EffectsProcessor.java
git commit -m "feat(bitmap): add trail/persistence post-processing effect"
```

---

### Task 8: Register All New Patterns

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java`

**Step 1: Add registration calls**

In `registerBuiltInPatterns()`, add the new patterns to the Tier 3 section (after line 170, after `BitmapRotatingGeometry`):

```java
// Tier 4: Psychedelic / immersive — Sphere-inspired
register(new BitmapMetaballs());
register(new BitmapReactionDiffusion());
register(new BitmapFluid());
register(new BitmapVoronoiShatter());
register(new BitmapSacredSpiral());
register(new BitmapFeedbackLoop());
```

**Step 2: Verify it compiles**

Run: `cd minecraft_plugin && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

**Step 3: Build the full JAR to verify shading**

Run: `cd minecraft_plugin && mvn package -q 2>&1 | tail -5`
Expected: BUILD SUCCESS with JAR output

**Step 4: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java
git commit -m "feat(bitmap): register 6 psychedelic patterns in pattern manager"
```

---

### Task 9: Final Build Verification

**Step 1: Full clean build**

Run: `cd minecraft_plugin && mvn clean package 2>&1 | tail -10`
Expected: BUILD SUCCESS

**Step 2: Verify pattern count**

Run: `grep -c "register(new Bitmap" minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java`
Expected: Previous count + 6 (should be ~35 Bitmap* patterns)

**Step 3: Commit the plan**

```bash
git add docs/plans/2026-03-05-psychedelic-bitmap-patterns.md
git commit -m "docs: psychedelic bitmap patterns implementation plan"
```
