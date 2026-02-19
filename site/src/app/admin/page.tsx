"use client";

import { useEffect, useState, useCallback } from "react";
import { useRouter } from "next/navigation";
import Image from "next/image";
import { useAuth } from "@/components/AuthProvider";
import {
  fetchAdminStats,
  fetchAdminUsers,
  fetchAdminOrgs,
  fetchAdminServers,
  fetchAdminShows,
  updateAdminUser,
} from "@/lib/auth";
import type {
  AdminStats,
  AdminUserRow,
  AdminOrgRow,
  AdminServerRow,
  AdminShowRow,
} from "@/lib/auth";

type Tab = "overview" | "users" | "orgs" | "servers" | "shows";

function relativeTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}

function StatCard({
  label,
  value,
  sub,
}: {
  label: string;
  value: number | string;
  sub?: string;
}) {
  return (
    <div className="glass-card rounded-xl p-5">
      <div className="text-xs font-medium uppercase tracking-wider text-text-secondary">
        {label}
      </div>
      <div className="mt-1 text-2xl font-bold text-white">{value}</div>
      {sub && (
        <div className="mt-0.5 text-xs text-text-secondary">{sub}</div>
      )}
    </div>
  );
}

function StatusBadge({ active }: { active: boolean }) {
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${
        active
          ? "bg-green-500/10 text-green-400"
          : "bg-red-500/10 text-red-400"
      }`}
    >
      <span
        className={`h-1.5 w-1.5 rounded-full ${
          active ? "bg-green-400" : "bg-red-400"
        }`}
      />
      {active ? "Active" : "Inactive"}
    </span>
  );
}

function AdminBadge() {
  return (
    <span className="inline-flex items-center rounded-full bg-amber-500/10 px-2 py-0.5 text-xs font-medium text-amber-400">
      Admin
    </span>
  );
}

function ShowStatusBadge({ status }: { status: string }) {
  const colors: Record<string, string> = {
    active: "bg-green-500/10 text-green-400",
    ended: "bg-white/5 text-text-secondary",
  };
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${
        colors[status] || "bg-white/5 text-text-secondary"
      }`}
    >
      {status}
    </span>
  );
}

export default function AdminPage() {
  const router = useRouter();
  const { user, accessToken, loading } = useAuth();
  const [tab, setTab] = useState<Tab>("overview");
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [users, setUsers] = useState<AdminUserRow[]>([]);
  const [orgs, setOrgs] = useState<AdminOrgRow[]>([]);
  const [servers, setServers] = useState<AdminServerRow[]>([]);
  const [shows, setShows] = useState<AdminShowRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loadingData, setLoadingData] = useState(true);
  const [userSearch, setUserSearch] = useState("");
  const [searchTimeout, setSearchTimeout] = useState<NodeJS.Timeout | null>(null);

  // Redirect non-admin users
  useEffect(() => {
    if (!loading && (!user || !accessToken)) {
      router.push("/login");
    }
  }, [loading, user, accessToken, router]);

  const loadTab = useCallback(
    async (t: Tab) => {
      if (!accessToken) return;
      setLoadingData(true);
      setError(null);
      try {
        switch (t) {
          case "overview":
            setStats(await fetchAdminStats(accessToken));
            break;
          case "users":
            setUsers(
              await fetchAdminUsers(accessToken, {
                limit: 100,
                search: userSearch || undefined,
              })
            );
            break;
          case "orgs":
            setOrgs(await fetchAdminOrgs(accessToken, { limit: 100 }));
            break;
          case "servers":
            setServers(
              await fetchAdminServers(accessToken, { limit: 100 })
            );
            break;
          case "shows":
            setShows(await fetchAdminShows(accessToken, { limit: 100 }));
            break;
        }
      } catch (err) {
        const msg = err instanceof Error ? err.message : "Failed to load data";
        if (msg.includes("Admin access required")) {
          router.push("/dashboard");
          return;
        }
        setError(msg);
      } finally {
        setLoadingData(false);
      }
    },
    [accessToken, userSearch, router]
  );

  // Load data when tab changes
  useEffect(() => {
    if (accessToken) {
      loadTab(tab);
    }
  }, [tab, accessToken, loadTab]);

  // Debounced user search
  const handleUserSearch = (value: string) => {
    setUserSearch(value);
    if (searchTimeout) clearTimeout(searchTimeout);
    setSearchTimeout(
      setTimeout(() => {
        if (tab === "users" && accessToken) {
          fetchAdminUsers(accessToken, {
            limit: 100,
            search: value || undefined,
          })
            .then(setUsers)
            .catch(() => {});
        }
      }, 300)
    );
  };

  const toggleAdmin = async (userId: string, current: boolean) => {
    if (!accessToken) return;
    try {
      const updated = await updateAdminUser(accessToken, userId, {
        is_admin: !current,
      });
      setUsers((prev) =>
        prev.map((u) => (u.id === userId ? updated : u))
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to update user");
    }
  };

  const toggleActive = async (userId: string, current: boolean) => {
    if (!accessToken) return;
    try {
      const updated = await updateAdminUser(accessToken, userId, {
        is_active: !current,
      });
      setUsers((prev) =>
        prev.map((u) => (u.id === userId ? updated : u))
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to update user");
    }
  };

  if (loading) {
    return (
      <main className="flex min-h-screen items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-disc-cyan border-t-transparent" />
      </main>
    );
  }

  const tabs: { key: Tab; label: string; count?: number }[] = [
    { key: "overview", label: "Overview" },
    { key: "users", label: "Users", count: stats?.total_users },
    { key: "orgs", label: "Organizations", count: stats?.total_organizations },
    { key: "servers", label: "Servers", count: stats?.total_servers },
    { key: "shows", label: "Shows", count: stats?.total_shows },
  ];

  return (
    <main className="min-h-screen bg-bg-primary pt-24 pb-16">
      <div className="mx-auto max-w-7xl px-6">
        {/* Header */}
        <div className="mb-8 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-amber-500/10">
            <svg
              className="h-5 w-5 text-amber-400"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={2}
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
              />
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
              />
            </svg>
          </div>
          <div>
            <h1 className="text-2xl font-bold text-white">Site Admin</h1>
            <p className="text-sm text-text-secondary">
              Manage users, organizations, servers, and shows
            </p>
          </div>
        </div>

        {/* Tab bar */}
        <div className="mb-6 flex gap-1 overflow-x-auto rounded-xl border border-white/5 bg-white/[0.02] p-1">
          {tabs.map((t) => (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={`flex items-center gap-2 whitespace-nowrap rounded-lg px-4 py-2 text-sm font-medium transition-colors ${
                tab === t.key
                  ? "bg-white/10 text-white"
                  : "text-text-secondary hover:bg-white/5 hover:text-white"
              }`}
            >
              {t.label}
              {t.count !== undefined && (
                <span className="rounded-full bg-white/5 px-1.5 py-0.5 text-[10px] tabular-nums">
                  {t.count}
                </span>
              )}
            </button>
          ))}
        </div>

        {error && (
          <div className="mb-4 rounded-lg border border-red-500/20 bg-red-500/5 px-4 py-3 text-sm text-red-400">
            {error}
          </div>
        )}

        {/* Overview tab */}
        {tab === "overview" && (
          <div>
            {loadingData ? (
              <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                {Array.from({ length: 7 }).map((_, i) => (
                  <div
                    key={i}
                    className="glass-card h-24 animate-pulse rounded-xl"
                  />
                ))}
              </div>
            ) : stats ? (
              <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                <StatCard label="Total Users" value={stats.total_users} sub={`+${stats.users_last_30_days} last 30 days`} />
                <StatCard label="Organizations" value={stats.total_organizations} />
                <StatCard label="Servers" value={stats.total_servers} />
                <StatCard label="Total Shows" value={stats.total_shows} />
                <StatCard label="Active Shows" value={stats.active_shows} />
                <StatCard label="DJ Profiles" value={stats.dj_profiles} />
                <StatCard label="New Users (30d)" value={stats.users_last_30_days} />
              </div>
            ) : null}
          </div>
        )}

        {/* Users tab */}
        {tab === "users" && (
          <div>
            <div className="mb-4">
              <input
                type="text"
                placeholder="Search users by name, email, or Discord..."
                value={userSearch}
                onChange={(e) => handleUserSearch(e.target.value)}
                className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2.5 text-sm text-white placeholder:text-text-secondary focus:border-disc-cyan/50 focus:outline-none"
              />
            </div>

            {loadingData ? (
              <div className="space-y-2">
                {Array.from({ length: 5 }).map((_, i) => (
                  <div
                    key={i}
                    className="glass-card h-16 animate-pulse rounded-lg"
                  />
                ))}
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead>
                    <tr className="border-b border-white/5 text-xs uppercase tracking-wider text-text-secondary">
                      <th className="px-4 py-3">User</th>
                      <th className="px-4 py-3">Email</th>
                      <th className="px-4 py-3">Type</th>
                      <th className="px-4 py-3">Status</th>
                      <th className="px-4 py-3">Orgs</th>
                      <th className="px-4 py-3">DJ</th>
                      <th className="px-4 py-3">Joined</th>
                      <th className="px-4 py-3">Actions</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-white/5">
                    {users.map((u) => (
                      <tr
                        key={u.id}
                        className="transition-colors hover:bg-white/[0.02]"
                      >
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-2.5">
                            {u.avatar_url ? (
                              <Image
                                src={u.avatar_url}
                                alt={u.display_name}
                                width={28}
                                height={28}
                                className="h-7 w-7 rounded-full"
                                unoptimized
                              />
                            ) : (
                              <div className="flex h-7 w-7 items-center justify-center rounded-full bg-white/10 text-xs font-bold">
                                {u.display_name.charAt(0).toUpperCase()}
                              </div>
                            )}
                            <div>
                              <div className="flex items-center gap-1.5 font-medium text-white">
                                {u.display_name}
                                {u.is_admin && <AdminBadge />}
                              </div>
                              {u.discord_username && (
                                <div className="text-xs text-text-secondary">
                                  {u.discord_username}
                                </div>
                              )}
                            </div>
                          </div>
                        </td>
                        <td className="px-4 py-3 font-mono text-xs text-text-secondary">
                          {u.email || "\u2014"}
                        </td>
                        <td className="px-4 py-3 text-xs text-text-secondary">
                          {u.user_type || "\u2014"}
                        </td>
                        <td className="px-4 py-3">
                          <StatusBadge active={u.is_active} />
                        </td>
                        <td className="px-4 py-3 tabular-nums text-text-secondary">
                          {u.org_count}
                        </td>
                        <td className="px-4 py-3">
                          {u.has_dj_profile ? (
                            <span className="text-disc-cyan">Yes</span>
                          ) : (
                            <span className="text-text-secondary">\u2014</span>
                          )}
                        </td>
                        <td className="px-4 py-3 text-xs text-text-secondary">
                          {relativeTime(u.created_at)}
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-2">
                            <button
                              onClick={() => toggleActive(u.id, u.is_active)}
                              className={`rounded px-2 py-1 text-xs font-medium transition-colors ${
                                u.is_active
                                  ? "bg-red-500/10 text-red-400 hover:bg-red-500/20"
                                  : "bg-green-500/10 text-green-400 hover:bg-green-500/20"
                              }`}
                              title={
                                u.is_active
                                  ? "Deactivate user"
                                  : "Activate user"
                              }
                            >
                              {u.is_active ? "Deactivate" : "Activate"}
                            </button>
                            <button
                              onClick={() => toggleAdmin(u.id, u.is_admin)}
                              className={`rounded px-2 py-1 text-xs font-medium transition-colors ${
                                u.is_admin
                                  ? "bg-amber-500/10 text-amber-400 hover:bg-amber-500/20"
                                  : "bg-white/5 text-text-secondary hover:bg-white/10"
                              }`}
                              title={
                                u.is_admin
                                  ? "Remove admin"
                                  : "Make admin"
                              }
                            >
                              {u.is_admin ? "Remove Admin" : "Make Admin"}
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {users.length === 0 && (
                  <div className="py-12 text-center text-sm text-text-secondary">
                    No users found
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {/* Organizations tab */}
        {tab === "orgs" && (
          <div>
            {loadingData ? (
              <div className="space-y-2">
                {Array.from({ length: 5 }).map((_, i) => (
                  <div
                    key={i}
                    className="glass-card h-16 animate-pulse rounded-lg"
                  />
                ))}
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead>
                    <tr className="border-b border-white/5 text-xs uppercase tracking-wider text-text-secondary">
                      <th className="px-4 py-3">Organization</th>
                      <th className="px-4 py-3">Slug</th>
                      <th className="px-4 py-3">Owner</th>
                      <th className="px-4 py-3">Members</th>
                      <th className="px-4 py-3">Servers</th>
                      <th className="px-4 py-3">Status</th>
                      <th className="px-4 py-3">Created</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-white/5">
                    {orgs.map((o) => (
                      <tr
                        key={o.id}
                        className="transition-colors hover:bg-white/[0.02]"
                      >
                        <td className="px-4 py-3 font-medium text-white">
                          {o.name}
                        </td>
                        <td className="px-4 py-3 font-mono text-xs text-disc-cyan">
                          {o.slug}
                        </td>
                        <td className="px-4 py-3 text-text-secondary">
                          {o.owner_name}
                        </td>
                        <td className="px-4 py-3 tabular-nums text-text-secondary">
                          {o.member_count}
                        </td>
                        <td className="px-4 py-3 tabular-nums text-text-secondary">
                          {o.server_count}
                        </td>
                        <td className="px-4 py-3">
                          <StatusBadge active={o.is_active} />
                        </td>
                        <td className="px-4 py-3 text-xs text-text-secondary">
                          {relativeTime(o.created_at)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {orgs.length === 0 && (
                  <div className="py-12 text-center text-sm text-text-secondary">
                    No organizations found
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {/* Servers tab */}
        {tab === "servers" && (
          <div>
            {loadingData ? (
              <div className="space-y-2">
                {Array.from({ length: 5 }).map((_, i) => (
                  <div
                    key={i}
                    className="glass-card h-16 animate-pulse rounded-lg"
                  />
                ))}
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead>
                    <tr className="border-b border-white/5 text-xs uppercase tracking-wider text-text-secondary">
                      <th className="px-4 py-3">Server</th>
                      <th className="px-4 py-3">WebSocket URL</th>
                      <th className="px-4 py-3">Organization</th>
                      <th className="px-4 py-3">Status</th>
                      <th className="px-4 py-3">Active Shows</th>
                      <th className="px-4 py-3">Last Heartbeat</th>
                      <th className="px-4 py-3">Created</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-white/5">
                    {servers.map((s) => (
                      <tr
                        key={s.id}
                        className="transition-colors hover:bg-white/[0.02]"
                      >
                        <td className="px-4 py-3 font-medium text-white">
                          {s.name}
                        </td>
                        <td className="px-4 py-3 font-mono text-xs text-text-secondary">
                          {s.websocket_url}
                        </td>
                        <td className="px-4 py-3 text-text-secondary">
                          {s.org_name || "\u2014"}
                        </td>
                        <td className="px-4 py-3">
                          <StatusBadge active={s.is_active} />
                        </td>
                        <td className="px-4 py-3 tabular-nums text-text-secondary">
                          {s.active_show_count}
                        </td>
                        <td className="px-4 py-3 text-xs text-text-secondary">
                          {s.last_heartbeat
                            ? relativeTime(s.last_heartbeat)
                            : "Never"}
                        </td>
                        <td className="px-4 py-3 text-xs text-text-secondary">
                          {relativeTime(s.created_at)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {servers.length === 0 && (
                  <div className="py-12 text-center text-sm text-text-secondary">
                    No servers found
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {/* Shows tab */}
        {tab === "shows" && (
          <div>
            {loadingData ? (
              <div className="space-y-2">
                {Array.from({ length: 5 }).map((_, i) => (
                  <div
                    key={i}
                    className="glass-card h-16 animate-pulse rounded-lg"
                  />
                ))}
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead>
                    <tr className="border-b border-white/5 text-xs uppercase tracking-wider text-text-secondary">
                      <th className="px-4 py-3">Show</th>
                      <th className="px-4 py-3">Server</th>
                      <th className="px-4 py-3">Connect Code</th>
                      <th className="px-4 py-3">Status</th>
                      <th className="px-4 py-3">DJs</th>
                      <th className="px-4 py-3">Created</th>
                      <th className="px-4 py-3">Ended</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-white/5">
                    {shows.map((sh) => (
                      <tr
                        key={sh.id}
                        className="transition-colors hover:bg-white/[0.02]"
                      >
                        <td className="px-4 py-3 font-medium text-white">
                          {sh.name}
                        </td>
                        <td className="px-4 py-3 text-text-secondary">
                          {sh.server_name}
                        </td>
                        <td className="px-4 py-3 font-mono text-xs text-disc-cyan">
                          {sh.connect_code || "\u2014"}
                        </td>
                        <td className="px-4 py-3">
                          <ShowStatusBadge status={sh.status} />
                        </td>
                        <td className="px-4 py-3 tabular-nums text-text-secondary">
                          {sh.current_djs}/{sh.max_djs}
                        </td>
                        <td className="px-4 py-3 text-xs text-text-secondary">
                          {relativeTime(sh.created_at)}
                        </td>
                        <td className="px-4 py-3 text-xs text-text-secondary">
                          {sh.ended_at
                            ? relativeTime(sh.ended_at)
                            : "\u2014"}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {shows.length === 0 && (
                  <div className="py-12 text-center text-sm text-text-secondary">
                    No shows found
                  </div>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </main>
  );
}
