"use client";

import { useEffect, useState, useCallback, useRef } from "react";
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
import Alert from "@/components/ui/Alert";
import Badge from "@/components/ui/Badge";
import PageHeader from "@/components/ui/PageHeader";
import Spinner from "@/components/ui/Spinner";
import { Input } from "@/components/ui/Field";

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
    <div className="flat-card rounded-2xl p-6">
      <div className="font-mono text-[11px] font-semibold uppercase tracking-wider text-text-secondary">
        {label}
      </div>
      <div className="mt-1 font-heading text-2xl font-bold tabular-nums text-white">{value}</div>
      {sub && (
        <div className="mt-0.5 text-xs text-text-secondary">{sub}</div>
      )}
    </div>
  );
}

function StatusBadge({ active }: { active: boolean }) {
  return (
    <Badge tone={active ? "success" : "danger"} dot>
      {active ? "Active" : "Inactive"}
    </Badge>
  );
}

function AdminBadge() {
  return <Badge tone="amber">Admin</Badge>;
}

function ShowStatusBadge({ status }: { status: string }) {
  return <Badge tone={status === "active" ? "success" : "neutral"}>{status}</Badge>;
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
  const searchTimeoutRef = useRef<NodeJS.Timeout | null>(null);

  // Redirect non-admin users
  useEffect(() => {
    if (!loading && (!user || !accessToken || !user.is_admin)) {
      router.push(user ? "/dashboard" : "/login");
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
    if (searchTimeoutRef.current) clearTimeout(searchTimeoutRef.current);
    searchTimeoutRef.current = setTimeout(() => {
      searchTimeoutRef.current = null;
      if (tab === "users" && accessToken) {
        fetchAdminUsers(accessToken, {
          limit: 100,
          search: value || undefined,
        })
          .then(setUsers)
          .catch(() => {});
      }
    }, 300);
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

  // Show spinner while auth is loading OR if the user isn't an admin (redirect is pending)
  if (loading || !user || !accessToken || !user.is_admin) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-bg-primary">
        <Spinner />
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
        <PageHeader
          eyebrow="Site admin"
          title="Site admin"
          description="Manage users, organizations, servers, and shows"
          actions={<Badge tone="amber">Admin</Badge>}
          className="mb-8"
        />

        {/* Tab bar */}
        <div
          className="mb-6 flex gap-1 overflow-x-auto rounded-xl border border-white/10 bg-bg-secondary p-1"
          role="tablist"
          aria-label="Admin sections"
        >
          {tabs.map((t) => (
            <button
              key={t.key}
              type="button"
              role="tab"
              aria-selected={tab === t.key}
              onClick={() => setTab(t.key)}
              className={`flex items-center gap-2 whitespace-nowrap rounded-lg px-4 py-2 text-sm font-medium transition-colors ${
                tab === t.key
                  ? "bg-white/[0.06] text-white"
                  : "text-text-secondary hover:bg-white/[0.03] hover:text-white"
              }`}
            >
              {t.label}
              {t.count !== undefined && (
                <span className="rounded-full border border-white/10 bg-white/5 px-1.5 py-0.5 font-mono text-[10px] tabular-nums">
                  {t.count}
                </span>
              )}
            </button>
          ))}
        </div>

        {error && (
          <Alert tone="danger" className="mb-4">
            {error}
          </Alert>
        )}

        {/* Overview tab */}
        {tab === "overview" && (
          <div>
            {loadingData ? (
              <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                {Array.from({ length: 7 }).map((_, i) => (
                  <div
                    key={i}
                    className="flat-card h-24 animate-pulse rounded-2xl"
                  />
                ))}
              </div>
            ) : stats ? (
              <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                <StatCard label="Total users" value={stats.total_users} sub={`+${stats.users_last_30_days} last 30 days`} />
                <StatCard label="Organizations" value={stats.total_organizations} />
                <StatCard label="Servers" value={stats.total_servers} />
                <StatCard label="Total shows" value={stats.total_shows} />
                <StatCard label="Active shows" value={stats.active_shows} />
                <StatCard label="DJ profiles" value={stats.dj_profiles} />
                <StatCard label="New users (30d)" value={stats.users_last_30_days} />
              </div>
            ) : null}
          </div>
        )}

        {/* Users tab */}
        {tab === "users" && (
          <div>
            <div className="mb-4">
              <Input
                type="text"
                placeholder="Search users by name, email, or Discord..."
                value={userSearch}
                onChange={(e) => handleUserSearch(e.target.value)}
                aria-label="Search users"
              />
            </div>

            {loadingData ? (
              <div className="space-y-2">
                {Array.from({ length: 5 }).map((_, i) => (
                  <div
                    key={i}
                    className="flat-card h-16 animate-pulse rounded-xl"
                  />
                ))}
              </div>
            ) : (
              <div className="overflow-x-auto rounded-xl border border-white/10">
                <table className="w-full text-left text-sm">
                  <thead className="bg-white/[0.03] font-mono text-[11px] uppercase tracking-wider text-text-secondary">
                    <tr>
                      <th className="px-4 py-3 font-semibold">User</th>
                      <th className="px-4 py-3 font-semibold">Email</th>
                      <th className="px-4 py-3 font-semibold">Type</th>
                      <th className="px-4 py-3 font-semibold">Status</th>
                      <th className="px-4 py-3 font-semibold">Orgs</th>
                      <th className="px-4 py-3 font-semibold">DJ</th>
                      <th className="px-4 py-3 font-semibold">Joined</th>
                      <th className="px-4 py-3 font-semibold">Actions</th>
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
                                /* Avatar URLs come from Discord/Google OAuth or S3 uploads — domains are dynamic */
                                unoptimized
                              />
                            ) : (
                              <div className="flex h-7 w-7 items-center justify-center rounded-full bg-white/10 font-heading text-xs font-bold text-white">
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
                              type="button"
                              onClick={() => toggleActive(u.id, u.is_active)}
                              className={`rounded-lg border px-2.5 py-1 text-xs font-semibold transition-colors ${
                                u.is_active
                                  ? "border-danger/40 bg-danger/10 text-danger hover:bg-danger/20"
                                  : "border-success/40 bg-success/10 text-success hover:bg-success/20"
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
                              type="button"
                              onClick={() => toggleAdmin(u.id, u.is_admin)}
                              className={`rounded-lg border px-2.5 py-1 text-xs font-semibold transition-colors ${
                                u.is_admin
                                  ? "border-noteblock-amber/40 bg-noteblock-amber/10 text-noteblock-amber hover:bg-noteblock-amber/20"
                                  : "border-white/10 bg-white/5 text-text-secondary hover:bg-white/10 hover:text-white"
                              }`}
                              title={
                                u.is_admin
                                  ? "Remove admin"
                                  : "Make admin"
                              }
                            >
                              {u.is_admin ? "Remove admin" : "Make admin"}
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
                    className="flat-card h-16 animate-pulse rounded-xl"
                  />
                ))}
              </div>
            ) : (
              <div className="overflow-x-auto rounded-xl border border-white/10">
                <table className="w-full text-left text-sm">
                  <thead className="bg-white/[0.03] font-mono text-[11px] uppercase tracking-wider text-text-secondary">
                    <tr>
                      <th className="px-4 py-3 font-semibold">Organization</th>
                      <th className="px-4 py-3 font-semibold">Slug</th>
                      <th className="px-4 py-3 font-semibold">Owner</th>
                      <th className="px-4 py-3 font-semibold">Members</th>
                      <th className="px-4 py-3 font-semibold">Servers</th>
                      <th className="px-4 py-3 font-semibold">Status</th>
                      <th className="px-4 py-3 font-semibold">Created</th>
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
                    className="flat-card h-16 animate-pulse rounded-xl"
                  />
                ))}
              </div>
            ) : (
              <div className="overflow-x-auto rounded-xl border border-white/10">
                <table className="w-full text-left text-sm">
                  <thead className="bg-white/[0.03] font-mono text-[11px] uppercase tracking-wider text-text-secondary">
                    <tr>
                      <th className="px-4 py-3 font-semibold">Server</th>
                      <th className="px-4 py-3 font-semibold">WebSocket URL</th>
                      <th className="px-4 py-3 font-semibold">Organization</th>
                      <th className="px-4 py-3 font-semibold">Status</th>
                      <th className="px-4 py-3 font-semibold">Active shows</th>
                      <th className="px-4 py-3 font-semibold">Last heartbeat</th>
                      <th className="px-4 py-3 font-semibold">Created</th>
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
                    className="flat-card h-16 animate-pulse rounded-xl"
                  />
                ))}
              </div>
            ) : (
              <div className="overflow-x-auto rounded-xl border border-white/10">
                <table className="w-full text-left text-sm">
                  <thead className="bg-white/[0.03] font-mono text-[11px] uppercase tracking-wider text-text-secondary">
                    <tr>
                      <th className="px-4 py-3 font-semibold">Show</th>
                      <th className="px-4 py-3 font-semibold">Server</th>
                      <th className="px-4 py-3 font-semibold">Connect code</th>
                      <th className="px-4 py-3 font-semibold">Status</th>
                      <th className="px-4 py-3 font-semibold">DJs</th>
                      <th className="px-4 py-3 font-semibold">Created</th>
                      <th className="px-4 py-3 font-semibold">Ended</th>
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
