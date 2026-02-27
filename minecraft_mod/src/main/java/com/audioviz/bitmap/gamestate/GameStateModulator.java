package com.audioviz.bitmap.gamestate;

import com.audioviz.bitmap.BitmapFrameBuffer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

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
 * <p>Ported from Paper: Bukkit.getWorlds() → server.getWorlds(),
 * World.getTime() → world.getTimeOfDay(), Player → ServerPlayerEntity.
 */
public class GameStateModulator {

    private final MinecraftServer server;

    private boolean timeOfDayEnabled = true;
    private boolean weatherEnabled = true;
    private boolean crowdEnergyEnabled = true;

    /** Cached world state (refreshed every 20 ticks). */
    private long worldTime = 6000;    // Noon
    private boolean isRaining = false;
    private boolean isThundering = false;
    private int nearbyPlayerCount = 0;
    private int refreshCounter = 0;

    private double crowdRadius = 50.0;

    // Smooth values for gradual transitions
    private double smoothTimeHue = 0;
    private double smoothWeatherDesaturation = 0;
    private double smoothCrowdEnergy = 0;

    public GameStateModulator(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Refresh world state (call from main thread, every ~20 ticks).
     */
    public void refreshWorldState() {
        refreshCounter++;
        if (refreshCounter < 20) return;
        refreshCounter = 0;

        // Get the overworld (first world)
        ServerWorld world = null;
        for (ServerWorld w : server.getWorlds()) {
            world = w;
            break;
        }
        if (world == null) return;

        worldTime = world.getTimeOfDay();
        isRaining = world.isRaining();
        isThundering = world.isThundering();

        // Count players near spawn (proximity-filtered)
        nearbyPlayerCount = 0;
        BlockPos spawnPos = world.getSpawnPoint().getPos();
        double cx = spawnPos.getX();
        double cz = spawnPos.getZ();

        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        for (ServerPlayerEntity player : players) {
            if (player.getEntityWorld() != world) continue;
            double dx = player.getX() - cx;
            double dz = player.getZ() - cz;
            if (dx * dx + dz * dz <= crowdRadius * crowdRadius) {
                nearbyPlayerCount++;
            }
        }
    }

    /**
     * Apply game state modulation to a rendered frame.
     */
    public void modulate(BitmapFrameBuffer buffer, double dt) {
        if (timeOfDayEnabled) {
            applyTimeOfDay(buffer, dt);
        }
        if (weatherEnabled) {
            applyWeather(buffer, dt);
        }
    }

    public double getCrowdEnergy() {
        if (!crowdEnergyEnabled) return 1.0;
        double target = Math.min(2.0, nearbyPlayerCount / 10.0);
        smoothCrowdEnergy += (target - smoothCrowdEnergy) * 0.05;
        return 0.5 + smoothCrowdEnergy * 0.5;
    }

    // ========== Time of Day ==========

    private void applyTimeOfDay(BitmapFrameBuffer buffer, double dt) {
        double phase = (worldTime % 24000) / 24000.0;

        float warmth = 0;
        float dimming = 1.0f;

        if (phase < 0.25) {
            warmth = (float) (0.3 * (1.0 - phase / 0.25));
            dimming = 0.9f + (float) (0.1 * phase / 0.25);
        } else if (phase < 0.5) {
            double t = (phase - 0.25) / 0.25;
            warmth = (float) (0.5 * t);
            dimming = 1.0f;
        } else if (phase < 0.55) {
            warmth = 0.6f;
            dimming = 0.95f;
        } else if (phase < 0.75) {
            double t = (phase - 0.55) / 0.2;
            warmth = (float) (0.6 - 1.0 * t);
            dimming = (float) (0.95 - 0.15 * t);
        } else {
            warmth = -0.3f;
            dimming = 0.8f;
        }

        double targetHue = warmth;
        smoothTimeHue += (targetHue - smoothTimeHue) * 0.02;

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
        double target = 0;
        if (isThundering) {
            target = 0.4;
        } else if (isRaining) {
            target = 0.2;
        }

        smoothWeatherDesaturation += (target - smoothWeatherDesaturation) * 0.05;

        if (smoothWeatherDesaturation > 0.01) {
            desaturate(buffer, (float) smoothWeatherDesaturation);
        }

        if (isThundering && Math.random() < 0.02) {
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
