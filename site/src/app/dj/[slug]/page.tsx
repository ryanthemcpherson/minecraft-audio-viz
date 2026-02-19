"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import Image from "next/image";
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
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-disc-cyan" />
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
        <div className="absolute inset-0 bg-gradient-to-b from-transparent to-[#08090d]" />
      </div>

      {/* Profile content */}
      <div className="relative mx-auto max-w-2xl px-6 -mt-16">
        {/* Avatar */}
        <div className="mb-4">
          {profile.avatar_url ? (
            <Image
              src={profile.avatar_url}
              alt={profile.dj_name}
              width={112}
              height={112}
              className="h-28 w-28 rounded-full border-4 border-[#08090d] object-cover"
              unoptimized
            />
          ) : (
            <div
              className="flex h-28 w-28 items-center justify-center rounded-full border-4 border-[#08090d] text-3xl font-bold"
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

        {/* Social Links */}
        {(profile.soundcloud_url || profile.spotify_url || profile.website_url) && (
          <div className="mb-6 flex flex-wrap gap-3">
            {profile.soundcloud_url && (
              <a
                href={profile.soundcloud_url}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-2 rounded-lg border border-white/10 px-4 py-2 text-sm text-text-secondary transition-colors hover:border-[#ff5500]/30 hover:text-[#ff5500]"
              >
                <svg className="h-4 w-4" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M1.175 12.225c-.051 0-.094.046-.101.1l-.233 2.154.233 2.105c.007.058.05.098.101.098.05 0 .09-.04.099-.098l.255-2.105-.27-2.154c-.01-.057-.05-.1-.1-.1m-.899.828c-.06 0-.091.037-.104.094L0 14.479l.172 1.308c.013.06.045.094.104.094.057 0 .09-.037.104-.094l.199-1.308-.199-1.332c-.014-.057-.047-.094-.104-.094m1.818-1.154c-.07 0-.113.054-.12.114l-.217 2.466.217 2.387c.007.06.05.113.12.113.068 0 .113-.053.12-.113l.244-2.387-.244-2.466c-.007-.06-.052-.114-.12-.114m.824-.557c-.08 0-.127.06-.135.127l-.198 3.023.198 2.907c.008.068.055.127.135.127.076 0 .127-.059.135-.127l.224-2.907-.224-3.023c-.008-.068-.059-.127-.135-.127m.83-.471c-.09 0-.14.068-.147.143l-.182 3.494.182 3.297c.007.076.058.143.147.143.084 0 .14-.067.147-.143l.206-3.297-.206-3.494c-.007-.075-.063-.143-.147-.143m.85-.261c-.1 0-.155.077-.16.16l-.166 3.755.166 3.447c.005.082.06.16.16.16.093 0 .154-.078.16-.16l.186-3.447-.186-3.756c-.006-.082-.067-.16-.16-.16m.862-.137c-.11 0-.168.084-.173.175l-.15 3.893.15 3.534c.005.09.063.175.173.175.106 0 .168-.084.173-.175l.17-3.534-.17-3.893c-.005-.09-.067-.175-.173-.175m.86 0c-.118 0-.181.094-.185.193l-.138 3.893.138 3.54c.004.1.067.194.185.194.113 0 .18-.094.186-.193l.154-3.54-.154-3.894c-.006-.1-.073-.193-.186-.193m.87-.12c-.13 0-.194.1-.2.206l-.118 4.014.119 3.534c.006.106.07.206.2.206.124 0 .193-.1.2-.206l.134-3.534-.134-4.014c-.007-.106-.076-.206-.2-.206m.86.037c-.14 0-.207.11-.213.22l-.104 3.957.104 3.477c.006.11.073.22.213.22.135 0 .207-.11.213-.22l.117-3.477-.117-3.957c-.006-.11-.078-.22-.213-.22m.882-.189c-.147 0-.22.116-.225.232l-.09 4.146.09 3.408c.005.117.078.232.225.232.145 0 .22-.115.225-.232l.1-3.408-.1-4.146c-.005-.116-.08-.232-.225-.232m.853.074c-.155 0-.233.126-.238.25l-.073 4.072.073 3.338c.005.124.083.25.238.25.152 0 .233-.126.237-.25l.084-3.338-.084-4.072c-.004-.124-.085-.25-.237-.25m.864-.17c-.167 0-.246.133-.25.264l-.06 4.242.06 3.262c.004.13.083.264.25.264.161 0 .245-.134.25-.264l.07-3.262-.07-4.242c-.005-.13-.089-.264-.25-.264m2.425-.63c-.233 0-.346.178-.354.356l-.057 4.872.057 3.145c.008.178.121.356.354.356.228 0 .345-.178.354-.356l.063-3.145-.063-4.872c-.009-.178-.126-.356-.354-.356m-.864.356c-.225 0-.34.17-.348.34l-.046 4.516.046 3.186c.008.17.123.34.348.34.22 0 .34-.17.348-.34l.054-3.186-.054-4.516c-.008-.17-.128-.34-.348-.34m1.728-.53c-.073 0-.145.014-.213.042a3.285 3.285 0 0 0-2.888-1.725c-.343 0-.68.064-1 .182-.132.05-.167.1-.167.2v7.5c0 .1.073.187.167.2h4.1a2.453 2.453 0 0 0 2.451-2.451 2.453 2.453 0 0 0-2.45-2.45" />
                </svg>
                SoundCloud
              </a>
            )}
            {profile.spotify_url && (
              <a
                href={profile.spotify_url}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-2 rounded-lg border border-white/10 px-4 py-2 text-sm text-text-secondary transition-colors hover:border-[#1DB954]/30 hover:text-[#1DB954]"
              >
                <svg className="h-4 w-4" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M12 0C5.4 0 0 5.4 0 12s5.4 12 12 12 12-5.4 12-12S18.66 0 12 0zm5.521 17.34c-.24.359-.66.48-1.021.24-2.82-1.74-6.36-2.101-10.561-1.141-.418.122-.779-.179-.899-.539-.12-.421.18-.78.54-.9 4.56-1.021 8.52-.6 11.64 1.32.42.18.479.659.301 1.02zm1.44-3.3c-.301.42-.841.6-1.262.3-3.239-1.98-8.159-2.58-11.939-1.38-.479.12-1.02-.12-1.14-.6-.12-.48.12-1.021.6-1.141C9.6 9.9 15 10.561 18.72 12.84c.361.181.54.78.241 1.2zm.12-3.36C15.24 8.4 8.82 8.16 5.16 9.301c-.6.179-1.2-.181-1.38-.721-.18-.601.18-1.2.72-1.381 4.26-1.26 11.28-1.02 15.721 1.621.539.3.719 1.02.419 1.56-.299.421-1.02.599-1.559.3z" />
                </svg>
                Spotify
              </a>
            )}
            {profile.website_url && (
              <a
                href={profile.website_url}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-2 rounded-lg border border-white/10 px-4 py-2 text-sm text-text-secondary transition-colors hover:border-white/30 hover:text-white"
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M12 21a9.004 9.004 0 008.716-6.747M12 21a9.004 9.004 0 01-8.716-6.747M12 21c2.485 0 4.5-4.03 4.5-9S14.485 3 12 3m0 18c-2.485 0-4.5-4.03-4.5-9S9.515 3 12 3m0 0a8.997 8.997 0 017.843 4.582M12 3a8.997 8.997 0 00-7.843 4.582m15.686 0A11.953 11.953 0 0112 10.5c-2.998 0-5.74-1.1-7.843-2.918m15.686 0A8.959 8.959 0 0121 12c0 .778-.099 1.533-.284 2.253m0 0A17.919 17.919 0 0112 16.5a17.92 17.92 0 01-8.716-2.247m0 0A9.015 9.015 0 013 12c0-1.605.42-3.113 1.157-4.418" />
                </svg>
                Website
              </a>
            )}
          </div>
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
