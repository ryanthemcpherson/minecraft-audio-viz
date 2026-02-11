export default function DemoVideo() {
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
            {/* Placeholder state -- replace src with actual YouTube embed */}
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-6">
              {/* Animated bars behind the play button */}
              <div className="flex items-end gap-1">
                {[...Array(7)].map((_, i) => (
                  <div
                    key={i}
                    className="eq-bar w-2 rounded-full"
                    style={{
                      height: "48px",
                      background:
                        "linear-gradient(to top, #00D4FF, #8B5CF6, #FF006E)",
                      animationDelay: `${i * 0.1}s`,
                      animationDuration: `${0.8 + (i * 0.09)}s`,
                      opacity: 0.4,
                    }}
                  />
                ))}
              </div>

              {/* Play button */}
              <button aria-label="Play demo video" className="flex h-20 w-20 items-center justify-center rounded-full border border-white/20 bg-white/10 text-white backdrop-blur-sm transition-all hover:scale-110 hover:border-white/30 hover:bg-white/20">
                <svg
                  width="28"
                  height="28"
                  viewBox="0 0 24 24"
                  fill="currentColor"
                >
                  <polygon points="6,3 20,12 6,21" />
                </svg>
              </button>

              <p className="text-sm text-text-secondary">
                Demo video coming soon
              </p>
            </div>

            {/*
              When ready, replace the placeholder above with:
              <iframe
                className="absolute inset-0 h-full w-full"
                src="https://www.youtube.com/embed/YOUR_VIDEO_ID"
                title="MCAV Demo"
                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
                allowFullScreen
              />
            */}
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
