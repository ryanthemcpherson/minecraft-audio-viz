import { Metadata } from "next";
import CodeBlock from "@/components/CodeBlock";
import TableOfContents from "@/components/TableOfContents";
import Footer from "@/components/Footer";

export const metadata: Metadata = {
  title: "Getting Started - MCAV",
  description:
    "Set up MCAV on your Minecraft server in minutes. Install the Fabric mod or Paper plugin, set up the VJ server, and start visualizing music in real-time.",
};

const tocItems = [
  { id: "prerequisites", label: "Prerequisites" },
  { id: "server-setup", label: "Server Owners / VJ Operators" },
  { id: "install-plugin", label: "Choose Platform", indent: true },
  { id: "install-processor", label: "Install VJ Server", indent: true },
  { id: "start-vj-server", label: "Start the VJ Server", indent: true },
  { id: "in-game-setup", label: "In-Game Setup", indent: true },
  { id: "dj-setup", label: "For DJs" },
  { id: "dj-client-availability", label: "DJ Client Availability", indent: true },
  { id: "connect", label: "Connect", indent: true },
  { id: "troubleshooting", label: "Troubleshooting" },
  { id: "next-steps", label: "Next Steps" },
];

export default function GettingStartedPage() {
  return (
    <>
      {/* Hero */}
      <section className="px-6 pt-32 pb-16">
        <div className="mx-auto max-w-4xl text-center">
          <h1 className="text-4xl font-bold tracking-tight sm:text-5xl md:text-6xl">
            Getting <span className="text-disc-cyan">Started</span>
          </h1>
          <p className="mt-4 text-lg text-text-secondary sm:text-xl">
            Get MCAV running on your Minecraft server in minutes.
          </p>

          {/* Audience cards */}
          <div className="mt-10 grid gap-4 sm:grid-cols-2 max-w-2xl mx-auto">
            <a
              href="#server-setup"
              className="glass-card rounded-2xl p-6 text-left group"
            >
              <div className="flex items-center gap-3 mb-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-disc-cyan to-disc-blue text-white">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                    <rect x="2" y="2" width="20" height="8" rx="2" ry="2" />
                    <rect x="2" y="14" width="20" height="8" rx="2" ry="2" />
                    <line x1="6" y1="6" x2="6.01" y2="6" />
                    <line x1="6" y1="18" x2="6.01" y2="18" />
                  </svg>
                </div>
                <h3 className="font-bold text-lg">Server Owner</h3>
              </div>
              <p className="text-sm text-text-secondary">
                Set up the Minecraft mod or plugin and VJ server to host audio visualization shows.
              </p>
            </a>

            <a
              href="#dj-setup"
              className="glass-card rounded-2xl p-6 text-left group"
            >
              <div className="flex items-center gap-3 mb-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-disc-blue to-noteblock-amber text-white">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M9 18V5l12-2v13" />
                    <circle cx="6" cy="18" r="3" />
                    <circle cx="18" cy="16" r="3" />
                  </svg>
                </div>
                <h3 className="font-bold text-lg">DJ</h3>
              </div>
              <p className="text-sm text-text-secondary">
                Review the Phase 0 distribution status and source-only development path.
              </p>
            </a>
          </div>
        </div>
      </section>

      {/* Main content with TOC sidebar */}
      <div className="px-6 pb-32">
        <div className="mx-auto max-w-6xl lg:grid lg:grid-cols-[1fr_220px] lg:gap-12">
          {/* Content */}
          <div className="space-y-24">

            {/* Prerequisites */}
            <section id="prerequisites">
              <div className="mb-8">
                <p className="mb-3 text-sm font-semibold uppercase tracking-widest text-disc-cyan">
                  Before You Begin
                </p>
                <h2 className="text-2xl font-bold tracking-tight sm:text-3xl">
                  Prerequisites
                </h2>
              </div>

              <div className="grid gap-4 sm:grid-cols-2">
                <div className="glass-card rounded-xl p-5">
                  <div className="flex items-center gap-3 mb-2">
                    <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-disc-cyan/10 text-disc-cyan">
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
                        <line x1="8" y1="21" x2="16" y2="21" />
                        <line x1="12" y1="17" x2="12" y2="21" />
                      </svg>
                    </div>
                    <h3 className="font-semibold">Windows PC</h3>
                  </div>
                  <p className="text-sm text-text-secondary">
                    Audio capture uses WASAPI, which requires Windows 10 or 11.
                  </p>
                </div>

                <div className="glass-card rounded-xl p-5">
                  <div className="flex items-center gap-3 mb-2">
                    <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-disc-blue/10 text-disc-blue">
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
                      </svg>
                    </div>
                    <h3 className="font-semibold">MC Server 1.21.1+</h3>
                  </div>
                  <p className="text-sm text-text-secondary">
                    Fabric (with Fabric API, SGUI, Polymer) or Paper/Spigot. See{" "}
                    <a href="#install-plugin" className="text-disc-cyan hover:underline">Step 1</a> for comparison.
                  </p>
                </div>

                <div className="glass-card rounded-xl p-5">
                  <div className="flex items-center gap-3 mb-2">
                    <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-noteblock-amber/10 text-noteblock-amber">
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M12 19l7-7 3 3-7 7-3-3z" />
                        <path d="M18 13l-1.5-7.5L2 2l3.5 14.5L13 18l5-5z" />
                        <path d="M2 2l7.586 7.586" />
                        <circle cx="11" cy="11" r="2" />
                      </svg>
                    </div>
                    <h3 className="font-semibold">Python 3.11+</h3>
                  </div>
                  <p className="text-sm text-text-secondary">
                    Required for server owners running the VJ server.
                  </p>
                </div>

                <div className="glass-card rounded-xl p-5">
                  <div className="flex items-center gap-3 mb-2">
                    <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-disc-cyan/10 text-disc-cyan">
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" />
                        <path d="M19.07 4.93a10 10 0 0 1 0 14.14" />
                        <path d="M15.54 8.46a5 5 0 0 1 0 7.07" />
                      </svg>
                    </div>
                    <h3 className="font-semibold">Audio Source</h3>
                  </div>
                  <p className="text-sm text-text-secondary">
                    Spotify, Chrome, Discord, or any application playing audio.
                  </p>
                </div>
              </div>
            </section>

            {/* ===== SERVER OWNERS ===== */}
            <section id="server-setup">
              <div className="mb-12">
                <p className="mb-3 text-sm font-semibold uppercase tracking-widest text-disc-blue">
                  Server Setup
                </p>
                <h2 className="text-2xl font-bold tracking-tight sm:text-3xl">
                  For Server Owners / VJ Operators
                </h2>
                <p className="mt-3 text-text-secondary max-w-2xl">
                  Choose between Fabric mod or Paper plugin, install the VJ server, and start hosting visualization shows.
                </p>
              </div>

              {/* Step 1: Choose Platform */}
              <div id="install-plugin" className="mb-16">
                <div className="flex items-center gap-4 mb-6">
                  <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-disc-cyan to-disc-blue text-white font-bold text-sm">
                    01
                  </div>
                  <h3 className="text-xl font-bold">Choose Your Server Platform</h3>
                </div>

                <div className="space-y-6 pl-0 sm:pl-16">
                  <p className="text-text-secondary">
                    MCAV ships two server-side JARs with full feature parity. Pick whichever fits your server.
                    Both render the same visualizations — no client mods needed for players.
                  </p>

                  {/* Comparison cards */}
                  <div className="grid gap-4 sm:grid-cols-2">
                    <div className="glass-card rounded-xl p-6 border border-disc-cyan/20">
                      <div className="flex items-center gap-3 mb-4">
                        <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-disc-cyan/10 text-disc-cyan font-bold text-lg">F</div>
                        <div>
                          <h4 className="font-bold text-lg">Fabric Mod</h4>
                          <p className="text-xs text-text-secondary">audioviz-mod.jar</p>
                        </div>
                      </div>
                      <ul className="space-y-2 text-sm mb-4">
                        <li className="flex gap-2">
                          <span className="text-emerald-400 shrink-0">+</span>
                          <span className="text-text-secondary">Map-based bitmap rendering — true pixel-level resolution (128x128 per tile)</span>
                        </li>
                        <li className="flex gap-2">
                          <span className="text-emerald-400 shrink-0">+</span>
                          <span className="text-text-secondary">Better performance — direct access to Minecraft internals</span>
                        </li>
                        <li className="flex gap-2">
                          <span className="text-emerald-400 shrink-0">+</span>
                          <span className="text-text-secondary">No client mods needed — uses Polymer for vanilla compatibility</span>
                        </li>
                        <li className="flex gap-2">
                          <span className="text-amber-400 shrink-0">&minus;</span>
                          <span className="text-text-secondary">Requires Fabric API + SGUI + Polymer dependencies</span>
                        </li>
                        <li className="flex gap-2">
                          <span className="text-amber-400 shrink-0">&minus;</span>
                          <span className="text-text-secondary">Smaller mod ecosystem than Paper/Spigot</span>
                        </li>
                      </ul>
                      <a
                        href="https://github.com/ryanthemcpherson/minecraft-audio-viz/releases"
                        target="_blank"
                        rel="noopener noreferrer"
                        className="inline-flex items-center gap-2 rounded-lg border border-disc-cyan/30 bg-disc-cyan/5 px-4 py-2 text-sm font-semibold text-disc-cyan transition-all hover:bg-disc-cyan/10"
                      >
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                          <polyline points="7 10 12 15 17 10" />
                          <line x1="12" y1="15" x2="12" y2="3" />
                        </svg>
                        Download Fabric Mod
                      </a>
                    </div>

                    <div className="glass-card rounded-xl p-6 border border-disc-blue/20">
                      <div className="flex items-center gap-3 mb-4">
                        <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-disc-blue/10 text-disc-blue font-bold text-lg">P</div>
                        <div>
                          <h4 className="font-bold text-lg">Paper Plugin</h4>
                          <p className="text-xs text-text-secondary">audioviz-plugin.jar</p>
                        </div>
                      </div>
                      <ul className="space-y-2 text-sm mb-4">
                        <li className="flex gap-2">
                          <span className="text-emerald-400 shrink-0">+</span>
                          <span className="text-text-secondary">Zero dependencies — single self-contained JAR</span>
                        </li>
                        <li className="flex gap-2">
                          <span className="text-emerald-400 shrink-0">+</span>
                          <span className="text-text-secondary">Works with the huge Paper/Spigot plugin ecosystem</span>
                        </li>
                        <li className="flex gap-2">
                          <span className="text-emerald-400 shrink-0">+</span>
                          <span className="text-text-secondary">Hot-reload support — <code className="rounded bg-white/5 px-1 py-0.5 font-mono text-xs">/reload</code> without restart</span>
                        </li>
                        <li className="flex gap-2">
                          <span className="text-amber-400 shrink-0">&minus;</span>
                          <span className="text-text-secondary">Lower resolution bitmaps — uses Display Entities (1 entity per pixel) instead of maps</span>
                        </li>
                        <li className="flex gap-2">
                          <span className="text-amber-400 shrink-0">&minus;</span>
                          <span className="text-text-secondary">Slightly higher overhead from Bukkit API abstraction layer</span>
                        </li>
                      </ul>
                      <a
                        href="https://github.com/ryanthemcpherson/minecraft-audio-viz/releases"
                        target="_blank"
                        rel="noopener noreferrer"
                        className="inline-flex items-center gap-2 rounded-lg border border-disc-blue/30 bg-disc-blue/5 px-4 py-2 text-sm font-semibold text-disc-blue transition-all hover:bg-disc-blue/10"
                      >
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                          <polyline points="7 10 12 15 17 10" />
                          <line x1="12" y1="15" x2="12" y2="3" />
                        </svg>
                        Download Paper Plugin
                      </a>
                    </div>
                  </div>

                  {/* Install instructions */}
                  <div className="grid gap-4 sm:grid-cols-2">
                    <div>
                      <h4 className="font-semibold mb-3 text-disc-cyan text-sm">Fabric Install</h4>
                      <CodeBlock
                        title="Terminal"
                        code={`# Drop into mods/ folder
cp audioviz-mod-*.jar /path/to/server/mods/

# Also install these Fabric mods:
# - Fabric API
# - SGUI
# - Polymer`}
                      />
                    </div>
                    <div>
                      <h4 className="font-semibold mb-3 text-disc-blue text-sm">Paper Install</h4>
                      <CodeBlock
                        title="Terminal"
                        code={`# Drop into plugins/ folder
cp audioviz-plugin-*.jar /path/to/server/plugins/

# No other dependencies needed!
# Restart the server.`}
                      />
                    </div>
                  </div>

                  <details className="glass-card rounded-xl overflow-hidden">
                    <summary className="flex items-center justify-between p-5 text-sm font-semibold cursor-pointer">
                      Build from source instead
                      <svg className="faq-chevron h-4 w-4 text-text-secondary transition-transform duration-200" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="6 9 12 15 18 9" />
                      </svg>
                    </summary>
                    <div className="border-t border-white/5 px-5 pb-5 pt-4">
                      <div className="grid gap-4 sm:grid-cols-2">
                        <div>
                          <p className="text-xs text-text-secondary mb-3">Requires Java 21 + Gradle</p>
                          <CodeBlock
                            title="Fabric Mod"
                            code={`cd minecraft_mod
./gradlew build
cp build/libs/audioviz-mod-*.jar \\
  /path/to/server/mods/`}
                          />
                        </div>
                        <div>
                          <p className="text-xs text-text-secondary mb-3">Requires Java 21 + Maven</p>
                          <CodeBlock
                            title="Paper Plugin"
                            code={`cd minecraft_plugin
./mvnw package
cp target/audioviz-plugin-*.jar \\
  /path/to/server/plugins/`}
                          />
                        </div>
                      </div>
                    </div>
                  </details>

                  <div className="callout-tip text-sm text-text-secondary">
                    <strong className="text-white">Both platforms have full feature parity</strong> — bitmap rendering, entity pools,
                    particle effects, recording/playback, stage management, beat sync, Simple Voice Chat integration,
                    and the admin panel all work identically. Players see the same visualizations regardless of which platform you choose.
                  </div>
                </div>
              </div>

              {/* Step 2: Install VJ Server */}
              <div id="install-processor" className="mb-16">
                <div className="flex items-center gap-4 mb-6">
                  <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-disc-cyan to-disc-blue text-white font-bold text-sm">
                    02
                  </div>
                  <h3 className="text-xl font-bold">Install the VJ Server</h3>
                </div>

                <div className="space-y-6 pl-0 sm:pl-16">
                  <p className="text-text-secondary">
                    The VJ server receives audio data from DJ clients, runs Lua visualization patterns, and sends entity updates to the Minecraft plugin over WebSocket.
                  </p>

                  <CodeBlock
                    title="Terminal"
                    code={`# Clone the repo and install the VJ server
git clone https://github.com/ryanthemcpherson/minecraft-audio-viz.git
cd minecraft-audio-viz/vj_server
pip install -e .`}
                  />

                  <div className="border-t border-white/5 pt-6">
                    <p className="text-sm text-text-secondary mb-3">
                      <strong className="text-white">With auth support</strong> — install the optional bcrypt dependency for DJ authentication:
                    </p>
                    <CodeBlock
                      title="Terminal"
                      code={`cd minecraft-audio-viz/vj_server
pip install -e ".[full]"`}
                    />
                  </div>

                  <div className="callout-tip text-sm text-text-secondary">
                    <strong className="text-white">Tip:</strong> You can use{" "}
                    <a href="https://docs.astral.sh/uv/" target="_blank" rel="noopener noreferrer" className="text-disc-cyan hover:underline">UV</a>{" "}
                    instead of pip for significantly faster installs:{" "}
                    <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">uv pip install -e &quot;.[full]&quot;</code>
                  </div>
                </div>
              </div>

              {/* Step 3: Start VJ Server */}
              <div id="start-vj-server" className="mb-16">
                <div className="flex items-center gap-4 mb-6">
                  <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-disc-cyan to-disc-blue text-white font-bold text-sm">
                    03
                  </div>
                  <h3 className="text-xl font-bold">Start the VJ Server</h3>
                </div>

                <div className="space-y-6 pl-0 sm:pl-16">
                  <p className="text-text-secondary">
                    The VJ server is the central hub that DJs connect to. It coordinates audio feeds and controls which DJ is live.
                  </p>

                  <CodeBlock
                    title="Terminal"
                    code={`# Same host as Minecraft (recommended)
audioviz-vj --port 9000

# Separate VJ host: Terminal 1 creates an encrypted tunnel
ssh -N -L 18765:127.0.0.1:8765 operator@YOUR_MC_SERVER

# Terminal 2 connects only to that local tunnel endpoint
audioviz-vj --port 9000 --minecraft-host 127.0.0.1 --minecraft-port 18765`}
                  />

                  <p className="text-sm text-text-secondary">
                    The Minecraft renderer port is loopback-only. A shared secret authenticates the tunneled connection but never enables plaintext LAN access.
                  </p>

                  <div className="callout-tip text-sm text-text-secondary">
                    <strong className="text-white">Dev mode:</strong> For quick testing without authentication, add the{" "}
                    <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">--no-auth</code> flag:{" "}
                    <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">audioviz-vj --no-auth</code>
                  </div>
                </div>
              </div>

              {/* Step 4: In-Game Setup */}
              <div id="in-game-setup">
                <div className="flex items-center gap-4 mb-6">
                  <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-disc-cyan to-disc-blue text-white font-bold text-sm">
                    04
                  </div>
                  <h3 className="text-xl font-bold">In-Game Setup</h3>
                </div>

                <div className="space-y-6 pl-0 sm:pl-16">
                  <p className="text-text-secondary">
                    Once the mod is installed and the VJ server is running, create a visualization zone in-game.
                  </p>

                  <CodeBlock
                    title="Minecraft Console"
                    language="minecraft"
                    code={`# Create a visualization zone
/audioviz zone create main

# Open the interactive control menu
/audioviz menu

# Check connection status
/audioviz status`}
                  />

                  <p className="text-sm text-text-secondary">
                    Use the in-game menu to manage zones, select patterns, adjust sizes, and configure stages.
                    Zone entity pools are initialized automatically when you create or place a zone.
                  </p>

                  <div className="callout-tip text-sm text-text-secondary">
                    <strong className="text-white">Tip:</strong> Use{" "}
                    <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">/audioviz test main wave</code>{" "}
                    to run a test animation without audio connected. Great for verifying the mod is working.
                  </div>
                </div>
              </div>
            </section>

            {/* ===== DJ SETUP ===== */}
            <section id="dj-setup">
              <div className="mb-12">
                <p className="mb-3 text-sm font-semibold uppercase tracking-widest text-noteblock-amber">
                  DJ Setup
                </p>
                <h2 className="text-2xl font-bold tracking-tight sm:text-3xl">
                  For DJs
                </h2>
                <p className="mt-3 text-text-secondary max-w-2xl">
                  Review the current remote-session availability before preparing a DJ setup.
                </p>
              </div>

              {/* Step 1: DJ Client availability */}
              <div id="dj-client-availability" className="mb-16">
                <div className="flex items-center gap-4 mb-6">
                  <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-disc-blue to-noteblock-amber text-white font-bold text-sm">
                    01
                  </div>
                  <h3 className="text-xl font-bold">DJ Client Distribution Paused</h3>
                </div>

                <div className="space-y-6 pl-0 sm:pl-16">
                  <p className="text-text-secondary">
                    Prebuilt DJ Client distribution is paused during Phase 0. Remote DJ sessions are not supported for general use until signed release, rollback, and clean-install gates pass.
                  </p>

                  <div className="glass-card rounded-xl border border-noteblock-amber/20 p-6">
                    <p className="mb-2 text-xs font-semibold uppercase tracking-widest text-noteblock-amber">
                      Development verification only
                    </p>
                    <p className="text-sm text-text-secondary">
                      Contributors who need to verify remote workflows can build and run the unsigned client from source. These local builds are unsupported and are not published release artifacts.
                    </p>
                    <a
                      href="https://github.com/ryanthemcpherson/minecraft-audio-viz/blob/main/dj_client/README.md"
                      target="_blank"
                      rel="noopener noreferrer"
                      className="mt-5 inline-flex items-center gap-2 rounded-lg border border-noteblock-amber/30 bg-noteblock-amber/5 px-4 py-2 text-sm font-semibold text-noteblock-amber transition-colors hover:bg-noteblock-amber/10"
                    >
                      Open the source development guide
                    </a>
                  </div>
                </div>
              </div>

              {/* Step 2: Connect */}
              <div id="connect" className="mb-16">
                <div className="flex items-center gap-4 mb-6">
                  <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-disc-blue to-noteblock-amber text-white font-bold text-sm">
                    02
                  </div>
                  <h3 className="text-xl font-bold">Connect to a Server</h3>
                </div>

                <div className="space-y-6 pl-0 sm:pl-16">
                  <p className="text-text-secondary">
                    Get a connect code from the VJ operator, then connect in three steps:
                  </p>

                  <div className="grid gap-4 sm:grid-cols-3">
                    <div className="glass-card rounded-xl p-5 text-center">
                      <div className="mb-3 text-3xl font-bold text-disc-cyan">1</div>
                      <p className="text-sm font-semibold mb-1">Enter Your DJ Name</p>
                      <p className="text-xs text-text-secondary">How you appear in the DJ queue</p>
                    </div>
                    <div className="glass-card rounded-xl p-5 text-center">
                      <div className="mb-3 text-3xl font-bold text-disc-blue">2</div>
                      <p className="text-sm font-semibold mb-1">Paste Connect Code</p>
                      <p className="text-xs text-text-secondary">
                        Format: <code className="font-mono text-noteblock-amber">BEAT-7K3M</code>
                      </p>
                    </div>
                    <div className="glass-card rounded-xl p-5 text-center">
                      <div className="mb-3 text-3xl font-bold text-noteblock-amber">3</div>
                      <p className="text-sm font-semibold mb-1">Select Audio Source</p>
                      <p className="text-xs text-text-secondary">Spotify, Chrome, or system audio</p>
                    </div>
                  </div>

                  <p className="text-sm text-text-secondary">
                    Click <strong className="text-white">Connect</strong> and the app handles everything — audio capture, FFT analysis, and streaming to the VJ server at 60fps.
                  </p>
                </div>
              </div>

            </section>

            {/* ===== TROUBLESHOOTING ===== */}
            <section id="troubleshooting">
              <div className="mb-8">
                <p className="mb-3 text-sm font-semibold uppercase tracking-widest text-text-secondary">
                  Help
                </p>
                <h2 className="text-2xl font-bold tracking-tight sm:text-3xl">
                  Troubleshooting
                </h2>
              </div>

              <div className="space-y-3">
                <details className="faq-item glass-card rounded-xl overflow-hidden">
                  <summary className="flex items-center justify-between p-5 font-semibold">
                    No audio sources detected
                    <svg className="faq-chevron h-5 w-5 text-text-secondary transition-transform duration-200" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <polyline points="6 9 12 15 18 9" />
                    </svg>
                  </summary>
                  <div className="border-t border-white/5 px-5 pb-5 pt-4 text-sm text-text-secondary">
                    <p className="mb-3">Make sure your audio application (Spotify, Chrome, etc.) is playing audio when you start the capture.</p>
                    <ul className="list-disc pl-5 space-y-2">
                      <li>The DJ Client auto-detects available audio sources when the app opens</li>
                      <li>On Windows, per-app capture requires Windows 10 build 20348+ (Process Loopback API)</li>
                      <li>Try selecting &quot;System Audio&quot; instead of a specific app if per-app capture fails</li>
                    </ul>
                  </div>
                </details>

                <details className="faq-item glass-card rounded-xl overflow-hidden">
                  <summary className="flex items-center justify-between p-5 font-semibold">
                    Can&apos;t connect to server
                    <svg className="faq-chevron h-5 w-5 text-text-secondary transition-transform duration-200" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <polyline points="6 9 12 15 18 9" />
                    </svg>
                  </summary>
                  <div className="border-t border-white/5 px-5 pb-5 pt-4 text-sm text-text-secondary">
                    <ul className="list-disc pl-5 space-y-2">
                      <li>Verify the VJ server is using the Minecraft host&apos;s loopback listener or a working local encrypted-tunnel endpoint</li>
                      <li>Never open renderer port <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">8765</code> in a firewall; split-host setups must tunnel it. Restrict VJ port <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">9000</code> to intended DJs</li>
                      <li>Run <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">/audioviz status</code> in Minecraft to check the mod status</li>
                      <li>Make sure the Minecraft mod loaded successfully — check server logs for errors</li>
                    </ul>
                  </div>
                </details>

                <details className="faq-item glass-card rounded-xl overflow-hidden">
                  <summary className="flex items-center justify-between p-5 font-semibold">
                    Entities not appearing in Minecraft
                    <svg className="faq-chevron h-5 w-5 text-text-secondary transition-transform duration-200" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <polyline points="6 9 12 15 18 9" />
                    </svg>
                  </summary>
                  <div className="border-t border-white/5 px-5 pb-5 pt-4 text-sm text-text-secondary">
                    <ul className="list-disc pl-5 space-y-2">
                      <li>Make sure you&apos;ve created a zone: <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">/audioviz zone create main</code></li>
                      <li>Stand near the visualization zone — entities spawn at your location</li>
                      <li>Open the menu to check status: <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">/audioviz menu</code></li>
                      <li>Try the test animation: <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">/audioviz test main wave</code></li>
                    </ul>
                  </div>
                </details>

                <details className="faq-item glass-card rounded-xl overflow-hidden">
                  <summary className="flex items-center justify-between p-5 font-semibold">
                    High latency or desync
                    <svg className="faq-chevron h-5 w-5 text-text-secondary transition-transform duration-200" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <polyline points="6 9 12 15 18 9" />
                    </svg>
                  </summary>
                  <div className="border-t border-white/5 px-5 pb-5 pt-4 text-sm text-text-secondary">
                    <ul className="list-disc pl-5 space-y-2">
                      <li>The DJ Client uses ultra-low-latency mode by default (~21ms window)</li>
                      <li>Reduce entity count for better server performance</li>
                      <li>Ensure the VJ server and Minecraft server are on the same network for minimal latency</li>
                      <li>Close resource-heavy applications on the audio capture machine</li>
                    </ul>
                  </div>
                </details>
              </div>
            </section>

            {/* ===== NEXT STEPS ===== */}
            <section id="next-steps">
              <div className="mb-8">
                <p className="mb-3 text-sm font-semibold uppercase tracking-widest text-disc-cyan">
                  Keep Going
                </p>
                <h2 className="text-2xl font-bold tracking-tight sm:text-3xl">
                  Next Steps
                </h2>
              </div>

              <div className="grid gap-4 sm:grid-cols-3">
                <a
                  href="https://github.com/ryanthemcpherson/minecraft-audio-viz/blob/main/README.md#visualization-patterns"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="glass-card rounded-xl p-6 hover:border-disc-cyan/30"
                >
                  <div className="mb-3 text-disc-cyan">
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="12" cy="12" r="10" />
                      <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
                      <line x1="2" y1="12" x2="22" y2="12" />
                    </svg>
                  </div>
                  <h3 className="font-bold mb-2">Pattern Gallery</h3>
                  <p className="text-sm text-text-secondary">
                    Explore 40+ visualization patterns — spirals, auroras, galaxies, and more.
                  </p>
                </a>

                <a
                  href="https://github.com/ryanthemcpherson/minecraft-audio-viz/blob/main/README.md#admin-panel"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="glass-card rounded-xl p-6 hover:border-disc-blue/30"
                >
                  <div className="mb-3 text-disc-blue">
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="12" cy="12" r="3" />
                      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
                    </svg>
                  </div>
                  <h3 className="font-bold mb-2">Admin Panel</h3>
                  <p className="text-sm text-text-secondary">
                    Control patterns, effects, and presets in real-time through the browser-based VJ interface.
                  </p>
                </a>

                <a
                  href="https://github.com/ryanthemcpherson/minecraft-audio-viz/blob/main/docs/COORDINATOR_ARCHITECTURE.md"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="glass-card rounded-xl p-6 hover:border-noteblock-amber/30"
                >
                  <div className="mb-3 text-noteblock-amber">
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                      <circle cx="9" cy="7" r="4" />
                      <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
                      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
                    </svg>
                  </div>
                  <h3 className="font-bold mb-2">Multi-DJ Events</h3>
                  <p className="text-sm text-text-secondary">
                    Host live events with multiple DJs connecting and queuing via connect codes.
                  </p>
                </a>
              </div>
            </section>

          </div>

          {/* Sidebar TOC */}
          <TableOfContents items={tocItems} />
        </div>
      </div>

      <Footer />
    </>
  );
}
