"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import {
  fetchMe,
  updateAccount,
  changePassword,
  getDiscordAuthUrl,
  getStoredRefreshToken,
} from "@/lib/auth";
import type { UserProfile } from "@/lib/auth";

export default function AccountSettingsPage() {
  const router = useRouter();
  const { user, accessToken, loading: authLoading, setAuth } = useAuth();
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
      .catch(() => {})
      .finally(() => setLoading(false));
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
    } catch {
      setDiscordRedirecting(false);
    }
  }

  // -- Loading / guard --

  if (authLoading || loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-electric-blue" />
      </div>
    );
  }

  if (!profile) return null;

  const hasEmail = !!profile.email;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h2 className="mb-1 text-lg font-semibold">Account</h2>
        <p className="text-sm text-text-secondary">
          Your account information
        </p>
      </div>

      {/* Display name + Email */}
      <div className="glass-card rounded-xl p-5">
        <div className="flex flex-col gap-4">
          {/* Display Name */}
          <div>
            <label className="mb-1 block text-xs font-medium uppercase tracking-wider text-text-secondary">
              Display Name
            </label>
            {editingName ? (
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  name="display_name"
                  autoComplete="name"
                  value={nameValue}
                  onChange={(e) => setNameValue(e.target.value)}
                  className="flex-1 rounded-lg border border-white/10 bg-white/[0.03] px-3 py-1.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
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
                  className="rounded-lg bg-electric-blue/20 px-3 py-1.5 text-xs font-medium text-electric-blue transition-colors hover:bg-electric-blue/30 disabled:opacity-50"
                >
                  {nameSaving ? "Saving..." : "Save"}
                </button>
                <button
                  type="button"
                  onClick={cancelEditingName}
                  disabled={nameSaving}
                  className="rounded-lg bg-white/5 px-3 py-1.5 text-xs font-medium text-text-secondary transition-colors hover:bg-white/10"
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
              <p className="mt-1 text-xs text-red-400">{nameError}</p>
            )}
            {nameSuccess && (
              <p className="mt-1 text-xs text-green-400">{nameSuccess}</p>
            )}
          </div>

          {/* Email */}
          <div>
            <label className="mb-1 block text-xs font-medium uppercase tracking-wider text-text-secondary">
              Email
            </label>
            <p className="text-sm text-white">
              {profile.email || (
                <span className="text-text-secondary">Not set</span>
              )}
            </p>
          </div>

          {/* Discord */}
          <div>
            <label className="mb-1 block text-xs font-medium uppercase tracking-wider text-text-secondary">
              Discord
            </label>
            <div className="flex items-center gap-2">
              {profile.discord_username ? (
                <>
                  <span className="inline-flex items-center gap-1.5 rounded-full bg-[#5865F2]/10 px-2.5 py-0.5 text-sm text-[#5865F2]">
                    <svg
                      className="h-3.5 w-3.5"
                      viewBox="0 0 24 24"
                      fill="currentColor"
                    >
                      <path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994a.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03z" />
                    </svg>
                    {profile.discord_username}
                  </span>
                  <span className="rounded-full bg-green-500/10 px-2 py-0.5 text-xs text-green-400">
                    Connected
                  </span>
                </>
              ) : (
                <button
                  type="button"
                  onClick={handleConnectDiscord}
                  disabled={discordRedirecting}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-[#5865F2]/10 px-3 py-1.5 text-sm font-medium text-[#5865F2] transition-colors hover:bg-[#5865F2]/20 disabled:opacity-50"
                >
                  <svg
                    className="h-4 w-4"
                    viewBox="0 0 24 24"
                    fill="currentColor"
                  >
                    <path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994a.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03z" />
                  </svg>
                  {discordRedirecting ? "Redirecting..." : "Connect Discord"}
                </button>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Password section */}
      <div className="glass-card rounded-xl p-5">
        <label className="mb-3 block text-xs font-medium uppercase tracking-wider text-text-secondary">
          Password
        </label>

        {hasEmail ? (
          <form
            onSubmit={handleChangePassword}
            className="flex flex-col gap-4"
          >
            <div>
              <label
                htmlFor="currentPassword"
                className="mb-1 block text-sm text-text-secondary"
              >
                Current password
              </label>
              <input
                id="currentPassword"
                name="current-password"
                type="password"
                autoComplete="current-password"
                required
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
                className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
              />
            </div>

            <div>
              <label
                htmlFor="newPassword"
                className="mb-1 block text-sm text-text-secondary"
              >
                New password
              </label>
              <input
                id="newPassword"
                name="new-password"
                type="password"
                autoComplete="new-password"
                required
                minLength={8}
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
              />
            </div>

            <div>
              <label
                htmlFor="confirmPassword"
                className="mb-1 block text-sm text-text-secondary"
              >
                Confirm new password
              </label>
              <input
                id="confirmPassword"
                name="confirm-password"
                type="password"
                autoComplete="new-password"
                required
                minLength={8}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
              />
            </div>

            {pwError && (
              <p className="rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">
                {pwError}
              </p>
            )}
            {pwSuccess && (
              <p className="rounded-lg bg-green-500/10 px-3 py-2 text-sm text-green-400">
                {pwSuccess}
              </p>
            )}

            <button
              type="submit"
              disabled={pwSaving || !currentPassword || !newPassword || !confirmPassword}
              className="w-fit rounded-lg bg-white/5 px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-white/10 disabled:opacity-50"
            >
              {pwSaving ? "Changing..." : "Change Password"}
            </button>
          </form>
        ) : (
          <p className="text-sm text-text-secondary">
            You signed in with Discord. Password login is not available for your
            account.
          </p>
        )}
      </div>
    </div>
  );
}
