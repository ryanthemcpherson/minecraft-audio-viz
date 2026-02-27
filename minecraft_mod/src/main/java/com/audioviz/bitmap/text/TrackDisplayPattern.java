package com.audioviz.bitmap.text;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

/**
 * "Now Playing" track display pattern.
 *
 * <p>Shows artist and track title centered on the bitmap wall with
 * fade-in → hold → fade-out animation. Designed to overlay briefly
 * when the DJ switches tracks, then return to the underlying pattern.
 *
 * <p>The VJ server triggers this via the {@code bitmap_track_display}
 * WebSocket message with artist/title from track metadata.
 */
public class TrackDisplayPattern extends BitmapPattern {

    private String artist = "";
    private String title = "";

    /** Phase: 0=idle, 1=fade-in, 2=hold, 3=fade-out */
    private int phase = 0;
    private double phaseTimer = 0;

    /** Timing in seconds. */
    private double fadeInDuration = 0.5;
    private double holdDuration = 4.0;
    private double fadeOutDuration = 1.0;

    /** Colors. */
    private int artistColor = 0xFFFFCC00; // Gold
    private int titleColor = 0xFFFFFFFF;  // White
    private int backgroundColor = 0xE0000000; // Semi-transparent black

    /** Animation state. */
    private double lastTime = -1;

    public TrackDisplayPattern() {
        super("bmp_track_display", "Track Display",
              "Now Playing overlay with fade animation");
    }

    /**
     * Set the current track info. Triggers the display animation.
     */
    public void setTrack(String artist, String title) {
        this.artist = artist != null ? artist.toUpperCase() : "";
        this.title = title != null ? title.toUpperCase() : "";
        this.phase = 1; // Start fade-in
        this.phaseTimer = 0;
    }

    /**
     * Check if the display is currently active (visible).
     */
    public boolean isActive() {
        return phase > 0;
    }

    public void setArtistColor(int argb) { this.artistColor = argb; }
    public void setTitleColor(int argb) { this.titleColor = argb; }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        if (phase == 0) return; // Idle — don't touch buffer (transparent overlay)

        // Advance timer using actual time delta
        double dt;
        if (lastTime < 0) {
            dt = 0.05; // First tick fallback
        } else {
            dt = time - lastTime;
        }
        lastTime = time;
        phaseTimer += dt;

        double opacity = 0;
        switch (phase) {
            case 1: // Fade in
                opacity = Math.min(1.0, phaseTimer / fadeInDuration);
                if (phaseTimer >= fadeInDuration) {
                    phase = 2;
                    phaseTimer = 0;
                }
                break;
            case 2: // Hold
                opacity = 1.0;
                if (phaseTimer >= holdDuration) {
                    phase = 3;
                    phaseTimer = 0;
                }
                break;
            case 3: // Fade out
                opacity = 1.0 - Math.min(1.0, phaseTimer / fadeOutDuration);
                if (phaseTimer >= fadeOutDuration) {
                    phase = 0;
                    phaseTimer = 0;
                    return;
                }
                break;
        }

        // Draw semi-transparent background overlay
        int bgAlpha = (int) (((backgroundColor >> 24) & 0xFF) * opacity);
        int bg = (bgAlpha << 24) | (backgroundColor & 0x00FFFFFF);
        blendOverlay(buffer, bg);

        // Fade text alpha
        int aA = (int) (((artistColor >> 24) & 0xFF) * opacity);
        int tA = (int) (((titleColor >> 24) & 0xFF) * opacity);
        int ac = (aA << 24) | (artistColor & 0x00FFFFFF);
        int tc = (tA << 24) | (titleColor & 0x00FFFFFF);

        // Draw text
        if (!artist.isEmpty() && !title.isEmpty()) {
            BitmapTextRenderer.drawTwoLines(buffer, artist, ac, title, tc);
        } else {
            String text = !title.isEmpty() ? title : artist;
            BitmapTextRenderer.drawTextCentered(buffer, text, tc);
        }
    }

    /**
     * Blend a semi-transparent color over the entire buffer.
     */
    private void blendOverlay(BitmapFrameBuffer buffer, int overlayARGB) {
        int oa = (overlayARGB >> 24) & 0xFF;
        if (oa == 0) return;
        float alpha = oa / 255.0f;

        int or_ = (overlayARGB >> 16) & 0xFF;
        int og = (overlayARGB >> 8) & 0xFF;
        int ob = overlayARGB & 0xFF;

        int[] pixels = buffer.getRawPixels();
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (int) (((c >> 16) & 0xFF) * (1 - alpha) + or_ * alpha);
            int g = (int) (((c >> 8) & 0xFF) * (1 - alpha) + og * alpha);
            int b = (int) ((c & 0xFF) * (1 - alpha) + ob * alpha);
            pixels[i] = BitmapFrameBuffer.packARGB(255, r, g, b);
        }
    }

    @Override
    public void reset() {
        phase = 0;
        phaseTimer = 0;
        lastTime = -1;
    }
}
