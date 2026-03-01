"""Tests for vj_server.bitmap_effects — luminance, bloom, brightness."""

from vj_server.bitmap_effects import bloom, compute_brightness, luminance


def _argb(r: int, g: int, b: int, a: int = 255) -> int:
    """Build an ARGB int from components."""
    return (a << 24) | (r << 16) | (g << 8) | b


# ============================================================================
# luminance spot-checks
# ============================================================================


class TestLuminance:
    def test_black_is_zero(self):
        assert luminance(_argb(0, 0, 0)) == 0.0

    def test_white_is_one(self):
        assert abs(luminance(_argb(255, 255, 255)) - 1.0) < 0.01

    def test_pure_blue(self):
        """Pure blue should be ~0.0722 (BT.709 blue coefficient)."""
        result = luminance(_argb(0, 0, 255))
        assert abs(result - 0.0722) < 0.01

    def test_pure_red(self):
        """Pure red should be ~0.2126."""
        result = luminance(_argb(255, 0, 0))
        assert abs(result - 0.2126) < 0.01

    def test_pure_green(self):
        """Pure green should be ~0.7152."""
        result = luminance(_argb(0, 255, 0))
        assert abs(result - 0.7152) < 0.01

    def test_mid_gray(self):
        result = luminance(_argb(128, 128, 128))
        assert 0.45 < result < 0.55


# ============================================================================
# bloom — all-dark pixels = identity
# ============================================================================


class TestBloomDarkPixels:
    def test_all_dark_identity(self):
        """Bloom on an all-black frame should return identical pixels."""
        black = _argb(0, 0, 0)
        pixels = [black] * 9
        result = bloom(pixels, 3, 3)
        assert result == pixels

    def test_all_below_threshold_identity(self):
        """Pixels below bloom threshold should pass through unchanged."""
        dark = _argb(10, 10, 10)  # luminance ~0.04, well below 0.5
        pixels = [dark] * 4
        result = bloom(pixels, 2, 2)
        assert result == pixels


# ============================================================================
# bloom — single bright pixel bleeds to neighbors
# ============================================================================


class TestBloomBleeding:
    def test_single_bright_center_bleeds(self):
        """A single bright pixel in the center should increase neighbor values."""
        black = _argb(0, 0, 0)
        white = _argb(255, 255, 255)
        # 3x3 grid with white center
        pixels = [black] * 9
        pixels[4] = white  # center
        result = bloom(pixels, 3, 3, threshold=0.5, radius=1, strength=0.4)
        # Center should remain unchanged (it's above threshold, but bloom writes to neighbors)
        assert result[4] == white
        # At least one neighbor should have increased luminance
        neighbor_changed = any(result[i] != black for i in [1, 3, 5, 7])
        assert neighbor_changed, "bloom did not bleed to any neighbor"

    def test_corner_bright_pixel_no_index_error(self):
        """Bright pixel in corner should not cause index errors."""
        black = _argb(0, 0, 0)
        white = _argb(255, 255, 255)
        pixels = [black] * 9
        pixels[0] = white  # top-left corner
        result = bloom(pixels, 3, 3, threshold=0.5, radius=1, strength=0.4)
        assert len(result) == 9


# ============================================================================
# Output array length matches input
# ============================================================================


class TestBloomOutputLength:
    def test_output_length_matches_input(self):
        pixels = [_argb(100, 100, 100)] * 20
        result = bloom(pixels, 5, 4)
        assert len(result) == len(pixels)

    def test_single_pixel_frame(self):
        """1x1 pixel frame: no neighbors to bleed into, no index errors."""
        pixels = [_argb(255, 255, 255)]
        result = bloom(pixels, 1, 1, threshold=0.5, radius=1, strength=0.4)
        assert len(result) == 1
        # Single pixel above threshold, but no neighbors — should be unchanged
        assert result[0] == pixels[0]


# ============================================================================
# bloom edge cases — radius=0, strength=0.0
# ============================================================================


class TestBloomEdgeCases:
    def test_radius_zero_identity(self):
        """radius=0 means no neighbor offsets, so output should equal input."""
        black = _argb(0, 0, 0)
        white = _argb(255, 255, 255)
        pixels = [black] * 9
        pixels[4] = white
        result = bloom(pixels, 3, 3, threshold=0.5, radius=0, strength=0.4)
        assert result == pixels

    def test_strength_zero_identity(self):
        """strength=0.0 means falloff is 0, so additive contribution is 0."""
        black = _argb(0, 0, 0)
        white = _argb(255, 255, 255)
        pixels = [black] * 9
        pixels[4] = white
        result = bloom(pixels, 3, 3, threshold=0.5, radius=1, strength=0.0)
        assert result == pixels


# ============================================================================
# compute_brightness
# ============================================================================


class TestComputeBrightness:
    def test_black_pixels_zero_brightness(self):
        pixels = [_argb(0, 0, 0)] * 4
        result = compute_brightness(pixels)
        assert result == [0, 0, 0, 0]

    def test_white_pixels_near_base_brightness(self):
        pixels = [_argb(255, 255, 255)] * 2
        result = compute_brightness(pixels, base_brightness=12)
        # luminance(white) ≈ 0.999, so int(0.999 * 12) = 11 due to truncation
        assert all(b >= 11 for b in result)

    def test_beat_boost_applied(self):
        pixels = [_argb(255, 255, 255)]
        no_beat = compute_brightness(pixels, base_brightness=12, beat_boost=3, is_beat=False)
        with_beat = compute_brightness(pixels, base_brightness=12, beat_boost=3, is_beat=True)
        assert with_beat[0] == min(15, no_beat[0] + 3)

    def test_brightness_clamped_to_15(self):
        pixels = [_argb(255, 255, 255)]
        result = compute_brightness(pixels, base_brightness=14, beat_boost=5, is_beat=True)
        assert result[0] <= 15

    def test_output_length_matches_input(self):
        pixels = [_argb(50, 50, 50)] * 10
        result = compute_brightness(pixels)
        assert len(result) == 10
