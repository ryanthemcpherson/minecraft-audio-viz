"""Unit tests for app.services.r2_storage.

Mocks boto3 to test presigned URL generation and input validation
without needing real R2/S3 credentials.
"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from app.config import Settings
from app.services.r2_storage import generate_presigned_upload


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _r2_settings() -> Settings:
    return Settings(
        database_url="sqlite+aiosqlite:///:memory:",
        user_jwt_secret="test-user-jwt-secret-for-tests-32+chars",
        r2_account_id="test-account-id",
        r2_access_key_id="test-access-key",
        r2_secret_access_key="test-secret-key",
        r2_bucket_name="test-bucket",
        r2_public_url="https://cdn.mcav.live",
    )


# ---------------------------------------------------------------------------
# Input validation
# ---------------------------------------------------------------------------


class TestInputValidation:
    def test_rejects_unsupported_content_type(self) -> None:
        with pytest.raises(ValueError, match="Unsupported content type"):
            generate_presigned_upload(
                _r2_settings(),
                user_id="user-123",
                context="avatar",
                content_type="application/pdf",
            )

    def test_rejects_invalid_context_with_slashes(self) -> None:
        with pytest.raises(ValueError, match="Invalid context"):
            generate_presigned_upload(
                _r2_settings(),
                user_id="user-123",
                context="../../etc",
                content_type="image/png",
            )

    def test_rejects_invalid_context_with_spaces(self) -> None:
        with pytest.raises(ValueError, match="Invalid context"):
            generate_presigned_upload(
                _r2_settings(),
                user_id="user-123",
                context="has space",
                content_type="image/png",
            )

    def test_rejects_invalid_context_with_uppercase(self) -> None:
        with pytest.raises(ValueError, match="Invalid context"):
            generate_presigned_upload(
                _r2_settings(),
                user_id="user-123",
                context="Avatar",
                content_type="image/png",
            )

    def test_accepts_valid_context_with_hyphens_and_underscores(self) -> None:
        with patch("app.services.r2_storage.boto3") as mock_boto3:
            mock_client = MagicMock()
            mock_client.generate_presigned_url.return_value = "https://presigned.example.com"
            mock_boto3.client.return_value = mock_client

            # Should not raise
            generate_presigned_upload(
                _r2_settings(),
                user_id="user-123",
                context="dj-avatar_v2",
                content_type="image/png",
            )


# ---------------------------------------------------------------------------
# Presigned URL generation
# ---------------------------------------------------------------------------


class TestPresignedUpload:
    @patch("app.services.r2_storage.boto3")
    def test_returns_upload_and_public_urls(self, mock_boto3: MagicMock) -> None:
        mock_client = MagicMock()
        mock_client.generate_presigned_url.return_value = (
            "https://test-account-id.r2.cloudflarestorage.com/presigned"
        )
        mock_boto3.client.return_value = mock_client

        upload_url, public_url = generate_presigned_upload(
            _r2_settings(),
            user_id="user-456",
            context="avatar",
            content_type="image/jpeg",
        )

        assert upload_url == "https://test-account-id.r2.cloudflarestorage.com/presigned"
        assert public_url.startswith("https://cdn.mcav.live/dj/avatar/user-456/")
        assert public_url.endswith(".jpg")

    @patch("app.services.r2_storage.boto3")
    def test_png_extension(self, mock_boto3: MagicMock) -> None:
        mock_client = MagicMock()
        mock_client.generate_presigned_url.return_value = "https://presigned.example.com"
        mock_boto3.client.return_value = mock_client

        _, public_url = generate_presigned_upload(
            _r2_settings(),
            user_id="user-789",
            context="banner",
            content_type="image/png",
        )

        assert public_url.endswith(".png")
        assert "/dj/banner/user-789/" in public_url

    @patch("app.services.r2_storage.boto3")
    def test_webp_extension(self, mock_boto3: MagicMock) -> None:
        mock_client = MagicMock()
        mock_client.generate_presigned_url.return_value = "https://presigned.example.com"
        mock_boto3.client.return_value = mock_client

        _, public_url = generate_presigned_upload(
            _r2_settings(),
            user_id="user-abc",
            context="photo",
            content_type="image/webp",
        )

        assert public_url.endswith(".webp")

    @patch("app.services.r2_storage.boto3")
    def test_boto3_client_configured_correctly(self, mock_boto3: MagicMock) -> None:
        mock_client = MagicMock()
        mock_client.generate_presigned_url.return_value = "https://presigned.example.com"
        mock_boto3.client.return_value = mock_client

        settings = _r2_settings()
        generate_presigned_upload(settings, "uid", "ctx", "image/jpeg")

        mock_boto3.client.assert_called_once()
        call_kwargs = mock_boto3.client.call_args
        assert call_kwargs.args[0] == "s3"
        assert "test-account-id.r2.cloudflarestorage.com" in call_kwargs.kwargs["endpoint_url"]
        assert call_kwargs.kwargs["aws_access_key_id"] == "test-access-key"
        assert call_kwargs.kwargs["aws_secret_access_key"] == "test-secret-key"

    @patch("app.services.r2_storage.boto3")
    def test_presigned_url_called_with_correct_params(self, mock_boto3: MagicMock) -> None:
        mock_client = MagicMock()
        mock_client.generate_presigned_url.return_value = "https://presigned.example.com"
        mock_boto3.client.return_value = mock_client

        settings = _r2_settings()
        generate_presigned_upload(settings, "uid", "avatar", "image/jpeg")

        mock_client.generate_presigned_url.assert_called_once()
        call_args = mock_client.generate_presigned_url.call_args
        assert call_args.args[0] == "put_object"
        params = call_args.kwargs["Params"]
        assert params["Bucket"] == "test-bucket"
        assert params["Key"].startswith("dj/avatar/uid/")
        assert params["ContentType"] == "image/jpeg"
        assert call_args.kwargs["ExpiresIn"] == 300
