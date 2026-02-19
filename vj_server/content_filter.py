"""Slur detection for user-facing text in the VJ server.

Uses ``better-profanity`` loaded with a **custom curated list** of severe
slurs only (racial, ethnic, homophobic).  General profanity ("damn", "hell",
etc.) is intentionally NOT flagged.

The library auto-generates common leet-speak / substitution variants for
every word in the list and uses word-boundary matching.

# SYNC: keep SEVERE_SLURS list in sync with coordinator/app/services/content_filter.py
"""

from __future__ import annotations

import re
import unicodedata

from better_profanity import profanity

# ---------------------------------------------------------------------------
# Curated word list — severe slurs only
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

    Suitable for use as a validation helper.
    """
    if text and contains_slur(text):
        raise ValueError(f"{field_name} {_VAGUE_ERROR}")
    return text
