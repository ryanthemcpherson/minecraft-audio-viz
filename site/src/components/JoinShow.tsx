"use client";

import { useState } from "react";

export default function JoinShow() {
  const [code, setCode] = useState("");
  const [showToast, setShowToast] = useState(false);

  const handleJoin = () => {
    if (!code.trim()) return;
    setShowToast(true);
    setTimeout(() => setShowToast(false), 3000);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      handleJoin();
    }
  };

  return (
    <section id="join" className="relative px-6 py-32">
      {/* Background glow */}
      <div className="pointer-events-none absolute inset-0">
        <div className="absolute left-1/2 top-1/2 h-[600px] w-[600px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-deep-purple/10 blur-[150px]" />
      </div>

      <div className="relative mx-auto max-w-2xl text-center">
        {/* Section Header */}
        <p className="mb-3 text-sm font-semibold uppercase tracking-widest text-electric-blue">
          Join a Show
        </p>
        <h2 className="text-3xl font-bold tracking-tight sm:text-4xl md:text-5xl">
          Got a <span className="text-gradient">connect code</span>?
        </h2>
        <p className="mx-auto mt-4 max-w-lg text-lg text-text-secondary">
          Enter the DJ connect code to join a live visualization show. No downloads, no setup -- just paste and go.
        </p>

        {/* Code Input */}
        <div className="mt-10 flex flex-col items-center gap-4 sm:flex-row sm:justify-center">
          <div className="relative w-full max-w-sm">
            <input
              type="text"
              value={code}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
              onKeyDown={handleKeyDown}
              placeholder="WORD-XXXX"
              maxLength={9}
              className="w-full rounded-xl border border-white/10 bg-white/5 px-6 py-4 text-center font-mono text-lg tracking-widest text-white placeholder-white/20 outline-none transition-all focus:border-electric-blue/50 focus:bg-white/8 focus:ring-2 focus:ring-electric-blue/20"
            />
          </div>
          <button
            onClick={handleJoin}
            disabled={!code.trim()}
            className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-electric-blue to-deep-purple px-8 py-4 text-sm font-semibold text-white shadow-lg shadow-electric-blue/20 transition-all hover:shadow-xl hover:shadow-electric-blue/30 hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:shadow-lg disabled:hover:brightness-100"
          >
            Join Show
            <svg
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M5 12h14M12 5l7 7-7 7" />
            </svg>
          </button>
        </div>

        {/* Example code hint */}
        <p className="mt-4 text-xs text-text-secondary/60">
          Example: BASS-4F2A or BEAT-9C1D
        </p>

        {/* How it works mini-steps */}
        <div className="mt-12 grid grid-cols-3 gap-4">
          {[
            {
              step: "1",
              text: "Get a code from the DJ",
            },
            {
              step: "2",
              text: "Enter code above",
            },
            {
              step: "3",
              text: "Watch the show in Minecraft",
            },
          ].map((item) => (
            <div key={item.step} className="text-center">
              <div className="mx-auto mb-2 flex h-8 w-8 items-center justify-center rounded-full border border-white/10 bg-white/5 text-xs font-bold text-electric-blue">
                {item.step}
              </div>
              <p className="text-xs text-text-secondary">{item.text}</p>
            </div>
          ))}
        </div>
      </div>

      {/* Toast notification */}
      {showToast && (
        <div className="fixed bottom-8 left-1/2 z-50 -translate-x-1/2 animate-slide-up">
          <div className="flex items-center gap-3 rounded-xl border border-white/10 bg-[#1a1a2e] px-6 py-4 shadow-2xl backdrop-blur-xl">
            <div className="flex h-8 w-8 items-center justify-center rounded-full bg-deep-purple/20 text-deep-purple">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
              </svg>
            </div>
            <div className="text-left">
              <p className="text-sm font-semibold text-white">
                Coordinator API coming soon
              </p>
              <p className="text-xs text-text-secondary">
                Code: {code}
              </p>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
