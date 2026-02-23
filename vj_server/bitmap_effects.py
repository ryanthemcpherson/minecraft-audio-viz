"""Bitmap frame post-processing effects (bloom, brightness mapping)."""

from __future__ import annotations


def luminance(argb: int) -> float:
    """Perceived luminance (0-1) from an ARGB int (ITU-R BT.709)."""
    r = (argb >> 16) & 0xFF
    g = (argb >> 8) & 0xFF
    b = argb & 0xFF
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0


def bloom(
    pixels: list[int],
    width: int,
    height: int,
    threshold: float = 0.5,
    radius: int = 1,
    strength: float = 0.4,
) -> list[int]:
    """Apply bloom halo to bright pixels.

    For each pixel above the luminance threshold, bleed a semi-transparent
    version of its color into neighboring dark pixels.

    Args:
        pixels: Flat ARGB int array (length = width * height).
        width: Frame width.
        height: Frame height.
        threshold: Luminance threshold (0-1) for a pixel to emit bloom.
        radius: Bloom spread in pixels (1-3).
        strength: Blend strength (0-1) for the halo.

    Returns:
        New pixel array with bloom applied.
    """
    out = list(pixels)
    lum_cache = [luminance(p) for p in pixels]

    for y in range(height):
        for x in range(width):
            idx = y * width + x
            if lum_cache[idx] < threshold:
                continue

            src = pixels[idx]
            sr = (src >> 16) & 0xFF
            sg = (src >> 8) & 0xFF
            sb = src & 0xFF

            for dy in range(-radius, radius + 1):
                for dx in range(-radius, radius + 1):
                    if dx == 0 and dy == 0:
                        continue
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < width and 0 <= ny < height:
                        nidx = ny * width + nx
                        if lum_cache[nidx] < threshold:
                            dist = max(abs(dx), abs(dy))
                            falloff = strength / dist

                            dst = out[nidx]
                            dr = (dst >> 16) & 0xFF
                            dg = (dst >> 8) & 0xFF
                            db = dst & 0xFF

                            nr = min(255, dr + int(sr * falloff))
                            ng = min(255, dg + int(sg * falloff))
                            nb = min(255, db + int(sb * falloff))

                            out[nidx] = (0xFF << 24) | (nr << 16) | (ng << 8) | nb

    return out


def compute_brightness(
    pixels: list[int],
    base_brightness: int = 12,
    beat_boost: int = 3,
    is_beat: bool = False,
) -> list[int]:
    """Compute per-pixel brightness from pixel luminance.

    Maps pixel luminance to brightness 0-15. On beat, adds a boost.

    Args:
        pixels: ARGB pixel array.
        base_brightness: Maximum brightness for full-luminance pixels.
        beat_boost: Extra brightness added on beat (clamped to 15).
        is_beat: Whether a beat was detected this frame.

    Returns:
        Brightness array (0-15 per pixel).
    """
    boost = beat_boost if is_beat else 0
    result = []
    for argb in pixels:
        lum = luminance(argb)
        b = int(lum * base_brightness) + boost
        result.append(max(0, min(15, b)))
    return result
