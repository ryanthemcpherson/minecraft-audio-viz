package com.audioviz.bitmap.gamestate;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.bitmap.text.BitmapTextRenderer;
import com.audioviz.patterns.AudioState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * "Crowd Cam" — selects a random nearby player and displays their
 * name on the LED wall with a spotlight effect. Cycles between players
 * on the beat, creating an interactive "jumbotron" experience.
 *
 * <p>Since we can't render actual player skins at 5×7 pixel font
 * resolution, this instead:
 * <ul>
 *   <li>Shows player names in large text with flashy animations</li>
 *   <li>Cycles through nearby players on beat drops</li>
 *   <li>Adds particle-style accents around the text</li>
 * </ul>
 */
public class CrowdCamPattern extends BitmapPattern {

    private final AudioVizPlugin plugin;

    /** Names of nearby players. */
    private final List<String> nearbyPlayers = new ArrayList<>();

    /** Currently featured player. */
    private String currentPlayer = "";
    private int currentIndex = 0;
    private double displayTimer = 0;
    private double flashIntensity = 0;

    /** Refresh player list every N ticks. */
    private int refreshCounter = 0;
    private static final int REFRESH_INTERVAL = 40; // 2 seconds

    /** How long each player is shown (in beats). */
    private int beatsPerPlayer = 8;
    private int beatCount = 0;

    // Sparkle effect
    private final Random sparkleRng = new Random();

    public CrowdCamPattern(AudioVizPlugin plugin) {
        super("bmp_crowd_cam", "Crowd Cam",
              "Jumbotron — features nearby player names with beat-synced cycling");
        this.plugin = plugin;
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        buffer.fill(0xFF000000);

        // Refresh player list periodically (must be called from main thread context)
        refreshCounter++;
        if (refreshCounter >= REFRESH_INTERVAL) {
            refreshCounter = 0;
            refreshNearbyPlayers();
        }

        if (nearbyPlayers.isEmpty()) {
            // No players — show idle animation
            int pulse = (int) (100 + 50 * Math.sin(time * 2));
            int color = BitmapFrameBuffer.packARGB(255, pulse, pulse, pulse);
            BitmapTextRenderer.drawTextCentered(buffer, "WAITING...", color);
            return;
        }

        // Beat cycling
        if (audio != null && audio.isBeat()) {
            beatCount++;
            flashIntensity = 1.0;
            if (beatCount >= beatsPerPlayer) {
                beatCount = 0;
                cyclePlayer();
            }
        }

        // Flash decay
        flashIntensity *= 0.85;

        // Background: subtle gradient pulse
        double amplitude = audio != null ? audio.getAmplitude() : 0;
        int bgBright = (int) (10 + amplitude * 30);
        buffer.fill(BitmapFrameBuffer.packARGB(255, bgBright, 0, bgBright));

        // Sparkle effects
        int sparkleCount = (int) (5 + flashIntensity * 20);
        for (int i = 0; i < sparkleCount; i++) {
            int sx = sparkleRng.nextInt(buffer.getWidth());
            int sy = sparkleRng.nextInt(buffer.getHeight());
            int bright = 80 + sparkleRng.nextInt(175);
            buffer.setPixel(sx, sy, BitmapFrameBuffer.packARGB(255, bright, bright, bright));
        }

        // Player name — outlined for readability
        String displayName = currentPlayer.toUpperCase();

        // Beat flash: bright accent
        int nameColor;
        if (flashIntensity > 0.5) {
            nameColor = 0xFFFFFF00; // Yellow flash
        } else {
            // Cycle through neon colors
            double hue = (time * 30 + currentIndex * 60) % 360;
            nameColor = hsvToArgb(hue, 0.8, 1.0);
        }

        BitmapTextRenderer.drawTextOutlined(buffer, displayName,
            (buffer.getWidth() - com.audioviz.bitmap.text.BitmapFont.measureString(displayName)) / 2,
            (buffer.getHeight() - com.audioviz.bitmap.text.BitmapFont.CHAR_HEIGHT) / 2,
            nameColor, 0xFF333333);

        // Flash overlay
        if (flashIntensity > 0.1) {
            int[] pixels = buffer.getRawPixels();
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = BitmapFrameBuffer.lerpColor(pixels[i], 0xFFFFFFFF,
                    (float) (flashIntensity * 0.3));
            }
        }
    }

    private void cyclePlayer() {
        if (nearbyPlayers.isEmpty()) return;
        currentIndex = (currentIndex + 1) % nearbyPlayers.size();
        currentPlayer = nearbyPlayers.get(currentIndex);
    }

    private void refreshNearbyPlayers() {
        nearbyPlayers.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            nearbyPlayers.add(p.getName());
        }
        // If current player is no longer nearby, cycle
        if (!nearbyPlayers.isEmpty() && !nearbyPlayers.contains(currentPlayer)) {
            currentIndex = 0;
            currentPlayer = nearbyPlayers.get(0);
        }
    }

    private static int hsvToArgb(double h, double s, double v) {
        h = h % 360;
        double c = v * s;
        double x = c * (1 - Math.abs((h / 60) % 2 - 1));
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

    @Override
    public void reset() {
        nearbyPlayers.clear();
        currentPlayer = "";
        currentIndex = 0;
        beatCount = 0;
        flashIntensity = 0;
    }
}
