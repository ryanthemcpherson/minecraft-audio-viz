package com.audioviz.bitmap.patterns;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Spinning wireframe geometric shapes rendered to pixel grid.
 *
 * <p>Renders nested 3D wireframe polyhedra (cube and octahedron) with
 * independent rotation speeds. Vertices are projected to 2D and connected
 * by pixel-drawn edges using Bresenham-style line walking. The shapes rotate
 * at speeds synced to the BPM and audio energy.
 *
 * <p>Audio reactivity:
 * <ul>
 *   <li>Beat phase drives base rotation speed (synced to BPM)</li>
 *   <li>Beat triggers a sudden rotation speed burst</li>
 *   <li>Bass controls shape scale — expands on heavy bass</li>
 *   <li>Band energies control vertex brightness per shape layer</li>
 *   <li>High frequencies add a neon glow trail effect</li>
 * </ul>
 *
 * <p>Multiple nested shapes at different sizes and rotation speeds create
 * a sense of depth, like looking into a geometric tunnel.
 */
public class BitmapRotatingGeometry extends BitmapPattern {

    // Cube vertices (unit cube centered at origin)
    private static final double[][] CUBE_VERTICES = {
        {-1, -1, -1}, {-1, -1,  1}, {-1,  1, -1}, {-1,  1,  1},
        { 1, -1, -1}, { 1, -1,  1}, { 1,  1, -1}, { 1,  1,  1}
    };

    // Cube edges (vertex index pairs)
    private static final int[][] CUBE_EDGES = {
        {0,1}, {0,2}, {0,4}, {1,3}, {1,5}, {2,3},
        {2,6}, {3,7}, {4,5}, {4,6}, {5,7}, {6,7}
    };

    // Octahedron vertices (unit octahedron)
    private static final double[][] OCTA_VERTICES = {
        { 0,  1,  0}, { 0, -1,  0},
        { 1,  0,  0}, {-1,  0,  0},
        { 0,  0,  1}, { 0,  0, -1}
    };

    // Octahedron edges
    private static final int[][] OCTA_EDGES = {
        {0,2}, {0,3}, {0,4}, {0,5},
        {1,2}, {1,3}, {1,4}, {1,5},
        {2,4}, {4,3}, {3,5}, {5,2}
    };

    /** Rotation angles for X, Y, Z axes. */
    private double rotX = 0, rotY = 0, rotZ = 0;

    /** Beat speed burst. */
    private double beatBurst = 0.0;

    /** Previous frame buffer for trail effect. */
    private int[] trailBuffer;
    private int lastWidth;
    private int lastHeight;

    public BitmapRotatingGeometry() {
        super("bmp_geometry", "Rotating Geometry",
              "Spinning neon wireframe polyhedra synced to the beat");
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        int pixelCount = w * h;

        // Lazy-init trail buffer
        if (trailBuffer == null || lastWidth != w || lastHeight != h) {
            trailBuffer = new int[pixelCount];
            lastWidth = w;
            lastHeight = h;
        }

        double bass = audio.getBass();
        double mid = audio.getMid();
        double high = audio.getHigh();
        double amplitude = audio.getAmplitude();

        // Beat burst: sudden rotation speed increase
        if (audio.isBeat()) {
            beatBurst = Math.max(beatBurst, audio.getBeatIntensity());
        } else {
            beatBurst *= 0.88;
        }

        // Rotation speed: base + amplitude + beat burst
        double rotSpeed = 0.02 + amplitude * 0.04 + beatBurst * 0.1;
        rotX += rotSpeed * 0.7;
        rotY += rotSpeed * 1.0;
        rotZ += rotSpeed * 0.3;

        // --- Trail fade: dim previous frame for glow trails ---
        double trailFade = 0.5 + high * 0.3; // More trails with high frequencies
        for (int i = 0; i < pixelCount; i++) {
            int argb = trailBuffer[i];
            int r = (int) (((argb >> 16) & 0xFF) * trailFade);
            int g = (int) (((argb >> 8) & 0xFF) * trailFade);
            int b = (int) ((argb & 0xFF) * trailFade);
            trailBuffer[i] = BitmapFrameBuffer.packARGB(255, r, g, b);
        }

        // --- Render shapes ---
        double centerX = w * 0.5;
        double centerY = h * 0.5;
        double baseScale = Math.min(w, h) * 0.2;

        // Shape 1: Outer cube (bass-driven)
        double cubeScale = baseScale * (0.8 + bass * 0.6);
        int cubeColor = BitmapFrameBuffer.fromHSB(
                (float) ((time * 30) % 360), 0.9f,
                (float) Math.min(1.0, 0.5 + bass * 0.5));
        renderShape(trailBuffer, w, h, centerX, centerY,
                CUBE_VERTICES, CUBE_EDGES, cubeScale,
                rotX, rotY, rotZ, cubeColor);

        // Shape 2: Inner octahedron (mid-driven, different rotation)
        double octaScale = baseScale * (0.4 + mid * 0.4);
        int octaColor = BitmapFrameBuffer.fromHSB(
                (float) ((time * 30 + 120) % 360), 0.85f,
                (float) Math.min(1.0, 0.5 + mid * 0.5));
        renderShape(trailBuffer, w, h, centerX, centerY,
                OCTA_VERTICES, OCTA_EDGES, octaScale,
                -rotX * 1.3, rotY * 0.8, -rotZ * 1.1, octaColor);

        // Shape 3: Tiny inner cube (high-driven, fast rotation)
        double innerScale = baseScale * (0.2 + high * 0.25);
        int innerColor = BitmapFrameBuffer.fromHSB(
                (float) ((time * 30 + 240) % 360), 0.8f,
                (float) Math.min(1.0, 0.4 + high * 0.6));
        renderShape(trailBuffer, w, h, centerX, centerY,
                CUBE_VERTICES, CUBE_EDGES, innerScale,
                rotX * 2.0, -rotY * 1.5, rotZ * 2.5, innerColor);

        // --- Copy trail buffer to frame buffer ---
        buffer.loadPixels(trailBuffer);
    }

    /**
     * Render a 3D wireframe shape into a pixel array.
     */
    private void renderShape(int[] pixels, int w, int h,
                              double cx, double cy,
                              double[][] vertices, int[][] edges,
                              double scale,
                              double rx, double ry, double rz,
                              int color) {
        // Precompute sin/cos for rotation
        double sinX = Math.sin(rx), cosX = Math.cos(rx);
        double sinY = Math.sin(ry), cosY = Math.cos(ry);
        double sinZ = Math.sin(rz), cosZ = Math.cos(rz);

        // Project all vertices to 2D
        int[] projX = new int[vertices.length];
        int[] projY = new int[vertices.length];

        for (int i = 0; i < vertices.length; i++) {
            double x = vertices[i][0];
            double y = vertices[i][1];
            double z = vertices[i][2];

            // Rotate around X axis
            double y1 = y * cosX - z * sinX;
            double z1 = y * sinX + z * cosX;

            // Rotate around Y axis
            double x2 = x * cosY + z1 * sinY;
            double z2 = -x * sinY + z1 * cosY;

            // Rotate around Z axis
            double x3 = x2 * cosZ - y1 * sinZ;
            double y3 = x2 * sinZ + y1 * cosZ;

            // Simple perspective projection
            double perspectiveFactor = 3.0 / (3.0 + z2 * 0.5);
            projX[i] = (int) (cx + x3 * scale * perspectiveFactor);
            projY[i] = (int) (cy + y3 * scale * perspectiveFactor);
        }

        // Draw edges using Bresenham line algorithm
        for (int[] edge : edges) {
            drawLine(pixels, w, h,
                    projX[edge[0]], projY[edge[0]],
                    projX[edge[1]], projY[edge[1]],
                    color);
        }

        // Draw vertex points (brighter)
        int vertexColor = brighten(color, 1.5);
        for (int i = 0; i < vertices.length; i++) {
            setPixelAdditive(pixels, w, h, projX[i], projY[i], vertexColor);
        }
    }

    /**
     * Bresenham line drawing into a pixel array.
     */
    private void drawLine(int[] pixels, int w, int h,
                           int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        // Limit iterations to prevent infinite loops on large coordinates
        int maxSteps = (dx + dy) * 2 + 1;
        maxSteps = Math.min(maxSteps, 500);

        for (int step = 0; step < maxSteps; step++) {
            setPixelAdditive(pixels, w, h, x0, y0, color);

            if (x0 == x1 && y0 == y1) break;

            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    /** Set a pixel with additive blending into a raw pixel array. */
    private void setPixelAdditive(int[] pixels, int w, int h, int x, int y, int color) {
        if (x < 0 || x >= w || y < 0 || y >= h) return;
        int idx = y * w + x;
        int existing = pixels[idx];
        int r = Math.min(255, ((existing >> 16) & 0xFF) + ((color >> 16) & 0xFF));
        int g = Math.min(255, ((existing >> 8) & 0xFF) + ((color >> 8) & 0xFF));
        int b = Math.min(255, (existing & 0xFF) + (color & 0xFF));
        pixels[idx] = BitmapFrameBuffer.packARGB(255, r, g, b);
    }

    /** Brighten a color by a multiplier, clamped to 255. */
    private static int brighten(int color, double factor) {
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((color & 0xFF) * factor));
        return BitmapFrameBuffer.packARGB(255, r, g, b);
    }

    @Override
    public void reset() {
        rotX = 0;
        rotY = 0;
        rotZ = 0;
        beatBurst = 0.0;
        trailBuffer = null;
    }

    @Override
    public void onResize(int width, int height) {
        trailBuffer = null;
        lastWidth = 0;
        lastHeight = 0;
    }
}
