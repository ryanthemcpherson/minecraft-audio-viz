"use client";

import { useEffect, useState, useCallback, useRef } from "react";
import { useRouter } from "next/navigation";
import Image from "next/image";
import { useAuth } from "@/components/AuthProvider";
import {
  fetchMe,
  createDJProfile,
  updateDJProfile,
  checkSlugAvailability,
} from "@/lib/auth";
import { uploadImage } from "@/lib/upload";
import type { DJProfile } from "@/lib/auth";

const DEFAULT_COLORS = ["#6366f1", "#8b5cf6", "#ec4899"];

export default function ProfileEditPage() {
  const router = useRouter();
  const { user, accessToken, loading: authLoading } = useAuth();

  const [profile, setProfile] = useState<DJProfile | null>(null);
  const [hasProfile, setHasProfile] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  // Form fields
  const [djName, setDjName] = useState("");
  const [slug, setSlug] = useState("");
  const [bio, setBio] = useState("");
  const [genres, setGenres] = useState("");
  const [colors, setColors] = useState<string[]>(DEFAULT_COLORS);
  const [isPublic, setIsPublic] = useState(true);
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);
  const [bannerUrl, setBannerUrl] = useState<string | null>(null);

  // Slug availability
  const [slugAvailable, setSlugAvailable] = useState<boolean | null>(null);
  const [slugChecking, setSlugChecking] = useState(false);
  const slugTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Upload state
  const [uploadingAvatar, setUploadingAvatar] = useState(false);
  const [uploadingBanner, setUploadingBanner] = useState(false);

  // Load existing profile
  useEffect(() => {
    if (authLoading) return;
    if (!user || !accessToken) {
      router.push("/login");
      return;
    }

    fetchMe(accessToken)
      .then((me) => {
        if (me.dj_profile) {
          const p = me.dj_profile;
          setProfile(p);
          setHasProfile(true);
          setDjName(p.dj_name);
          setSlug(p.slug || "");
          setBio(p.bio || "");
          setGenres(p.genres || "");
          setColors(p.color_palette && p.color_palette.length >= 3 ? p.color_palette : DEFAULT_COLORS);
          setIsPublic(p.is_public);
          setAvatarUrl(p.avatar_url);
          setBannerUrl(p.banner_url);
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [user, accessToken, authLoading, router]);

  // Debounced slug check
  const checkSlug = useCallback(
    (value: string) => {
      if (slugTimerRef.current) clearTimeout(slugTimerRef.current);
      if (!value || value.length < 3) {
        setSlugAvailable(null);
        return;
      }
      setSlugChecking(true);
      slugTimerRef.current = setTimeout(async () => {
        try {
          const res = await checkSlugAvailability(value);
          // If the slug is ours, it's available
          if (profile?.slug === value) {
            setSlugAvailable(true);
          } else {
            setSlugAvailable(res.available);
          }
        } catch {
          setSlugAvailable(null);
        } finally {
          setSlugChecking(false);
        }
      }, 300);
    },
    [profile?.slug]
  );

  function handleSlugChange(value: string) {
    const cleaned = value.toLowerCase().replace(/[^a-z0-9-]/g, "");
    setSlug(cleaned);
    checkSlug(cleaned);
  }

  async function handleImageUpload(
    file: File,
    context: "avatar" | "banner"
  ) {
    if (!accessToken) return;
    const setUploading = context === "avatar" ? setUploadingAvatar : setUploadingBanner;
    const setUrl = context === "avatar" ? setAvatarUrl : setBannerUrl;

    setUploading(true);
    setError("");
    try {
      const url = await uploadImage(accessToken, file, context);
      setUrl(url);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploading(false);
    }
  }

  function handleFileDrop(
    e: React.DragEvent,
    context: "avatar" | "banner"
  ) {
    e.preventDefault();
    const file = e.dataTransfer.files[0];
    if (file) handleImageUpload(file, context);
  }

  function handleFileSelect(
    e: React.ChangeEvent<HTMLInputElement>,
    context: "avatar" | "banner"
  ) {
    const file = e.target.files?.[0];
    if (file) handleImageUpload(file, context);
  }

  function addColor() {
    if (colors.length < 5) {
      setColors([...colors, "#ffffff"]);
    }
  }

  function removeColor(index: number) {
    if (colors.length > 3) {
      setColors(colors.filter((_, i) => i !== index));
    }
  }

  function updateColor(index: number, value: string) {
    const updated = [...colors];
    updated[index] = value;
    setColors(updated);
  }

  async function handleSave(e: React.FormEvent) {
    e.preventDefault();
    if (!accessToken) return;
    setSaving(true);
    setError("");
    setSuccess("");

    try {
      const data: Record<string, unknown> = {
        dj_name: djName,
        bio: bio || undefined,
        genres: genres || undefined,
        slug: slug || undefined,
        color_palette: colors,
        is_public: isPublic,
      };

      if (avatarUrl !== profile?.avatar_url) {
        data.avatar_url = avatarUrl || undefined;
      }
      if (bannerUrl !== profile?.banner_url) {
        data.banner_url = bannerUrl || undefined;
      }

      let result: DJProfile;
      if (hasProfile) {
        result = await updateDJProfile(accessToken, data as Parameters<typeof updateDJProfile>[1]);
      } else {
        result = await createDJProfile(accessToken, data as Parameters<typeof createDJProfile>[1]);
        setHasProfile(true);
      }

      setProfile(result);
      setSuccess("Profile saved!");
      setTimeout(() => setSuccess(""), 3000);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Something went wrong");
    } finally {
      setSaving(false);
    }
  }

  if (authLoading || loading) {
    return (
      <div className="flex min-h-screen items-center justify-center pt-20">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-electric-blue" />
      </div>
    );
  }

  return (
    <div className="relative mx-auto max-w-2xl px-6 pt-28 pb-20">
      {/* Background glows */}
      <div className="pointer-events-none absolute inset-0 z-0">
        <div className="absolute top-0 left-1/2 h-[400px] w-[500px] -translate-x-1/2 rounded-full bg-electric-blue/5 blur-[120px]" />
        <div className="absolute top-1/4 right-1/4 h-[300px] w-[300px] rounded-full bg-deep-purple/5 blur-[100px]" />
      </div>

      <div className="relative z-10">
        <h1 className="mb-2 text-2xl font-bold">
          <span className="text-gradient">DJ Profile</span>
        </h1>
        <p className="mb-8 text-text-secondary">
          {hasProfile ? "Edit your public DJ profile" : "Create your public DJ profile"}
        </p>

        <form onSubmit={handleSave} className="flex flex-col gap-6">
          {/* Banner upload */}
          <div>
            <label className="mb-1 block text-sm text-text-secondary">Banner</label>
            <label
              className="relative flex h-40 cursor-pointer items-center justify-center overflow-hidden rounded-xl border border-dashed border-white/10 transition-colors hover:border-white/20"
              style={
                bannerUrl
                  ? { backgroundImage: `url(${bannerUrl})`, backgroundSize: "cover", backgroundPosition: "center" }
                  : colors.length >= 2
                    ? { background: `linear-gradient(135deg, ${colors[0]}, ${colors[colors.length - 1]})` }
                    : undefined
              }
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => handleFileDrop(e, "banner")}
            >
              {!bannerUrl && (
                <span className="text-sm text-text-secondary">
                  {uploadingBanner ? "Uploading..." : "Click or drag to upload banner"}
                </span>
              )}
              {bannerUrl && (
                <div className="absolute inset-0 flex items-center justify-center bg-black/40 opacity-0 transition-opacity hover:opacity-100">
                  <span className="text-sm text-white">
                    {uploadingBanner ? "Uploading..." : "Change banner"}
                  </span>
                </div>
              )}
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp"
                className="hidden"
                onChange={(e) => handleFileSelect(e, "banner")}
              />
            </label>
          </div>

          {/* Avatar upload */}
          <div className="-mt-14 ml-6">
            <label
              className="relative flex h-24 w-24 cursor-pointer items-center justify-center overflow-hidden rounded-full border-4 border-[#0a0a0a] bg-white/5 transition-all hover:bg-white/10"
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => handleFileDrop(e, "avatar")}
            >
              {avatarUrl ? (
                <>
                  <Image src={avatarUrl} alt="Avatar" width={96} height={96} className="h-full w-full rounded-full object-cover" unoptimized />
                  <div className="absolute inset-0 flex items-center justify-center bg-black/40 opacity-0 transition-opacity hover:opacity-100">
                    <span className="text-xs text-white">
                      {uploadingAvatar ? "..." : "Change"}
                    </span>
                  </div>
                </>
              ) : (
                <span className="text-xs text-text-secondary">
                  {uploadingAvatar ? "..." : "Avatar"}
                </span>
              )}
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp"
                className="hidden"
                onChange={(e) => handleFileSelect(e, "avatar")}
              />
            </label>
          </div>

          {/* DJ Name */}
          <div>
            <label htmlFor="djName" className="mb-1 block text-sm text-text-secondary">
              DJ / Stage name
            </label>
            <input
              id="djName"
              type="text"
              required
              value={djName}
              onChange={(e) => setDjName(e.target.value)}
              className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
              placeholder="DJ Nova"
            />
          </div>

          {/* Slug */}
          <div>
            <label htmlFor="slug" className="mb-1 block text-sm text-text-secondary">
              Profile URL
            </label>
            <div className="flex items-center gap-2">
              <span className="text-sm text-text-secondary whitespace-nowrap">mcav.live/dj/</span>
              <input
                id="slug"
                type="text"
                value={slug}
                onChange={(e) => handleSlugChange(e.target.value)}
                className="flex-1 rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
                placeholder="dj-nova"
                maxLength={30}
              />
            </div>
            {slug.length >= 3 && (
              <p className="mt-1 text-xs">
                {slugChecking ? (
                  <span className="text-text-secondary">Checking...</span>
                ) : slugAvailable === true ? (
                  <span className="text-green-400">Available</span>
                ) : slugAvailable === false ? (
                  <span className="text-red-400">Already taken</span>
                ) : null}
              </p>
            )}
          </div>

          {/* Bio */}
          <div>
            <label htmlFor="bio" className="mb-1 block text-sm text-text-secondary">
              Bio <span className="text-text-secondary/50">(optional)</span>
            </label>
            <textarea
              id="bio"
              value={bio}
              onChange={(e) => setBio(e.target.value)}
              className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50 resize-none"
              rows={3}
              placeholder="Tell people about yourself"
              maxLength={500}
            />
          </div>

          {/* Genres */}
          <div>
            <label htmlFor="genres" className="mb-1 block text-sm text-text-secondary">
              Genres <span className="text-text-secondary/50">(optional)</span>
            </label>
            <input
              id="genres"
              type="text"
              value={genres}
              onChange={(e) => setGenres(e.target.value)}
              className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
              placeholder="House, Techno, Drum & Bass"
              maxLength={500}
            />
          </div>

          {/* Color Palette */}
          <div>
            <label className="mb-2 block text-sm text-text-secondary">
              Color Palette <span className="text-text-secondary/50">(3-5 colors, used for visualizations)</span>
            </label>
            <div className="flex flex-wrap items-center gap-3">
              {colors.map((color, i) => (
                <div key={i} className="flex items-center gap-2">
                  <input
                    type="color"
                    value={color}
                    onChange={(e) => updateColor(i, e.target.value)}
                    className="h-10 w-10 cursor-pointer rounded-lg border border-white/10 bg-transparent"
                  />
                  <span className="font-mono text-xs text-text-secondary">{color}</span>
                  {colors.length > 3 && (
                    <button
                      type="button"
                      onClick={() => removeColor(i)}
                      className="text-text-secondary/50 transition-colors hover:text-red-400"
                      aria-label="Remove color"
                    >
                      <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                      </svg>
                    </button>
                  )}
                </div>
              ))}
              {colors.length < 5 && (
                <button
                  type="button"
                  onClick={addColor}
                  className="flex h-10 w-10 items-center justify-center rounded-lg border border-dashed border-white/10 text-text-secondary transition-colors hover:border-white/20 hover:text-white"
                  aria-label="Add color"
                >
                  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M12 6v12m6-6H6" />
                  </svg>
                </button>
              )}
            </div>
            {/* Palette preview */}
            <div className="mt-2 flex h-3 overflow-hidden rounded-full">
              {colors.map((color, i) => (
                <div key={i} className="flex-1" style={{ backgroundColor: color }} />
              ))}
            </div>
          </div>

          {/* Public toggle */}
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-white">Public profile</p>
              <p className="text-xs text-text-secondary">Allow others to view your DJ profile</p>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={isPublic}
              onClick={() => setIsPublic(!isPublic)}
              className={`relative h-6 w-11 rounded-full transition-colors ${isPublic ? "bg-electric-blue" : "bg-white/10"}`}
            >
              <span
                className={`absolute top-0.5 left-0.5 h-5 w-5 rounded-full bg-white transition-transform ${isPublic ? "translate-x-5" : ""}`}
              />
            </button>
          </div>

          {/* Error / Success */}
          {error && (
            <p className="rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">
              {error}
            </p>
          )}
          {success && (
            <p className="rounded-lg bg-green-500/10 px-3 py-2 text-sm text-green-400">
              {success}
            </p>
          )}

          {/* Save */}
          <button
            type="submit"
            disabled={saving || !djName}
            className="rounded-lg bg-gradient-to-r from-electric-blue to-deep-purple px-4 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
          >
            {saving ? "Saving..." : hasProfile ? "Save Changes" : "Create Profile"}
          </button>

          {/* View public profile link */}
          {hasProfile && slug && (
            <a
              href={`/dj/${slug}`}
              className="block text-center text-sm text-text-secondary transition-colors hover:text-electric-blue"
            >
              View public profile &rarr;
            </a>
          )}
        </form>
      </div>
    </div>
  );
}
