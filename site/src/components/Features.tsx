const features = [
  {
    title: "27+ Visualization Patterns",
    description:
      "From spirals and auroras to DNA helixes and wave terrain. Switch patterns live during a set with instant transitions.",
    icon: (
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="10" />
        <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
        <line x1="2" y1="12" x2="22" y2="12" />
      </svg>
    ),
    accent: "text-disc-cyan",
    borderHover: "hover:border-disc-cyan/30",
  },
  {
    title: "Multi-DJ Support",
    description:
      "Multiple DJs can connect simultaneously to one Minecraft server. The VJ server coordinates audio feeds and controls transitions.",
    icon: (
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
        <circle cx="9" cy="7" r="4" />
        <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
        <path d="M16 3.13a4 4 0 0 1 0 7.75" />
      </svg>
    ),
    accent: "text-disc-blue",
    borderHover: "hover:border-disc-blue/30",
  },
  {
    title: "Browser Preview",
    description:
      "Three.js-powered 3D preview renders the exact same visualization in your browser. Test patterns without launching Minecraft.",
    icon: (
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
        <line x1="8" y1="21" x2="16" y2="21" />
        <line x1="12" y1="17" x2="12" y2="21" />
      </svg>
    ),
    accent: "text-noteblock-amber",
    borderHover: "hover:border-noteblock-amber/30",
  },
  {
    title: "Beat Detection",
    description:
      "Aubio-powered onset and tempo detection with sub-21ms latency. BPM tracking, beat phase, and drum hit detection built in.",
    icon: (
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M22 12h-4l-3 9L9 3l-3 9H2" />
      </svg>
    ),
    accent: "text-disc-cyan",
    borderHover: "hover:border-disc-cyan/30",
  },
  {
    title: "DJ Connect Codes",
    description:
      "Share connect codes with your audience so they can watch your visualization show live in Minecraft. No IP addresses, no port forwarding.",
    icon: (
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
        <path d="M7 11V7a5 5 0 0 1 10 0v4" />
      </svg>
    ),
    accent: "text-disc-blue",
    borderHover: "hover:border-disc-blue/30",
  },
  {
    title: "Zero Client Mods",
    description:
      "Everything runs server-side using Display Entities. Any vanilla Minecraft client can join and watch the show. Java 1.21.1+.",
    icon: (
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
        <polyline points="9 12 11 14 15 10" />
      </svg>
    ),
    accent: "text-noteblock-amber",
    borderHover: "hover:border-noteblock-amber/30",
  },
];

export default function Features() {
  return (
    <section id="features" className="relative px-6 py-32">
      {/* Background accent */}
      <div className="pointer-events-none absolute inset-0">
        <div className="absolute left-1/2 top-0 h-px w-2/3 -translate-x-1/2 bg-gradient-to-r from-transparent via-white/10 to-transparent" />
      </div>

      <div className="mx-auto max-w-7xl">
        {/* Section Header */}
        <div className="mb-20 text-center">
          <p className="mb-3 text-sm font-semibold uppercase tracking-widest text-noteblock-amber">
            Features
          </p>
          <h2 className="text-3xl font-bold tracking-tight sm:text-4xl md:text-5xl">
            Everything you need for{" "}
            <span className="text-gradient">live shows</span>
          </h2>
          <p className="mx-auto mt-4 max-w-2xl text-lg text-text-secondary">
            A complete audio visualization pipeline from system audio to Minecraft world,
            with tools for DJs and VJs alike.
          </p>
        </div>

        {/* Feature Grid */}
        <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
          {features.map((feature) => (
            <div
              key={feature.title}
              className={`glass-card rounded-2xl p-8 ${feature.borderHover}`}
            >
              <div className={`mb-5 ${feature.accent}`}>{feature.icon}</div>
              <h3 className="mb-3 text-lg font-bold">{feature.title}</h3>
              <p className="text-sm leading-relaxed text-text-secondary">
                {feature.description}
              </p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
