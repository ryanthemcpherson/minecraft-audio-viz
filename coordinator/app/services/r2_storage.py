"""Cloudflare R2 (S3-compatible) storage utilities for presigned uploads."""

from __future__ import annotations

import secrets
from typing import TYPE_CHECKING

import boto3
from botocore.config import Config as BotoConfig

if TYPE_CHECKING:
    from app.config import Settings

_CONTENT_TYPES = {"image/jpeg", "image/png", "image/webp"}
_EXTENSIONS = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}
_PRESIGNED_TTL = 300  # 5 minutes


def create_r2_client(settings: Settings):
    """Create a boto3 S3 client configured for Cloudflare R2."""
    return boto3.client(
        "s3",
        endpoint_url=f"https://{settings.r2_account_id}.r2.cloudflarestorage.com",
        aws_access_key_id=settings.r2_access_key_id,
        aws_secret_access_key=settings.r2_secret_access_key,
        config=BotoConfig(signature_version="s3v4"),
        region_name="auto",
    )


def generate_presigned_upload(
    settings: Settings,
    user_id: str,
    context: str,
    content_type: str,
) -> tuple[str, str]:
    """Generate a presigned PUT URL for R2 upload.

    Returns:
        (upload_url, public_url)
    """
    if content_type not in _CONTENT_TYPES:
        raise ValueError(f"Unsupported content type: {content_type}")

    ext = _EXTENSIONS[content_type]
    random_name = secrets.token_urlsafe(16)
    key = f"dj/{context}/{user_id}/{random_name}.{ext}"

    client = create_r2_client(settings)
    upload_url = client.generate_presigned_url(
        "put_object",
        Params={
            "Bucket": settings.r2_bucket_name,
            "Key": key,
            "ContentType": content_type,
        },
        ExpiresIn=_PRESIGNED_TTL,
    )

    public_url = f"{settings.r2_public_url}/{key}"
    return upload_url, public_url
