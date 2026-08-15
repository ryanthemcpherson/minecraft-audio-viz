"""Unit tests for app.services.email.

Mocks the resend SDK to test email sending logic and configuration checks
without making real API calls.
"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from app.config import Settings
from app.services.email import send_password_reset_email, send_verification_email


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _settings_with_resend() -> Settings:
    return Settings(
        database_url="sqlite+aiosqlite:///:memory:",
        user_jwt_secret="test-user-jwt-secret-for-tests-32+chars",
        resend_api_key="re_test_key_12345",
        email_from="MCAV <noreply@mcav.live>",
        base_url="https://mcav.live",
        password_reset_expiry_minutes=30,
    )


def _settings_no_resend() -> Settings:
    return Settings(
        database_url="sqlite+aiosqlite:///:memory:",
        user_jwt_secret="test-user-jwt-secret-for-tests-32+chars",
        resend_api_key="",
    )


# ---------------------------------------------------------------------------
# Configuration checks
# ---------------------------------------------------------------------------


class TestEnsureConfigured:
    async def test_raises_when_resend_not_configured(self) -> None:
        with pytest.raises(RuntimeError, match="Email not configured"):
            await send_password_reset_email(
                to_email="user@example.com",
                reset_token="tok-123",
                settings=_settings_no_resend(),
            )

    async def test_raises_for_verification_when_not_configured(self) -> None:
        with pytest.raises(RuntimeError, match="Email not configured"):
            await send_verification_email(
                to_email="user@example.com",
                token="tok-123",
                settings=_settings_no_resend(),
            )


# ---------------------------------------------------------------------------
# send_password_reset_email
# ---------------------------------------------------------------------------


class TestSendPasswordResetEmail:
    @patch("app.services.email.resend")
    async def test_sends_email_with_correct_payload(self, mock_resend: MagicMock) -> None:
        mock_resend.Emails.send = MagicMock()

        await send_password_reset_email(
            to_email="user@example.com",
            reset_token="reset-tok-abc",
            settings=_settings_with_resend(),
        )

        mock_resend.Emails.send.assert_called_once()
        payload = mock_resend.Emails.send.call_args.args[0]
        assert payload["from"] == "MCAV <noreply@mcav.live>"
        assert payload["to"] == ["user@example.com"]
        assert payload["subject"] == "MCAV - Password Reset"
        assert "reset-tok-abc" in payload["html"]
        assert "reset-tok-abc" in payload["text"]
        assert "https://mcav.live/reset-password" in payload["html"]

    @patch("app.services.email.resend")
    async def test_sets_api_key(self, mock_resend: MagicMock) -> None:
        mock_resend.Emails.send = MagicMock()

        await send_password_reset_email(
            to_email="user@example.com",
            reset_token="tok",
            settings=_settings_with_resend(),
        )

        assert mock_resend.api_key == "re_test_key_12345"


# ---------------------------------------------------------------------------
# send_verification_email
# ---------------------------------------------------------------------------


class TestSendVerificationEmail:
    @patch("app.services.email.resend")
    async def test_sends_email_with_correct_payload(self, mock_resend: MagicMock) -> None:
        mock_resend.Emails.send = MagicMock()

        await send_verification_email(
            to_email="newuser@example.com",
            token="verify-tok-xyz",
            settings=_settings_with_resend(),
        )

        mock_resend.Emails.send.assert_called_once()
        payload = mock_resend.Emails.send.call_args.args[0]
        assert payload["from"] == "MCAV <noreply@mcav.live>"
        assert payload["to"] == ["newuser@example.com"]
        assert payload["subject"] == "MCAV - Verify Your Email"
        assert "verify-tok-xyz" in payload["html"]
        assert "verify-tok-xyz" in payload["text"]
        assert "https://mcav.live/verify-email" in payload["html"]
