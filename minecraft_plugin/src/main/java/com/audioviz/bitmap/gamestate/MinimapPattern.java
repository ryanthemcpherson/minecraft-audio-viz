package com.audioviz.bitmap.gamestate;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Live minimap — shows the stage area from above with pulsing dots
 * for each player. Dots pulse with the beat and scale with proximity
 * to the stage center.
 */
public class MinimapPattern extends BitmapPattern {

    private final AudioVizPlugin plugin;

    /** Map center and range (world coordinates). */
    private double centerX = 0, centerZ = 0;
    private double range = 100; // blocks radius

    private final List<PlayerDot> dots = new ArrayList<>();
    private int refreshCounter = 0;
    private double beatPulse = 0;

    public MinimapPattern(AudioVizPlugin plugin) {
        super("bmp_minimap", "Minimap",
              "Live overhead map with pulsing player dots");
        this.plugin = plugin;
    }

    public void setCenter(double x, double z) { this.centerX = x; this.centerZ = z; }
    public void setRange(double blocks) { this.range = Math.max(10, blocks); }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        // Dark background with subtle grid
        buffer.fill(0xFF0A0A1A);

        int w = buffer.getWidth();
        int h = buffer.getHeight();

        // Grid lines every ~10 blocks
        int gridSpacing = Math.max(2, (int) (10.0 / (range * 2) * w));
        int gridColor = 0xFF151530;
        for (int x = 0; x < w; x += gridSpacing) {
            for (int y = 0; y < h; y++) buffer.setPixel(x, y, gridColor);
        }
        for (int y = 0; y < h; y += gridSpacing) {
            for (int x = 0; x < w; x++) buffer.setPixel(x, y, gridColor);
        }

        // Center crosshair (stage marker)
        int cx = w / 2, cy = h / 2;
        for (int i = -2; i <= 2; i++) {
            buffer.setPixel(cx + i, cy, 0xFF444466);
            buffer.setPixel(cx, cy + i, 0xFF444466);
        }

        // Beat pulse
        if (audio != null && audio.isBeat()) {
            beatPulse = 1.0;
        }
        beatPulse *= 0.9;

        // Refresh player positions
        refreshCounter++;
        if (refreshCounter >= 10) { // Every 0.5s
            refreshCounter = 0;
            refreshPlayers(w, h);
        }

        // Render dots
        for (PlayerDot dot : dots) {
            if (dot.px < 0 || dot.px >= w || dot.py < 0 || dot.py >= h) continue;

            // Pulsing radius
            int radius = 1 + (int) (beatPulse * 1.5);

            // Color based on distance from center (close = bright)
            double dist = Math.sqrt(
                (dot.px - cx) * (dot.px - cx) + (dot.py - cy) * (dot.py - cy));
            double normDist = dist / Math.max(1, Math.sqrt(w * w + h * h) / 2);
            int bright = (int) (255 * (1.0 - normDist * 0.5));
            int dotColor = BitmapFrameBuffer.packARGB(255, bright, (int)(bright * 0.8), 0);

            buffer.fillCircle(dot.px, dot.py, radius, dotColor);

            // Glow ring on beat
            if (beatPulse > 0.3) {
                int glowAlpha = (int) (100 * beatPulse);
                int glow = BitmapFrameBuffer.packARGB(glowAlpha, bright, (int)(bright * 0.6), 0);
                buffer.drawRing(dot.px, dot.py, radius + 1, 1, glow);
            }
        }

        // Player count
        String countStr = String.valueOf(dots.size());
        com.audioviz.bitmap.text.BitmapTextRenderer.drawText(buffer, countStr,
            1, 1, 0xFF666688);
    }

    private void refreshPlayers(int bufW, int bufH) {
        dots.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location loc = p.getLocation();
            // Map world coords to pixel coords
            double relX = (loc.getX() - centerX) / (range * 2) + 0.5;
            double relZ = (loc.getZ() - centerZ) / (range * 2) + 0.5;
            int px = (int) (relX * bufW);
            int py = (int) (relZ * bufH);
            dots.add(new PlayerDot(p.getName(), px, py));
        }
    }

    @Override
    public void reset() {
        dots.clear();
        beatPulse = 0;
    }

    private record PlayerDot(String name, int px, int py) {}
}
