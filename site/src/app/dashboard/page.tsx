"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import type { UserProfile, OrgSummary } from "@/lib/auth";
import { fetchMe } from "@/lib/auth";

export default function DashboardPage() {
  const router = useRouter();
  const { user, accessToken, loading: authLoading, logout } = useAuth();
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
      .catch(() => {
        // Token may have expired
        router.push("/login");
      })
      .finally(() => setLoading(false));
  }, [user, accessToken, authLoading, router]);

  if (authLoading || loading) {
    return (
      <div className="flex min-h-screen items-center justify-center pt-20">
        <div className="w-full max-w-4xl px-6">
          {/* Skeleton header */}
          <div className="mb-10 flex items-center gap-4">
            <div className="h-14 w-14 animate-pulse rounded-full bg-white/5" />
            <div className="flex-1">
              <div className="h-6 w-40 animate-pulse rounded-lg bg-white/5" />
              <div className="mt-2 h-4 w-56 animate-pulse rounded-lg bg-white/5" />
            </div>
          </div>
          {/* Skeleton cards */}
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
          <div>
            <h1 className="text-2xl font-bold">
              <span className="text-gradient">{profile.display_name}</span>
            </h1>
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

        {/* Organizations */}
        <section>
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-lg font-semibold">Organizations</h2>
          </div>

          {profile.organizations.length === 0 ? (
            <div className="glass-card rounded-xl p-8 text-center">
              <p className="mb-2 text-text-secondary">No organizations yet.</p>
              <p className="text-sm text-text-secondary/70">
                Create an organization to manage your VJ servers and get a custom
                subdomain on mcav.live.
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
                      <p className="text-sm text-text-secondary">
                        {org.slug}.mcav.live
                      </p>
                    </div>
                    <span className="rounded-full bg-white/5 px-3 py-1 text-xs text-text-secondary">
                      {org.role}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
