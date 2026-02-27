package com.audioviz.bitmap.text;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Scrolling horizontal text marquee — the LED wall ticker.
 *
 * <p>Messages queue up and scroll right-to-left across the screen.
 * Scroll speed modulates with audio amplitude for a reactive feel.
 * Background can be transparent (overlay mode) or solid color.
 *
 * <p>Messages can be pushed via WebSocket ({@code bitmap_text} message)
 * or from Minecraft chat events.
 */
public class MarqueePattern extends BitmapPattern {

    /** Queued messages waiting to scroll. */
    private final ConcurrentLinkedQueue<MarqueeMessage> messageQueue = new ConcurrentLinkedQueue<>();

    /** Currently scrolling message. */
    private MarqueeMessage currentMessage;

    /** Current scroll X offset (decrements each tick). */
    private double scrollX;

    /** Base scroll speed in pixels per tick. */
    private double baseSpeed = 1.5;

    /** Background color (0 = transparent / overlay mode). */
    private int backgroundColor = 0xFF000000;

    /** Default text color. */
    private int defaultTextColor = 0xFFFFFFFF;

    public MarqueePattern() {
        super("bmp_marquee", "Marquee", "Scrolling text ticker with audio-reactive speed");
    }

    /**
     * Queue a message to display.
     */
    public void queueMessage(String text) {
        queueMessage(text, defaultTextColor);
    }

    /**
     * Queue a message with a specific color.
     */
    public void queueMessage(String text, int color) {
        if (text != null && !text.isEmpty()) {
            messageQueue.add(new MarqueeMessage(text.toUpperCase(), color));
        }
    }

    public void setBaseSpeed(double pixelsPerTick) {
        this.baseSpeed = Math.max(0.5, Math.min(5.0, pixelsPerTick));
    }

    public void setBackgroundColor(int argb) {
        this.backgroundColor = argb;
    }

    public void setDefaultTextColor(int argb) {
        this.defaultTextColor = argb;
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        // Fill background
        buffer.fill(backgroundColor);

        // If no current message, dequeue
        if (currentMessage == null) {
            currentMessage = messageQueue.poll();
            if (currentMessage == null) {
                // Nothing to show — render idle indicator
                renderIdle(buffer, time);
                return;
            }
            // Start off-screen right
            scrollX = buffer.getWidth();
        }

        // Audio-reactive speed: faster with more energy
        double amplitude = audio != null ? audio.getAmplitude() : 0.0;
        double speed = baseSpeed * (1.0 + amplitude * 0.5);
        scrollX -= speed;

        // Render text at current position
        int textWidth = BitmapFont.measureString(currentMessage.text);
        int textY = (buffer.getHeight() - BitmapFont.CHAR_HEIGHT) / 2;

        BitmapTextRenderer.drawTextWithShadow(buffer, currentMessage.text,
            (int) scrollX, textY, currentMessage.color, 0xFF333333);

        // If fully scrolled off left edge, advance to next message
        if (scrollX + textWidth < 0) {
            currentMessage = null;
        }
    }

    private void renderIdle(BitmapFrameBuffer buffer, double time) {
        // Subtle pulsing dot to show the marquee is active but idle
        int brightness = (int) (80 + 40 * Math.sin(time * 3));
        int dotColor = BitmapFrameBuffer.packARGB(255, brightness, brightness, brightness);
        buffer.setPixel(buffer.getWidth() / 2, buffer.getHeight() / 2, dotColor);
    }

    @Override
    public void reset() {
        messageQueue.clear();
        currentMessage = null;
        scrollX = 0;
    }

    private record MarqueeMessage(String text, int color) {}
}
