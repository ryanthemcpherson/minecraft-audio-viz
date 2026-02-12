"use client";

import { useEffect, useState, useCallback } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
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
          className="h-full rounded-full bg-gradient-to-r from-electric-blue to-deep-purple transition-all duration-500"
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
    <div className="glass-card rounded-xl p-5 transition-all duration-200 hover:border-electric-blue/20">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="font-semibold">{org.name}</h3>
          <p className="text-sm text-text-secondary">{org.slug}.mcav.live</p>
        </div>
        <span className="rounded-full bg-white/5 px-3 py-1 text-xs text-text-secondary">{org.role}</span>
      </div>
      <div className="mt-3 flex gap-4 text-xs text-text-secondary">
        <Link href={`/org/${org.slug}/servers`} className="transition-colors hover:text-electric-blue">
          {org.server_count} server{org.server_count !== 1 ? "s" : ""}
        </Link>
        <span>{org.member_count} member{org.member_count !== 1 ? "s" : ""}</span>
        <span>{org.active_show_count} active show{org.active_show_count !== 1 ? "s" : ""}</span>
      </div>
      {org.role === "owner" && (
        <div className="mt-2">
          <Link
            href={`/org/${org.slug}/servers`}
            className="text-xs text-electric-blue/70 transition-colors hover:text-electric-blue"
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
          <div key={show.id} className="glass-card rounded-xl p-4 transition-all duration-200 hover:border-electric-blue/20">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-sm font-medium">{show.name}</h3>
                <p className="text-xs text-text-secondary">{show.server_name}</p>
              </div>
              <div className="flex items-center gap-2">
                {show.connect_code && (
                  <span className="rounded bg-white/5 px-2 py-0.5 font-mono text-xs text-electric-blue">
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
          className="flex-1 rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white placeholder:text-text-secondary/50 focus:border-electric-blue/30 focus:outline-none"
          maxLength={100}
        />
        <input
          type="text"
          placeholder="slug"
          value={slug}
          onChange={(e) => setSlug(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ""))}
          className="w-36 rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white placeholder:text-text-secondary/50 focus:border-electric-blue/30 focus:outline-none"
          maxLength={63}
        />
        <button
          type="submit"
          disabled={submitting || !name.trim() || !slug.trim()}
          className="rounded-lg bg-gradient-to-r from-electric-blue to-deep-purple px-4 py-2 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
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
        className="w-32 rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm font-mono text-white placeholder:text-text-secondary/50 focus:border-electric-blue/30 focus:outline-none"
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
        <div className="absolute top-0 left-1/2 h-[400px] w-[500px] -translate-x-1/2 rounded-full bg-electric-blue/5 blur-[120px]" />
        <div className="absolute top-1/4 right-1/4 h-[300px] w-[300px] rounded-full bg-deep-purple/5 blur-[100px]" />
      </div>

      <div className="relative z-10">
        {/* Profile header with capability badges */}
        <div className="mb-10 flex items-center gap-4">
          {profile.avatar_url ? (
            <img
              src={profile.avatar_url}
              alt={profile.display_name}
              className="h-14 w-14 rounded-full"
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
                  <CapabilityBadge label="DJ" color="bg-electric-blue/10 text-electric-blue" />
                )}
                {dashboard?.has_orgs && (
                  <CapabilityBadge label="Server Owner" color="bg-deep-purple/10 text-deep-purple" />
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
                <div className="flex h-14 w-14 items-center justify-center rounded-full bg-electric-blue/10 text-2xl">
                  <svg className="h-7 w-7 text-electric-blue" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
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
                    <div className="text-2xl font-bold text-electric-blue">{dashboard.dj.session_count}</div>
                    <div className="text-xs text-text-secondary">session{dashboard.dj.session_count !== 1 ? "s" : ""}</div>
                  </div>
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
                <svg className="h-5 w-5 text-electric-blue" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2z" />
                </svg>
              }
              title="Set Up DJ Profile"
              description="Create your DJ profile to start performing at shows and build your session history."
            >
              <Link
                href="/settings/profile"
                className="inline-block rounded-lg bg-gradient-to-r from-electric-blue to-deep-purple px-4 py-2 text-sm font-semibold text-white transition-opacity hover:opacity-90"
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
                  <svg className="h-5 w-5 text-deep-purple" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
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
                className="rounded-lg bg-gradient-to-r from-electric-blue to-deep-purple px-4 py-2 text-sm font-semibold text-white transition-opacity hover:opacity-90"
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
