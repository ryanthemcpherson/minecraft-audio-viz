export default function DemoVideo() {
  const demoVideoUrl = "/mcav-demo-thunderstruck-daftpunk.mp4";

  return (
    <section id="demo" className="relative px-6 py-32">
      <div className="mx-auto max-w-5xl">
        {/* Section Header */}
        <div className="mb-16 text-center">
          <p className="mb-3 text-sm font-semibold uppercase tracking-widest text-deep-purple">
            See It Live
          </p>
          <h2 className="text-3xl font-bold tracking-tight sm:text-4xl md:text-5xl">
            MCAV in <span className="text-gradient">action</span>
          </h2>
          <p className="mx-auto mt-4 max-w-xl text-lg text-text-secondary">
            Watch real audio being transformed into Minecraft architecture in real-time.
          </p>
        </div>

        {/* Video Embed */}
        <div className="group relative overflow-hidden rounded-2xl border border-white/10 bg-bg-secondary shadow-2xl">
          {/* Glow effect behind the video */}
          <div className="absolute -inset-1 rounded-2xl bg-gradient-to-r from-electric-blue/20 via-deep-purple/20 to-hot-pink/20 opacity-0 blur-xl transition-opacity duration-500 group-hover:opacity-100" />

          <div className="relative aspect-video w-full overflow-hidden rounded-2xl bg-[#111]">
            <video className="h-full w-full" controls preload="metadata">
              <source src={demoVideoUrl} type="video/mp4" />
            </video>
            <a
              href={demoVideoUrl}
              target="_blank"
              rel="noreferrer"
              className="absolute bottom-3 right-3 rounded-md border border-white/20 bg-black/60 px-3 py-1 text-xs text-white transition-colors hover:bg-black/80"
            >
              Open local demo video
            </a>
          </div>
        </div>

        {/* Stats row */}
        <div className="mt-12 grid grid-cols-2 gap-6 sm:grid-cols-4">
          {[
            { value: "21ms", label: "Latency" },
            { value: "5", label: "Frequency Bands" },
            { value: "27+", label: "Patterns" },
            { value: "60", label: "FPS Rendering" },
          ].map((stat) => (
            <div key={stat.label} className="text-center">
              <div className="text-2xl font-bold text-gradient sm:text-3xl">
                {stat.value}
              </div>
              <div className="mt-1 text-sm text-text-secondary">
                {stat.label}
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
