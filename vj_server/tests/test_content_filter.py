"""Tests for the content filter (slur detection)."""

import pytest

from vj_server.content_filter import contains_slur, validate_no_slurs

# ============================================================================
# contains_slur
# ============================================================================


class TestContainsSlur:
    def test_detects_known_slur(self):
        assert contains_slur("nigger") is True

    def test_detects_slur_in_sentence(self):
        assert contains_slur("hey you are a faggot") is True

    def test_normal_text_passes(self):
        assert contains_slur("hello world") is False

    def test_empty_string_passes(self):
        assert contains_slur("") is False

    def test_none_passes(self):
        # contains_slur checks `if not text: return False`
        assert contains_slur(None) is False

    def test_normal_dj_names_pass(self):
        names = ["DJ Shadow", "CoolBeats42", "AudioWizard", "The Bassline", "xX_Player_Xx"]
        for name in names:
            assert contains_slur(name) is False, f"False positive on: {name}"

    def test_general_profanity_not_flagged(self):
        """General swear words should NOT be flagged — only severe slurs."""
        assert contains_slur("damn") is False
        assert contains_slur("hell") is False
        assert contains_slur("crap") is False

    def test_case_insensitive_detection(self):
        assert contains_slur("NIGGER") is True
        assert contains_slur("Faggot") is True


# ============================================================================
# validate_no_slurs
# ============================================================================


class TestValidateNoSlurs:
    def test_clean_text_returns_unchanged(self):
        assert validate_no_slurs("DJ Shadow", "dj_name") == "DJ Shadow"

    def test_slur_raises_value_error(self):
        with pytest.raises(ValueError, match="not allowed"):
            validate_no_slurs("nigger", "dj_name")

    def test_error_includes_field_name(self):
        with pytest.raises(ValueError, match="dj_name"):
            validate_no_slurs("faggot", "dj_name")

    def test_empty_string_passes(self):
        assert validate_no_slurs("", "field") == ""

    def test_none_passes(self):
        """None input should pass through (guard: `if text and ...`)."""
        result = validate_no_slurs(None, "field")
        assert result is None


# ============================================================================
# Unicode bypass resistance
# ============================================================================


class TestUnicodeBypass:
    def test_zero_width_chars_stripped(self):
        """Invisible chars inserted into a slur should still be detected."""
        # Insert zero-width spaces into a slur
        disguised = "n\u200big\u200bger"
        assert contains_slur(disguised) is True

    def test_zero_width_joiner_stripped(self):
        disguised = "fa\u200dggot"
        assert contains_slur(disguised) is True
