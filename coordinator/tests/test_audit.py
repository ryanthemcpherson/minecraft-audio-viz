"""Unit tests for app.services.audit.

The audit module is a thin logging wrapper (~35 lines), so we just verify
it logs the expected structured event data.
"""

from __future__ import annotations

import logging

from app.services.audit import log_auth_event


class TestLogAuthEvent:
    def test_logs_event_with_all_fields(self, caplog: logging.LogRecord) -> None:
        with caplog.at_level(logging.INFO, logger="audit"):
            log_auth_event(
                "login",
                user_id="u-123",
                email_hash="abc",
                ip_address="1.2.3.4",
                detail="browser login",
            )

        assert len(caplog.records) == 1
        record = caplog.records[0]
        assert "login" in record.message
        assert record.event == "login"  # type: ignore[attr-defined]
        assert record.user_id == "u-123"  # type: ignore[attr-defined]
        assert record.email_hash == "abc"  # type: ignore[attr-defined]
        assert record.ip_address == "1.2.3.4"  # type: ignore[attr-defined]
        assert record.detail == "browser login"  # type: ignore[attr-defined]

    def test_logs_event_with_minimal_fields(self, caplog: logging.LogRecord) -> None:
        with caplog.at_level(logging.INFO, logger="audit"):
            log_auth_event("register")

        assert len(caplog.records) == 1
        record = caplog.records[0]
        assert record.event == "register"  # type: ignore[attr-defined]
        assert record.user_id is None  # type: ignore[attr-defined]
        assert record.email_hash is None  # type: ignore[attr-defined]

    def test_multiple_events_logged_independently(self, caplog: logging.LogRecord) -> None:
        with caplog.at_level(logging.INFO, logger="audit"):
            log_auth_event("login", user_id="u-1")
            log_auth_event("login_failed", user_id="u-2")

        assert len(caplog.records) == 2
        assert caplog.records[0].event == "login"  # type: ignore[attr-defined]
        assert caplog.records[1].event == "login_failed"  # type: ignore[attr-defined]
