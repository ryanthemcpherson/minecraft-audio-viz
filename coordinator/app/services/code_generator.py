"""Connect-code generation in WORD-XXXX format.

Codes are case-insensitive and collision-checked against the database.
"""

from __future__ import annotations

import secrets
from typing import TYPE_CHECKING

from sqlalchemy import select

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db import Show

# 24 four-letter music-themed words
MUSIC_WORDS: list[str] = [
    "BASS", "BEAT", "DROP", "DRUM", "ECHO", "FADE", "FLOW", "FUNK",
    "GLOW", "HYPE", "JAZZ", "KICK", "LOOP", "MIDI", "NOTE", "PEAK",
    "RAVE", "RIFF", "SYNC", "TAPE", "TONE", "TUNE", "VIBE", "WAVE",
]

# 30 unambiguous alphanumeric characters (no 0/O/1/I/L)
SAFE_CHARS: str = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"


def _random_suffix(length: int = 4) -> str:
    """Return a random string of *length* characters from ``SAFE_CHARS``."""
    return "".join(secrets.choice(SAFE_CHARS) for _ in range(length))


def generate_code() -> str:
    """Generate a single WORD-XXXX connect code (not collision-checked)."""
    word = secrets.choice(MUSIC_WORDS)
    suffix = _random_suffix()
    return f"{word}-{suffix}"


async def generate_unique_code(session: AsyncSession, max_attempts: int = 10) -> str:
    """Generate a WORD-XXXX code that does not collide with any active show.

    Raises ``RuntimeError`` if a unique code cannot be produced within
    *max_attempts* tries (astronomically unlikely given the 19M keyspace).
    """
    for _ in range(max_attempts):
        code = generate_code()
        # Check for active shows already using this code
        stmt = select(Show).where(
            Show.connect_code == code,
            Show.status == "active",
        )
        result = await session.execute(stmt)
        existing = result.scalar_one_or_none()
        if existing is None:
            return code

    raise RuntimeError(
        f"Failed to generate a unique connect code after {max_attempts} attempts"
    )


def normalise_code(raw: str) -> str:
    """Normalise user input to uppercase, stripping whitespace."""
    return raw.strip().upper()
