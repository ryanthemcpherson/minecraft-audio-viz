"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { getDJProfileBySlug } from "@/lib/auth";
import type { DJProfile } from "@/lib/auth";

export default function PublicDJProfilePage() {
  const params = useParams();
  const slug = params.slug as string;

  const [profile, setProfile] = useState<DJProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    if (!slug) return;
    getDJProfileBySlug(slug)
      .then(setProfile)
      .catch(() => setNotFound(true))
      .finally(() => setLoading(false));
  }, [slug]);

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center pt-20">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-electric-blue" />
      </div>
    );
  }

  if (notFound || !profile) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center pt-20">
        <h1 className="mb-2 text-2xl font-bold">DJ not found</h1>
        <p className="text-text-secondary">This profile doesn&apos;t exist or isn&apos;t public.</p>
      </div>
    );
  }

  const colors = profile.color_palette && profile.color_palette.length >= 2
    ? profile.color_palette
    : ["#6366f1", "#8b5cf6"];

  const bannerStyle = profile.banner_url
    ? { backgroundImage: `url(${profile.banner_url})`, backgroundSize: "cover", backgroundPosition: "center" }
    : { background: `linear-gradient(135deg, ${colors[0]}, ${colors[colors.length - 1]})` };

  return (
    <div className="min-h-screen pt-16">
      {/* Banner */}
      <div className="relative h-48 sm:h-64" style={bannerStyle}>
        <div className="absolute inset-0 bg-gradient-to-b from-transparent to-[#0a0a0a]" />
      </div>

      {/* Profile content */}
      <div className="relative mx-auto max-w-2xl px-6 -mt-16">
        {/* Avatar */}
        <div className="mb-4">
          {profile.avatar_url ? (
            <img
              src={profile.avatar_url}
              alt={profile.dj_name}
              className="h-28 w-28 rounded-full border-4 border-[#0a0a0a] object-cover"
            />
          ) : (
            <div
              className="flex h-28 w-28 items-center justify-center rounded-full border-4 border-[#0a0a0a] text-3xl font-bold"
              style={{ background: `linear-gradient(135deg, ${colors[0]}, ${colors[1]})` }}
            >
              {profile.dj_name.charAt(0).toUpperCase()}
            </div>
          )}
        </div>

        {/* Name */}
        <h1 className="mb-1 text-3xl font-bold">
          <span className="text-gradient">{profile.dj_name}</span>
        </h1>

        {/* Genres */}
        {profile.genres && (
          <div className="mb-4 flex flex-wrap gap-2">
            {profile.genres.split(",").map((g) => (
              <span
                key={g.trim()}
                className="rounded-full bg-white/5 px-3 py-1 text-xs text-text-secondary"
              >
                {g.trim()}
              </span>
            ))}
          </div>
        )}

        {/* Bio */}
        {profile.bio && (
          <p className="mb-6 text-sm leading-relaxed text-text-secondary">
            {profile.bio}
          </p>
        )}

        {/* Color palette swatches */}
        {profile.color_palette && profile.color_palette.length > 0 && (
          <div className="glass-card rounded-xl p-5">
            <h3 className="mb-3 text-xs font-semibold uppercase tracking-wider text-text-secondary">
              Color Palette
            </h3>
            <div className="flex gap-3">
              {profile.color_palette.map((color, i) => (
                <div key={i} className="flex flex-col items-center gap-1.5">
                  <div
                    className="h-10 w-10 rounded-lg border border-white/10"
                    style={{ backgroundColor: color }}
                  />
                  <span className="font-mono text-[10px] text-text-secondary">{color}</span>
                </div>
              ))}
            </div>
            {/* Gradient bar */}
            <div className="mt-3 flex h-2 overflow-hidden rounded-full">
              {profile.color_palette.map((color, i) => (
                <div key={i} className="flex-1" style={{ backgroundColor: color }} />
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
