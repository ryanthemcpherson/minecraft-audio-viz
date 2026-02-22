package com.audioviz.bitmap;

import com.audioviz.patterns.AudioState;

/**
 * Base class for 2D bitmap visualization patterns.
 *
 * <p>While the old VisualizationPattern system calculated 3D entity
 * positions for volumetric effects (spirals, cubes, spheres), BitmapPattern writes
 * pixel colors to a 2D {@link BitmapFrameBuffer} — like an LED wall.
 *
 * <p>This enables an entire category of festival-standard visuals:
 * spectrograms, plasma shaders, scrolling text, image modulation, etc.
 *
 * <p>Patterns write to the shared frame buffer each tick. The
 * {@link BitmapRendererBackend} then pushes changed pixels to TextDisplay entities.
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
     * Called every tick (~20 TPS). The buffer is NOT auto-cleared between frames —
     * patterns that need a clean slate should call {@code buffer.clear()} themselves.
     * Patterns that want trailing/persistence effects (spectrogram, plasma) can
     * skip clearing to build on the previous frame.
     *
     * @param buffer the frame buffer to write pixels into
     * @param audio  current audio analysis state
     * @param time   elapsed time in seconds since pattern started
     */
    public abstract void render(BitmapFrameBuffer buffer, AudioState audio, double time);

    /**
     * Reset pattern state (called when switching patterns).
     */
    public void reset() {
        // Override in subclasses if needed
    }

    /**
     * Called when the buffer dimensions change (zone resize).
     */
    public void onResize(int width, int height) {
        // Override in subclasses if needed
    }
}
