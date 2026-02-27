package com.audioviz.bitmap.gamestate;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Pixel firework overlay — bursts spawn on beat drops or player interaction.
 *
 * <p>Players right-clicking with a designated item (or the VJ server)
 * can trigger firework bursts at specific positions on the wall.
 * Can be used as a standalone pattern or composited over another.
 */
public class FireworkPattern extends BitmapPattern {

    /** Pending firework spawns from external triggers. */
    private final ConcurrentLinkedQueue<float[]> pendingSpawns = new ConcurrentLinkedQueue<>();

    /** Active firework particles. */
    private final List<Firework> fireworks = new ArrayList<>();

    private final Random rng = new Random();
    private boolean autoSpawnOnBeat = true;
    private int beatCounter = 0;
    private int beatsPerFirework = 4;

    public FireworkPattern() {
        super("bmp_fireworks", "Fireworks",
              "Pixel art firework bursts with beat-sync and player triggers");
    }

    /**
     * Trigger a firework at a normalized position (0-1, 0-1).
     * Thread-safe.
     */
    public void spawn(float normX, float normY) {
        pendingSpawns.add(new float[]{normX, normY});
    }

    public void setAutoSpawnOnBeat(boolean enabled) { this.autoSpawnOnBeat = enabled; }
    public void setBeatsPerFirework(int beats) { this.beatsPerFirework = Math.max(1, beats); }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        // Fade existing pixels (trail effect)
        int[] pixels = buffer.getRawPixels();
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (int) (((c >> 16) & 0xFF) * 0.88);
            int g = (int) (((c >> 8) & 0xFF) * 0.88);
            int b = (int) ((c & 0xFF) * 0.88);
            pixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }

        // Beat-triggered spawns
        if (audio != null && audio.isBeat() && autoSpawnOnBeat) {
            beatCounter++;
            if (beatCounter >= beatsPerFirework) {
                beatCounter = 0;
                // Random position, upper half of screen
                pendingSpawns.add(new float[]{
                    0.15f + rng.nextFloat() * 0.7f,
                    0.1f + rng.nextFloat() * 0.4f
                });
            }
        }

        // Process pending spawns
        float[] spawn;
        while ((spawn = pendingSpawns.poll()) != null) {
            int cx = (int) (spawn[0] * buffer.getWidth());
            int cy = (int) (spawn[1] * buffer.getHeight());
            fireworks.add(new Firework(cx, cy, rng));
        }

        // Update and render fireworks
        Iterator<Firework> it = fireworks.iterator();
        while (it.hasNext()) {
            Firework fw = it.next();
            fw.tick();
            if (fw.isDead()) {
                it.remove();
                continue;
            }
            fw.render(buffer);
        }
    }

    @Override
    public void reset() {
        fireworks.clear();
        pendingSpawns.clear();
        beatCounter = 0;
    }

    // ========== Firework Simulation ==========

    private static class Firework {
        final int cx, cy;
        final Particle[] particles;
        int age = 0;
        final int maxAge;
        final int color;

        Firework(int cx, int cy, Random rng) {
            this.cx = cx;
            this.cy = cy;
            this.maxAge = 15 + rng.nextInt(10);

            // Random bright color
            int hue = rng.nextInt(360);
            this.color = hsvToArgb(hue, 0.9, 1.0);

            // Spawn burst of particles
            int count = 8 + rng.nextInt(8);
            particles = new Particle[count];
            for (int i = 0; i < count; i++) {
                double angle = (Math.PI * 2 * i) / count + rng.nextDouble() * 0.3;
                double speed = 1.0 + rng.nextDouble() * 1.5;
                particles[i] = new Particle(
                    cx, cy,
                    (float) (Math.cos(angle) * speed),
                    (float) (Math.sin(angle) * speed)
                );
            }
        }

        void tick() {
            age++;
            for (Particle p : particles) {
                p.x += p.vx;
                p.y += p.vy;
                p.vy += 0.05f; // Gravity
                p.vx *= 0.96f; // Drag
                p.vy *= 0.96f;
            }
        }

        boolean isDead() { return age >= maxAge; }

        void render(BitmapFrameBuffer buffer) {
            float fade = 1.0f - (float) age / maxAge;
            fade = fade * fade; // Quadratic falloff
            int alpha = (int) (255 * fade);
            if (alpha < 10) return;

            int fadedColor = (alpha << 24) | (color & 0x00FFFFFF);

            for (Particle p : particles) {
                int px = (int) p.x;
                int py = (int) p.y;
                if (px >= 0 && px < buffer.getWidth() && py >= 0 && py < buffer.getHeight()) {
                    // Additive blend for glow
                    int existing = buffer.getPixel(px, py);
                    int r = Math.min(255, ((existing >> 16) & 0xFF) + (int) (((color >> 16) & 0xFF) * fade));
                    int g = Math.min(255, ((existing >> 8) & 0xFF) + (int) (((color >> 8) & 0xFF) * fade));
                    int b = Math.min(255, ((existing) & 0xFF) + (int) (((color) & 0xFF) * fade));
                    buffer.setPixel(px, py, BitmapFrameBuffer.packARGB(255, r, g, b));
                }
            }
        }

        private static int hsvToArgb(int h, double s, double v) {
            double c = v * s;
            double x = c * (1 - Math.abs((h / 60.0) % 2 - 1));
            double m = v - c;
            double r, g, b;
            if (h < 60)       { r = c; g = x; b = 0; }
            else if (h < 120) { r = x; g = c; b = 0; }
            else if (h < 180) { r = 0; g = c; b = x; }
            else if (h < 240) { r = 0; g = x; b = c; }
            else if (h < 300) { r = x; g = 0; b = c; }
            else               { r = c; g = 0; b = x; }
            return BitmapFrameBuffer.packARGB(255,
                (int) ((r + m) * 255), (int) ((g + m) * 255), (int) ((b + m) * 255));
        }
    }

    private static class Particle {
        float x, y, vx, vy;
        Particle(float x, float y, float vx, float vy) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy;
        }
    }
}
