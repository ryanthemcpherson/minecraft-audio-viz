package com.audioviz.bitmap.gamestate;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bitmap.BitmapFrameBuffer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Modulates bitmap output based on Minecraft game state.
 *
 * <p>Applies environmental color grading and intensity modulation from:
 * <ul>
 *   <li><b>Time of day</b>: warm palette during sunset, cool at night, bright at noon</li>
 *   <li><b>Weather</b>: desaturated during rain, lightning flashes during storms</li>
 *   <li><b>Player count</b>: energy ramps with more players in the zone</li>
 * </ul>
 *
 * <p>Applied as a post-process step after the pattern renders but before
 * the effects processor. The VJ can enable/disable each modulator independently.
 */
public class GameStateModulator {

    private final AudioVizPlugin plugin;

    private boolean timeOfDayEnabled = true;
    private boolean weatherEnabled = true;
    private boolean crowdEnergyEnabled = true;

    /** Cached world state (refreshed every 20 ticks). */
    private long worldTime = 6000;    // Noon
    private boolean isRaining = false;
    private boolean isThundering = false;
    private int nearbyPlayerCount = 0;
    private int refreshCounter = 0;

    /** The zone to count nearby players around. */
    private String targetZone = "main";
    private double crowdRadius = 50.0;

    // Smooth values for gradual transitions
    private double smoothTimeHue = 0;
    private double smoothWeatherDesaturation = 0;
    private double smoothCrowdEnergy = 0;

    public GameStateModulator(AudioVizPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Refresh world state (call from main thread, every ~20 ticks).
     */
    public void refreshWorldState() {
        refreshCounter++;
        if (refreshCounter < 20) return; // Refresh every second
        refreshCounter = 0;

        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (world == null) return;

        worldTime = world.getTime();
        isRaining = world.hasStorm();
        isThundering = world.isThundering();

        // Count players near the stage (proximity-filtered)
        nearbyPlayerCount = 0;
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        // If a stage center location is known, filter by distance
        // For now, use world spawn as reference point; the VJ server
        // can set centerX/centerZ via config
        double cx = world.getSpawnLocation().getX();
        double cz = world.getSpawnLocation().getZ();
        for (Player player : players) {
            if (!player.getWorld().equals(world)) continue;
            double dx = player.getLocation().getX() - cx;
            double dz = player.getLocation().getZ() - cz;
            if (dx * dx + dz * dz <= crowdRadius * crowdRadius) {
                nearbyPlayerCount++;
            }
        }
    }

    /**
     * Apply game state modulation to a rendered frame.
     * Call after pattern rendering, before effects processing.
     */
    public void modulate(BitmapFrameBuffer buffer, double dt) {
        if (timeOfDayEnabled) {
            applyTimeOfDay(buffer, dt);
        }
        if (weatherEnabled) {
            applyWeather(buffer, dt);
        }
        // Crowd energy is exposed as a multiplier, not directly applied here
    }

    /**
     * Get the crowd energy multiplier (0.0 = empty, 1.0+ = energetic).
     * Patterns can use this to intensify their output.
     */
    public double getCrowdEnergy() {
        if (!crowdEnergyEnabled) return 1.0;
        double target = Math.min(2.0, nearbyPlayerCount / 10.0); // 10 players = 1.0
        smoothCrowdEnergy += (target - smoothCrowdEnergy) * 0.05;
        return 0.5 + smoothCrowdEnergy * 0.5; // Range: 0.5 to 1.5
    }

    // ========== Time of Day ==========

    private void applyTimeOfDay(BitmapFrameBuffer buffer, double dt) {
        // Minecraft day: 0=sunrise(6am), 6000=noon, 12000=sunset, 18000=midnight
        double phase = (worldTime % 24000) / 24000.0;

        // Color temperature based on time
        float warmth = 0;      // -1 = cool blue, 0 = neutral, 1 = warm orange
        float dimming = 1.0f;  // Brightness factor

        if (phase < 0.25) {
            // 6am-12pm: sunrise to noon — warming up
            warmth = (float) (0.3 * (1.0 - phase / 0.25));
            dimming = 0.9f + (float) (0.1 * phase / 0.25);
        } else if (phase < 0.5) {
            // 12pm-6pm: noon to sunset — getting warm
            double t = (phase - 0.25) / 0.25;
            warmth = (float) (0.5 * t);
            dimming = 1.0f;
        } else if (phase < 0.55) {
            // 6pm-7:12pm: golden hour — warmest
            warmth = 0.6f;
            dimming = 0.95f;
        } else if (phase < 0.75) {
            // 7:12pm-12am: evening to midnight — cooling, dimming
            double t = (phase - 0.55) / 0.2;
            warmth = (float) (0.6 - 1.0 * t); // warm → cool
            dimming = (float) (0.95 - 0.15 * t);
        } else {
            // 12am-6am: night — cool and dim
            warmth = -0.3f;
            dimming = 0.8f;
        }

        // Smooth transitions
        double targetHue = warmth;
        smoothTimeHue += (targetHue - smoothTimeHue) * 0.02;

        // Apply: shift R/B channels based on warmth
        if (Math.abs(smoothTimeHue) > 0.01 || dimming < 0.99) {
            int[] pixels = buffer.getRawPixels();
            float w = (float) smoothTimeHue;
            for (int i = 0; i < pixels.length; i++) {
                int c = pixels[i];
                int a = (c >> 24) & 0xFF;
                int r = (int) Math.min(255, ((c >> 16) & 0xFF) * dimming + 30 * w);
                int g = (int) (((c >> 8) & 0xFF) * dimming);
                int b = (int) Math.min(255, (c & 0xFF) * dimming - 20 * w);
                r = Math.max(0, Math.min(255, r));
                b = Math.max(0, Math.min(255, b));
                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }

    // ========== Weather ==========

    private void applyWeather(BitmapFrameBuffer buffer, double dt) {
        // Target desaturation based on weather
        double target = 0;
        if (isThundering) {
            target = 0.4; // Heavy desaturation + lightning flashes
        } else if (isRaining) {
            target = 0.2; // Mild desaturation
        }

        smoothWeatherDesaturation += (target - smoothWeatherDesaturation) * 0.05;

        if (smoothWeatherDesaturation > 0.01) {
            desaturate(buffer, (float) smoothWeatherDesaturation);
        }

        // Lightning flash during thunderstorm
        if (isThundering && Math.random() < 0.02) { // ~2% chance per tick = ~0.4 flashes/sec
            flashBuffer(buffer, 0xFFEEEEFF, 0.5f);
        }
    }

    // ========== Helpers ==========

    private static void desaturate(BitmapFrameBuffer buffer, float amount) {
        int[] pixels = buffer.getRawPixels();
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int a = (c >> 24) & 0xFF;
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int gray = (int) (r * 0.299 + g * 0.587 + b * 0.114);
            r = (int) (r + (gray - r) * amount);
            g = (int) (g + (gray - g) * amount);
            b = (int) (b + (gray - b) * amount);
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static void flashBuffer(BitmapFrameBuffer buffer, int flashColor, float intensity) {
        int[] pixels = buffer.getRawPixels();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = BitmapFrameBuffer.lerpColor(pixels[i], flashColor, intensity);
        }
    }

    // ========== Configuration ==========

    public void setTimeOfDayEnabled(boolean enabled) { this.timeOfDayEnabled = enabled; }
    public void setWeatherEnabled(boolean enabled) { this.weatherEnabled = enabled; }
    public void setCrowdEnergyEnabled(boolean enabled) { this.crowdEnergyEnabled = enabled; }
    public void setCrowdRadius(double radius) { this.crowdRadius = radius; }

    public boolean isTimeOfDayEnabled() { return timeOfDayEnabled; }
    public boolean isWeatherEnabled() { return weatherEnabled; }
    public boolean isCrowdEnergyEnabled() { return crowdEnergyEnabled; }
    public int getNearbyPlayerCount() { return nearbyPlayerCount; }
}
