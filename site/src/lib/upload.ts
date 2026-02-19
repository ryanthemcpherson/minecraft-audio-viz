/**
 * Image upload utility for DJ profile avatars and banners.
 * Uses presigned R2 URLs - no new npm dependencies needed.
 */

import { getPresignedUploadUrl } from "./auth";

const ALLOWED_TYPES = ["image/jpeg", "image/png", "image/webp"];
const MAX_AVATAR_SIZE = 5 * 1024 * 1024; // 5MB
const MAX_BANNER_SIZE = 10 * 1024 * 1024; // 10MB

/**
 * Upload an image file to R2 via presigned URL.
 *
 * @returns The public URL of the uploaded image.
 */
export async function uploadImage(
  accessToken: string,
  file: File,
  context: "avatar" | "banner"
): Promise<string> {
  if (!ALLOWED_TYPES.includes(file.type)) {
    throw new Error("Only JPG, PNG, and WebP images are allowed");
  }

  const maxSize = context === "avatar" ? MAX_AVATAR_SIZE : MAX_BANNER_SIZE;
  if (file.size > maxSize) {
    const maxMB = maxSize / (1024 * 1024);
    throw new Error(`File too large. Maximum size is ${maxMB}MB`);
  }

  const { upload_url, public_url } = await getPresignedUploadUrl(
    accessToken,
    context,
    file.type
  );

  const uploadRes = await fetch(upload_url, {
    method: "PUT",
    headers: { "Content-Type": file.type },
    body: file,
  });

  if (!uploadRes.ok) {
    throw new Error("Upload failed. Please try again.");
  }

  return public_url;
}
