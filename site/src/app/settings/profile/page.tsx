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

  // Social links
  const [soundcloudUrl, setSoundcloudUrl] = useState("");
  const [spotifyUrl, setSpotifyUrl] = useState("");
  const [websiteUrl, setWebsiteUrl] = useState("");

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
          setSoundcloudUrl(p.soundcloud_url || "");
          setSpotifyUrl(p.spotify_url || "");
          setWebsiteUrl(p.website_url || "");
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
        soundcloud_url: soundcloudUrl,
        spotify_url: spotifyUrl,
        website_url: websiteUrl,
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
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-disc-cyan" />
      </div>
    );
  }

  return (
    <div>
      <div className="mb-6">
        <h2 className="mb-1 text-lg font-semibold">DJ Profile</h2>
        <p className="text-sm text-text-secondary">
          {hasProfile ? "Edit your public DJ profile" : "Create your public DJ profile"}
        </p>
      </div>

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
              className="relative flex h-24 w-24 cursor-pointer items-center justify-center overflow-hidden rounded-full border-4 border-[#08090d] bg-white/5 transition-all hover:bg-white/10"
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
              className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-disc-cyan/50"
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
                className="flex-1 rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-disc-cyan/50"
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
              className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-disc-cyan/50 resize-none"
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
              className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-disc-cyan/50"
              placeholder="House, Techno, Drum & Bass"
              maxLength={500}
            />
          </div>

          {/* Social Links */}
          <div className="flex flex-col gap-4">
            <label className="block text-sm text-text-secondary">
              Social Links <span className="text-text-secondary/50">(optional)</span>
            </label>

            {/* SoundCloud */}
            <div className="flex items-center gap-3">
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg" style={{ backgroundColor: "rgba(255, 85, 0, 0.1)" }}>
                <svg className="h-4 w-4" viewBox="0 0 24 24" fill="#ff5500">
                  <path d="M1.175 12.225c-.051 0-.094.046-.101.1l-.233 2.154.233 2.105c.007.058.05.098.101.098.05 0 .09-.04.099-.098l.255-2.105-.27-2.154c-.01-.057-.05-.1-.1-.1m-.899.828c-.06 0-.091.037-.104.094L0 14.479l.172 1.308c.013.06.045.094.104.094.057 0 .09-.037.104-.094l.199-1.308-.199-1.332c-.014-.057-.047-.094-.104-.094m1.818-1.154c-.07 0-.113.054-.12.114l-.217 2.466.217 2.387c.007.06.05.113.12.113.068 0 .113-.053.12-.113l.244-2.387-.244-2.466c-.007-.06-.052-.114-.12-.114m.824-.557c-.08 0-.127.06-.135.127l-.198 3.023.198 2.907c.008.068.055.127.135.127.076 0 .127-.059.135-.127l.224-2.907-.224-3.023c-.008-.068-.059-.127-.135-.127m.83-.471c-.09 0-.14.068-.147.143l-.182 3.494.182 3.297c.007.076.058.143.147.143.084 0 .14-.067.147-.143l.206-3.297-.206-3.494c-.007-.075-.063-.143-.147-.143m.85-.261c-.1 0-.155.077-.16.16l-.166 3.755.166 3.447c.005.082.06.16.16.16.093 0 .154-.078.16-.16l.186-3.447-.186-3.756c-.006-.082-.067-.16-.16-.16m.862-.137c-.11 0-.168.084-.173.175l-.15 3.893.15 3.534c.005.09.063.175.173.175.106 0 .168-.084.173-.175l.17-3.534-.17-3.893c-.005-.09-.067-.175-.173-.175m.86 0c-.118 0-.181.094-.185.193l-.138 3.893.138 3.54c.004.1.067.194.185.194.113 0 .18-.094.186-.193l.154-3.54-.154-3.894c-.006-.1-.073-.193-.186-.193m.87-.12c-.13 0-.194.1-.2.206l-.118 4.014.119 3.534c.006.106.07.206.2.206.124 0 .193-.1.2-.206l.134-3.534-.134-4.014c-.007-.106-.076-.206-.2-.206m.86.037c-.14 0-.207.11-.213.22l-.104 3.957.104 3.477c.006.11.073.22.213.22.135 0 .207-.11.213-.22l.117-3.477-.117-3.957c-.006-.11-.078-.22-.213-.22m.882-.189c-.147 0-.22.116-.225.232l-.09 4.146.09 3.408c.005.117.078.232.225.232.145 0 .22-.115.225-.232l.1-3.408-.1-4.146c-.005-.116-.08-.232-.225-.232m.853.074c-.155 0-.233.126-.238.25l-.073 4.072.073 3.338c.005.124.083.25.238.25.152 0 .233-.126.237-.25l.084-3.338-.084-4.072c-.004-.124-.085-.25-.237-.25m.864-.17c-.167 0-.246.133-.25.264l-.06 4.242.06 3.262c.004.13.083.264.25.264.161 0 .245-.134.25-.264l.07-3.262-.07-4.242c-.005-.13-.089-.264-.25-.264m2.425-.63c-.233 0-.346.178-.354.356l-.057 4.872.057 3.145c.008.178.121.356.354.356.228 0 .345-.178.354-.356l.063-3.145-.063-4.872c-.009-.178-.126-.356-.354-.356m-.864.356c-.225 0-.34.17-.348.34l-.046 4.516.046 3.186c.008.17.123.34.348.34.22 0 .34-.17.348-.34l.054-3.186-.054-4.516c-.008-.17-.128-.34-.348-.34m1.728-.53c-.073 0-.145.014-.213.042a3.285 3.285 0 0 0-2.888-1.725c-.343 0-.68.064-1 .182-.132.05-.167.1-.167.2v7.5c0 .1.073.187.167.2h4.1a2.453 2.453 0 0 0 2.451-2.451 2.453 2.453 0 0 0-2.45-2.45" />
                </svg>
              </div>
              <input
                type="url"
                value={soundcloudUrl}
                onChange={(e) => setSoundcloudUrl(e.target.value)}
                className="flex-1 rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-[#ff5500]/50"
                placeholder="https://soundcloud.com/your-name"
              />
            </div>

            {/* Spotify */}
            <div className="flex items-center gap-3">
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg" style={{ backgroundColor: "rgba(29, 185, 84, 0.1)" }}>
                <svg className="h-4 w-4" viewBox="0 0 24 24" fill="#1DB954">
                  <path d="M12 0C5.4 0 0 5.4 0 12s5.4 12 12 12 12-5.4 12-12S18.66 0 12 0zm5.521 17.34c-.24.359-.66.48-1.021.24-2.82-1.74-6.36-2.101-10.561-1.141-.418.122-.779-.179-.899-.539-.12-.421.18-.78.54-.9 4.56-1.021 8.52-.6 11.64 1.32.42.18.479.659.301 1.02zm1.44-3.3c-.301.42-.841.6-1.262.3-3.239-1.98-8.159-2.58-11.939-1.38-.479.12-1.02-.12-1.14-.6-.12-.48.12-1.021.6-1.141C9.6 9.9 15 10.561 18.72 12.84c.361.181.54.78.241 1.2zm.12-3.36C15.24 8.4 8.82 8.16 5.16 9.301c-.6.179-1.2-.181-1.38-.721-.18-.601.18-1.2.72-1.381 4.26-1.26 11.28-1.02 15.721 1.621.539.3.719 1.02.419 1.56-.299.421-1.02.599-1.559.3z" />
                </svg>
              </div>
              <input
                type="url"
                value={spotifyUrl}
                onChange={(e) => setSpotifyUrl(e.target.value)}
                className="flex-1 rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-[#1DB954]/50"
                placeholder="https://open.spotify.com/artist/..."
              />
            </div>

            {/* Website */}
            <div className="flex items-center gap-3">
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-white/5">
                <svg className="h-4 w-4 text-text-secondary" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M12 21a9.004 9.004 0 008.716-6.747M12 21a9.004 9.004 0 01-8.716-6.747M12 21c2.485 0 4.5-4.03 4.5-9S14.485 3 12 3m0 18c-2.485 0-4.5-4.03-4.5-9S9.515 3 12 3m0 0a8.997 8.997 0 017.843 4.582M12 3a8.997 8.997 0 00-7.843 4.582m15.686 0A11.953 11.953 0 0112 10.5c-2.998 0-5.74-1.1-7.843-2.918m15.686 0A8.959 8.959 0 0121 12c0 .778-.099 1.533-.284 2.253m0 0A17.919 17.919 0 0112 16.5a17.92 17.92 0 01-8.716-2.247m0 0A9.015 9.015 0 013 12c0-1.605.42-3.113 1.157-4.418" />
                </svg>
              </div>
              <input
                type="url"
                value={websiteUrl}
                onChange={(e) => setWebsiteUrl(e.target.value)}
                className="flex-1 rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-disc-cyan/50"
                placeholder="https://yoursite.com"
              />
            </div>
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
              className={`relative h-6 w-11 rounded-full transition-colors ${isPublic ? "bg-disc-cyan" : "bg-white/10"}`}
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
            className="rounded-lg bg-gradient-to-r from-disc-cyan to-disc-blue px-4 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
          >
            {saving ? "Saving..." : hasProfile ? "Save Changes" : "Create Profile"}
          </button>

          {/* View public profile link */}
          {hasProfile && slug && (
            <a
              href={`/dj/${slug}`}
              className="block text-center text-sm text-text-secondary transition-colors hover:text-disc-cyan"
            >
              View public profile &rarr;
            </a>
          )}
        </form>
    </div>
  );
}
