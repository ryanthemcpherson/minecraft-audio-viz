package com.audioviz.decorators;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Configuration for a DJ's background banner.
 * Received from the VJ server via banner_config WebSocket messages.
 * Supports text mode (DJ name display) and image mode (pixel-art logo).
 */
public class BannerConfig {

    private String mode;           // "text" or "image"
    private String textStyle;      // "bold", "block", "neon"
    private String textColorMode;  // "frequency", "rainbow", "fixed"
    private String textFixedColor; // Minecraft color code char (0-f)
    private String textFormat;     // Format string, %s = DJ name
    private int gridWidth;         // Pixel grid width for image mode
    private int gridHeight;        // Pixel grid height for image mode
    private int[] imagePixels;     // ARGB pixel data for image mode

    private static final BannerConfig EMPTY = new BannerConfig(
        "text", "bold", "frequency", "f", "%s", 24, 12, null
    );

    public BannerConfig(String mode, String textStyle, String textColorMode,
                        String textFixedColor, String textFormat,
                        int gridWidth, int gridHeight, int[] imagePixels) {
        this.mode = mode != null ? mode : "text";
        this.textStyle = textStyle != null ? textStyle : "bold";
        this.textColorMode = textColorMode != null ? textColorMode : "frequency";
        this.textFixedColor = textFixedColor != null ? textFixedColor : "f";
        this.textFormat = textFormat != null ? textFormat : "%s";
        this.gridWidth = Math.max(4, Math.min(48, gridWidth));
        this.gridHeight = Math.max(2, Math.min(24, gridHeight));
        this.imagePixels = imagePixels;
    }

    /**
     * Returns an empty/default banner config.
     */
    public static BannerConfig empty() {
        return EMPTY;
    }

    /**
     * Parse a BannerConfig from a JSON message.
     */
    public static BannerConfig fromJson(JsonObject json) {
        String mode = json.has("banner_mode") ? json.get("banner_mode").getAsString() : "text";
        String textStyle = json.has("text_style") ? json.get("text_style").getAsString() : "bold";
        String textColorMode = json.has("text_color_mode") ? json.get("text_color_mode").getAsString() : "frequency";
        String textFixedColor = json.has("text_fixed_color") ? json.get("text_fixed_color").getAsString() : "f";
        String textFormat = json.has("text_format") ? json.get("text_format").getAsString() : "%s";
        int gridWidth = json.has("grid_width") ? json.get("grid_width").getAsInt() : 24;
        int gridHeight = json.has("grid_height") ? json.get("grid_height").getAsInt() : 12;

        int[] imagePixels = null;
        if (json.has("image_pixels") && json.get("image_pixels").isJsonArray()) {
            JsonArray arr = json.getAsJsonArray("image_pixels");
            imagePixels = new int[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                imagePixels[i] = arr.get(i).getAsInt();
            }
        }

        return new BannerConfig(mode, textStyle, textColorMode, textFixedColor, textFormat,
                                gridWidth, gridHeight, imagePixels);
    }

    // ========== Getters ==========

    public String getMode() { return mode; }
    public String getTextStyle() { return textStyle; }
    public String getTextColorMode() { return textColorMode; }
    public String getTextFixedColor() { return textFixedColor; }
    public String getTextFormat() { return textFormat; }
    public int getGridWidth() { return gridWidth; }
    public int getGridHeight() { return gridHeight; }
    public int[] getImagePixels() { return imagePixels; }

    public boolean isTextMode() { return "text".equals(mode); }
    public boolean isImageMode() { return "image".equals(mode); }
    public boolean hasImagePixels() { return imagePixels != null && imagePixels.length > 0; }

    @Override
    public String toString() {
        return "BannerConfig[mode=" + mode + ", style=" + textStyle
            + ", colorMode=" + textColorMode + ", grid=" + gridWidth + "x" + gridHeight
            + ", pixels=" + (imagePixels != null ? imagePixels.length : 0) + "]";
    }
}
