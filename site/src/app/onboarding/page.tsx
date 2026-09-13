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
import PageHeader from "@/components/ui/PageHeader";
import Button from "@/components/ui/Button";
import Alert from "@/components/ui/Alert";
import Spinner from "@/components/ui/Spinner";
import { Label, Input, TextArea } from "@/components/ui/Field";

type UserType = "server_owner" | "team_member" | "dj";
type Step = "role" | "server_owner" | "team_member" | "dj" | "server_owner_complete" | "team_member_complete" | "dj_complete";

// Button.tsx does not export its variant classes and its "disabled" variant
// renders a span, so submit buttons that disable while submitting mirror the
// primary variant here on a native <button>.
const primaryButton =
  "inline-flex w-full items-center justify-center gap-2 rounded-xl px-6 py-3 text-sm font-semibold whitespace-nowrap transition-all duration-200 select-none bg-gradient-to-r from-disc-cyan to-disc-blue text-white shadow-lg shadow-disc-cyan/20 hover:shadow-xl hover:shadow-disc-cyan/30 hover:brightness-110 active:brightness-95 disabled:cursor-not-allowed disabled:opacity-50";

const sectionLabel = "mb-3 font-mono text-[11px] uppercase tracking-wider text-text-secondary/70";

function StepProgress({ current }: { current: number }) {
  return (
    <div className="mb-8 flex items-center justify-center gap-2">
      {[0, 1, 2].map((i) => (
        <div
          key={i}
          className={`h-2 rounded-full transition-all duration-300 ${
            i === current
              ? "w-8 bg-disc-cyan"
              : i < current
                ? "w-2 bg-disc-cyan/40"
                : "w-2 bg-white/10"
          }`}
        />
      ))}
    </div>
  );
}

function BackButton({ onClick }: { onClick: () => void }) {
  return (
    <Button variant="ghost" size="sm" onClick={onClick} className="mb-6 -ml-4">
      <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
        <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
      </svg>
      Back
    </Button>
  );
}

function SuccessIcon() {
  return (
    <div className="mb-4 flex justify-center">
      <div className="flex h-16 w-16 items-center justify-center rounded-full bg-success/10">
        <svg className="h-8 w-8 text-success" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
          <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
        </svg>
      </div>
    </div>
  );
}

function NextStepsList({ items }: { items: { label: string; icon: string }[] }) {
  return (
    <div className="flex flex-col gap-2">
      {items.map((item) => (
        <div key={item.label} className="flex items-center gap-3 rounded-lg bg-white/[0.03] px-4 py-3 text-sm">
          <svg className="h-4 w-4 shrink-0 text-text-secondary" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round" d={item.icon} />
          </svg>
          <span className="text-text-secondary">{item.label}</span>
        </div>
      ))}
    </div>
  );
}

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
  const [createdOrg, setCreatedOrg] = useState<{ name: string; slug: string } | null>(null);

  // Team Member fields
  const [inviteCode, setInviteCode] = useState("");
  const [joinedOrg, setJoinedOrg] = useState<{ name: string; slug: string } | null>(null);

  // DJ fields
  const [djName, setDjName] = useState("");
  const [djBio, setDjBio] = useState("");
  const [djGenres, setDjGenres] = useState("");
  const [createdProfileSlug, setCreatedProfileSlug] = useState<string | null>(null);

  // Check if user needs onboarding
  useEffect(() => {
    if (authLoading) return;
    if (!user || !accessToken) {
      router.push("/login");
      return;
    }

    const controller = new AbortController();

    fetchMe(accessToken)
      .then((profile) => {
        if (controller.signal.aborted) return;
        if (profile.onboarding_completed) {
          router.replace("/dashboard");
        } else {
          setCheckingOnboarding(false);
        }
      })
      .catch(() => {
        if (controller.signal.aborted) return;
        router.push("/login");
      });

    return () => controller.abort();
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
      setError(err instanceof Error ? err.message : "Unable to complete. Please try again.");
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
      setCreatedOrg({ name: orgName, slug: orgSlug });
      setStep("server_owner_complete");
      setSubmitting(false);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Unable to create organization. Please try again.");
      setSubmitting(false);
    }
  }

  async function handleTeamMember(e: React.FormEvent) {
    e.preventDefault();
    if (!accessToken) return;
    setSubmitting(true);
    setError("");

    try {
      const result = await joinOrg(accessToken, inviteCode);
      await completeOnboarding(accessToken, "team_member");
      setJoinedOrg({ name: result.org_name, slug: result.org_slug });
      setStep("team_member_complete");
      setSubmitting(false);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Unable to join organization. Please try again.");
      setSubmitting(false);
    }
  }

  async function handleDJ(e: React.FormEvent) {
    e.preventDefault();
    if (!accessToken || !user) return;
    setSubmitting(true);
    setError("");

    try {
      const profile = await createDJProfile(accessToken, {
        dj_name: djName,
        bio: djBio || undefined,
        genres: djGenres || undefined,
      });
      await completeOnboarding(accessToken, "dj");
      setCreatedProfileSlug(profile.slug ?? null);
      setStep("dj_complete");
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Unable to create profile. Please try again.");
    } finally {
      setSubmitting(false);
    }
  }

  if (authLoading || checkingOnboarding) {
    return (
      <div className="flex min-h-screen items-center justify-center pt-20">
        <Spinner />
      </div>
    );
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center px-4 pt-20 pb-20">
      {/* Background glows */}
      <div className="pointer-events-none absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] bg-disc-cyan/5 rounded-full blur-[120px]" />
      <div className="pointer-events-none absolute top-1/3 right-1/4 w-[300px] h-[300px] bg-disc-blue/5 rounded-full blur-[100px]" />

      <div className="relative w-full max-w-lg">
        {/* Step 1: Role Selection */}
        {step === "role" && (
          <div className="animate-slide-up">
            <StepProgress current={0} />

            <PageHeader
              className="mb-8 text-center"
              eyebrow="Onboarding"
              title={<span className="text-disc-cyan">Set up your experience</span>}
              description="How will you be using MCAV?"
            />

            <div className="flex flex-col gap-4">
              <button
                type="button"
                onClick={() => selectRole("server_owner")}
                className="flat-card rounded-2xl p-6 text-left hover:border-disc-cyan/30"
              >
                <div className="mb-2 flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-disc-cyan/10 text-disc-cyan">
                    <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2" />
                    </svg>
                  </div>
                  <h3 className="font-heading text-lg font-semibold">Server owner</h3>
                </div>
                <p className="text-sm text-text-secondary">
                  I run a Minecraft server and want to set up MCAV
                </p>
                <p className="mt-1 text-xs text-text-secondary/70">
                  Set up your organization, register servers, and host shows
                </p>
              </button>

              <button
                type="button"
                onClick={() => selectRole("team_member")}
                className="flat-card rounded-2xl p-6 text-left hover:border-disc-blue/30"
              >
                <div className="mb-2 flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-disc-blue/10 text-disc-blue">
                    <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
                    </svg>
                  </div>
                  <h3 className="font-heading text-lg font-semibold">Team member</h3>
                </div>
                <p className="text-sm text-text-secondary">
                  I work with a server and want to join their organization
                </p>
                <p className="mt-1 text-xs text-text-secondary/70">
                  Help manage servers, coordinate events, and monitor shows
                </p>
              </button>

              <button
                type="button"
                onClick={() => selectRole("dj")}
                className="flat-card rounded-2xl p-6 text-left hover:border-noteblock-amber/30"
              >
                <div className="mb-2 flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-noteblock-amber/10 text-noteblock-amber">
                    <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2z" />
                    </svg>
                  </div>
                  <h3 className="font-heading text-lg font-semibold">DJ</h3>
                </div>
                <p className="text-sm text-text-secondary">
                  I&apos;m a DJ and want to perform at MCAV events
                </p>
                <p className="mt-1 text-xs text-text-secondary/70">
                  Create a profile and review the Phase 0 source-development path
                </p>
              </button>
            </div>

            <button
              type="button"
              onClick={handleSkip}
              disabled={submitting}
              className="mt-6 block w-full rounded-xl px-4 py-2 text-center text-sm text-text-secondary transition-colors hover:bg-white/5 hover:text-white disabled:cursor-not-allowed disabled:opacity-50"
            >
              {submitting ? "..." : "Skip for now"}
            </button>
          </div>
        )}

        {/* Step 2a: Server Owner - Create Org */}
        {step === "server_owner" && (
          <div className="animate-slide-up">
            <StepProgress current={1} />

            <BackButton onClick={() => { setStep("role"); setError(""); }} />

            <PageHeader
              className="mb-8"
              eyebrow="Server owner"
              title={<span className="text-disc-cyan">Create your organization</span>}
              description={<>Set up your server&apos;s home on MCAV</>}
            />

            <form onSubmit={handleServerOwner} className="glass-card rounded-2xl p-6">
              <div className="flex flex-col gap-4">
                <div>
                  <Label htmlFor="orgName">Organization name</Label>
                  <Input
                    id="orgName"
                    type="text"
                    required
                    value={orgName}
                    onChange={(e) => setOrgName(e.target.value)}
                    placeholder="My Minecraft Server"
                  />
                </div>

                <div>
                  <Label htmlFor="orgSlug">Subdomain</Label>
                  <div className="flex items-center gap-2">
                    <Input
                      id="orgSlug"
                      type="text"
                      required
                      value={orgSlug}
                      onChange={(e) => setOrgSlug(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ""))}
                      className="flex-1 font-mono"
                      placeholder="my-server"
                    />
                    <span className="whitespace-nowrap font-mono text-sm text-text-secondary">.mcav.live</span>
                  </div>
                </div>

                <div>
                  <Label htmlFor="orgDesc">
                    Description <span className="normal-case tracking-normal text-text-secondary/50">(optional)</span>
                  </Label>
                  <TextArea
                    id="orgDesc"
                    value={orgDescription}
                    onChange={(e) => setOrgDescription(e.target.value)}
                    className="resize-none"
                    rows={3}
                    placeholder="Tell people about your server"
                  />
                </div>

                {error && <Alert tone="danger">{error}</Alert>}

                <button
                  type="submit"
                  disabled={submitting}
                  className={`mt-2 ${primaryButton}`}
                >
                  {submitting ? "Creating..." : "Create organization"}
                </button>
              </div>
            </form>
          </div>
        )}

        {/* Step 3a: Server Owner Complete */}
        {step === "server_owner_complete" && (
          <div className="animate-slide-up text-center">
            <StepProgress current={2} />
            <div className="glass-card rounded-2xl p-8">
              <SuccessIcon />

              <h1 className="mb-2 font-heading text-2xl font-bold">
                <span className="text-disc-cyan">Your organization is ready!</span>
              </h1>
              {createdOrg && (
                <p className="mb-6 text-text-secondary">
                  <span className="font-mono text-disc-cyan">{createdOrg.slug}.mcav.live</span> is all yours
                </p>
              )}

              <div className="mb-6 text-left">
                <h3 className={sectionLabel}>Next steps</h3>
                <NextStepsList
                  items={[
                    { label: "Install the Minecraft plugin", icon: "M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" },
                    { label: "Register your server", icon: "M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2" },
                    { label: "Create an invite for your team", icon: "M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z" },
                    { label: "Start your first show", icon: "M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z" },
                  ]}
                />
              </div>

              <div className="flex flex-col gap-3">
                <Button href="/getting-started" className="w-full">
                  View setup guide
                </Button>
                <Button href="/dashboard" variant="secondary" className="w-full">
                  Go to dashboard
                </Button>
              </div>
            </div>
          </div>
        )}

        {/* Step 2b: Team Member - Join Org */}
        {step === "team_member" && (
          <div className="animate-slide-up">
            <StepProgress current={1} />

            <BackButton onClick={() => { setStep("role"); setError(""); }} />

            <PageHeader
              className="mb-8"
              eyebrow="Team member"
              title={<span className="text-disc-cyan">Join an organization</span>}
              description="Enter the invite code from your server owner"
            />

            <form onSubmit={handleTeamMember} className="glass-card rounded-2xl p-6">
              <div className="flex flex-col gap-4">
                <div>
                  <Label htmlFor="inviteCode">Invite code</Label>
                  <Input
                    id="inviteCode"
                    type="text"
                    required
                    value={inviteCode}
                    onChange={(e) => setInviteCode(e.target.value.toUpperCase())}
                    className="text-center font-mono text-lg tracking-widest"
                    placeholder="ABCD1234"
                    maxLength={8}
                  />
                </div>

                {error && <Alert tone="danger">{error}</Alert>}

                <button
                  type="submit"
                  disabled={submitting}
                  className={`mt-2 ${primaryButton}`}
                >
                  {submitting ? "Joining..." : "Join organization"}
                </button>
              </div>
            </form>
          </div>
        )}

        {/* Step 3b: Team Member Complete */}
        {step === "team_member_complete" && (
          <div className="animate-slide-up text-center">
            <StepProgress current={2} />
            <div className="glass-card rounded-2xl p-8">
              <SuccessIcon />

              <h1 className="mb-2 font-heading text-2xl font-bold">
                <span className="text-disc-cyan">Welcome to {joinedOrg?.name || "the team"}!</span>
              </h1>
              <p className="mb-6 text-text-secondary">
                You&apos;re now part of the organization
              </p>

              <div className="mb-6 text-left">
                <h3 className={sectionLabel}>What you can do</h3>
                <NextStepsList
                  items={[
                    { label: "View connected servers", icon: "M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2" },
                    { label: "See active shows", icon: "M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" },
                    { label: "Coordinate events with your team", icon: "M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" },
                  ]}
                />
              </div>

              <div className="flex flex-col gap-3">
                <Button href="/dashboard" className="w-full">
                  Go to dashboard
                </Button>
              </div>
            </div>
          </div>
        )}

        {/* Step 2c: DJ - Create Profile */}
        {step === "dj" && (
          <div className="animate-slide-up">
            <StepProgress current={1} />

            <BackButton onClick={() => { setStep("role"); setError(""); }} />

            <PageHeader
              className="mb-8"
              eyebrow="DJ"
              title={<span className="text-disc-cyan">Set up your DJ profile</span>}
              description="Create your public profile for MCAV events"
            />

            <form onSubmit={handleDJ} className="glass-card rounded-2xl p-6">
              <div className="flex flex-col gap-4">
                <div>
                  <Label htmlFor="djName">DJ / Stage name</Label>
                  <Input
                    id="djName"
                    type="text"
                    required
                    value={djName}
                    onChange={(e) => setDjName(e.target.value)}
                    placeholder="DJ Nova"
                  />
                </div>

                <div>
                  <Label htmlFor="djBio">
                    Bio <span className="normal-case tracking-normal text-text-secondary/50">(optional)</span>
                  </Label>
                  <TextArea
                    id="djBio"
                    value={djBio}
                    onChange={(e) => setDjBio(e.target.value)}
                    className="resize-none"
                    rows={3}
                    placeholder="Tell people about yourself"
                  />
                </div>

                <div>
                  <Label htmlFor="djGenres">
                    Genres <span className="normal-case tracking-normal text-text-secondary/50">(optional)</span>
                  </Label>
                  <Input
                    id="djGenres"
                    type="text"
                    value={djGenres}
                    onChange={(e) => setDjGenres(e.target.value)}
                    placeholder="House, Techno, Drum & Bass"
                  />
                </div>

                {error && <Alert tone="danger">{error}</Alert>}

                <button
                  type="submit"
                  disabled={submitting}
                  className={`mt-2 ${primaryButton}`}
                >
                  {submitting ? "Creating..." : "Create profile"}
                </button>
              </div>
            </form>
          </div>
        )}

        {/* Step 3c: DJ Complete */}
        {step === "dj_complete" && (
          <div className="animate-slide-up text-center">
            <StepProgress current={2} />
            <div className="glass-card rounded-2xl p-8">
              <SuccessIcon />

              <h1 className="mb-2 font-heading text-2xl font-bold">
                <span className="text-disc-cyan">Profile created!</span>
              </h1>
              <p className="mb-4 text-text-secondary">
                Your DJ profile is ready. Prebuilt client distribution remains paused during Phase 0.
              </p>
              <Alert tone="warning" className="mb-8 text-left">
                Remote workflow verification is limited to unsupported local builds from source. The development guide does not provide a signed release artifact.
              </Alert>

              <div className="mb-6 text-left">
                <h3 className={sectionLabel}>Next steps</h3>
                <NextStepsList
                  items={[
                    { label: "Review the DJ source development guide", icon: "M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" },
                    { label: "Get a connect code from a server", icon: "M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z" },
                    { label: "Perform at your first show", icon: "M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2z" },
                  ]}
                />
              </div>

              <div className="flex flex-col gap-3">
                <Button
                  href="https://github.com/ryanthemcpherson/minecraft-audio-viz/blob/main/dj_client/README.md"
                  external
                  className="w-full"
                >
                  Open DJ development guide
                </Button>

                {createdProfileSlug && (
                  <Button href={`/dj/${createdProfileSlug}`} variant="secondary" className="w-full">
                    View your profile
                  </Button>
                )}

                <Button href="/dashboard" variant="secondary" className="w-full">
                  Go to dashboard
                </Button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
