"""
Bitmap (LED wall) rendering client for the VJ server.

Provides a high-level API for rendering 2D pixel art visuals on Minecraft
bitmap zones. Works alongside the built-in Java patterns but allows
VJ-server-driven rendering for advanced effects (image modulation,
video playback, custom shaders, etc.).

Usage:
    from vj_server.bitmap_client import BitmapClient

    client = BitmapClient(viz_client, "main", 32, 16)
    await client.initialize()

    # Per-tick rendering
    client.clear(0xFF000000)           # Black
    client.fill_rect(0, 0, 5, 16, 0xFFFF0000)  # Red bar
    await client.push_frame()

    # Or switch to a built-in Java-side pattern
    await client.set_pattern("bmp_plasma")
"""

import base64
import logging
import struct

logger = logging.getLogger(__name__)


def pack_argb(a: int, r: int, g: int, b: int) -> int:
    """Pack ARGB components (0-255 each) into a single 32-bit integer."""
    return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF)


def from_rgb(r: int, g: int, b: int) -> int:
    """Create opaque ARGB from RGB components (0-255)."""
    return pack_argb(255, r, g, b)


def from_hsb(h: float, s: float, b: float) -> int:
    """Create opaque ARGB from HSB (hue 0-360, saturation 0-1, brightness 0-1)."""
    import colorsys

    h_norm = (h % 360) / 360.0
    r, g, bl = colorsys.hsv_to_rgb(h_norm, max(0, min(1, s)), max(0, min(1, b)))
    return from_rgb(int(r * 255), int(g * 255), int(bl * 255))


def lerp_color(c1: int, c2: int, t: float) -> int:
    """Linearly interpolate between two ARGB colors."""
    t = max(0.0, min(1.0, t))
    a1 = (c1 >> 24) & 0xFF
    r1 = (c1 >> 16) & 0xFF
    g1 = (c1 >> 8) & 0xFF
    b1 = c1 & 0xFF
    a2 = (c2 >> 24) & 0xFF
    r2 = (c2 >> 16) & 0xFF
    g2 = (c2 >> 8) & 0xFF
    b2 = c2 & 0xFF
    return pack_argb(
        int(a1 + (a2 - a1) * t),
        int(r1 + (r2 - r1) * t),
        int(g1 + (g2 - g1) * t),
        int(b1 + (b2 - b1) * t),
    )


class BitmapBuffer:
    """2D pixel buffer for composing bitmap frames.

    Row-major ARGB pixel array. (0,0) is top-left.
    """

    __slots__ = ("width", "height", "pixels")

    def __init__(self, width: int, height: int):
        self.width = width
        self.height = height
        self.pixels = [0xFF000000] * (width * height)  # Opaque black

    def clear(self, color: int = 0xFF000000) -> None:
        """Fill entire buffer with a single color."""
        for i in range(len(self.pixels)):
            self.pixels[i] = color

    def set_pixel(self, x: int, y: int, color: int) -> None:
        """Set a single pixel. Bounds-checked."""
        if 0 <= x < self.width and 0 <= y < self.height:
            self.pixels[y * self.width + x] = color

    def get_pixel(self, x: int, y: int) -> int:
        """Get a pixel color. Returns 0 for out-of-bounds."""
        if 0 <= x < self.width and 0 <= y < self.height:
            return self.pixels[y * self.width + x]
        return 0

    def fill_rect(self, x: int, y: int, w: int, h: int, color: int) -> None:
        """Fill a rectangle."""
        for py in range(max(0, y), min(self.height, y + h)):
            for px in range(max(0, x), min(self.width, x + w)):
                self.pixels[py * self.width + px] = color

    def fill_column(self, x: int, y_start: int, y_end: int, color: int) -> None:
        """Fill a vertical column from y_start to y_end (inclusive)."""
        if x < 0 or x >= self.width:
            return
        for y in range(max(0, y_start), min(self.height, y_end + 1)):
            self.pixels[y * self.width + x] = color

    def fill_column_gradient(
        self, x: int, y_start: int, y_end: int, color_top: int, color_bottom: int
    ) -> None:
        """Fill a column with a vertical gradient."""
        if x < 0 or x >= self.width:
            return
        span = max(1, y_end - y_start)
        for y in range(max(0, y_start), min(self.height, y_end + 1)):
            t = (y - y_start) / span
            self.pixels[y * self.width + x] = lerp_color(color_top, color_bottom, t)

    def scroll_left(self) -> None:
        """Scroll all pixels one column left. Rightmost column becomes black."""
        w, h = self.width, self.height
        for y in range(h):
            row = y * w
            for x in range(w - 1):
                self.pixels[row + x] = self.pixels[row + x + 1]
            self.pixels[row + w - 1] = 0xFF000000

    def apply_brightness(self, factor: float) -> None:
        """Multiply all pixel brightness by a factor (for fade effects)."""
        f = max(0.0, min(1.0, factor))
        for i in range(len(self.pixels)):
            c = self.pixels[i]
            a = (c >> 24) & 0xFF
            r = int(((c >> 16) & 0xFF) * f)
            g = int(((c >> 8) & 0xFF) * f)
            b = int((c & 0xFF) * f)
            self.pixels[i] = (a << 24) | (r << 16) | (g << 8) | b

    def to_base64(self) -> str:
        """Encode pixel array as little-endian base64 for wire transport."""
        raw = struct.pack(f"<{len(self.pixels)}I", *[p & 0xFFFFFFFF for p in self.pixels])
        return base64.b64encode(raw).decode("ascii")


class BitmapClient:
    """High-level bitmap rendering client.

    Wraps VizClient with zone-specific bitmap operations and a local
    frame buffer for composing pixels before pushing.

    Args:
        viz_client: Connected VizClient instance.
        zone_name: Target zone for bitmap rendering.
        width: Grid width in pixels.
        height: Grid height in pixels.
    """

    def __init__(self, viz_client, zone_name: str, width: int = 32, height: int = 16):
        self.viz = viz_client
        self.zone = zone_name
        self.width = width
        self.height = height
        self.buffer = BitmapBuffer(width, height)
        self._initialized = False

    async def initialize(self, pattern: str = "bmp_spectrum") -> bool:
        """Initialize the bitmap grid on the Minecraft server.

        Must be called before push_frame(). Sets up the TextDisplay entity grid.
        """
        result = await self.viz.init_bitmap(self.zone, self.width, self.height, pattern)
        if result:
            self._initialized = True
            # Update dimensions if server clamped them
            self.width = result.get("width", self.width)
            self.height = result.get("height", self.height)
            self.buffer = BitmapBuffer(self.width, self.height)
            return True
        return False

    async def push_frame(self) -> None:
        """Push the current buffer to the Minecraft server (fire-and-forget)."""
        if not self._initialized:
            logger.warning("BitmapClient: push_frame called before initialize()")
            return
        await self.viz.send_bitmap_frame_fast(self.zone, self.buffer.pixels)

    async def set_pattern(self, pattern_id: str) -> bool:
        """Switch to a built-in Java-side pattern (stops VJ-driven rendering)."""
        result = await self.viz.set_bitmap_pattern(self.zone, pattern_id)
        return result is not None

    async def get_patterns(self) -> list:
        """List available built-in patterns."""
        return await self.viz.get_bitmap_patterns()

    # === Convenience drawing methods (delegate to buffer) ===

    def clear(self, color: int = 0xFF000000) -> None:
        self.buffer.clear(color)

    def set_pixel(self, x: int, y: int, color: int) -> None:
        self.buffer.set_pixel(x, y, color)

    def fill_rect(self, x: int, y: int, w: int, h: int, color: int) -> None:
        self.buffer.fill_rect(x, y, w, h, color)

    def fill_column(self, x: int, y_start: int, y_end: int, color: int) -> None:
        self.buffer.fill_column(x, y_start, y_end, color)

    def scroll_left(self) -> None:
        self.buffer.scroll_left()

    def fade(self, factor: float = 0.85) -> None:
        """Fade all pixels toward black (for trail effects)."""
        self.buffer.apply_brightness(factor)
