"use client";

import { useEffect, useState, useCallback } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import Image from "next/image";
import { useAuth } from "@/components/AuthProvider";
import type {
  UserProfile,
  UnifiedDashboard,
  OrgDashboardSummary,
  RecentShowSummary,
} from "@/lib/auth";
import {
  fetchMe,
  createInvite,
  createOrg,
  joinOrg,
  fetchUnifiedDashboard,
  resetAccountFull,
} from "@/lib/auth";

// ---------------------------------------------------------------------------
// Checklist component
// ---------------------------------------------------------------------------

function SetupChecklist({ checklist }: { checklist: { org_created: boolean; server_registered: boolean; invite_created: boolean; show_started: boolean } }) {
  const items = [
    { key: "org_created", label: "Create an organization", done: checklist.org_created },
    { key: "server_registered", label: "Register a server", done: checklist.server_registered },
    { key: "invite_created", label: "Create a team invite", done: checklist.invite_created },
    { key: "show_started", label: "Start your first show", done: checklist.show_started },
  ];
  const completed = items.filter((i) => i.done).length;
  const progress = (completed / items.length) * 100;

  return (
    <div className="glass-card rounded-xl p-6">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-semibold">Setup Progress</h2>
        <span className="text-sm text-text-secondary">{completed}/{items.length}</span>
      </div>
      <div className="mb-4 h-2 overflow-hidden rounded-full bg-white/5">
        <div
          className="h-full rounded-full bg-gradient-to-r from-disc-cyan to-disc-blue transition-all duration-500"
          style={{ width: `${progress}%` }}
        />
      </div>
      <div className="flex flex-col gap-2">
        {items.map((item) => (
          <div key={item.key} className="flex items-center gap-3 text-sm">
            <div className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full ${item.done ? "bg-green-500/20" : "bg-white/5"}`}>
              {item.done ? (
                <svg className="h-3 w-3 text-green-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={3}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                </svg>
              ) : (
                <div className="h-1.5 w-1.5 rounded-full bg-white/20" />
              )}
            </div>
            <span className={item.done ? "text-text-secondary line-through" : "text-white"}>
              {item.label}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Org card component
// ---------------------------------------------------------------------------

function OrgCard({
  org,
  accessToken,
}: {
  org: OrgDashboardSummary;
  accessToken: string;
}) {
  const [inviteCode, setInviteCode] = useState<string | null>(null);
  const [inviteLoading, setInviteLoading] = useState(false);
  const [copied, setCopied] = useState(false);

  async function handleCreateInvite() {
    setInviteLoading(true);
    try {
      const res = await createInvite(accessToken, org.id);
      setInviteCode(res.code);
    } catch {
      // silently fail
    } finally {
      setInviteLoading(false);
    }
  }

  function handleCopy(code: string) {
    try {
      navigator.clipboard.writeText(code);
    } catch {
      // Clipboard API may be unavailable in some contexts
    }
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <div className="glass-card rounded-xl p-5 transition-all duration-200 hover:border-disc-cyan/20">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="font-semibold">{org.name}</h3>
          <p className="text-sm text-text-secondary">{org.slug}.mcav.live</p>
        </div>
        <span className="rounded-full bg-white/5 px-3 py-1 text-xs text-text-secondary">{org.role}</span>
      </div>
      <div className="mt-3 flex gap-4 text-xs text-text-secondary">
        <Link href={`/org/${org.slug}/servers`} className="transition-colors hover:text-disc-cyan">
          {org.server_count} server{org.server_count !== 1 ? "s" : ""}
        </Link>
        <span>{org.member_count} member{org.member_count !== 1 ? "s" : ""}</span>
        <span>{org.active_show_count} active show{org.active_show_count !== 1 ? "s" : ""}</span>
      </div>
      {org.role === "owner" && (
        <div className="mt-2">
          <Link
            href={`/org/${org.slug}/servers`}
            className="text-xs text-disc-cyan/70 transition-colors hover:text-disc-cyan"
          >
            Manage Servers
          </Link>
        </div>
      )}
      {org.role === "owner" && (
        <div className="mt-3 border-t border-white/5 pt-3">
          {inviteCode ? (
            <button
              onClick={() => handleCopy(inviteCode)}
              className="flex items-center gap-2 rounded-lg border border-white/10 px-3 py-1.5 text-xs font-mono transition-colors hover:bg-white/5"
            >
              <span className="tracking-widest">{inviteCode}</span>
              <span className="text-text-secondary">{copied ? "Copied!" : "Copy"}</span>
            </button>
          ) : (
            <button
              onClick={handleCreateInvite}
              disabled={inviteLoading}
              className="rounded-lg border border-white/10 px-3 py-1.5 text-xs text-text-secondary transition-colors hover:bg-white/5 hover:text-white disabled:opacity-50"
            >
              {inviteLoading ? "..." : "Create Invite"}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Show list component
// ---------------------------------------------------------------------------

function ShowList({ shows, title }: { shows: RecentShowSummary[]; title: string }) {
  if (shows.length === 0) return null;

  return (
    <div>
      <h2 className="mb-4 text-lg font-semibold">{title}</h2>
      <div className="flex flex-col gap-3">
        {shows.map((show) => (
          <div key={show.id} className="glass-card rounded-xl p-4 transition-all duration-200 hover:border-disc-cyan/20">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-sm font-medium">{show.name}</h3>
                <p className="text-xs text-text-secondary">{show.server_name}</p>
              </div>
              <div className="flex items-center gap-2">
                {show.connect_code && (
                  <span className="rounded bg-white/5 px-2 py-0.5 font-mono text-xs text-disc-cyan">
                    {show.connect_code}
                  </span>
                )}
                <span className={`rounded-full px-2 py-0.5 text-xs ${show.status === "active" ? "bg-green-500/10 text-green-400" : "bg-white/5 text-text-secondary"}`}>
                  {show.status}
                </span>
                <span className="text-xs text-text-secondary">{show.current_djs} DJ{show.current_djs !== 1 ? "s" : ""}</span>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Setup prompt card (dashed border, calls-to-action for missing capabilities)
// ---------------------------------------------------------------------------

function SetupPromptCard({
  icon,
  title,
  description,
  children,
}: {
  icon: React.ReactNode;
  title: string;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-xl border border-dashed border-white/10 bg-white/[0.02] p-6">
      <div className="flex items-start gap-4">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-white/5">
          {icon}
        </div>
        <div className="flex-1">
          <h3 className="font-semibold">{title}</h3>
          <p className="mt-1 text-sm text-text-secondary">{description}</p>
          <div className="mt-4">{children}</div>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Capability badge
// ---------------------------------------------------------------------------

function CapabilityBadge({ label, color }: { label: string; color: string }) {
  return (
    <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${color}`}>
      {label}
    </span>
  );
}

// ---------------------------------------------------------------------------
// Inline create-org form
// ---------------------------------------------------------------------------

function InlineCreateOrgForm({
  accessToken,
  onCreated,
}: {
  accessToken: string;
  onCreated: () => void;
}) {
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function handleNameChange(val: string) {
    setName(val);
    // Auto-generate slug from name
    setSlug(
      val
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-|-$/g, "")
        .slice(0, 63)
    );
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim() || !slug.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      await createOrg(accessToken, name.trim(), slug.trim());
      onCreated();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create organization");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-3">
      <div className="flex gap-3">
        <input
          type="text"
          placeholder="Organization name"
          value={name}
          onChange={(e) => handleNameChange(e.target.value)}
          className="flex-1 rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white placeholder:text-text-secondary/50 focus:border-disc-cyan/30 focus:outline-none"
          maxLength={100}
        />
        <input
          type="text"
          placeholder="slug"
          value={slug}
          onChange={(e) => setSlug(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ""))}
          className="w-36 rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white placeholder:text-text-secondary/50 focus:border-disc-cyan/30 focus:outline-none"
          maxLength={63}
        />
        <button
          type="submit"
          disabled={submitting || !name.trim() || !slug.trim()}
          className="rounded-lg bg-gradient-to-r from-disc-cyan to-disc-blue px-4 py-2 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
        >
          {submitting ? "..." : "Create"}
        </button>
      </div>
      {slug && (
        <p className="text-xs text-text-secondary">{slug}.mcav.live</p>
      )}
      {error && <p className="text-xs text-red-400">{error}</p>}
    </form>
  );
}

// ---------------------------------------------------------------------------
// Inline join-org form
// ---------------------------------------------------------------------------

function InlineJoinOrgForm({
  accessToken,
  onJoined,
}: {
  accessToken: string;
  onJoined: () => void;
}) {
  const [code, setCode] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!code.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      await joinOrg(accessToken, code.trim());
      onJoined();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Invalid invite code");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex gap-3">
      <input
        type="text"
        placeholder="Invite code"
        value={code}
        onChange={(e) => setCode(e.target.value)}
        className="w-32 rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm font-mono text-white placeholder:text-text-secondary/50 focus:border-disc-cyan/30 focus:outline-none"
        maxLength={8}
      />
      <button
        type="submit"
        disabled={submitting || !code.trim()}
        className="rounded-lg border border-white/10 px-4 py-2 text-sm text-text-secondary transition-colors hover:bg-white/5 hover:text-white disabled:opacity-50"
      >
        {submitting ? "..." : "Join"}
      </button>
      {error && <p className="ml-2 self-center text-xs text-red-400">{error}</p>}
    </form>
  );
}

// ---------------------------------------------------------------------------
// Main dashboard page
// ---------------------------------------------------------------------------

export default function DashboardPage() {
  const router = useRouter();
  const { user, accessToken, loading: authLoading, logout } = useAuth();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [dashboard, setDashboard] = useState<UnifiedDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [resettingFull, setResettingFull] = useState(false);
  const [confirmFullReset, setConfirmFullReset] = useState(false);

  const loadDashboard = useCallback(async () => {
    if (!accessToken) return;
    try {
      const d = await fetchUnifiedDashboard(accessToken);
      setDashboard(d);
    } catch {
      // keep existing dashboard state
    }
  }, [accessToken]);

  useEffect(() => {
    if (authLoading) return;
    if (!user || !accessToken) {
      router.push("/login");
      return;
    }

    Promise.all([
      fetchMe(accessToken),
      fetchUnifiedDashboard(accessToken).catch(() => null),
    ])
      .then(([p, d]) => {
        if (!p.onboarding_completed) {
          router.replace("/onboarding");
          return;
        }
        setProfile(p);
        setDashboard(d);
      })
      .catch(() => {
        router.push("/login");
      })
      .finally(() => setLoading(false));
  }, [user, accessToken, authLoading, router]);

  async function handleFullReset() {
    if (!accessToken) return;
    setResettingFull(true);
    try {
      await resetAccountFull(accessToken);
      router.push("/onboarding");
    } catch {
      setResettingFull(false);
      setConfirmFullReset(false);
    }
  }

  if (authLoading || loading) {
    return (
      <div className="flex min-h-screen items-center justify-center pt-20">
        <div className="w-full max-w-4xl px-6">
          <div className="mb-10 flex items-center gap-4">
            <div className="h-14 w-14 animate-pulse rounded-full bg-white/5" />
            <div className="flex-1">
              <div className="h-6 w-40 animate-pulse rounded-lg bg-white/5" />
              <div className="mt-2 h-4 w-56 animate-pulse rounded-lg bg-white/5" />
            </div>
          </div>
          <div className="grid gap-4">
            {[0, 1].map((i) => (
              <div key={i} className="h-20 animate-pulse rounded-xl bg-white/5" />
            ))}
          </div>
        </div>
      </div>
    );
  }

  if (!profile) return null;

  return (
    <div className="relative mx-auto max-w-4xl px-6 pt-28 pb-20">
      {/* Background glows */}
      <div className="pointer-events-none absolute inset-0 z-0">
        <div className="absolute top-0 left-1/2 h-[400px] w-[500px] -translate-x-1/2 rounded-full bg-disc-cyan/5 blur-[120px]" />
        <div className="absolute top-1/4 right-1/4 h-[300px] w-[300px] rounded-full bg-disc-blue/5 blur-[100px]" />
      </div>

      <div className="relative z-10">
        {/* Profile header with capability badges */}
        <div className="mb-10 flex items-center gap-4">
          {profile.avatar_url ? (
            <Image
              src={profile.avatar_url}
              alt={profile.display_name}
              width={56}
              height={56}
              className="h-14 w-14 rounded-full"
              unoptimized
            />
          ) : (
            <div className="flex h-14 w-14 items-center justify-center rounded-full bg-white/10 text-xl font-bold">
              {profile.display_name.charAt(0).toUpperCase()}
            </div>
          )}
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-2xl font-bold">
                <span className="text-gradient">{profile.display_name}</span>
              </h1>
              <div className="flex gap-1.5">
                {dashboard?.has_dj_profile && (
                  <CapabilityBadge label="DJ" color="bg-disc-cyan/10 text-disc-cyan" />
                )}
                {dashboard?.has_orgs && (
                  <CapabilityBadge label="Server Owner" color="bg-disc-blue/10 text-disc-blue" />
                )}
                {dashboard?.organizations.some((o) => o.role === "member") && (
                  <CapabilityBadge label="Team Member" color="bg-green-500/10 text-green-400" />
                )}
              </div>
            </div>
            <p className="text-sm text-text-secondary">
              {profile.email || profile.discord_username || "No email"}
            </p>
          </div>
          <button
            onClick={logout}
            className="ml-auto rounded-lg border border-white/10 px-4 py-2 text-sm text-text-secondary transition-colors hover:bg-white/5 hover:text-white"
          >
            Log out
          </button>
        </div>

        {/* Additive content — all sections render if applicable */}
        <div className="flex flex-col gap-8">
          {/* Setup checklist */}
          {dashboard?.checklist && (
            <SetupChecklist checklist={dashboard.checklist} />
          )}

          {/* DJ Profile card */}
          {dashboard?.dj && (
            <div className="glass-card rounded-xl p-6">
              <div className="flex items-center gap-4">
                <div className="flex h-14 w-14 items-center justify-center rounded-full bg-disc-cyan/10 text-2xl">
                  <svg className="h-7 w-7 text-disc-cyan" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2z" />
                  </svg>
                </div>
                <div className="flex-1">
                  <h2 className="text-xl font-bold">{dashboard.dj.dj_name}</h2>
                  {dashboard.dj.bio && <p className="text-sm text-text-secondary">{dashboard.dj.bio}</p>}
                  {dashboard.dj.genres && (
                    <div className="mt-2 flex flex-wrap gap-1">
                      {dashboard.dj.genres.split(",").map((g) => (
                        <span key={g.trim()} className="rounded-full bg-white/5 px-2 py-0.5 text-xs text-text-secondary">
                          {g.trim()}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
                <div className="flex flex-col items-end gap-2">
                  <div className="text-right">
                    <div className="text-2xl font-bold text-disc-cyan">{dashboard.dj.session_count}</div>
                    <div className="text-xs text-text-secondary">session{dashboard.dj.session_count !== 1 ? "s" : ""}</div>
                  </div>
                  {/* Social link icons */}
                  {(dashboard.dj.soundcloud_url || dashboard.dj.spotify_url || dashboard.dj.website_url) && (
                    <div className="flex gap-1.5">
                      {dashboard.dj.soundcloud_url && (
                        <a href={dashboard.dj.soundcloud_url} target="_blank" rel="noopener noreferrer" className="flex h-7 w-7 items-center justify-center rounded-md transition-colors hover:bg-[#ff5500]/10" title="SoundCloud">
                          <svg className="h-3.5 w-3.5 text-text-secondary hover:text-[#ff5500]" viewBox="0 0 24 24" fill="currentColor">
                            <path d="M1.175 12.225c-.051 0-.094.046-.101.1l-.233 2.154.233 2.105c.007.058.05.098.101.098.05 0 .09-.04.099-.098l.255-2.105-.27-2.154c-.01-.057-.05-.1-.1-.1m-.899.828c-.06 0-.091.037-.104.094L0 14.479l.172 1.308c.013.06.045.094.104.094.057 0 .09-.037.104-.094l.199-1.308-.199-1.332c-.014-.057-.047-.094-.104-.094m1.818-1.154c-.07 0-.113.054-.12.114l-.217 2.466.217 2.387c.007.06.05.113.12.113.068 0 .113-.053.12-.113l.244-2.387-.244-2.466c-.007-.06-.052-.114-.12-.114m.824-.557c-.08 0-.127.06-.135.127l-.198 3.023.198 2.907c.008.068.055.127.135.127.076 0 .127-.059.135-.127l.224-2.907-.224-3.023c-.008-.068-.059-.127-.135-.127m.83-.471c-.09 0-.14.068-.147.143l-.182 3.494.182 3.297c.007.076.058.143.147.143.084 0 .14-.067.147-.143l.206-3.297-.206-3.494c-.007-.075-.063-.143-.147-.143m.85-.261c-.1 0-.155.077-.16.16l-.166 3.755.166 3.447c.005.082.06.16.16.16.093 0 .154-.078.16-.16l.186-3.447-.186-3.756c-.006-.082-.067-.16-.16-.16m.862-.137c-.11 0-.168.084-.173.175l-.15 3.893.15 3.534c.005.09.063.175.173.175.106 0 .168-.084.173-.175l.17-3.534-.17-3.893c-.005-.09-.067-.175-.173-.175m.86 0c-.118 0-.181.094-.185.193l-.138 3.893.138 3.54c.004.1.067.194.185.194.113 0 .18-.094.186-.193l.154-3.54-.154-3.894c-.006-.1-.073-.193-.186-.193m.87-.12c-.13 0-.194.1-.2.206l-.118 4.014.119 3.534c.006.106.07.206.2.206.124 0 .193-.1.2-.206l.134-3.534-.134-4.014c-.007-.106-.076-.206-.2-.206m.86.037c-.14 0-.207.11-.213.22l-.104 3.957.104 3.477c.006.11.073.22.213.22.135 0 .207-.11.213-.22l.117-3.477-.117-3.957c-.006-.11-.078-.22-.213-.22m.882-.189c-.147 0-.22.116-.225.232l-.09 4.146.09 3.408c.005.117.078.232.225.232.145 0 .22-.115.225-.232l.1-3.408-.1-4.146c-.005-.116-.08-.232-.225-.232m.853.074c-.155 0-.233.126-.238.25l-.073 4.072.073 3.338c.005.124.083.25.238.25.152 0 .233-.126.237-.25l.084-3.338-.084-4.072c-.004-.124-.085-.25-.237-.25m.864-.17c-.167 0-.246.133-.25.264l-.06 4.242.06 3.262c.004.13.083.264.25.264.161 0 .245-.134.25-.264l.07-3.262-.07-4.242c-.005-.13-.089-.264-.25-.264m2.425-.63c-.233 0-.346.178-.354.356l-.057 4.872.057 3.145c.008.178.121.356.354.356.228 0 .345-.178.354-.356l.063-3.145-.063-4.872c-.009-.178-.126-.356-.354-.356m-.864.356c-.225 0-.34.17-.348.34l-.046 4.516.046 3.186c.008.17.123.34.348.34.22 0 .34-.17.348-.34l.054-3.186-.054-4.516c-.008-.17-.128-.34-.348-.34m1.728-.53c-.073 0-.145.014-.213.042a3.285 3.285 0 0 0-2.888-1.725c-.343 0-.68.064-1 .182-.132.05-.167.1-.167.2v7.5c0 .1.073.187.167.2h4.1a2.453 2.453 0 0 0 2.451-2.451 2.453 2.453 0 0 0-2.45-2.45" />
                          </svg>
                        </a>
                      )}
                      {dashboard.dj.spotify_url && (
                        <a href={dashboard.dj.spotify_url} target="_blank" rel="noopener noreferrer" className="flex h-7 w-7 items-center justify-center rounded-md transition-colors hover:bg-[#1DB954]/10" title="Spotify">
                          <svg className="h-3.5 w-3.5 text-text-secondary hover:text-[#1DB954]" viewBox="0 0 24 24" fill="currentColor">
                            <path d="M12 0C5.4 0 0 5.4 0 12s5.4 12 12 12 12-5.4 12-12S18.66 0 12 0zm5.521 17.34c-.24.359-.66.48-1.021.24-2.82-1.74-6.36-2.101-10.561-1.141-.418.122-.779-.179-.899-.539-.12-.421.18-.78.54-.9 4.56-1.021 8.52-.6 11.64 1.32.42.18.479.659.301 1.02zm1.44-3.3c-.301.42-.841.6-1.262.3-3.239-1.98-8.159-2.58-11.939-1.38-.479.12-1.02-.12-1.14-.6-.12-.48.12-1.021.6-1.141C9.6 9.9 15 10.561 18.72 12.84c.361.181.54.78.241 1.2zm.12-3.36C15.24 8.4 8.82 8.16 5.16 9.301c-.6.179-1.2-.181-1.38-.721-.18-.601.18-1.2.72-1.381 4.26-1.26 11.28-1.02 15.721 1.621.539.3.719 1.02.419 1.56-.299.421-1.02.599-1.559.3z" />
                          </svg>
                        </a>
                      )}
                      {dashboard.dj.website_url && (
                        <a href={dashboard.dj.website_url} target="_blank" rel="noopener noreferrer" className="flex h-7 w-7 items-center justify-center rounded-md transition-colors hover:bg-white/5" title="Website">
                          <svg className="h-3.5 w-3.5 text-text-secondary hover:text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                            <path strokeLinecap="round" strokeLinejoin="round" d="M12 21a9.004 9.004 0 008.716-6.747M12 21a9.004 9.004 0 01-8.716-6.747M12 21c2.485 0 4.5-4.03 4.5-9S14.485 3 12 3m0 18c-2.485 0-4.5-4.03-4.5-9S9.515 3 12 3m0 0a8.997 8.997 0 017.843 4.582M12 3a8.997 8.997 0 00-7.843 4.582m15.686 0A11.953 11.953 0 0112 10.5c-2.998 0-5.74-1.1-7.843-2.918m15.686 0A8.959 8.959 0 0121 12c0 .778-.099 1.533-.284 2.253m0 0A17.919 17.919 0 0112 16.5a17.92 17.92 0 01-8.716-2.247m0 0A9.015 9.015 0 013 12c0-1.605.42-3.113 1.157-4.418" />
                          </svg>
                        </a>
                      )}
                    </div>
                  )}
                  <Link
                    href="/settings/profile"
                    className="rounded-lg border border-white/10 px-3 py-1.5 text-xs text-text-secondary transition-colors hover:bg-white/5 hover:text-white"
                  >
                    Edit Profile
                  </Link>
                </div>
              </div>
            </div>
          )}

          {/* Set Up DJ Profile prompt */}
          {dashboard && !dashboard.has_dj_profile && (
            <SetupPromptCard
              icon={
                <svg className="h-5 w-5 text-disc-cyan" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2z" />
                </svg>
              }
              title="Set Up DJ Profile"
              description="Create your DJ profile to start performing at shows and build your session history."
            >
              <Link
                href="/settings/profile"
                className="inline-block rounded-lg bg-gradient-to-r from-disc-cyan to-disc-blue px-4 py-2 text-sm font-semibold text-white transition-opacity hover:opacity-90"
              >
                Create DJ Profile
              </Link>
            </SetupPromptCard>
          )}

          {/* Organizations section — always shown */}
          <section>
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-lg font-semibold">Organizations</h2>
            </div>
            {dashboard && dashboard.organizations.length > 0 ? (
              <div className="grid gap-4">
                {dashboard.organizations.map((org) => (
                  <OrgCard key={org.id} org={org} accessToken={accessToken!} />
                ))}
              </div>
            ) : (
              <div className="glass-card rounded-xl p-8 text-center">
                <p className="text-sm text-text-secondary">No organizations yet.</p>
              </div>
            )}
          </section>

          {/* Create / Join org prompts if user has no orgs */}
          {dashboard && !dashboard.has_orgs && (
            <div className="grid gap-4 sm:grid-cols-2">
              <SetupPromptCard
                icon={
                  <svg className="h-5 w-5 text-disc-blue" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M12 6v6m0 0v6m0-6h6m-6 0H6" />
                  </svg>
                }
                title="Create Organization"
                description="Set up a new org with your own mcav.live subdomain."
              >
                <InlineCreateOrgForm accessToken={accessToken!} onCreated={loadDashboard} />
              </SetupPromptCard>
              <SetupPromptCard
                icon={
                  <svg className="h-5 w-5 text-green-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z" />
                  </svg>
                }
                title="Join with Invite"
                description="Enter an invite code to join an existing organization."
              >
                <InlineJoinOrgForm accessToken={accessToken!} onJoined={loadDashboard} />
              </SetupPromptCard>
            </div>
          )}

          {/* Recent Shows (from orgs) */}
          {dashboard && dashboard.recent_shows.length > 0 && (
            <ShowList shows={dashboard.recent_shows} title="Recent Shows" />
          )}

          {/* Recent DJ Sessions */}
          {dashboard?.dj && dashboard.dj.recent_sessions.length > 0 && (
            <ShowList shows={dashboard.dj.recent_sessions} title="Recent DJ Sessions" />
          )}

          {/* Getting Started */}
          <div className="glass-card rounded-xl p-5">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="font-semibold">Getting Started Guide</h3>
                <p className="text-sm text-text-secondary">Step-by-step setup for MCAV</p>
              </div>
              <Link
                href="/getting-started"
                className="rounded-lg bg-gradient-to-r from-disc-cyan to-disc-blue px-4 py-2 text-sm font-semibold text-white transition-opacity hover:opacity-90"
              >
                View Guide
              </Link>
            </div>
          </div>

          {/* Developer Tools */}
          {dashboard && (
            <div className="glass-card rounded-xl p-5">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-sm font-semibold text-text-secondary">Developer Tools</h3>
                  <p className="text-xs text-text-secondary/60">Reset account to re-test the new user experience</p>
                </div>
                {confirmFullReset ? (
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-red-400">Delete all orgs, servers, and profile?</span>
                    <button
                      onClick={handleFullReset}
                      disabled={resettingFull}
                      className="rounded-lg bg-red-500/10 px-3 py-1.5 text-xs text-red-400 transition-colors hover:bg-red-500/20 disabled:opacity-50"
                    >
                      {resettingFull ? "Resetting..." : "Yes, reset everything"}
                    </button>
                    <button
                      onClick={() => setConfirmFullReset(false)}
                      className="rounded-lg border border-white/10 px-3 py-1.5 text-xs text-text-secondary transition-colors hover:bg-white/5"
                    >
                      Cancel
                    </button>
                  </div>
                ) : (
                  <button
                    onClick={() => setConfirmFullReset(true)}
                    className="rounded-lg border border-red-500/20 px-3 py-1.5 text-xs text-red-400/60 transition-colors hover:bg-red-500/10 hover:text-red-400"
                  >
                    Full Account Reset
                  </button>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
