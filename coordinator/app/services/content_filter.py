"""Slur detection for user-facing text fields.

Uses ``better-profanity`` loaded with a **custom curated list** of severe
slurs only (racial, ethnic, homophobic).  General profanity ("damn", "hell",
etc.) is intentionally NOT flagged.

The library auto-generates common leet-speak / substitution variants for
every word in the list and uses word-boundary matching, so we only need to
supply canonical forms.

# SYNC: keep SEVERE_SLURS list in sync with vj_server/content_filter.py
"""

from __future__ import annotations

import re
import unicodedata

from better_profanity import profanity

# ---------------------------------------------------------------------------
# Curated word list — severe slurs only
# ---------------------------------------------------------------------------
# These are canonical forms.  ``better-profanity`` auto-expands each entry
# with common leet-speak substitutions (e.g. 1→i, 0→o, @→a, 3→e, $→s).
#
# Criteria for inclusion:
#   1. Widely recognised as a severe slur targeting a protected group.
#   2. No common benign meaning that would trigger false positives.
#
# This list replaces the library's default 400+ word list so that everyday
# profanity is never flagged.
# ---------------------------------------------------------------------------

SEVERE_SLURS: list[str] = [
    # Anti-Black
    "nigger",
    "nigga",
    "niggers",
    "niggas",
    "coon",
    "coons",
    "darkie",
    "darkies",
    "jiggaboo",
    "jigaboo",
    "sambo",
    # Anti-Asian
    "chink",
    "chinks",
    "gook",
    "gooks",
    "zipperhead",
    # Anti-Hispanic
    "spic",
    "spick",
    "spics",
    "wetback",
    "wetbacks",
    "beaner",
    "beaners",
    # Anti-Jewish
    "kike",
    "kikes",
    # Anti-LGBTQ
    "faggot",
    "faggots",
    "fag",
    "fags",
    "dyke",
    "dykes",
    "tranny",
    "trannies",
    # Anti-Roma / general ethnic
    "gyppo",
    "raghead",
    "ragheads",
    "towelhead",
    "towelheads",
    "camel jockey",
    # Anti-white (also severe)
    # Disability-related slurs
    "retard",
    "retards",
    "retarded",
]

# ---------------------------------------------------------------------------
# Initialise the profanity filter with our custom list
# ---------------------------------------------------------------------------
profanity.load_censor_words(SEVERE_SLURS)

# Zero-width and invisible Unicode characters that could be inserted to
# bypass the filter.
_INVISIBLE_RE = re.compile(
    "["
    "\u200b"  # zero-width space
    "\u200c"  # zero-width non-joiner
    "\u200d"  # zero-width joiner
    "\u200e"  # LTR mark
    "\u200f"  # RTL mark
    "\u2060"  # word joiner
    "\ufeff"  # BOM / zero-width no-break space
    "\u00ad"  # soft hyphen
    "\u034f"  # combining grapheme joiner
    "\u061c"  # Arabic letter mark
    "\u115f"  # Hangul choseong filler
    "\u1160"  # Hangul jungseong filler
    "\u17b4"  # Khmer vowel inherent aq
    "\u17b5"  # Khmer vowel inherent aa
    "\u180e"  # Mongolian vowel separator
    "]+"
)


def _normalise(text: str) -> str:
    """Strip invisible chars and apply NFKD Unicode normalisation."""
    text = _INVISIBLE_RE.sub("", text)
    text = unicodedata.normalize("NFKD", text)
    return text


def contains_slur(text: str) -> bool:
    """Return ``True`` if *text* contains a severe slur."""
    if not text:
        return False
    cleaned = _normalise(text)
    return profanity.contains_profanity(cleaned)


_VAGUE_ERROR = "contains language that is not allowed"


def validate_no_slurs(text: str, field_name: str = "value") -> str:
    """Return *text* unchanged, or raise ``ValueError`` if a slur is found.

    Suitable for use inside Pydantic ``@field_validator`` methods.
    """
    if text and contains_slur(text):
        raise ValueError(f"{field_name} {_VAGUE_ERROR}")
    return text
