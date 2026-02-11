"""Presigned upload URL endpoints for R2 image storage."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.database import get_session
from app.dependencies.auth import get_current_user
from app.models.db import User
from app.models.schemas import UploadUrlRequest, UploadUrlResponse
from app.services.r2_storage import generate_presigned_upload

router = APIRouter(prefix="/uploads", tags=["uploads"])


@router.post(
    "/presigned-url",
    response_model=UploadUrlResponse,
    summary="Get a presigned URL for image upload",
)
async def get_presigned_url(
    body: UploadUrlRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> UploadUrlResponse:
    if not settings.r2_account_id or not settings.r2_bucket_name:
        raise HTTPException(
            status_code=501,
            detail="Image upload not configured. Set R2 environment variables.",
        )

    try:
        upload_url, public_url = generate_presigned_upload(
            settings=settings,
            user_id=str(user.id),
            context=body.context,
            content_type=body.content_type,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    return UploadUrlResponse(
        upload_url=upload_url,
        public_url=public_url,
        expires_in=300,
    )
