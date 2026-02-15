import { Metadata } from "next";
import CodeBlock from "@/components/CodeBlock";
import TableOfContents from "@/components/TableOfContents";
import Footer from "@/components/Footer";

export const metadata: Metadata = {
  title: "Getting Started - MCAV",
  description:
    "Set up MCAV on your Minecraft server in minutes. Download the plugin, install the VJ server, and start visualizing music in real-time.",
};

const tocItems = [
  { id: "prerequisites", label: "Prerequisites" },
  { id: "server-setup", label: "Server Owners / VJ Operators" },
  { id: "install-plugin", label: "Install the Plugin", indent: true },
  { id: "install-processor", label: "Install VJ Server", indent: true },
  { id: "start-vj-server", label: "Start the VJ Server", indent: true },
  { id: "in-game-setup", label: "In-Game Setup", indent: true },
  { id: "dj-setup", label: "For DJs" },
  { id: "download-dj-client", label: "Download DJ Client", indent: true },
  { id: "connect", label: "Connect", indent: true },
  { id: "python-cli", label: "Python CLI Alternative", indent: true },
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
            Getting <span className="text-gradient">Started</span>
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
                Set up the Minecraft plugin and VJ server to host audio visualization shows.
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
                Download the DJ Client app and connect to a server with a connect code.
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
                    <h3 className="font-semibold">Paper Server 1.21.1+</h3>
                  </div>
                  <p className="text-sm text-text-secondary">
                    Minecraft server with Display Entity support. Paper or Spigot.
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
                  Set up the Minecraft plugin and VJ server to host visualization shows on your server.
                </p>
              </div>

              {/* Step 1: Install Plugin */}
              <div id="install-plugin" className="mb-16">
                <div className="flex items-center gap-4 mb-6">
                  <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-disc-cyan to-disc-blue text-white font-bold text-sm">
                    01
                  </div>
                  <h3 className="text-xl font-bold">Install the Minecraft Plugin</h3>
                </div>

                <div className="space-y-6 pl-0 sm:pl-16">
                  <div>
                    <h4 className="font-semibold mb-3 text-disc-cyan">
                      Option A: Download Pre-Built JAR (Recommended)
                    </h4>
                    <p className="text-text-secondary mb-4">
                      Download the latest plugin JAR from GitHub Releases. No build tools required.
                    </p>
                    <a
                      href="https://github.com/ryanthemcpherson/minecraft-audio-viz/releases"
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-2 rounded-xl border border-white/10 bg-white/5 px-5 py-3 text-sm font-semibold text-white backdrop-blur-sm transition-all hover:border-white/20 hover:bg-white/10 mb-4"
                    >
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                        <polyline points="7 10 12 15 17 10" />
                        <line x1="12" y1="15" x2="12" y2="3" />
                      </svg>
                      Download from GitHub Releases
                    </a>
                    <p className="text-sm text-text-secondary">
                      Copy the <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">audioviz-plugin-*.jar</code> file
                      into your server&apos;s <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">plugins/</code> directory
                      and restart the server.
                    </p>
                  </div>

                  <div className="border-t border-white/5 pt-6">
                    <h4 className="font-semibold mb-3 text-text-secondary">
                      Option B: Build from Source
                    </h4>
                    <p className="text-sm text-text-secondary mb-4">
                      Requires Java 21 and Maven installed.
                    </p>
                    <CodeBlock
                      title="Terminal"
                      code={`git clone https://github.com/ryanthemcpherson/minecraft-audio-viz.git
cd minecraft-audio-viz/minecraft_plugin
mvn package

# Copy the built JAR to your server
cp target/audioviz-plugin-*.jar /path/to/server/plugins/`}
                    />
                  </div>

                  <div className="callout-tip text-sm text-text-secondary">
                    <strong className="text-white">Note:</strong> The plugin requires Paper or Spigot 1.21.1+ for Display Entity support.
                    Any vanilla Minecraft client can see the visualizations — no client mods needed.
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
                    code={`# Install directly from GitHub
pip install git+https://github.com/ryanthemcpherson/minecraft-audio-viz.git`}
                  />

                  <div className="border-t border-white/5 pt-6">
                    <p className="text-sm text-text-secondary mb-3">
                      <strong className="text-white">For development</strong> — clone the repo first for access to all tools:
                    </p>
                    <CodeBlock
                      title="Terminal"
                      code={`git clone https://github.com/ryanthemcpherson/minecraft-audio-viz.git
cd minecraft-audio-viz
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
                    code={`# Start the VJ server on port 9000
audioviz-vj --port 9000 --minecraft-host YOUR_MC_SERVER_IP`}
                  />

                  <div className="callout-tip text-sm text-text-secondary">
                    <strong className="text-white">Solo mode:</strong> If you&apos;re the only DJ (no remote DJs connecting), you can skip the VJ server and run directly:
                    <CodeBlock
                      title="Terminal"
                      code={`audioviz --app spotify --host YOUR_MC_SERVER_IP`}
                    />
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
                    Once the plugin is installed and the VJ server is running, initialize the visualization zone in-game.
                  </p>

                  <CodeBlock
                    title="Minecraft Console"
                    language="minecraft"
                    code={`# Initialize a visualization zone with 16 display entities
/audioviz pool init main 16

# Set a visualization pattern
/audioviz pattern set spiral

# Open the control menu
/audioviz menu`}
                  />

                  <p className="text-sm text-text-secondary">
                    The <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">16</code> is the number of Display Entities to spawn.
                    More entities means more detailed visualizations but higher server load. Start with 16 and adjust as needed.
                  </p>

                  <div className="callout-tip text-sm text-text-secondary">
                    <strong className="text-white">Tip:</strong> Use{" "}
                    <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">/audioviz test main wave</code>{" "}
                    to run a test animation without audio connected. Great for verifying the plugin is working.
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
                  Connect to a server and start streaming your audio for real-time visualization in Minecraft.
                </p>
              </div>

              {/* Step 1: Download DJ Client */}
              <div id="download-dj-client" className="mb-16">
                <div className="flex items-center gap-4 mb-6">
                  <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-disc-blue to-noteblock-amber text-white font-bold text-sm">
                    01
                  </div>
                  <h3 className="text-xl font-bold">Download the DJ Client</h3>
                </div>

                <div className="space-y-6 pl-0 sm:pl-16">
                  <p className="text-text-secondary">
                    The DJ Client is a lightweight desktop app that captures your audio, performs real-time FFT analysis, and streams it to the VJ server. No Python or command line needed.
                  </p>

                  {/* Platform download buttons */}
                  <div className="grid gap-3 sm:grid-cols-3">
                    <a
                      href="https://github.com/ryanthemcpherson/minecraft-audio-viz/releases"
                      target="_blank"
                      rel="noopener noreferrer"
                      className="glass-card rounded-xl p-4 text-center hover:border-disc-cyan/30"
                    >
                      <div className="mb-2 text-2xl">
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor" className="mx-auto text-disc-cyan">
                          <path d="M0 3.449L9.75 2.1v9.451H0m10.949-9.602L24 0v11.4H10.949M0 12.6h9.75v9.451L0 20.699M10.949 12.6H24V24l-12.9-1.801" />
                        </svg>
                      </div>
                      <p className="text-sm font-semibold">Windows</p>
                      <p className="text-xs text-text-secondary">.msi installer</p>
                    </a>

                    <a
                      href="https://github.com/ryanthemcpherson/minecraft-audio-viz/releases"
                      target="_blank"
                      rel="noopener noreferrer"
                      className="glass-card rounded-xl p-4 text-center hover:border-disc-blue/30"
                    >
                      <div className="mb-2 text-2xl">
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor" className="mx-auto text-disc-blue">
                          <path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.8-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11z" />
                        </svg>
                      </div>
                      <p className="text-sm font-semibold">macOS</p>
                      <p className="text-xs text-text-secondary">.dmg</p>
                    </a>

                    <a
                      href="https://github.com/ryanthemcpherson/minecraft-audio-viz/releases"
                      target="_blank"
                      rel="noopener noreferrer"
                      className="glass-card rounded-xl p-4 text-center hover:border-noteblock-amber/30"
                    >
                      <div className="mb-2 text-2xl">
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor" className="mx-auto text-noteblock-amber">
                          <path d="M12.504 0c-.155 0-.311.015-.466.044-3.046.547-4.728 3.994-5.188 5.5-.458 1.499-1.498 4.062-3.282 5.84-.02.019-.035.043-.057.063l-.008.007C1.466 13.445 0 16.048 0 18c0 3.314 2.686 6 6 6 1.5 0 2.863-.555 3.916-1.466.172-.149.347-.293.531-.428A5.989 5.989 0 0 0 12 22.5a5.989 5.989 0 0 0 1.553-.394c.184.135.359.28.531.428A5.977 5.977 0 0 0 18 24c3.314 0 6-2.686 6-6 0-1.952-1.466-4.555-3.503-6.546l-.008-.007c-.022-.02-.037-.044-.057-.063-1.784-1.778-2.824-4.341-3.282-5.84-.46-1.506-2.142-4.953-5.188-5.5A3.525 3.525 0 0 0 12.504 0z" />
                        </svg>
                      </div>
                      <p className="text-sm font-semibold">Linux</p>
                      <p className="text-xs text-text-secondary">.deb / AppImage</p>
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

              {/* Alternative: Python CLI */}
              <div id="python-cli">
                <div className="flex items-center gap-4 mb-6">
                  <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl border border-white/10 bg-white/5 text-text-secondary font-bold text-sm">
                    alt
                  </div>
                  <h3 className="text-xl font-bold">Alternative: Python CLI</h3>
                </div>

                <div className="space-y-6 pl-0 sm:pl-16">
                  <p className="text-text-secondary">
                    Prefer the command line? You can use the Python CLI directly instead of the DJ Client app.
                  </p>

                  <CodeBlock
                    title="Terminal"
                    code={`# Install the VJ server
pip install git+https://github.com/ryanthemcpherson/minecraft-audio-viz.git

# Connect directly to a Minecraft server
audioviz --app spotify --host 192.168.1.100

# Or connect to a VJ server as a remote DJ
audioviz --dj-relay --vj-server ws://SERVER:9000 --dj-name "DJ Spark"`}
                  />
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
                    <CodeBlock
                      title="Terminal"
                      code={`# List all capturable audio applications
audioviz --list-apps

# List audio devices
audioviz --list-devices`}
                    />
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
                      <li>Verify the server IP address and port are correct</li>
                      <li>Check that port <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">8765</code> (Minecraft WebSocket) and <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">9000</code> (VJ server) are open in your firewall</li>
                      <li>Run <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">/audioviz status</code> in Minecraft to check the plugin status</li>
                      <li>Make sure the Minecraft plugin loaded successfully — check server logs for errors</li>
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
                      <li>Make sure you&apos;ve initialized the entity pool: <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">/audioviz pool init main 16</code></li>
                      <li>Stand near the visualization zone — entities spawn at your location when initialized</li>
                      <li>Check that a pattern is set: <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">/audioviz pattern set bars</code></li>
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
                      <li>Enable low-latency mode: <code className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-xs">audioviz --low-latency</code> (~20ms window)</li>
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
                    Explore 27+ visualization patterns — spirals, auroras, DNA helixes, and more.
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
