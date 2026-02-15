"""Tests for the slur content filter."""

import pytest
from app.services.content_filter import contains_slur, validate_no_slurs

# ---------------------------------------------------------------------------
# Clean text should always pass
# ---------------------------------------------------------------------------


class TestCleanText:
    def test_normal_dj_names(self):
        assert not contains_slur("DJ BassDropper")
        assert not contains_slur("The Groove Machine")
        assert not contains_slur("Chill Vibes Only")
        assert not contains_slur("xX_BeAtMaStEr_Xx")

    def test_empty_and_whitespace(self):
        assert not contains_slur("")
        assert not contains_slur("   ")
        assert not contains_slur(None)  # type: ignore[arg-type]

    def test_numbers_and_symbols(self):
        assert not contains_slur("DJ 808")
        assert not contains_slur("bass >>> treble")
        assert not contains_slur("100% vibes")


# ---------------------------------------------------------------------------
# Basic profanity should NOT be flagged (only severe slurs)
# ---------------------------------------------------------------------------


class TestBasicProfanityAllowed:
    def test_common_swear_words_pass(self):
        assert not contains_slur("damn")
        assert not contains_slur("hell")
        assert not contains_slur("crap")
        assert not contains_slur("ass")
        assert not contains_slur("bastard")


# ---------------------------------------------------------------------------
# Severe slurs MUST be caught
# ---------------------------------------------------------------------------


class TestSevereSlursCaught:
    def test_racial_slurs(self):
        assert contains_slur("nigger")
        assert contains_slur("nigga")

    def test_ethnic_slurs(self):
        assert contains_slur("kike")
        assert contains_slur("wetback")
        assert contains_slur("chink")
        assert contains_slur("gook")

    def test_homophobic_slurs(self):
        assert contains_slur("faggot")
        assert contains_slur("tranny")

    def test_slur_in_sentence(self):
        assert contains_slur("DJ nigga beats")
        assert contains_slur("the faggot show")

    def test_case_insensitive(self):
        assert contains_slur("NIGGER")
        assert contains_slur("Faggot")
        assert contains_slur("KIKE")


# ---------------------------------------------------------------------------
# Leet-speak evasion should be caught
# ---------------------------------------------------------------------------


class TestLeetSpeakCaught:
    def test_common_substitutions(self):
        assert contains_slur("n1gger")
        assert contains_slur("f@ggot")
        assert contains_slur("n1gg3r")


# ---------------------------------------------------------------------------
# Zero-width character evasion should be caught
# ---------------------------------------------------------------------------


class TestZeroWidthBypass:
    def test_zero_width_space_stripped(self):
        # Insert zero-width space between letters
        assert contains_slur("nig\u200bger")
        assert contains_slur("fag\u200dgot")

    def test_soft_hyphen_stripped(self):
        assert contains_slur("nig\u00adger")


# ---------------------------------------------------------------------------
# Scunthorpe problem — benign words must NOT be flagged
# ---------------------------------------------------------------------------


class TestScunthorpeSafe:
    def test_words_containing_slur_substrings(self):
        assert not contains_slur("Scunthorpe")
        assert not contains_slur("classic")
        assert not contains_slur("snigger")  # British English "to laugh"


# ---------------------------------------------------------------------------
# validate_no_slurs() helper
# ---------------------------------------------------------------------------


class TestValidateNoSlurs:
    def test_clean_text_returns_unchanged(self):
        assert validate_no_slurs("DJ Cool", "DJ name") == "DJ Cool"

    def test_none_returns_none(self):
        # Pydantic optional fields may pass None
        assert validate_no_slurs(None, "DJ name") is None  # type: ignore[arg-type]

    def test_slur_raises_value_error(self):
        with pytest.raises(ValueError, match="contains language that is not allowed"):
            validate_no_slurs("nigger", "DJ name")

    def test_error_includes_field_name(self):
        with pytest.raises(ValueError, match="display_name"):
            validate_no_slurs("faggot", "display_name")

    def test_error_never_reveals_slur(self):
        """The error message must be vague — never echoing the detected word."""
        with pytest.raises(ValueError) as exc_info:
            validate_no_slurs("nigger", "DJ name")
        assert "nigger" not in str(exc_info.value)
