"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import {
  fetchMe,
  updateAccount,
  changePassword,
  deleteAccount,
  getDiscordAuthUrl,
  getStoredRefreshToken,
  clearStoredRefreshToken,
  fetchSessions,
  revokeSession,
} from "@/lib/auth";
import type { UserProfile, User, SessionInfo } from "@/lib/auth";
import Alert from "@/components/ui/Alert";
import Badge from "@/components/ui/Badge";
import Spinner from "@/components/ui/Spinner";
import { Label, Input } from "@/components/ui/Field";

const primaryButtonClassName =
  "inline-flex items-center justify-center gap-2 rounded-xl bg-disc-cyan font-semibold whitespace-nowrap text-white transition-all duration-200 select-none active:brightness-95 disabled:cursor-not-allowed disabled:opacity-50";

const secondaryButtonClassName =
  "inline-flex items-center justify-center gap-2 rounded-xl border border-white/10 bg-white/5 font-semibold whitespace-nowrap text-white backdrop-blur-sm transition-all duration-200 select-none hover:border-white/20 hover:bg-white/10 disabled:cursor-not-allowed disabled:opacity-50";

const dangerButtonClassName =
  "inline-flex items-center justify-center gap-2 rounded-xl border border-danger/40 bg-danger/10 font-semibold whitespace-nowrap text-danger transition-all duration-200 select-none hover:bg-danger/20 disabled:cursor-not-allowed disabled:opacity-50";

function timeAgo(dateStr: string | null): string {
  if (!dateStr) return "Never";
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return "Just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

export default function AccountSettingsPage() {
  const router = useRouter();
  const { user, accessToken, loading: authLoading, setAuth, logout } = useAuth();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);

  // Display name editing
  const [editingName, setEditingName] = useState(false);
  const [nameValue, setNameValue] = useState("");
  const [nameSaving, setNameSaving] = useState(false);
  const [nameError, setNameError] = useState("");
  const [nameSuccess, setNameSuccess] = useState("");

  // Change password
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [pwSaving, setPwSaving] = useState(false);
  const [pwError, setPwError] = useState("");
  const [pwSuccess, setPwSuccess] = useState("");

  // Discord connect
  const [discordRedirecting, setDiscordRedirecting] = useState(false);

  // Active sessions
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(true);
  const [sessionsError, setSessionsError] = useState("");

  // Account deletion
  const [deleteConfirm, setDeleteConfirm] = useState("");
  const [deletePassword, setDeletePassword] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState("");

  useEffect(() => {
    if (authLoading) return;
    if (!user || !accessToken) {
      router.push("/login");
      return;
    }

    fetchMe(accessToken)
      .then((p) => {
        setProfile(p);
        setNameValue(p.display_name);
      })
      .catch((err) => { console.error("Failed to fetch user profile:", err); })
      .finally(() => setLoading(false));

    fetchSessions(accessToken)
      .then((s) => setSessions(s))
      .catch((err) => setSessionsError(err instanceof Error ? err.message : "Failed to load sessions"))
      .finally(() => setSessionsLoading(false));
  }, [user, accessToken, authLoading, router]);

  // -- Display name handlers --

  function startEditingName() {
    setNameValue(profile?.display_name ?? "");
    setNameError("");
    setNameSuccess("");
    setEditingName(true);
  }

  function cancelEditingName() {
    setNameValue(profile?.display_name ?? "");
    setEditingName(false);
    setNameError("");
  }

  async function saveName() {
    if (!accessToken || !nameValue.trim()) return;
    setNameSaving(true);
    setNameError("");
    setNameSuccess("");

    try {
      const updated = await updateAccount(accessToken, {
        display_name: nameValue.trim(),
      });
      setProfile(updated);
      setEditingName(false);
      setNameSuccess("Display name updated.");
      // Update the AuthProvider user state so the navbar reflects the change
      const storedRefresh = getStoredRefreshToken();
      if (user && storedRefresh) {
        setAuth(accessToken, storedRefresh, { ...user, display_name: updated.display_name });
      }
      setTimeout(() => setNameSuccess(""), 3000);
    } catch (err: unknown) {
      setNameError(err instanceof Error ? err.message : "Failed to update name");
    } finally {
      setNameSaving(false);
    }
  }

  // -- Change password handlers --

  async function handleChangePassword(e: React.FormEvent) {
    e.preventDefault();
    if (!accessToken) return;

    setPwError("");
    setPwSuccess("");

    if (newPassword.length < 8) {
      setPwError("Password must be at least 8 characters.");
      return;
    }
    if (newPassword !== confirmPassword) {
      setPwError("Passwords do not match.");
      return;
    }

    setPwSaving(true);
    try {
      const updated = await changePassword(
        accessToken,
        currentPassword,
        newPassword
      );
      setProfile(updated);
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
      setPwSuccess("Password changed successfully.");
      setTimeout(() => setPwSuccess(""), 5000);
    } catch (err: unknown) {
      setPwError(
        err instanceof Error ? err.message : "Failed to change password"
      );
    } finally {
      setPwSaving(false);
    }
  }

  // -- Discord connect handler --

  async function handleConnectDiscord() {
    setDiscordRedirecting(true);
    try {
      const url = await getDiscordAuthUrl();
      window.location.href = url;
    } catch (err) {
      console.error("Failed to get Discord auth URL:", err);
      setDiscordRedirecting(false);
    }
  }

  async function handleDeleteAccount(e: React.FormEvent) {
    e.preventDefault();
    if (!accessToken || deleteConfirm !== "DELETE") return;
    setDeleteError("");
    setDeleting(true);
    try {
      await deleteAccount(accessToken, deletePassword);
      // Clear auth and redirect to home
      await logout();
      router.push("/");
    } catch (err: unknown) {
      setDeleteError(err instanceof Error ? err.message : "Failed to delete account");
    } finally {
      setDeleting(false);
    }
  }

  // -- Session management --

  async function handleRevokeSession(sessionId: string) {
    if (!accessToken) return;
    try {
      await revokeSession(accessToken, sessionId);
      setSessions((prev) => prev.filter((s) => s.id !== sessionId));
    } catch (err) {
      setSessionsError(err instanceof Error ? err.message : "Failed to revoke session");
    }
  }

  // -- Loading / guard --

  if (authLoading || loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <Spinner />
      </div>
    );
  }

  if (!profile) return null;

  const hasEmail = !!profile.email;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h2 className="mb-1 font-heading text-lg font-bold">Account</h2>
        <p className="text-sm text-text-secondary">
          Your account information
        </p>
      </div>

      {/* Display name + Email */}
      <section className="flat-card rounded-2xl p-6">
        <p className="mb-4 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] text-disc-cyan">
          Identity
        </p>
        <div className="flex flex-col gap-5">
          {/* Display Name */}
          <div>
            <Label htmlFor="display_name">Display name</Label>
            {editingName ? (
              <div className="flex items-center gap-2">
                <Input
                  id="display_name"
                  type="text"
                  name="display_name"
                  autoComplete="name"
                  value={nameValue}
                  onChange={(e) => setNameValue(e.target.value)}
                  className="flex-1 py-1.5"
                  autoFocus
                  onKeyDown={(e) => {
                    if (e.key === "Enter") saveName();
                    if (e.key === "Escape") cancelEditingName();
                  }}
                />
                <button
                  type="button"
                  onClick={saveName}
                  disabled={nameSaving || !nameValue.trim()}
                  className={`${primaryButtonClassName} px-4 py-2 text-xs`}
                >
                  {nameSaving ? "Saving..." : "Save"}
                </button>
                <button
                  type="button"
                  onClick={cancelEditingName}
                  disabled={nameSaving}
                  className={`${secondaryButtonClassName} px-4 py-2 text-xs`}
                >
                  Cancel
                </button>
              </div>
            ) : (
              <div className="flex items-center gap-2">
                <p className="text-sm text-white">{profile.display_name}</p>
                <button
                  type="button"
                  onClick={startEditingName}
                  className="text-text-secondary transition-colors hover:text-white"
                  aria-label="Edit display name"
                >
                  <svg
                    className="h-3.5 w-3.5"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                    strokeWidth={2}
                    aria-hidden="true"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M16.862 4.487l1.687-1.688a1.875 1.875 0 112.652 2.652L6.832 19.82a4.5 4.5 0 01-1.897 1.13l-2.685.8.8-2.685a4.5 4.5 0 011.13-1.897L16.863 4.487z"
                    />
                  </svg>
                </button>
              </div>
            )}
            {nameError && (
              <p className="mt-1.5 text-xs text-danger" role="alert">{nameError}</p>
            )}
            {nameSuccess && (
              <p className="mt-1.5 text-xs text-success" role="status">{nameSuccess}</p>
            )}
          </div>

          {/* Email */}
          <div>
            <Label>Email</Label>
            <p className="text-sm text-white">
              {profile.email || (
                <span className="text-text-secondary">Not set</span>
              )}
            </p>
          </div>

          {/* Discord */}
          <div>
            <Label>Discord</Label>
            <div className="flex flex-wrap items-center gap-2">
              {profile.discord_username ? (
                <>
                  <span className="inline-flex items-center gap-1.5 rounded-full border border-[#5865F2]/30 bg-[#5865F2]/10 px-2.5 py-0.5 text-sm text-[#5865F2]">
                    <svg
                      className="h-3.5 w-3.5"
                      viewBox="0 0 24 24"
                      fill="currentColor"
                      aria-hidden="true"
                    >
                      <path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994a.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03z" />
                    </svg>
                    {profile.discord_username}
                  </span>
                  <Badge tone="success" dot>Connected</Badge>
                </>
              ) : (
                <button
                  type="button"
                  onClick={handleConnectDiscord}
                  disabled={discordRedirecting}
                  className="inline-flex items-center gap-1.5 rounded-xl border border-[#5865F2]/30 bg-[#5865F2]/10 px-4 py-2 text-xs font-semibold text-[#5865F2] transition-colors hover:bg-[#5865F2]/20 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <svg
                    className="h-4 w-4"
                    viewBox="0 0 24 24"
                    fill="currentColor"
                    aria-hidden="true"
                  >
                    <path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994a.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03z" />
                  </svg>
                  {discordRedirecting ? "Redirecting..." : "Connect Discord"}
                </button>
              )}
            </div>
          </div>
        </div>
      </section>

      {/* Password section */}
      <section className="flat-card rounded-2xl p-6">
        <p className="mb-1 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] text-disc-cyan">
          Security
        </p>
        <h3 className="mb-4 font-heading text-lg font-bold">Password</h3>

        {hasEmail ? (
          <form
            onSubmit={handleChangePassword}
            className="flex flex-col gap-4"
          >
            <div>
              <Label htmlFor="currentPassword">Current password</Label>
              <Input
                id="currentPassword"
                name="current-password"
                type="password"
                autoComplete="current-password"
                required
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
              />
            </div>

            <div>
              <Label htmlFor="newPassword">New password</Label>
              <Input
                id="newPassword"
                name="new-password"
                type="password"
                autoComplete="new-password"
                required
                minLength={8}
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
              />
            </div>

            <div>
              <Label htmlFor="confirmPassword">Confirm new password</Label>
              <Input
                id="confirmPassword"
                name="confirm-password"
                type="password"
                autoComplete="new-password"
                required
                minLength={8}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
              />
            </div>

            {pwError && <Alert tone="danger">{pwError}</Alert>}
            {pwSuccess && <Alert tone="success">{pwSuccess}</Alert>}

            <button
              type="submit"
              disabled={pwSaving || !currentPassword || !newPassword || !confirmPassword}
              className={`${secondaryButtonClassName} w-fit px-6 py-3 text-sm`}
            >
              {pwSaving ? "Changing..." : "Change password"}
            </button>
          </form>
        ) : (
          <p className="text-sm text-text-secondary">
            You signed in with Discord. Password login is not available for your
            account.
          </p>
        )}
      </section>

      {/* Active Sessions */}
      <section className="flat-card rounded-2xl p-6">
        <p className="mb-1 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] text-disc-cyan">
          Devices
        </p>
        <h3 className="mb-4 font-heading text-lg font-bold">Active sessions</h3>
        {sessionsLoading ? (
          <div className="flex items-center justify-center py-6">
            <Spinner size="sm" label="Loading sessions" />
          </div>
        ) : sessionsError ? (
          <Alert tone="danger">{sessionsError}</Alert>
        ) : sessions.length === 0 ? (
          <p className="text-sm text-text-secondary">No active sessions found.</p>
        ) : (
          <div className="flex flex-col gap-3">
            {sessions.map((session) => (
              <div
                key={session.id}
                className="flex items-center justify-between rounded-xl border border-white/10 bg-white/[0.02] px-4 py-3"
              >
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm text-white" title={session.user_agent ?? undefined}>
                    {session.user_agent
                      ? session.user_agent.length > 60
                        ? session.user_agent.slice(0, 60) + "..."
                        : session.user_agent
                      : "Unknown device"}
                  </p>
                  <div className="mt-1 flex flex-wrap items-center gap-3 font-mono text-xs text-text-secondary">
                    <span>{session.ip_address ?? "Unknown IP"}</span>
                    <span>Last used: {timeAgo(session.last_used_at)}</span>
                    {session.is_current && (
                      <Badge tone="success" dot>Current</Badge>
                    )}
                  </div>
                </div>
                {!session.is_current && (
                  <button
                    type="button"
                    onClick={() => handleRevokeSession(session.id)}
                    className={`${dangerButtonClassName} ml-4 shrink-0 px-4 py-2 text-xs`}
                  >
                    Revoke
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
      </section>

      {/* Danger Zone */}
      <section className="flat-card rounded-2xl border-danger/25 p-6">
        <p className="mb-1 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] text-danger">
          Danger zone
        </p>
        <h3 className="mb-1 font-heading text-lg font-bold text-danger">Delete account</h3>
        <p className="mb-4 text-sm text-text-secondary">
          Permanently delete your account. This action cannot be undone.
        </p>

        <form onSubmit={handleDeleteAccount} className="flex flex-col gap-4">
          {hasEmail && (
            <div>
              <Label htmlFor="delete-password">Confirm your password</Label>
              <Input
                id="delete-password"
                name="password"
                type="password"
                autoComplete="current-password"
                value={deletePassword}
                onChange={(e) => setDeletePassword(e.target.value)}
                className="focus:border-danger/50"
              />
            </div>
          )}

          <div>
            <Label htmlFor="delete-confirm">
              Type <span className="text-danger">DELETE</span> to confirm
            </Label>
            <Input
              id="delete-confirm"
              type="text"
              value={deleteConfirm}
              onChange={(e) => setDeleteConfirm(e.target.value)}
              className="focus:border-danger/50"
              placeholder="DELETE"
            />
          </div>

          {deleteError && <Alert tone="danger">{deleteError}</Alert>}

          <button
            type="submit"
            disabled={deleting || deleteConfirm !== "DELETE" || (hasEmail && !deletePassword)}
            className={`${dangerButtonClassName} w-fit px-6 py-3 text-sm`}
          >
            {deleting ? "Deleting..." : "Delete account"}
          </button>
        </form>
      </section>
    </div>
  );
}
