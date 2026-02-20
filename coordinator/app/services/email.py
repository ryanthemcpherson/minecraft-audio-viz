"""Email service using Resend for transactional emails."""

from __future__ import annotations

import logging

import resend

from app.config import Settings

_logger = logging.getLogger(__name__)


def _ensure_configured(settings: Settings) -> None:
    if not settings.resend_api_key:
        raise RuntimeError("Email not configured: MCAV_RESEND_API_KEY is empty")
    resend.api_key = settings.resend_api_key


async def send_password_reset_email(
    *,
    to_email: str,
    reset_token: str,
    settings: Settings,
) -> None:
    """Send a password reset email via Resend."""
    _ensure_configured(settings)

    reset_link = f"{settings.base_url}/reset-password?token={reset_token}"

    resend.Emails.send(
        {
            "from": settings.email_from,
            "to": [to_email],
            "subject": "MCAV - Password Reset",
            "html": (
                "<html><body>"
                "<h2>Reset your MCAV password</h2>"
                "<p>You requested a password reset for your MCAV account.</p>"
                f'<p><a href="{reset_link}" style="display:inline-block;padding:12px 24px;'
                "background:#00d4ff;color:#000;text-decoration:none;border-radius:8px;"
                'font-weight:bold;">Reset Password</a></p>'
                f"<p>This link expires in {settings.password_reset_expiry_minutes} minutes.</p>"
                "<p>If you did not request this, you can safely ignore this email.</p>"
                f'<p style="color:#888;font-size:12px;">Or copy this link: {reset_link}</p>'
                "</body></html>"
            ),
            "text": (
                f"You requested a password reset for your MCAV account.\n\n"
                f"Click the link below to reset your password:\n{reset_link}\n\n"
                f"This link expires in {settings.password_reset_expiry_minutes} minutes.\n\n"
                f"If you did not request this, you can safely ignore this email."
            ),
        }
    )

    _logger.info("Password reset email sent to %s", to_email)


async def send_verification_email(
    *,
    to_email: str,
    token: str,
    settings: Settings,
) -> None:
    """Send an email verification link via Resend."""
    _ensure_configured(settings)

    verify_link = f"{settings.base_url}/verify-email?token={token}"

    resend.Emails.send(
        {
            "from": settings.email_from,
            "to": [to_email],
            "subject": "MCAV - Verify Your Email",
            "html": (
                "<html><body>"
                "<h2>Verify your MCAV email</h2>"
                "<p>Welcome to MCAV! Please verify your email address to get started.</p>"
                f'<p><a href="{verify_link}" style="display:inline-block;padding:12px 24px;'
                "background:#00d4ff;color:#000;text-decoration:none;border-radius:8px;"
                'font-weight:bold;">Verify Email</a></p>'
                "<p>This link expires in 24 hours.</p>"
                "<p>If you did not create an account, you can safely ignore this email.</p>"
                f'<p style="color:#888;font-size:12px;">Or copy this link: {verify_link}</p>'
                "</body></html>"
            ),
            "text": (
                f"Welcome to MCAV! Please verify your email address.\n\n"
                f"Click the link below to verify:\n{verify_link}\n\n"
                f"This link expires in 24 hours.\n\n"
                f"If you did not create an account, you can safely ignore this email."
            ),
        }
    )

    _logger.info("Verification email sent to %s", to_email)
