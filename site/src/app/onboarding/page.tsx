"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import {
  completeOnboarding,
  skipOnboarding,
  createOrg,
  joinOrg,
  createDJProfile,
  fetchMe,
} from "@/lib/auth";
import Link from "next/link";

type UserType = "server_owner" | "team_member" | "dj";
type Step = "role" | "server_owner" | "team_member" | "dj" | "dj_complete";

export default function OnboardingPage() {
  const router = useRouter();
  const { user, accessToken, loading: authLoading } = useAuth();

  const [step, setStep] = useState<Step>("role");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [checkingOnboarding, setCheckingOnboarding] = useState(true);

  // Server Owner fields
  const [orgName, setOrgName] = useState("");
  const [orgSlug, setOrgSlug] = useState("");
  const [orgDescription, setOrgDescription] = useState("");

  // Team Member fields
  const [inviteCode, setInviteCode] = useState("");

  // DJ fields
  const [djName, setDjName] = useState("");
  const [djBio, setDjBio] = useState("");
  const [djGenres, setDjGenres] = useState("");
  const [createdProfileUserId, setCreatedProfileUserId] = useState<string | null>(null);

  // Check if user needs onboarding
  useEffect(() => {
    if (authLoading) return;
    if (!user || !accessToken) {
      router.push("/login");
      return;
    }

    fetchMe(accessToken)
      .then((profile) => {
        if (profile.onboarding_completed) {
          router.replace("/dashboard");
        } else {
          setCheckingOnboarding(false);
        }
      })
      .catch(() => {
        router.push("/login");
      });
  }, [user, accessToken, authLoading, router]);

  // Auto-generate slug from org name
  useEffect(() => {
    const slug = orgName
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-|-$/g, "")
      .slice(0, 63);
    setOrgSlug(slug);
  }, [orgName]);

  async function handleSkip() {
    if (!accessToken) return;
    setSubmitting(true);
    setError("");
    try {
      await skipOnboarding(accessToken);
      router.push("/dashboard");
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Something went wrong");
      setSubmitting(false);
    }
  }

  function selectRole(type: UserType) {
    setError("");
    setStep(type);
  }

  async function handleServerOwner(e: React.FormEvent) {
    e.preventDefault();
    if (!accessToken) return;
    setSubmitting(true);
    setError("");

    try {
      await createOrg(accessToken, orgName, orgSlug, orgDescription || undefined);
      await completeOnboarding(accessToken, "server_owner");
      router.push("/dashboard");
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Something went wrong");
      setSubmitting(false);
    }
  }

  async function handleTeamMember(e: React.FormEvent) {
    e.preventDefault();
    if (!accessToken) return;
    setSubmitting(true);
    setError("");

    try {
      await joinOrg(accessToken, inviteCode);
      await completeOnboarding(accessToken, "team_member");
      router.push("/dashboard");
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Something went wrong");
      setSubmitting(false);
    }
  }

  async function handleDJ(e: React.FormEvent) {
    e.preventDefault();
    if (!accessToken || !user) return;
    setSubmitting(true);
    setError("");

    try {
      await createDJProfile(accessToken, {
        dj_name: djName,
        bio: djBio || undefined,
        genres: djGenres || undefined,
      });
      await completeOnboarding(accessToken, "dj");
      setCreatedProfileUserId(user.id);
      setStep("dj_complete");
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Something went wrong");
    } finally {
      setSubmitting(false);
    }
  }

  if (authLoading || checkingOnboarding) {
    return (
      <div className="flex min-h-screen items-center justify-center pt-20">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-electric-blue" />
      </div>
    );
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center px-4 pt-20 pb-20">
      {/* Background glows */}
      <div className="pointer-events-none absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] bg-electric-blue/5 rounded-full blur-[120px]" />
      <div className="pointer-events-none absolute top-1/3 right-1/4 w-[300px] h-[300px] bg-deep-purple/5 rounded-full blur-[100px]" />

      <div className="relative w-full max-w-lg">
        {/* Step 1: Role Selection */}
        {step === "role" && (
          <div className="animate-slide-up">
            <h1 className="mb-2 text-center text-3xl font-bold">
              <span className="text-gradient">Welcome to MCAV</span>
            </h1>
            <p className="mb-8 text-center text-text-secondary">
              How will you be using MCAV?
            </p>

            <div className="flex flex-col gap-4">
              <button
                onClick={() => selectRole("server_owner")}
                className="glass-card rounded-xl p-6 text-left transition-all duration-200 hover:border-electric-blue/30 hover:bg-white/[0.04]"
              >
                <div className="mb-2 flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-electric-blue/10 text-electric-blue">
                    <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2" />
                    </svg>
                  </div>
                  <h3 className="text-lg font-semibold">Server Owner</h3>
                </div>
                <p className="text-sm text-text-secondary">
                  I run a Minecraft server and want to set up MCAV
                </p>
              </button>

              <button
                onClick={() => selectRole("team_member")}
                className="glass-card rounded-xl p-6 text-left transition-all duration-200 hover:border-electric-blue/30 hover:bg-white/[0.04]"
              >
                <div className="mb-2 flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-deep-purple/10 text-deep-purple">
                    <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
                    </svg>
                  </div>
                  <h3 className="text-lg font-semibold">Team Member</h3>
                </div>
                <p className="text-sm text-text-secondary">
                  I work with a server and want to join their organization
                </p>
              </button>

              <button
                onClick={() => selectRole("dj")}
                className="glass-card rounded-xl p-6 text-left transition-all duration-200 hover:border-electric-blue/30 hover:bg-white/[0.04]"
              >
                <div className="mb-2 flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-electric-blue/10 text-electric-blue">
                    <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2z" />
                    </svg>
                  </div>
                  <h3 className="text-lg font-semibold">DJ</h3>
                </div>
                <p className="text-sm text-text-secondary">
                  I&apos;m a DJ and want to perform at MCAV events
                </p>
              </button>
            </div>

            <button
              onClick={handleSkip}
              disabled={submitting}
              className="mt-6 block w-full text-center text-sm text-text-secondary hover:text-white transition-colors disabled:opacity-50"
            >
              {submitting ? "..." : "Skip for now"}
            </button>
          </div>
        )}

        {/* Step 2a: Server Owner - Create Org */}
        {step === "server_owner" && (
          <div className="animate-slide-up">
            <button
              onClick={() => { setStep("role"); setError(""); }}
              className="mb-6 flex items-center gap-1 text-sm text-text-secondary hover:text-white transition-colors"
            >
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
              </svg>
              Back
            </button>

            <h1 className="mb-2 text-2xl font-bold">
              <span className="text-gradient">Create your organization</span>
            </h1>
            <p className="mb-8 text-text-secondary">
              Set up your server&apos;s home on MCAV
            </p>

            <form onSubmit={handleServerOwner} className="glass-card rounded-xl p-6">
              <div className="flex flex-col gap-4">
                <div>
                  <label htmlFor="orgName" className="mb-1 block text-sm text-text-secondary">
                    Organization name
                  </label>
                  <input
                    id="orgName"
                    type="text"
                    required
                    value={orgName}
                    onChange={(e) => setOrgName(e.target.value)}
                    className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
                    placeholder="My Minecraft Server"
                  />
                </div>

                <div>
                  <label htmlFor="orgSlug" className="mb-1 block text-sm text-text-secondary">
                    Subdomain
                  </label>
                  <div className="flex items-center gap-2">
                    <input
                      id="orgSlug"
                      type="text"
                      required
                      value={orgSlug}
                      onChange={(e) => setOrgSlug(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ""))}
                      className="flex-1 rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
                      placeholder="my-server"
                    />
                    <span className="text-sm text-text-secondary whitespace-nowrap">.mcav.live</span>
                  </div>
                </div>

                <div>
                  <label htmlFor="orgDesc" className="mb-1 block text-sm text-text-secondary">
                    Description <span className="text-text-secondary/50">(optional)</span>
                  </label>
                  <textarea
                    id="orgDesc"
                    value={orgDescription}
                    onChange={(e) => setOrgDescription(e.target.value)}
                    className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50 resize-none"
                    rows={3}
                    placeholder="Tell people about your server"
                  />
                </div>

                {error && (
                  <p className="rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">
                    {error}
                  </p>
                )}

                <button
                  type="submit"
                  disabled={submitting}
                  className="mt-2 rounded-lg bg-gradient-to-r from-electric-blue to-deep-purple px-4 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
                >
                  {submitting ? "Creating..." : "Create Organization"}
                </button>
              </div>
            </form>
          </div>
        )}

        {/* Step 2b: Team Member - Join Org */}
        {step === "team_member" && (
          <div className="animate-slide-up">
            <button
              onClick={() => { setStep("role"); setError(""); }}
              className="mb-6 flex items-center gap-1 text-sm text-text-secondary hover:text-white transition-colors"
            >
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
              </svg>
              Back
            </button>

            <h1 className="mb-2 text-2xl font-bold">
              <span className="text-gradient">Join an organization</span>
            </h1>
            <p className="mb-8 text-text-secondary">
              Enter the invite code from your server owner
            </p>

            <form onSubmit={handleTeamMember} className="glass-card rounded-xl p-6">
              <div className="flex flex-col gap-4">
                <div>
                  <label htmlFor="inviteCode" className="mb-1 block text-sm text-text-secondary">
                    Invite code
                  </label>
                  <input
                    id="inviteCode"
                    type="text"
                    required
                    value={inviteCode}
                    onChange={(e) => setInviteCode(e.target.value.toUpperCase())}
                    className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50 tracking-widest font-mono text-center text-lg"
                    placeholder="ABCD1234"
                    maxLength={8}
                  />
                </div>

                {error && (
                  <p className="rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">
                    {error}
                  </p>
                )}

                <button
                  type="submit"
                  disabled={submitting}
                  className="mt-2 rounded-lg bg-gradient-to-r from-electric-blue to-deep-purple px-4 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
                >
                  {submitting ? "Joining..." : "Join Organization"}
                </button>
              </div>
            </form>
          </div>
        )}

        {/* Step 2c: DJ - Create Profile */}
        {step === "dj" && (
          <div className="animate-slide-up">
            <button
              onClick={() => { setStep("role"); setError(""); }}
              className="mb-6 flex items-center gap-1 text-sm text-text-secondary hover:text-white transition-colors"
            >
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
              </svg>
              Back
            </button>

            <h1 className="mb-2 text-2xl font-bold">
              <span className="text-gradient">Set up your DJ profile</span>
            </h1>
            <p className="mb-8 text-text-secondary">
              Create your public profile for MCAV events
            </p>

            <form onSubmit={handleDJ} className="glass-card rounded-xl p-6">
              <div className="flex flex-col gap-4">
                <div>
                  <label htmlFor="djName" className="mb-1 block text-sm text-text-secondary">
                    DJ / Stage name
                  </label>
                  <input
                    id="djName"
                    type="text"
                    required
                    value={djName}
                    onChange={(e) => setDjName(e.target.value)}
                    className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
                    placeholder="DJ Nova"
                  />
                </div>

                <div>
                  <label htmlFor="djBio" className="mb-1 block text-sm text-text-secondary">
                    Bio <span className="text-text-secondary/50">(optional)</span>
                  </label>
                  <textarea
                    id="djBio"
                    value={djBio}
                    onChange={(e) => setDjBio(e.target.value)}
                    className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50 resize-none"
                    rows={3}
                    placeholder="Tell people about yourself"
                  />
                </div>

                <div>
                  <label htmlFor="djGenres" className="mb-1 block text-sm text-text-secondary">
                    Genres <span className="text-text-secondary/50">(optional)</span>
                  </label>
                  <input
                    id="djGenres"
                    type="text"
                    value={djGenres}
                    onChange={(e) => setDjGenres(e.target.value)}
                    className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
                    placeholder="House, Techno, Drum & Bass"
                  />
                </div>

                {error && (
                  <p className="rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">
                    {error}
                  </p>
                )}

                <button
                  type="submit"
                  disabled={submitting}
                  className="mt-2 rounded-lg bg-gradient-to-r from-electric-blue to-deep-purple px-4 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
                >
                  {submitting ? "Creating..." : "Create Profile"}
                </button>
              </div>
            </form>
          </div>
        )}

        {/* Step 3c: DJ Complete */}
        {step === "dj_complete" && (
          <div className="animate-slide-up text-center">
            <div className="glass-card rounded-xl p-8">
              <div className="mb-4 flex justify-center">
                <div className="flex h-16 w-16 items-center justify-center rounded-full bg-green-500/10">
                  <svg className="h-8 w-8 text-green-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                  </svg>
                </div>
              </div>

              <h1 className="mb-2 text-2xl font-bold">
                <span className="text-gradient">Profile created!</span>
              </h1>
              <p className="mb-8 text-text-secondary">
                You&apos;re all set to start performing at MCAV events.
              </p>

              <div className="flex flex-col gap-3">
                <Link
                  href="/getting-started"
                  className="rounded-lg bg-gradient-to-r from-electric-blue to-deep-purple px-4 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90"
                >
                  Download DJ Client
                </Link>

                {createdProfileUserId && (
                  <Link
                    href={`/dj/${createdProfileUserId}`}
                    className="rounded-lg border border-white/10 px-4 py-3 text-sm font-medium text-text-secondary transition-colors hover:bg-white/5 hover:text-white"
                  >
                    View your profile
                  </Link>
                )}

                <Link
                  href="/dashboard"
                  className="rounded-lg border border-white/10 px-4 py-3 text-sm font-medium text-text-secondary transition-colors hover:bg-white/5 hover:text-white"
                >
                  Go to Dashboard
                </Link>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
