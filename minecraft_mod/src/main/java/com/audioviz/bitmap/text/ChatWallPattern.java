package com.audioviz.bitmap.text;

import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.patterns.AudioState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Interactive chat wall — player messages appear on the LED wall.
 *
 * <p>Messages scroll upward as new ones arrive. Each message gets a
 * color based on a hash of the player name for visual variety.
 * The wall holds the last N messages that fit vertically.
 *
 * <p>Fed by a Bukkit {@code AsyncPlayerChatEvent} listener that
 * calls {@link #addMessage}.
 */
public class ChatWallPattern extends BitmapPattern {

    /** Thread-safe input queue from chat events. */
    private final ConcurrentLinkedQueue<ChatMessage> incomingMessages = new ConcurrentLinkedQueue<>();

    /** Active display messages (most recent at bottom). */
    private final Deque<ChatMessage> displayMessages = new ArrayDeque<>();

    /** Max messages visible at once. */
    private int maxVisibleLines = 4;

    /** Scroll animation progress. */
    private double scrollOffset = 0;

    /** Background color. */
    private int backgroundColor = 0xC0000000;

    public ChatWallPattern() {
        super("bmp_chat_wall", "Chat Wall", "Player messages displayed on the LED wall");
    }

    /**
     * Add a message (thread-safe, called from async chat event).
     */
    public void addMessage(String playerName, String message) {
        if (message == null || message.isEmpty()) return;
        // Truncate long messages
        String text = message.length() > 40 ? message.substring(0, 37) + "..." : message;
        int color = nameToColor(playerName);
        incomingMessages.add(new ChatMessage(playerName.toUpperCase(), text.toUpperCase(), color));
    }

    @Override
    public void render(BitmapFrameBuffer buffer, AudioState audio, double time) {
        // Process incoming
        ChatMessage incoming;
        while ((incoming = incomingMessages.poll()) != null) {
            displayMessages.addLast(incoming);
            while (displayMessages.size() > maxVisibleLines + 2) {
                displayMessages.pollFirst();
            }
            scrollOffset = BitmapFont.CHAR_HEIGHT + 2; // Trigger scroll animation
        }

        // Animate scroll
        if (scrollOffset > 0) {
            scrollOffset = Math.max(0, scrollOffset - 1.5);
        }

        buffer.fill(backgroundColor);

        // Calculate visible lines
        int lineHeight = BitmapFont.CHAR_HEIGHT + 2;
        maxVisibleLines = Math.max(1, buffer.getHeight() / lineHeight);

        // Draw messages from bottom up
        int i = 0;
        for (ChatMessage msg : displayMessages) {
            if (i >= maxVisibleLines) break;
            int baseY = buffer.getHeight() - (i + 1) * lineHeight + (int) scrollOffset;

            // Name in color, message in white
            String display = msg.name + ": " + msg.text;
            int nameLen = msg.name.length() + 2; // "NAME: "

            // Draw name portion in player color
            BitmapTextRenderer.drawText(buffer, msg.name + ":",
                1, baseY, msg.color);
            // Draw message in white
            int nameWidth = BitmapFont.measureString(msg.name + ": ");
            BitmapTextRenderer.drawText(buffer, msg.text,
                1 + nameWidth, baseY, 0xFFDDDDDD);

            i++;
        }
    }

    /**
     * Deterministic color from player name.
     */
    private static int nameToColor(String name) {
        int hash = name.hashCode();
        // Generate a bright, saturated color
        float hue = (hash & 0xFFFF) / 65536.0f;
        float sat = 0.7f + (((hash >> 16) & 0xFF) / 255.0f) * 0.3f;
        // HSV to RGB (simplified)
        int hi = (int) (hue * 6) % 6;
        float f = hue * 6 - (int) (hue * 6);
        int v = 230;
        int p = (int) (v * (1 - sat));
        int q = (int) (v * (1 - f * sat));
        int t = (int) (v * (1 - (1 - f) * sat));
        return switch (hi) {
            case 0 -> BitmapFrameBuffer.packARGB(255, v, t, p);
            case 1 -> BitmapFrameBuffer.packARGB(255, q, v, p);
            case 2 -> BitmapFrameBuffer.packARGB(255, p, v, t);
            case 3 -> BitmapFrameBuffer.packARGB(255, p, q, v);
            case 4 -> BitmapFrameBuffer.packARGB(255, t, p, v);
            default -> BitmapFrameBuffer.packARGB(255, v, p, q);
        };
    }

    @Override
    public void reset() {
        incomingMessages.clear();
        displayMessages.clear();
        scrollOffset = 0;
    }

    private record ChatMessage(String name, String text, int color) {}
}
