"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import { fetchMe } from "@/lib/auth";
import type { UserProfile } from "@/lib/auth";

export default function AccountSettingsPage() {
  const router = useRouter();
  const { user, accessToken, loading: authLoading } = useAuth();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (authLoading) return;
    if (!user || !accessToken) {
      router.push("/login");
      return;
    }

    fetchMe(accessToken)
      .then(setProfile)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [user, accessToken, authLoading, router]);

  if (authLoading || loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-electric-blue" />
      </div>
    );
  }

  if (!profile) return null;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h2 className="mb-1 text-lg font-semibold">Account</h2>
        <p className="text-sm text-text-secondary">Your account information</p>
      </div>

      {/* Display name */}
      <div className="glass-card rounded-xl p-5">
        <div className="flex flex-col gap-4">
          <div>
            <label className="mb-1 block text-xs font-medium uppercase tracking-wider text-text-secondary">
              Display Name
            </label>
            <p className="text-sm text-white">{profile.display_name}</p>
          </div>

          {/* Email */}
          <div>
            <label className="mb-1 block text-xs font-medium uppercase tracking-wider text-text-secondary">
              Email
            </label>
            <p className="text-sm text-white">
              {profile.email || <span className="text-text-secondary">Not set</span>}
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
                    <svg className="h-3.5 w-3.5" viewBox="0 0 24 24" fill="currentColor">
                      <path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994a.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03z" />
                    </svg>
                    {profile.discord_username}
                  </span>
                  <span className="rounded-full bg-green-500/10 px-2 py-0.5 text-xs text-green-400">
                    Connected
                  </span>
                </>
              ) : (
                <span className="text-sm text-text-secondary">Not connected</span>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Password section */}
      <div className="glass-card rounded-xl p-5">
        <div>
          <label className="mb-1 block text-xs font-medium uppercase tracking-wider text-text-secondary">
            Password
          </label>
          <p className="text-sm text-text-secondary">
            Password management coming soon.
          </p>
        </div>
      </div>
    </div>
  );
}
