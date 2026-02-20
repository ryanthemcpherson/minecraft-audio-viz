"""SMTP email service for password reset and notifications."""

from __future__ import annotations

import logging
from email.message import EmailMessage

import aiosmtplib

from app.config import Settings

_logger = logging.getLogger(__name__)


async def send_password_reset_email(
    *,
    to_email: str,
    reset_token: str,
    settings: Settings,
) -> None:
    """Send a password reset email via SMTP.

    Raises RuntimeError if SMTP is not configured.
    """
    if not settings.smtp_host:
        raise RuntimeError("Email not configured: MCAV_SMTP_HOST is empty")

    reset_link = f"{settings.base_url}/reset-password?token={reset_token}"

    text_body = (
        f"You requested a password reset for your MCAV account.\n\n"
        f"Click the link below to reset your password:\n{reset_link}\n\n"
        f"This link expires in {settings.password_reset_expiry_minutes} minutes.\n\n"
        f"If you did not request this, you can safely ignore this email."
    )

    html_body = (
        f"<html><body>"
        f"<h2>Reset your MCAV password</h2>"
        f"<p>You requested a password reset for your MCAV account.</p>"
        f'<p><a href="{reset_link}" style="display:inline-block;padding:12px 24px;'
        f"background:#00d4ff;color:#000;text-decoration:none;border-radius:8px;"
        f'font-weight:bold;">Reset Password</a></p>'
        f"<p>This link expires in {settings.password_reset_expiry_minutes} minutes.</p>"
        f"<p>If you did not request this, you can safely ignore this email.</p>"
        f'<p style="color:#888;font-size:12px;">Or copy this link: {reset_link}</p>'
        f"</body></html>"
    )

    msg = EmailMessage()
    msg["From"] = settings.smtp_from_email
    msg["To"] = to_email
    msg["Subject"] = "MCAV - Password Reset"
    msg.set_content(text_body)
    msg.add_alternative(html_body, subtype="html")

    await aiosmtplib.send(
        msg,
        hostname=settings.smtp_host,
        port=settings.smtp_port,
        username=settings.smtp_user or None,
        password=settings.smtp_password or None,
        start_tls=True,
    )

    _logger.info("Password reset email sent to %s", to_email)


async def send_verification_email(
    *,
    to_email: str,
    token: str,
    settings: Settings,
) -> None:
    """Send an email verification link via SMTP.

    Raises RuntimeError if SMTP is not configured.
    """
    if not settings.smtp_host:
        raise RuntimeError("Email not configured: MCAV_SMTP_HOST is empty")

    verify_link = f"{settings.base_url}/verify-email?token={token}"

    text_body = (
        f"Welcome to MCAV! Please verify your email address.\n\n"
        f"Click the link below to verify:\n{verify_link}\n\n"
        f"This link expires in 24 hours.\n\n"
        f"If you did not create an account, you can safely ignore this email."
    )

    html_body = (
        f"<html><body>"
        f"<h2>Verify your MCAV email</h2>"
        f"<p>Welcome to MCAV! Please verify your email address to get started.</p>"
        f'<p><a href="{verify_link}" style="display:inline-block;padding:12px 24px;'
        f"background:#00d4ff;color:#000;text-decoration:none;border-radius:8px;"
        f'font-weight:bold;">Verify Email</a></p>'
        f"<p>This link expires in 24 hours.</p>"
        f"<p>If you did not create an account, you can safely ignore this email.</p>"
        f'<p style="color:#888;font-size:12px;">Or copy this link: {verify_link}</p>'
        f"</body></html>"
    )

    msg = EmailMessage()
    msg["From"] = settings.smtp_from_email
    msg["To"] = to_email
    msg["Subject"] = "MCAV - Verify Your Email"
    msg.set_content(text_body)
    msg.add_alternative(html_body, subtype="html")

    await aiosmtplib.send(
        msg,
        hostname=settings.smtp_host,
        port=settings.smtp_port,
        username=settings.smtp_user or None,
        password=settings.smtp_password or None,
        start_tls=True,
    )

    _logger.info("Verification email sent to %s", to_email)
