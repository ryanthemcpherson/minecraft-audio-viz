package com.audioviz.bitmap;

import com.audioviz.patterns.AudioState;

/**
 * Base class for 2D bitmap visualization patterns.
 * Patterns write pixel colors to a BitmapFrameBuffer each tick.
 * The MapRendererBackend then pushes changed pixels to map items.
 *
 * Direct copy from Paper plugin — no API dependencies.
 */
public abstract class BitmapPattern {

    private final String id;
    private final String name;
    private final String description;

    protected BitmapPattern(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }

    /**
     * Render one frame into the provided buffer.
     * Called every tick (~20 TPS). Buffer is NOT auto-cleared between frames.
     */
    public abstract void render(BitmapFrameBuffer buffer, AudioState audio, double time);

    /** Reset pattern state (called when switching patterns). */
    public void reset() {}

    /** Called when the buffer dimensions change (zone resize). */
    public void onResize(int width, int height) {}
}
