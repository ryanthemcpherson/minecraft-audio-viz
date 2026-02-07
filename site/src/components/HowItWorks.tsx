const steps = [
  {
    number: "01",
    title: "Capture",
    description:
      "System audio is captured in real-time via WASAPI. Target any application -- Spotify, Chrome, Discord -- or capture the full mix.",
    icon: (
      <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z" />
        <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
        <line x1="12" y1="19" x2="12" y2="23" />
        <line x1="8" y1="23" x2="16" y2="23" />
      </svg>
    ),
    gradient: "from-electric-blue to-electric-blue/50",
    glowColor: "shadow-electric-blue/20",
  },
  {
    number: "02",
    title: "Process",
    description:
      "5-band FFT analysis with Aubio-powered beat and onset detection. Sub-21ms latency keeps visualizations perfectly synced.",
    icon: (
      <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <rect x="1" y="6" width="4" height="14" rx="1" />
        <rect x="6" y="2" width="4" height="18" rx="1" />
        <rect x="11" y="8" width="4" height="12" rx="1" />
        <rect x="16" y="4" width="4" height="16" rx="1" />
      </svg>
    ),
    gradient: "from-deep-purple to-deep-purple/50",
    glowColor: "shadow-deep-purple/20",
  },
  {
    number: "03",
    title: "Visualize",
    description:
      "Display Entities render audio-reactive 3D structures in Minecraft. Zero client mods required -- any vanilla player can watch.",
    icon: (
      <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
        <polyline points="3.27 6.96 12 12.01 20.73 6.96" />
        <line x1="12" y1="22.08" x2="12" y2="12" />
      </svg>
    ),
    gradient: "from-hot-pink to-hot-pink/50",
    glowColor: "shadow-hot-pink/20",
  },
];

export default function HowItWorks() {
  return (
    <section className="relative px-6 py-32">
      <div className="mx-auto max-w-7xl">
        {/* Section Header */}
        <div className="mb-20 text-center">
          <p className="mb-3 text-sm font-semibold uppercase tracking-widest text-electric-blue">
            How It Works
          </p>
          <h2 className="text-3xl font-bold tracking-tight sm:text-4xl md:text-5xl">
            From sound wave to{" "}
            <span className="text-gradient">block world</span>
          </h2>
        </div>

        {/* Steps */}
        <div className="grid gap-8 md:grid-cols-3">
          {steps.map((step) => (
            <div key={step.number} className="group relative">
              {/* Connector line (hidden on mobile) */}
              {step.number !== "03" && (
                <div className="absolute right-0 top-1/2 hidden h-px w-8 translate-x-full bg-gradient-to-r from-white/10 to-transparent md:block" />
              )}

              <div className={`glass-card rounded-2xl p-8 shadow-lg ${step.glowColor}`}>
                {/* Step number */}
                <div className="mb-6 flex items-center gap-4">
                  <div
                    className={`flex h-14 w-14 items-center justify-center rounded-xl bg-gradient-to-br ${step.gradient} text-white shadow-lg ${step.glowColor}`}
                  >
                    {step.icon}
                  </div>
                  <span className="text-4xl font-bold text-white/10">
                    {step.number}
                  </span>
                </div>

                {/* Content */}
                <h3 className="mb-3 text-xl font-bold">{step.title}</h3>
                <p className="leading-relaxed text-text-secondary">
                  {step.description}
                </p>
              </div>
            </div>
          ))}
        </div>

        {/* Architecture line */}
        <div className="mt-16 flex items-center justify-center gap-3 text-xs text-text-secondary">
          <span className="rounded-md border border-white/10 bg-white/5 px-3 py-1.5 font-mono">
            WASAPI
          </span>
          <svg width="20" height="8" viewBox="0 0 20 8" fill="none">
            <path d="M0 4h16M13 1l4 3-4 3" stroke="currentColor" strokeWidth="1.5" />
          </svg>
          <span className="rounded-md border border-white/10 bg-white/5 px-3 py-1.5 font-mono">
            Python FFT
          </span>
          <svg width="20" height="8" viewBox="0 0 20 8" fill="none">
            <path d="M0 4h16M13 1l4 3-4 3" stroke="currentColor" strokeWidth="1.5" />
          </svg>
          <span className="rounded-md border border-white/10 bg-white/5 px-3 py-1.5 font-mono">
            WebSocket
          </span>
          <svg width="20" height="8" viewBox="0 0 20 8" fill="none">
            <path d="M0 4h16M13 1l4 3-4 3" stroke="currentColor" strokeWidth="1.5" />
          </svg>
          <span className="rounded-md border border-white/10 bg-white/5 px-3 py-1.5 font-mono">
            Minecraft
          </span>
        </div>
      </div>
    </section>
  );
}
