import { describe, it, expect, vi, beforeEach } from "vitest";

// Mock the auth module's getPresignedUploadUrl
vi.mock("../auth", () => ({
  getPresignedUploadUrl: vi.fn().mockResolvedValue({
    upload_url: "https://r2.example.com/upload",
    public_url: "https://cdn.example.com/image.png",
    expires_in: 3600,
  }),
}));

// We need to mock createImageBitmap (not available in jsdom)
vi.stubGlobal(
  "createImageBitmap",
  vi.fn().mockResolvedValue({
    width: 100,
    height: 100,
    close: vi.fn(),
  })
);

import { uploadImage } from "../upload";
import { getPresignedUploadUrl } from "../auth";

function makeFile(
  name: string,
  type: string,
  sizeBytes: number
): File {
  const buffer = new ArrayBuffer(sizeBytes);
  return new File([buffer], name, { type });
}

describe("uploadImage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: true, status: 200 })
    );
  });

  describe("file type validation", () => {
    it("accepts image/jpeg", async () => {
      const file = makeFile("photo.jpg", "image/jpeg", 1024);
      await expect(uploadImage("tok", file, "banner")).resolves.toBeDefined();
    });

    it("accepts image/png", async () => {
      const file = makeFile("photo.png", "image/png", 1024);
      await expect(uploadImage("tok", file, "banner")).resolves.toBeDefined();
    });

    it("accepts image/webp", async () => {
      const file = makeFile("photo.webp", "image/webp", 1024);
      await expect(uploadImage("tok", file, "banner")).resolves.toBeDefined();
    });

    it("rejects image/gif", async () => {
      const file = makeFile("anim.gif", "image/gif", 1024);
      await expect(uploadImage("tok", file, "banner")).rejects.toThrow(
        "Only JPG, PNG, and WebP images are allowed"
      );
    });

    it("rejects application/pdf", async () => {
      const file = makeFile("doc.pdf", "application/pdf", 1024);
      await expect(uploadImage("tok", file, "banner")).rejects.toThrow(
        "Only JPG, PNG, and WebP images are allowed"
      );
    });

    it("rejects empty string type", async () => {
      const file = makeFile("noext", "", 1024);
      await expect(uploadImage("tok", file, "banner")).rejects.toThrow(
        "Only JPG, PNG, and WebP images are allowed"
      );
    });
  });

  describe("file size validation", () => {
    it("rejects avatar files over 5MB", async () => {
      const file = makeFile("big.png", "image/png", 6 * 1024 * 1024);
      await expect(uploadImage("tok", file, "avatar")).rejects.toThrow(
        "File too large. Maximum size is 5MB"
      );
    });

    it("allows avatar files exactly at 5MB", async () => {
      const file = makeFile("exact.png", "image/png", 5 * 1024 * 1024);
      await expect(uploadImage("tok", file, "avatar")).resolves.toBeDefined();
    });

    it("rejects banner files over 10MB", async () => {
      const file = makeFile("huge.png", "image/png", 11 * 1024 * 1024);
      await expect(uploadImage("tok", file, "banner")).rejects.toThrow(
        "File too large. Maximum size is 10MB"
      );
    });

    it("allows banner files exactly at 10MB", async () => {
      const file = makeFile("exact.png", "image/png", 10 * 1024 * 1024);
      await expect(uploadImage("tok", file, "banner")).resolves.toBeDefined();
    });
  });

  describe("presigned URL flow", () => {
    it("calls getPresignedUploadUrl with correct args for banner", async () => {
      const file = makeFile("banner.png", "image/png", 1024);
      await uploadImage("my_token", file, "banner");

      expect(getPresignedUploadUrl).toHaveBeenCalledWith(
        "my_token",
        "banner",
        "image/png"
      );
    });

    it("returns the public_url from the presigned response", async () => {
      const file = makeFile("img.jpg", "image/jpeg", 1024);
      const url = await uploadImage("tok", file, "banner");
      expect(url).toBe("https://cdn.example.com/image.png");
    });

    it("PUTs the file to the upload_url", async () => {
      const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
      vi.stubGlobal("fetch", fetchMock);

      const file = makeFile("img.jpg", "image/jpeg", 2048);
      await uploadImage("tok", file, "banner");

      // fetch is called for the PUT upload
      expect(fetchMock).toHaveBeenCalledWith("https://r2.example.com/upload", {
        method: "PUT",
        headers: { "Content-Type": "image/jpeg" },
        body: file,
      });
    });
  });

  describe("upload failure", () => {
    it("throws when the PUT upload fails", async () => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue({ ok: false, status: 500 })
      );

      const file = makeFile("img.png", "image/png", 1024);
      await expect(uploadImage("tok", file, "banner")).rejects.toThrow(
        "Upload failed. Please try again."
      );
    });
  });
});
