package com.audioviz.bitmap.text;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * Countdown timer display — large centered digits counting down to zero.
 * Flashes and pulses as it approaches zero for dramatic effect.
 * Used for set transitions and event timing.
 */
public class CountdownPattern extends BitmapPattern {

    private int remainingSeconds = 0;
    private double fractionalTimer = 0;
    private boolean active = false;
    private int textColor = 0xFFFFFFFF;
    private int flashColor = 0xFFFF0000;

    public CountdownPattern() {
        super("bmp_countdown", "Countdown", "Large digit countdown timer with beat flash");
    }

    /**
     * Start a countdown from N seconds.
     */
    public void start(int seconds) {
        this.remainingSeconds = Math.max(0, seconds);
        this.fractionalTimer = 0;
        this.active = true;
    }

    public void stop() {
        this.active = false;
    }

    public boolean isActive() { return active; }
    public int getRemainingSeconds() { return remainingSeconds; }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        if (!active) return;

        buffer.fill(0xFF000000);

        // Advance timer
        fractionalTimer += 0.05; // ~20 TPS
        if (fractionalTimer >= 1.0) {
            fractionalTimer -= 1.0;
            remainingSeconds--;
            if (remainingSeconds <= 0) {
                remainingSeconds = 0;
                active = false;
                // Final flash
                buffer.fill(0xFFFFFFFF);
                return;
            }
        }

        // Format time
        String timeStr;
        if (remainingSeconds >= 60) {
            timeStr = String.format("%d:%02d", remainingSeconds / 60, remainingSeconds % 60);
        } else {
            timeStr = String.valueOf(remainingSeconds);
        }

        // Pulse effect as countdown gets low
        int color = textColor;
        if (remainingSeconds <= 10) {
            double pulse = Math.sin(time * 6) * 0.5 + 0.5;
            color = BitmapFrameBuffer.lerpColor(textColor, flashColor, pulse);
        }
        if (remainingSeconds <= 3) {
            // Big flash on each second
            double flash = 1.0 - fractionalTimer;
            flash = flash * flash; // Quadratic falloff
            int bg = BitmapFrameBuffer.lerpColor(0xFF000000, flashColor, flash * 0.3);
            buffer.fill(bg);
        }

        BitmapTextRenderer.drawTextCentered(buffer, timeStr, color);
    }

    @Override
    public void reset() {
        active = false;
        remainingSeconds = 0;
    }
}
