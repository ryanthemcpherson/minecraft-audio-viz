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
    private double[] projDiv, projP; // Scratch arrays for project()
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
        projDiv = new double[simSize]; projP = new double[simSize];
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
        double[] div = projDiv;
        double[] p = projP;
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
