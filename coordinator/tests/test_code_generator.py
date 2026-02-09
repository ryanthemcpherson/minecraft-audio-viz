"""Tests for the WORD-XXXX connect-code generator."""

from __future__ import annotations

import re

import pytest

from app.services.code_generator import (
    MUSIC_WORDS,
    SAFE_CHARS,
    generate_code,
    normalise_code,
)

CODE_RE = re.compile(r"^[A-Z]{4}-[A-Z2-9]{4}$")


class TestGenerateCode:
    """Unit tests for ``generate_code()``."""

    def test_format_matches_pattern(self) -> None:
        for _ in range(50):
            code = generate_code()
            assert CODE_RE.match(code), f"Code {code!r} does not match WORD-XXXX pattern"

    def test_word_portion_is_known(self) -> None:
        for _ in range(50):
            code = generate_code()
            word = code.split("-")[0]
            assert word in MUSIC_WORDS, f"Word {word!r} not in MUSIC_WORDS"

    def test_suffix_uses_safe_chars(self) -> None:
        for _ in range(50):
            code = generate_code()
            suffix = code.split("-")[1]
            for ch in suffix:
                assert ch in SAFE_CHARS, f"Character {ch!r} not in SAFE_CHARS"

    def test_generates_distinct_codes(self) -> None:
        codes = {generate_code() for _ in range(200)}
        # With ~19M keyspace, 200 draws should all be unique
        assert len(codes) == 200

    def test_code_length_is_nine(self) -> None:
        code = generate_code()
        assert len(code) == 9  # WORD (4) + '-' (1) + XXXX (4)


class TestNormaliseCode:
    """Unit tests for ``normalise_code()``."""

    def test_uppercases_lowercase_input(self) -> None:
        assert normalise_code("bass-k7m2") == "BASS-K7M2"

    def test_strips_whitespace(self) -> None:
        assert normalise_code("  BEAT-3X9N  ") == "BEAT-3X9N"

    def test_mixed_case(self) -> None:
        assert normalise_code("Drop-aB3c") == "DROP-AB3C"

    def test_already_normalised(self) -> None:
        assert normalise_code("WAVE-9KPT") == "WAVE-9KPT"
