"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "@/components/AuthProvider";
import Alert from "@/components/ui/Alert";
import Badge from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import { Input, Label } from "@/components/ui/Field";
import PageHeader from "@/components/ui/PageHeader";
import type { OrgDetail, OrgServerDetail, RegisterServerResponse } from "@/lib/auth";
import { getOrgBySlug, listOrgServers, registerOrgServer, removeOrgServer, fetchMe } from "@/lib/auth";

// Native <button> styles mirroring Button's primary variant, used where a real
// disabled state is required while submitting (Button's "disabled" variant renders a span).
const primaryButtonClassName =
  "inline-flex items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-disc-cyan to-disc-blue px-4 py-2 text-xs font-semibold whitespace-nowrap text-white shadow-lg shadow-disc-cyan/20 transition-all duration-200 select-none hover:shadow-xl hover:shadow-disc-cyan/30 hover:brightness-110 active:brightness-95 disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:brightness-100 disabled:hover:shadow-lg";

// ---------------------------------------------------------------------------
// Relative time helper
// ---------------------------------------------------------------------------

function relativeTime(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const seconds = Math.floor(diff / 1000);
  if (seconds < 60) return "just now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

// ---------------------------------------------------------------------------
// ApiKeyReveal component
// ---------------------------------------------------------------------------

function ApiKeyReveal({ data, onDismiss }: { data: RegisterServerResponse; onDismiss: () => void }) {
  const [copiedKey, setCopiedKey] = useState(false);
  const [copiedSecret, setCopiedSecret] = useState(false);

  function handleCopy(value: string, setter: (v: boolean) => void) {
    try {
      navigator.clipboard.writeText(value);
    } catch (err) {
      console.error("Failed to copy to clipboard:", err);
    }
    setter(true);
    setTimeout(() => setter(false), 2000);
  }

  return (
    <div className="flat-card rounded-xl border-warning/30 p-6">
      <div className="mb-4 flex items-center gap-3">
        <div className="flex h-8 w-8 items-center justify-center rounded-full bg-warning/10">
          <svg className="h-4 w-4 text-warning" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
          </svg>
        </div>
        <div>
          <h3 className="font-heading font-semibold text-warning">Save these credentials</h3>
          <p className="text-xs text-text-secondary">The API key will not be shown again.</p>
        </div>
      </div>

      <div className="flex flex-col gap-3">
        <div>
          <Label>API key</Label>
          <div className="flex items-center gap-2">
            <code className="flex-1 rounded-lg border border-white/10 bg-bg-primary px-3 py-2 font-mono text-sm text-success break-all">
              {data.api_key}
            </code>
            <Button size="sm" variant="secondary" onClick={() => handleCopy(data.api_key, setCopiedKey)}>
              {copiedKey ? "Copied!" : "Copy"}
            </Button>
          </div>
        </div>
        <div>
          <Label>JWT secret</Label>
          <div className="flex items-center gap-2">
            <code className="flex-1 rounded-lg border border-white/10 bg-bg-primary px-3 py-2 font-mono text-sm text-disc-cyan break-all">
              {data.jwt_secret}
            </code>
            <Button size="sm" variant="secondary" onClick={() => handleCopy(data.jwt_secret, setCopiedSecret)}>
              {copiedSecret ? "Copied!" : "Copy"}
            </Button>
          </div>
        </div>
      </div>

      <Button variant="ghost" size="sm" className="mt-4 w-full border border-white/10" onClick={onDismiss}>
        I&apos;ve saved these credentials
      </Button>
    </div>
  );
}

// ---------------------------------------------------------------------------
// RegisterServerForm component
// ---------------------------------------------------------------------------

function RegisterServerForm({
  orgId,
  accessToken,
  onRegistered,
  onCancel,
}: {
  orgId: string;
  accessToken: string;
  onRegistered: (data: RegisterServerResponse) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState("");
  const [wsUrl, setWsUrl] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const res = await registerOrgServer(accessToken, orgId, name, wsUrl);
      onRegistered(res);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Registration failed");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flat-card rounded-xl p-6">
      <h3 className="mb-4 font-heading font-semibold">Register new server</h3>
      <div className="flex flex-col gap-4">
        <div>
          <Label htmlFor="org-server-name">Server name</Label>
          <Input
            id="org-server-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="My VJ Server"
            required
            maxLength={100}
          />
        </div>
        <div>
          <Label htmlFor="org-server-ws-url">WebSocket URL</Label>
          <Input
            id="org-server-ws-url"
            type="text"
            value={wsUrl}
            onChange={(e) => setWsUrl(e.target.value)}
            placeholder="wss://your-server.com/ws"
            required
            maxLength={500}
            className="font-mono"
          />
        </div>
        {error && <Alert tone="danger">{error}</Alert>}
        <div className="flex gap-3">
          <button
            type="submit"
            disabled={submitting || !name || !wsUrl}
            className={primaryButtonClassName}
          >
            {submitting ? "Registering..." : "Register server"}
          </button>
          <Button type="button" variant="secondary" size="sm" onClick={onCancel}>
            Cancel
          </Button>
        </div>
      </div>
    </form>
  );
}

// ---------------------------------------------------------------------------
// ServerCard component
// ---------------------------------------------------------------------------

function ServerCard({
  server,
  isOwner,
  removingId,
  onRemove,
}: {
  server: OrgServerDetail;
  isOwner: boolean;
  removingId: string | null;
  onRemove: (id: string) => void;
}) {
  const [confirmRemove, setConfirmRemove] = useState(false);

  return (
    <div className="flat-card rounded-xl p-5">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h3 className="font-heading font-semibold">{server.name}</h3>
          <Badge tone={server.is_online ? "success" : "neutral"} dot>
            {server.is_online ? "Online" : "Offline"}
          </Badge>
        </div>
        {isOwner && (
          <div>
            {confirmRemove ? (
              <div className="flex items-center gap-2">
                <span className="text-xs text-text-secondary">Remove?</span>
                <button
                  onClick={() => onRemove(server.id)}
                  disabled={removingId === server.id}
                  className="rounded-lg border border-danger/25 bg-danger/10 px-3 py-1 text-xs font-semibold text-danger transition-colors hover:bg-danger/20 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {removingId === server.id ? "..." : "Yes"}
                </button>
                <button
                  onClick={() => setConfirmRemove(false)}
                  className="rounded-lg border border-white/10 px-3 py-1 text-xs text-text-secondary transition-colors hover:bg-white/5"
                >
                  No
                </button>
              </div>
            ) : (
              <button
                onClick={() => setConfirmRemove(true)}
                className="rounded-lg border border-white/10 px-3 py-1 text-xs text-text-secondary transition-colors hover:bg-white/5 hover:text-danger"
              >
                Remove
              </button>
            )}
          </div>
        )}
      </div>

      <code className="mt-2 block text-xs text-text-secondary font-mono">{server.websocket_url}</code>

      <div className="mt-3 flex flex-wrap gap-4 font-mono text-xs text-text-secondary">
        <span>{server.active_show_count} active show{server.active_show_count !== 1 ? "s" : ""}</span>
        <span>Created {relativeTime(server.created_at)}</span>
        {server.last_heartbeat && (
          <span>Last heartbeat {relativeTime(server.last_heartbeat)}</span>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main page
// ---------------------------------------------------------------------------

export default function OrgServersPage() {
  const params = useParams();
  const router = useRouter();
  const slug = params.slug as string;
  const { user, accessToken, loading: authLoading } = useAuth();

  const [org, setOrg] = useState<OrgDetail | null>(null);
  const [servers, setServers] = useState<OrgServerDetail[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isOwner, setIsOwner] = useState(false);
  const [showRegisterForm, setShowRegisterForm] = useState(false);
  const [newCredentials, setNewCredentials] = useState<RegisterServerResponse | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);

  useEffect(() => {
    if (authLoading) return;
    if (!user || !accessToken) {
      router.push("/login");
      return;
    }

    const controller = new AbortController();

    async function load() {
      try {
        const [orgData, profile] = await Promise.all([
          getOrgBySlug(accessToken!, slug),
          fetchMe(accessToken!),
        ]);
        if (controller.signal.aborted) return;
        setOrg(orgData);
        setIsOwner(orgData.owner_id === profile.id);

        const serverList = await listOrgServers(accessToken!, orgData.id);
        if (controller.signal.aborted) return;
        setServers(serverList);
      } catch (err) {
        if (controller.signal.aborted) return;
        setError(err instanceof Error ? err.message : "Failed to load");
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    }
    load();

    return () => controller.abort();
  }, [user, accessToken, authLoading, router, slug]);

  async function handleRegistered(data: RegisterServerResponse) {
    setNewCredentials(data);
    setShowRegisterForm(false);
    // Refresh server list
    if (accessToken && org) {
      const updated = await listOrgServers(accessToken, org.id);
      setServers(updated);
    }
  }

  async function handleRemove(serverId: string) {
    if (!accessToken || !org) return;
    setRemovingId(serverId);
    try {
      await removeOrgServer(accessToken, org.id, serverId);
      setServers((prev) => prev.filter((s) => s.id !== serverId));
    } catch (err) {
      console.error("Failed to remove server:", err);
    } finally {
      setRemovingId(null);
    }
  }

  if (authLoading || loading) {
    return (
      <div className="flex min-h-screen items-center justify-center pt-20">
        <div className="w-full max-w-4xl px-6">
          <div className="mb-6 h-4 w-48 animate-pulse rounded-lg bg-white/5" />
          <div className="mb-8 h-8 w-64 animate-pulse rounded-lg bg-white/5" />
          <div className="grid gap-4">
            {[0, 1].map((i) => (
              <div key={i} className="h-28 animate-pulse rounded-xl bg-white/5" />
            ))}
          </div>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex min-h-screen items-center justify-center pt-20">
        <div className="flat-card w-full max-w-md rounded-2xl p-8 text-center">
          <Alert tone="danger">{error}</Alert>
          <Link href="/dashboard" className="mt-4 inline-block text-sm text-disc-cyan hover:underline">
            Back to dashboard
          </Link>
        </div>
      </div>
    );
  }

  if (!org) return null;

  return (
    <div className="relative mx-auto max-w-4xl px-6 pt-28 pb-20">
      {/* Background glows */}
      <div className="pointer-events-none absolute inset-0 z-0">
        <div className="absolute top-0 left-1/2 h-[400px] w-[500px] -translate-x-1/2 rounded-full bg-disc-cyan/5 blur-[120px]" />
        <div className="absolute top-1/4 right-1/4 h-[300px] w-[300px] rounded-full bg-disc-blue/5 blur-[100px]" />
      </div>

      <div className="relative z-10">
        {/* Breadcrumb */}
        <nav className="mb-6 flex items-center gap-2 font-mono text-xs text-text-secondary">
          <Link href="/dashboard" className="transition-colors hover:text-white">Dashboard</Link>
          <span>/</span>
          <span>{org.name}</span>
          <span>/</span>
          <span className="text-white">Servers</span>
        </nav>

        {/* Heading */}
        <PageHeader
          eyebrow="Organization"
          title="Servers"
          description={org.name}
          className="mb-8"
          actions={
            isOwner && !showRegisterForm ? (
              <Button size="sm" onClick={() => setShowRegisterForm(true)}>
                Register server
              </Button>
            ) : undefined
          }
        />

        <div className="flex flex-col gap-6">
          {/* API key reveal */}
          {newCredentials && (
            <ApiKeyReveal data={newCredentials} onDismiss={() => setNewCredentials(null)} />
          )}

          {/* Register form */}
          {showRegisterForm && (
            <RegisterServerForm
              orgId={org.id}
              accessToken={accessToken!}
              onRegistered={handleRegistered}
              onCancel={() => setShowRegisterForm(false)}
            />
          )}

          {/* Server list */}
          {servers.length === 0 && !showRegisterForm ? (
            <div className="flat-card rounded-xl p-8 text-center">
              <div className="mb-4 flex justify-center">
                <div className="flex h-12 w-12 items-center justify-center rounded-full bg-disc-cyan/10">
                  <svg className="h-6 w-6 text-disc-cyan" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M5 12h14M12 5l7 7-7 7" />
                  </svg>
                </div>
              </div>
              <p className="mb-2 text-text-secondary">No servers registered yet.</p>
              {isOwner && (
                <Button size="sm" className="mt-2" onClick={() => setShowRegisterForm(true)}>
                  Register your first server
                </Button>
              )}
            </div>
          ) : (
            <div className="grid gap-4">
              {servers.map((server) => (
                <ServerCard
                  key={server.id}
                  server={server}
                  isOwner={isOwner}
                  removingId={removingId}
                  onRemove={handleRemove}
                />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
