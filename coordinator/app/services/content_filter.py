"""Slur detection for user-facing text fields.

This module re-exports from ``app.utils.content_filter`` for backwards
compatibility.  New code should import directly from
``app.utils.content_filter``.
"""

from app.utils.content_filter import (  # noqa: F401
    SEVERE_SLURS,
    contains_slur,
    validate_no_slurs,
)

__all__ = ["SEVERE_SLURS", "contains_slur", "validate_no_slurs"]
