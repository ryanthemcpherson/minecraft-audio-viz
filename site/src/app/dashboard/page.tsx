"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "@/components/AuthProvider";
import type {
  UserProfile,
  OrgSummary,
  DashboardSummary,
  OrgDashboardSummary,
  RecentShowSummary,
} from "@/lib/auth";
import { fetchMe, createInvite, fetchDashboardSummary, resetOnboarding } from "@/lib/auth";

// ---------------------------------------------------------------------------
// Checklist component for server owners
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
  showInvite,
  accessToken,
}: {
  org: OrgDashboardSummary;
  showInvite: boolean;
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
    navigator.clipboard.writeText(code);
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
      {showInvite && org.role === "owner" && (
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
  if (shows.length === 0) {
    return (
      <div className="glass-card rounded-xl p-6 text-center">
        <p className="text-sm text-text-secondary">No shows yet.</p>
      </div>
    );
  }

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
// Badge pill for user type
// ---------------------------------------------------------------------------

function UserTypeBadge({ userType }: { userType: string | null }) {
  if (!userType) return null;
  const labels: Record<string, string> = {
    server_owner: "Server Owner",
    team_member: "Team Member",
    dj: "DJ",
  };
  return (
    <span className="ml-2 rounded-full bg-electric-blue/10 px-2.5 py-0.5 text-xs font-medium text-electric-blue">
      {labels[userType] || userType}
    </span>
  );
}

// ---------------------------------------------------------------------------
// Main dashboard page
// ---------------------------------------------------------------------------

export default function DashboardPage() {
  const router = useRouter();
  const { user, accessToken, loading: authLoading, logout } = useAuth();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [dashboard, setDashboard] = useState<DashboardSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [resettingOnboarding, setResettingOnboarding] = useState(false);

  useEffect(() => {
    if (authLoading) return;
    if (!user || !accessToken) {
      router.push("/login");
      return;
    }

    Promise.all([
      fetchMe(accessToken),
      fetchDashboardSummary(accessToken).catch(() => null),
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

  async function handleResetOnboarding() {
    if (!accessToken) return;
    setResettingOnboarding(true);
    try {
      await resetOnboarding(accessToken);
      router.push("/onboarding");
    } catch {
      setResettingOnboarding(false);
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
        {/* Profile header */}
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
          <div className="flex items-center">
            <div>
              <div className="flex items-center">
                <h1 className="text-2xl font-bold">
                  <span className="text-gradient">{profile.display_name}</span>
                </h1>
                <UserTypeBadge userType={profile.user_type} />
              </div>
              <p className="text-sm text-text-secondary">
                {profile.email || profile.discord_username || "No email"}
              </p>
            </div>
          </div>
          <button
            onClick={logout}
            className="ml-auto rounded-lg border border-white/10 px-4 py-2 text-sm text-text-secondary transition-colors hover:bg-white/5 hover:text-white"
          >
            Log out
          </button>
        </div>

        {/* Role-specific content */}
        <div className="flex flex-col gap-8">
          {/* Server Owner */}
          {dashboard?.user_type === "server_owner" && (
            <>
              <SetupChecklist checklist={dashboard.checklist} />

              <section>
                <div className="mb-4 flex items-center justify-between">
                  <h2 className="text-lg font-semibold">Organizations</h2>
                </div>
                {dashboard.organizations.length === 0 ? (
                  <div className="glass-card rounded-xl p-8 text-center">
                    <p className="text-sm text-text-secondary">No organizations yet.</p>
                  </div>
                ) : (
                  <div className="grid gap-4">
                    {dashboard.organizations.map((org) => (
                      <OrgCard key={org.id} org={org} showInvite accessToken={accessToken!} />
                    ))}
                  </div>
                )}
              </section>

              {dashboard.recent_shows.length > 0 && (
                <ShowList shows={dashboard.recent_shows} title="Recent Shows" />
              )}

              <div className="glass-card rounded-xl p-5">
                <div className="flex items-center justify-between">
                  <div>
                    <h3 className="font-semibold">Getting Started Guide</h3>
                    <p className="text-sm text-text-secondary">Step-by-step setup for your Minecraft server</p>
                  </div>
                  <Link
                    href="/getting-started"
                    className="rounded-lg bg-gradient-to-r from-electric-blue to-deep-purple px-4 py-2 text-sm font-semibold text-white transition-opacity hover:opacity-90"
                  >
                    View Guide
                  </Link>
                </div>
              </div>
            </>
          )}

          {/* Team Member */}
          {dashboard?.user_type === "team_member" && (
            <>
              <section>
                <h2 className="mb-4 text-lg font-semibold">Your Organizations</h2>
                {dashboard.organizations.length === 0 ? (
                  <div className="glass-card rounded-xl p-8 text-center">
                    <p className="text-sm text-text-secondary">No organizations yet.</p>
                  </div>
                ) : (
                  <div className="grid gap-4">
                    {dashboard.organizations.map((org) => (
                      <OrgCard key={org.id} org={org} showInvite={false} accessToken={accessToken!} />
                    ))}
                  </div>
                )}
              </section>

              <ShowList shows={dashboard.active_shows} title="Active Shows" />

              <div className="glass-card rounded-xl p-5">
                <div className="flex items-center justify-between">
                  <div>
                    <h3 className="font-semibold">Getting Started</h3>
                    <p className="text-sm text-text-secondary">Learn how MCAV works</p>
                  </div>
                  <Link
                    href="/getting-started"
                    className="rounded-lg border border-white/10 px-4 py-2 text-sm text-text-secondary transition-colors hover:bg-white/5 hover:text-white"
                  >
                    View Guide
                  </Link>
                </div>
              </div>
            </>
          )}

          {/* DJ */}
          {dashboard?.user_type === "dj" && (
            <>
              <div className="glass-card rounded-xl p-6">
                <div className="flex items-center gap-4">
                  <div className="flex h-14 w-14 items-center justify-center rounded-full bg-electric-blue/10 text-2xl">
                    <svg className="h-7 w-7 text-electric-blue" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2z" />
                    </svg>
                  </div>
                  <div className="flex-1">
                    <h2 className="text-xl font-bold">{dashboard.dj_name}</h2>
                    {dashboard.bio && <p className="text-sm text-text-secondary">{dashboard.bio}</p>}
                    {dashboard.genres && (
                      <div className="mt-2 flex flex-wrap gap-1">
                        {dashboard.genres.split(",").map((g) => (
                          <span key={g.trim()} className="rounded-full bg-white/5 px-2 py-0.5 text-xs text-text-secondary">
                            {g.trim()}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                  <div className="text-right">
                    <div className="text-2xl font-bold text-electric-blue">{dashboard.session_count}</div>
                    <div className="text-xs text-text-secondary">session{dashboard.session_count !== 1 ? "s" : ""}</div>
                  </div>
                </div>
              </div>

              <div className="glass-card rounded-xl p-5">
                <h3 className="mb-3 font-semibold">Quick Connect</h3>
                <p className="mb-3 text-sm text-text-secondary">
                  Get a connect code from a server owner, then enter it in the DJ client to start performing.
                </p>
                <div className="flex gap-3">
                  <Link
                    href="/getting-started"
                    className="rounded-lg bg-gradient-to-r from-electric-blue to-deep-purple px-4 py-2.5 text-sm font-semibold text-white transition-opacity hover:opacity-90"
                  >
                    Download DJ Client
                  </Link>
                </div>
              </div>

              {dashboard.recent_sessions.length > 0 && (
                <ShowList shows={dashboard.recent_sessions} title="Recent Sessions" />
              )}
            </>
          )}

          {/* Generic (skipped onboarding) */}
          {dashboard?.user_type === "generic" && (
            <>
              <div className="glass-card rounded-xl p-6 text-center">
                <div className="mb-4 flex justify-center">
                  <div className="flex h-12 w-12 items-center justify-center rounded-full bg-electric-blue/10">
                    <svg className="h-6 w-6 text-electric-blue" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M12 6v6m0 0v6m0-6h6m-6 0H6" />
                    </svg>
                  </div>
                </div>
                <h2 className="mb-2 text-lg font-semibold">Complete Your Setup</h2>
                <p className="mb-4 text-sm text-text-secondary">
                  Tell us how you&apos;ll be using MCAV so we can personalize your experience.
                </p>
                <button
                  onClick={handleResetOnboarding}
                  disabled={resettingOnboarding}
                  className="rounded-lg bg-gradient-to-r from-electric-blue to-deep-purple px-6 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
                >
                  {resettingOnboarding ? "..." : "Start Setup"}
                </button>
              </div>

              {dashboard.organizations.length > 0 && (
                <section>
                  <h2 className="mb-4 text-lg font-semibold">Organizations</h2>
                  <div className="grid gap-4">
                    {dashboard.organizations.map((org) => (
                      <OrgCard key={org.id} org={org} showInvite accessToken={accessToken!} />
                    ))}
                  </div>
                </section>
              )}
            </>
          )}

          {/* Fallback if dashboard didn't load */}
          {!dashboard && (
            <section>
              <div className="mb-4 flex items-center justify-between">
                <h2 className="text-lg font-semibold">Organizations</h2>
              </div>
              {profile.organizations.length === 0 ? (
                <div className="glass-card rounded-xl p-8 text-center">
                  <p className="mb-2 text-text-secondary">No organizations yet.</p>
                  <p className="text-sm text-text-secondary/70">
                    Create an organization to manage your VJ servers and get a custom subdomain on mcav.live.
                  </p>
                </div>
              ) : (
                <div className="grid gap-4">
                  {profile.organizations.map((org: OrgSummary) => (
                    <div
                      key={org.id}
                      className="glass-card rounded-xl p-5 hover:border-electric-blue/20 transition-all duration-200"
                    >
                      <div className="flex items-center justify-between">
                        <div>
                          <h3 className="font-semibold">{org.name}</h3>
                          <p className="text-sm text-text-secondary">{org.slug}.mcav.live</p>
                        </div>
                        <span className="rounded-full bg-white/5 px-3 py-1 text-xs text-text-secondary">{org.role}</span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </section>
          )}
        </div>
      </div>
    </div>
  );
}
