"""Structured audit logging for authentication events."""

from __future__ import annotations

import logging

_audit_logger = logging.getLogger("audit")


def log_auth_event(
    event: str,
    *,
    user_id: str | None = None,
    email_hash: str | None = None,
    ip_address: str | None = None,
    detail: str | None = None,
) -> None:
    """Log an authentication event with structured extra fields.

    Events: register, login, login_failed, login_locked, password_change,
    password_reset_request, password_reset_complete, email_verified,
    account_deleted, session_revoked, token_refreshed
    """
    _audit_logger.info(
        "auth_event: %s",
        event,
        extra={
            "event": event,
            "user_id": user_id,
            "email_hash": email_hash,
            "ip_address": ip_address,
            "detail": detail,
        },
    )
