/**
 * Image upload utility for DJ profile avatars and banners.
 * Uses presigned R2 URLs - no new npm dependencies needed.
 */

import { getPresignedUploadUrl } from "./auth";

const ALLOWED_TYPES = ["image/jpeg", "image/png", "image/webp"];
const MAX_AVATAR_SIZE = 5 * 1024 * 1024; // 5MB
const MAX_BANNER_SIZE = 10 * 1024 * 1024; // 10MB
const MAX_AVATAR_WIDTH = 256;
const MAX_AVATAR_HEIGHT = 128;

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

  // Resize avatars to max 256x128 for LED wall compatibility
  let uploadFile = file;
  if (context === "avatar") {
    uploadFile = await resizeImage(file, MAX_AVATAR_WIDTH, MAX_AVATAR_HEIGHT);
  }

  const { upload_url, public_url } = await getPresignedUploadUrl(
    accessToken,
    context,
    uploadFile.type
  );

  const uploadRes = await fetch(upload_url, {
    method: "PUT",
    headers: { "Content-Type": uploadFile.type },
    body: uploadFile,
  });

  if (!uploadRes.ok) {
    throw new Error("Upload failed. Please try again.");
  }

  return public_url;
}

/**
 * Resize an image to fit within maxWidth x maxHeight, preserving aspect ratio.
 * Returns the original file if already within bounds.
 */
async function resizeImage(
  file: File,
  maxWidth: number,
  maxHeight: number
): Promise<File> {
  const bitmap = await createImageBitmap(file);

  if (bitmap.width <= maxWidth && bitmap.height <= maxHeight) {
    bitmap.close();
    return file;
  }

  const scale = Math.min(maxWidth / bitmap.width, maxHeight / bitmap.height);
  const w = Math.round(bitmap.width * scale);
  const h = Math.round(bitmap.height * scale);

  const canvas = new OffscreenCanvas(w, h);
  const ctx = canvas.getContext("2d")!;
  ctx.drawImage(bitmap, 0, 0, w, h);
  bitmap.close();

  const blob = await canvas.convertToBlob({ type: "image/png" });
  return new File([blob], file.name.replace(/\.\w+$/, ".png"), {
    type: "image/png",
  });
}
