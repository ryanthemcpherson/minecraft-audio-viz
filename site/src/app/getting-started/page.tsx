import type { Metadata } from "next";
import Link from "next/link";
import CodeBlock from "@/components/CodeBlock";
import TableOfContents from "@/components/TableOfContents";
import Footer from "@/components/Footer";
import Badge from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import ReleaseBanner from "@/components/getting-started/ReleaseBanner";
import DownloadButton from "@/components/getting-started/DownloadButton";
import Step, { Note } from "@/components/getting-started/Step";
import { RELEASE } from "@/lib/releaseStatus";
import { LINKS } from "@/lib/links";

export const metadata: Metadata = {
  title: "Getting started",
  description: `Install MCAV on a Paper ${RELEASE.minecraft} server in five steps: drop one JAR into plugins/, open a port, start Paper, finish setup in the browser. Then connect the DJ client.`,
  alternates: { canonical: "https://mcav.live/getting-started" },
};

const tocItems = [
  { id: "requirements", label: "Requirements" },
  { id: "server-setup", label: "Server operators" },
  { id: "download", label: "Download and verify", indent: true },
  { id: "install", label: "Drop it in plugins/", indent: true },
  { id: "port", label: "Open one port", indent: true },
  { id: "first-start", label: "Start Paper", indent: true },
  { id: "setup", label: "Finish setup", indent: true },
  { id: "commands", label: "Commands", indent: true },
  { id: "invite-djs", label: "Invite DJs", indent: true },
  { id: "upgrades", label: "Upgrades and rollback", indent: true },
  { id: "migration", label: "Migrating", indent: true },
  { id: "dj-setup", label: "DJs" },
  { id: "dj-install", label: "Get the client", indent: true },
  { id: "dj-invite", label: "Get an invite", indent: true },
  { id: "dj-connect", label: "Connect", indent: true },
  { id: "dj-source", label: "Pick a source", indent: true },
  { id: "troubleshooting", label: "Troubleshooting" },
  { id: "developers", label: "Developers" },
  { id: "next-steps", label: "Next steps" },
];

const requirements = [
  {
    title: `Paper ${RELEASE.minecraft}`,
    body: "The supported server software and Minecraft version. Fabric ships as a separate compatibility JAR without the self-installing flow.",
    accent: "text-disc-cyan bg-disc-cyan/10",
  },
  {
    title: `Java ${RELEASE.java}`,
    body: "The only Java version in the release matrix today. Others are added once they pass the same gates.",
    accent: "text-disc-blue bg-disc-blue/10",
  },
  {
    title: "Linux or Windows host",
    body: "Linux x86_64, Linux ARM64, or Windows x86_64. macOS hosting is not supported in this release.",
    accent: "text-noteblock-amber bg-noteblock-amber/10",
  },
  {
    title: "4 vCPU, 8 GiB memory",
    body: "The supported baseline for one active stage of 160 display entities. Give Paper about 5 GiB of heap and leave the rest for the VJ runtime.",
    accent: "text-success bg-success/10",
  },
  {
    title: "2 GiB free disk",
    body: "The plugin caches verified runtime archives so later restarts work offline and rollback is instant.",
    accent: "text-disc-cyan bg-disc-cyan/10",
  },
  {
    title: "One extra TCP port",
    body: `Default ${RELEASE.defaultPort}. It serves the control center, the browser preview, and DJ connections over TLS.`,
    accent: "text-disc-blue bg-disc-blue/10",
  },
];

const commands = [
  { cmd: "/audioviz status", what: "Plugin and runtime versions, lifecycle state, public address, renderer connection, health summary." },
  { cmd: "/audioviz setup", what: "Create or rotate a first-run or recovery setup session. Previous setup links stop working." },
  { cmd: "/audioviz runtime retry", what: "Close a crash circuit and retry the current verified runtime." },
  { cmd: "/audioviz runtime check", what: "Refresh signed release metadata without activating anything." },
  { cmd: "/audioviz runtime rollback", what: "Roll back to the last known good runtime. Asks for a time-limited confirmation." },
  { cmd: "/audioviz diagnostics", what: "Write a redacted support bundle under the plugin data directory." },
];

const troubleshooting = [
  {
    symptom: "Connect code rejected",
    fix: "Codes are short-lived and single use. Ask the operator for a fresh invite and paste it within a few minutes.",
  },
  {
    symptom: "Certificate fingerprint mismatch",
    fix: "The server's TLS certificate changed. The client refuses to connect on purpose. Get a new invite with the current fingerprint; there is no accept-anything mode.",
  },
  {
    symptom: "Meters stay flat",
    fix: "Pick the application that is actually playing, or switch to the system mix. Apps using exclusive-mode WASAPI cannot be captured per-app.",
  },
  {
    symptom: "Setup link expired",
    fix: `Setup sessions last 30 minutes and allow five failed attempts. Run /audioviz setup from the server console to mint a new one.`,
  },
  {
    symptom: "Runtime will not start",
    fix: "Run /audioviz status for the reason code, then /audioviz runtime retry. If a fresh install keeps failing, /audioviz diagnostics produces a bundle you can attach to a GitHub issue.",
  },
  {
    symptom: "Show looks choppy in-game",
    fix: "Lower performance.entity-budget or switch performance.profile in config.yml. Load shedding kicks in automatically when TPS drops.",
  },
];

export default function GettingStartedPage() {
  return (
    <>
      {/* Hero */}
      <section className="px-6 pt-28 pb-14 sm:pt-32">
        <div className="mx-auto max-w-6xl">
          <div className="max-w-2xl">
            <h1 className="font-heading text-[clamp(2.25rem,1.5rem+3vw,4rem)] font-bold leading-[1.04] tracking-[-0.03em]">
              One JAR. Five steps.
            </h1>
            <p className="mt-5 text-lg leading-relaxed text-text-secondary">
              Drop the plugin into a Paper server, open a port, start it, and finish setup in the
              browser. The plugin installs and supervises the VJ runtime for you. No Python, no
              Docker, no shell scripts.
            </p>
            <ReleaseBanner />
          </div>

          <nav aria-label="Guide sections" className="mt-10 flex flex-wrap gap-x-8 gap-y-2 border-t border-white/[0.08] pt-5 text-sm">
            <a href="#server-setup" className="text-text-primary hover:text-disc-cyan">
              Server operators <span className="text-text-secondary">/ install, set up, invite DJs</span>
            </a>
            <a href="#dj-setup" className="text-text-primary hover:text-disc-cyan">
              DJs <span className="text-text-secondary">/ client status, connect with an invite</span>
            </a>
            <a href="#developers" className="text-text-primary hover:text-disc-cyan">
              Developers <span className="text-text-secondary">/ build from source, write patterns</span>
            </a>
          </nav>
        </div>
      </section>

      {/* Main content with TOC sidebar */}
      <div className="px-6 pb-32">
        <div className="mx-auto max-w-6xl lg:grid lg:grid-cols-[1fr_220px] lg:gap-12">
          <div className="space-y-28">
            {/* ===== REQUIREMENTS ===== */}
            <section id="requirements" className="scroll-mt-28">
              <div className="mb-8">
                <p className="mb-2 text-sm font-medium text-disc-cyan">
                  Before you begin
                </p>
                <h2 className="font-heading text-2xl font-bold sm:text-3xl">Requirements</h2>
                <p className="mt-3 max-w-2xl text-text-secondary">
                  The plugin warns about anything it can observe, such as an unsupported Java
                  version or too little free disk. It will not refuse to start on a guess about
                  CPU or memory.
                </p>
              </div>

              <dl className="grid gap-x-10 gap-y-6 border-t border-white/[0.08] pt-6 sm:grid-cols-2 lg:grid-cols-3">
                {requirements.map((req) => (
                  <div key={req.title}>
                    <dt className="font-heading text-base font-bold">{req.title}</dt>
                    <dd className="mt-1.5 text-sm leading-relaxed text-text-secondary">{req.body}</dd>
                  </div>
                ))}
              </dl>
            </section>

            {/* ===== SERVER OPERATORS ===== */}
            <section id="server-setup" className="scroll-mt-28">
              <div className="mb-12">
                <p className="mb-2 text-sm font-medium text-disc-cyan">
                  Server setup
                </p>
                <h2 className="font-heading text-2xl font-bold sm:text-3xl">For server operators</h2>
                <p className="mt-3 max-w-2xl text-text-secondary">
                  Everything below happens on the machine that runs Paper. Paper stays playable
                  while the plugin sets itself up in the background.
                </p>
                <p className="mt-3 max-w-2xl text-sm text-text-secondary">
                  Running Fabric instead? The Fabric compatibility JAR connects to a VJ server you
                  run yourself and does not include the self-installing flow.{" "}
                  <a
                    href="https://github.com/ryanthemcpherson/minecraft-audio-viz/releases"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-disc-cyan hover:underline"
                  >
                    Download Fabric Mod
                  </a>
                </p>
              </div>

              <div className="space-y-16">
                <Step id="download" number="01" title="Download and verify">
                  <p>
                    Grab the plugin JAR and the checksum file from the release. Every artifact is
                    listed in <code className="font-mono text-noteblock-amber">{RELEASE.artifacts.checksums}</code>,
                    which is signed with the project GPG key, and the plugin embeds the public keys it
                    uses to verify the VJ runtime it downloads later.
                  </p>
                  <div className="flex flex-wrap items-center gap-3">
                    <a
                      href="https://github.com/ryanthemcpherson/minecraft-audio-viz/releases"
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center justify-center gap-2 rounded-xl bg-disc-cyan px-6 py-3 text-sm font-semibold text-white transition-all"
                    >
                      Download Paper Plugin
                    </a>
                    <DownloadButton label={RELEASE.artifacts.checksums} variant="secondary" />
                    {!RELEASE.isReleased && (
                      <Badge tone="warning" dot>
                        {RELEASE.version} pending: Releases carries the previous build
                      </Badge>
                    )}
                  </div>
                  <CodeBlock
                    title="verify (Linux / macOS / WSL)"
                    code={`sha256sum -c ${RELEASE.artifacts.checksums} --ignore-missing
# ${RELEASE.artifacts.plugin}: OK`}
                  />
                  <CodeBlock
                    title="verify (Windows PowerShell)"
                    language="powershell"
                    code={`(Get-FileHash .\\${RELEASE.artifacts.plugin} -Algorithm SHA256).Hash
# compare with the line for ${RELEASE.artifacts.plugin} in ${RELEASE.artifacts.checksums}`}
                  />
                </Step>

                <Step id="install" number="02" title="Drop it in plugins/">
                  <p>
                    Copy the JAR into your server&apos;s <code className="font-mono text-noteblock-amber">plugins/</code>{" "}
                    directory. That is the whole install. There is nothing to unzip and no startup
                    flags to change.
                  </p>
                  <CodeBlock
                    title="server layout"
                    language="text"
                    code={`your-server/
├── paper-${RELEASE.minecraft}.jar
├── plugins/
│   └── ${RELEASE.artifacts.plugin}      <- the only file you add
└── ...`}
                  />
                  <Note title="Hosting panels">
                    On Pterodactyl or a similar panel, upload the JAR through the file manager
                    exactly as you would any other plugin. You will also need one extra port
                    allocation for the next step.
                  </Note>
                </Step>

                <Step id="port" number="03" title="Open one port">
                  <p>
                    The VJ runtime serves the control center, browser preview, and DJ connections
                    from a single TCP port, <strong className="text-white">{RELEASE.defaultPort}</strong> by
                    default. Open it in your firewall or panel. Everything else stays on loopback.
                  </p>
                  <p>
                    To change the port, or to tell MCAV the public hostname to put in DJ invites,
                    edit <code className="font-mono text-noteblock-amber">plugins/AudioViz/config.yml</code>{" "}
                    (generated on first start) and restart:
                  </p>
                  <CodeBlock
                    title="plugins/AudioViz/config.yml"
                    language="yaml"
                    code={`runtime:
  enabled: true
  channel: stable
  install-on-start: true
  public-port: ${RELEASE.defaultPort}
  public-host: "0.0.0.0"
  public-url: ""          # e.g. https://mc.example.com:${RELEASE.defaultPort}
  resource-profile: auto
  retain-versions: 3
  tls:
    mode: generated       # or "provided" with certificate + private-key paths

performance:
  profile: balanced
  entity-budget: 160
  target-render-fps: 20
  tps-load-shedding: true`}
                  />
                  <Note tone="warning" title="TLS is on by default">
                    Admin logins and DJ traffic require TLS. MCAV generates a self-signed
                    certificate on first start and prints its SHA-256 fingerprint. DJ clients pin
                    that fingerprint, so you can run without a public certificate. If you have
                    one, point <code className="font-mono">tls.mode: provided</code> at it or terminate
                    TLS at a reverse proxy.
                  </Note>
                </Step>

                <Step id="first-start" number="04" title="Start Paper">
                  <p>Start or restart the server. On the first run the plugin will:</p>
                  <ul className="list-disc space-y-1.5 pl-5">
                    <li>generate <code className="font-mono text-noteblock-amber">config.yml</code> with the defaults above,</li>
                    <li>pick the VJ runtime build for your OS and CPU, download it from the official release origin, and verify its signature,</li>
                    <li>create the local renderer secret and TLS identity,</li>
                    <li>start the VJ service and connect it to the renderer over loopback,</li>
                    <li>print a one-time setup link to the console.</li>
                  </ul>
                  <p>
                    Paper is playable the whole time. Later starts reuse the cached runtime, so the
                    server comes up without internet access as long as a compatible verified
                    runtime is on disk.
                  </p>
                  <CodeBlock
                    title="console (example)"
                    language="text"
                    code={`[AudioViz] Plugin ${RELEASE.version} loaded on Paper ${RELEASE.minecraft} / Java ${RELEASE.java}
[AudioViz] Runtime linux-x86_64 ${RELEASE.version} downloaded and verified (Ed25519 manifest)
[AudioViz] Renderer link established on loopback
[AudioViz] Control center listening on https://0.0.0.0:${RELEASE.defaultPort}
[AudioViz] TLS fingerprint SHA256: 3A:9F:...:C1
[AudioViz] Finish setup within 30 minutes:
[AudioViz]   https://your-host:${RELEASE.defaultPort}/setup/#<one-time-token>`}
                  />
                  <Note title="Nothing runs on the tick thread">
                    Audio analysis and pattern math stay in the separate VJ process. The plugin
                    only applies batched entity updates once per tick, so the show cannot stall
                    your server the way a heavy plugin can.
                  </Note>
                </Step>

                <Step id="setup" number="05" title="Finish setup in the browser">
                  <p>
                    Open the setup link from the console. It is single use, expires after 30
                    minutes, and allows five failed attempts. Create the first administrator
                    account:
                  </p>
                  <ul className="list-disc space-y-1.5 pl-5">
                    <li>a username of 3 to 32 characters,</li>
                    <li>a password of 12 to 72 bytes (hashed with bcrypt, never stored in plain text).</li>
                  </ul>
                  <p>
                    That is it. Sign in to the control center at{" "}
                    <code className="font-mono text-noteblock-amber">https://your-host:{RELEASE.defaultPort}/</code>{" "}
                    and open the browser preview at{" "}
                    <code className="font-mono text-noteblock-amber">/preview/</code>. In-game,{" "}
                    <code className="font-mono text-noteblock-amber">/audioviz status</code> shows the same
                    health summary.
                  </p>
                  <Note tone="warning" title="Lost the link?">
                    Run <code className="font-mono">/audioviz setup</code> from the server console to mint
                    a fresh one. The console is the recovery surface on purpose: setup tokens are
                    never sent through chat.
                  </Note>
                </Step>

                {/* Commands */}
                <div id="commands" className="scroll-mt-28">
                  <h3 className="font-heading text-xl font-bold sm:text-2xl">Operator commands</h3>
                  <p className="mt-2 text-text-secondary">
                    <code className="font-mono text-noteblock-amber">/audioviz status</code> needs the{" "}
                    <code className="font-mono">audioviz.status</code> permission. The rest need the matching{" "}
                    <code className="font-mono">audioviz.admin</code> sub-permission; the console always
                    qualifies.
                  </p>
                  <div className="mt-5 overflow-x-auto rounded-xl border border-white/10">
                    <table className="w-full text-left text-sm">
                      <thead className="bg-white/[0.03] font-mono text-[11px] uppercase tracking-wider text-text-secondary">
                        <tr>
                          <th className="px-4 py-3 font-semibold">Command</th>
                          <th className="px-4 py-3 font-semibold">What it does</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-white/5">
                        {commands.map((c) => (
                          <tr key={c.cmd}>
                            <td className="whitespace-nowrap px-4 py-3 font-mono text-xs text-noteblock-amber">{c.cmd}</td>
                            <td className="px-4 py-3 text-text-secondary">{c.what}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>

                {/* Invite DJs */}
                <div id="invite-djs" className="scroll-mt-28">
                  <h3 className="font-heading text-xl font-bold sm:text-2xl">Invite DJs</h3>
                  <p className="mt-2 text-text-secondary">
                    From the control center, create a DJ invite. It bundles three things the DJ
                    client needs:
                  </p>
                  <div className="mt-5 grid gap-4 sm:grid-cols-3">
                    {[
                      { label: "Server URL", value: `wss://your-host:${RELEASE.defaultPort}/ws/dj` },
                      { label: "Connect code", value: "BEAT-7K3M" },
                      { label: "TLS fingerprint", value: "SHA256:3A:9F:...:C1" },
                    ].map((item) => (
                      <div key={item.label} className="border-t border-white/[0.08] pt-3">
                        <p className="text-xs text-text-secondary">{item.label}</p>
                        <p className="mt-1.5 break-all font-mono text-xs text-white">{item.value}</p>
                      </div>
                    ))}
                  </div>
                  <p className="mt-4 text-sm text-text-secondary">
                    Connect codes are short-lived and single use. Static DJ credentials remain
                    available for managed installs, but invites are the default path.
                  </p>
                </div>

                {/* Upgrades */}
                <div id="upgrades" className="scroll-mt-28">
                  <h3 className="font-heading text-xl font-bold sm:text-2xl">Upgrades and rollback</h3>
                  <div className="mt-3 space-y-3 text-text-secondary">
                    <p>
                      Replacing <code className="font-mono text-noteblock-amber">{RELEASE.artifacts.plugin}</code> in{" "}
                      <code className="font-mono text-noteblock-amber">plugins/</code> is the upgrade. On the
                      next start the plugin fetches the matching runtime, verifies it, and switches
                      over. If the new runtime fails its readiness checks, the plugin rolls back to
                      the last known good version automatically and says so in the console.
                    </p>
                    <p>
                      MCAV never installs new executable code during a show. Update discovery can
                      notify you, but the JAR swap is always your explicit action. To go back by
                      hand, run <code className="font-mono text-noteblock-amber">/audioviz runtime rollback</code>{" "}
                      and confirm within the time window it prints.
                    </p>
                  </div>
                </div>

                {/* Migration */}
                <div id="migration" className="scroll-mt-28">
                  <h3 className="font-heading text-xl font-bold sm:text-2xl">Migrating an existing install</h3>
                  <div className="mt-3 space-y-3 text-text-secondary">
                    <p>
                      Running the older plugin-only setup or the Pterodactyl bundle? Drop the new
                      JAR in place. Zones, stages, materials, effects, permissions, and
                      administrator accounts are preserved; the migration backs up your previous
                      config and only touches the keys it owns.
                    </p>
                    <p>
                      Fabric servers keep working with the separate Fabric compatibility JAR
                      connected to a VJ server you run yourself. The self-installing flow is
                      Paper only in this release.
                    </p>
                  </div>
                </div>
              </div>
            </section>

            {/* ===== DJs ===== */}
            <section id="dj-setup" className="scroll-mt-28">
              <div className="mb-12">
                <p className="mb-2 text-sm font-medium text-disc-cyan">
                  DJ setup
                </p>
                <h2 className="font-heading text-2xl font-bold sm:text-3xl">For DJs</h2>
                <p className="mt-3 max-w-2xl text-text-secondary">
                  You need a Windows 10 or 11 PC and an invite from the server operator. Audio
                  is analyzed on your machine; only band and beat data leaves it.
                </p>
              </div>

              <div className="space-y-16">
                <Step id="dj-install" number="01" title="Get the DJ client">
                  <p>
                    Prebuilt DJ client distribution is paused during Phase 0 containment. Remote
                    DJ sessions are for development verification only until the signed release,
                    rollback, and clean-install gates pass. The {RELEASE.version} release lifts
                    this with a signed Windows build.
                  </p>
                  <Note tone="warning" title="Development verification only">
                    Contributors who need to verify remote workflows can build the client from
                    source by following{" "}
                    <a
                      href={LINKS.docs.djClient}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="font-mono text-disc-cyan hover:underline"
                    >
                      dj_client/README.md
                    </a>
                    . Those local builds are unsupported and are not release artifacts.
                  </Note>
                </Step>

                <Step id="dj-invite" number="02" title="Get an invite">
                  <p>
                    Ask the operator for a DJ invite. It contains the server URL, a connect code
                    in the form <code className="font-mono text-noteblock-amber">BEAT-7K3M</code>, and the
                    server&apos;s certificate fingerprint. Codes expire quickly, so grab it right before
                    you plan to connect.
                  </p>
                </Step>

                <Step id="dj-connect" number="03" title="Add the server and connect">
                  <p>
                    In the client, add a server profile and paste the URL, code, and fingerprint.
                    The client checks the fingerprint before it trusts the connection and stores
                    the pin in that profile after the first successful login. If the server&apos;s
                    certificate ever changes, the client refuses until you enter the new
                    fingerprint from a fresh invite.
                  </p>
                  <div className="grid gap-4 sm:grid-cols-3">
                    {[
                      { n: "1", title: "Name yourself", body: "How you appear in the DJ queue and on stage billboards." },
                      { n: "2", title: "Paste the invite", body: "URL, connect code, and fingerprint from the operator." },
                      { n: "3", title: "Connect", body: "You land in the queue. The operator brings you live." },
                    ].map((s) => (
                      <div key={s.n} className="border-t border-white/[0.08] pt-3">
                        <div className="mb-1 font-mono text-sm text-disc-cyan">{s.n}</div>
                        <p className="text-sm font-semibold text-white">{s.title}</p>
                        <p className="mt-1 text-xs text-text-secondary">{s.body}</p>
                      </div>
                    ))}
                  </div>
                </Step>

                <Step id="dj-source" number="04" title="Pick a source and preset">
                  <p>
                    Choose the full system mix or a single application such as Spotify, a browser,
                    or your DJ software. Per-app capture uses the Windows process loopback API, so
                    the rest of your desktop audio stays out of the show.
                  </p>
                  <p>
                    Then pick a preset. Each one tunes attack, release, and beat thresholds for a
                    style of music:
                  </p>
                  <div className="flex flex-wrap gap-1.5">
                    {["auto", "edm", "chill", "rock", "hiphop", "classical"].map((p) => (
                      <Badge key={p} tone="blue">{p}</Badge>
                    ))}
                  </div>
                  <p>
                    Watch the five band meters lock to the music, then check the browser preview
                    the operator shares to see the stage react before you go live.
                  </p>
                </Step>
              </div>
            </section>

            {/* ===== TROUBLESHOOTING ===== */}
            <section id="troubleshooting" className="scroll-mt-28">
              <div className="mb-8">
                <p className="mb-2 text-sm font-medium text-disc-cyan">
                  When it does not work
                </p>
                <h2 className="font-heading text-2xl font-bold sm:text-3xl">Troubleshooting</h2>
              </div>
              <dl className="grid gap-x-10 gap-y-6 border-t border-white/[0.08] pt-6 sm:grid-cols-2">
                {troubleshooting.map((t) => (
                  <div key={t.symptom}>
                    <dt className="font-heading text-base font-bold">{t.symptom}</dt>
                    <dd className="mt-1.5 text-sm leading-relaxed text-text-secondary">{t.fix}</dd>
                  </div>
                ))}
              </dl>
              <p className="mt-6 text-sm text-text-secondary">
                Still stuck? Attach the output of{" "}
                <code className="font-mono text-noteblock-amber">/audioviz diagnostics</code> to an{" "}
                <a href={LINKS.issues} target="_blank" rel="noopener noreferrer" className="text-disc-cyan hover:underline">
                  issue on GitHub
                </a>{" "}
                or ask in the{" "}
                <a href={LINKS.discord} target="_blank" rel="noopener noreferrer" className="text-disc-cyan hover:underline">
                  Discord
                </a>
                . The bundle is redacted: no secrets, keys, chat, or audio.
              </p>
            </section>

            {/* ===== DEVELOPERS ===== */}
            <section id="developers" className="scroll-mt-28">
              <div className="mb-8">
                <p className="mb-2 text-sm font-medium text-disc-cyan">
                  Source builds
                </p>
                <h2 className="font-heading text-2xl font-bold sm:text-3xl">For developers</h2>
                <p className="mt-3 max-w-2xl text-text-secondary">
                  Source builds are for development on MCAV, not for running shows. They skip the
                  signed-runtime chain and the supervisor that the release provides.
                </p>
              </div>
              <CodeBlock
                title="clone and build the pieces"
                code={`git clone ${LINKS.github}.git
cd minecraft-audio-viz

# VJ server (Python 3.11+)
cd vj_server && pip install -e . && cd ..
audioviz-vj --no-auth              # dev only

# Paper plugin (Java ${RELEASE.java})
cd minecraft_plugin && mvn package && cd ..

# DJ client (Rust + Node, Tauri v2)
cd dj_client && npm install && npm run tauri dev`}
              />
              <div className="mt-6 flex flex-wrap gap-3">
                <Button href={LINKS.readme} external variant="secondary">
                  Read the README
                </Button>
                <Button href={LINKS.docs.patternGuide} external variant="secondary">
                  Pattern guide
                </Button>
                <Button href={LINKS.docs.architecture} external variant="ghost">
                  Architecture notes
                </Button>
              </div>
            </section>

            {/* ===== NEXT STEPS ===== */}
            <section id="next-steps" className="scroll-mt-28">
              <div className="mb-8">
                <p className="mb-2 text-sm font-medium text-disc-cyan">
                  You are set up
                </p>
                <h2 className="font-heading text-2xl font-bold sm:text-3xl">Next steps</h2>
              </div>
              <ul className="space-y-3 border-t border-white/[0.08] pt-6 text-[15px]">
                <li>
                  <Link href="/patterns" className="font-semibold text-disc-cyan hover:underline">Browse the patterns</Link>
                  <span className="text-text-secondary">: every pattern, running live in your browser.</span>
                </li>
                <li>
                  <a href={LINKS.docs.patternGuide} target="_blank" rel="noopener noreferrer" className="font-semibold text-disc-cyan hover:underline">Write your own</a>
                  <span className="text-text-secondary">: a Lua file with a calculate function is all it takes.</span>
                </li>
                <li>
                  <a href={LINKS.discord} target="_blank" rel="noopener noreferrer" className="font-semibold text-disc-cyan hover:underline">Join the Discord</a>
                  <span className="text-text-secondary">: trade patterns and get help from other operators.</span>
                </li>
              </ul>
            </section>
          </div>

          {/* TOC sidebar */}
          <aside className="hidden lg:block">
            <TableOfContents items={tocItems} />
          </aside>
        </div>
      </div>

      <Footer />
    </>
  );
}
