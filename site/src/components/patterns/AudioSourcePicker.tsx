"use client";

import { useEffect, useRef } from "react";
import {
  useAudioSource,
  stopLiveAudio,
  startMicrophone,
  startTabAudio,
  sampleAudio,
  type AudioSourceMode,
} from "@/lib/audio/audioSource";
import Alert from "@/components/ui/Alert";

const OPTIONS: { mode: AudioSourceMode; label: string; hint: string }[] = [
  { mode: "synthetic", label: "Synthetic", hint: "Built-in 128 BPM track" },
  { mode: "microphone", label: "Microphone", hint: "React to the room" },
  { mode: "tab", label: "Tab audio", hint: "Share a tab playing music (Chrome, Edge)" },
];

/** Live BPM readout that polls the current source every frame. */
function BpmReadout({ live }: { live: boolean }) {
  const ref = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    let raf = 0;
    const start = performance.now();
    const tick = (now: number) => {
      const audio = sampleAudio((now - start) / 1000, 0);
      if (ref.current) {
        ref.current.textContent =
          audio.bpm > 0 ? `${Math.round(audio.bpm)} BPM` : live ? "Listening for beats" : "";
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [live]);

  return <span ref={ref} />;
}

interface AudioSourcePickerProps {
  compact?: boolean;
  className?: string;
}

/**
 * Lets the visitor switch every live preview between the synthetic track,
 * their microphone, or a browser tab's audio. Audio stays in the browser.
 */
export default function AudioSourcePicker({ compact = false, className = "" }: AudioSourcePickerProps) {
  const source = useAudioSource();
  const isLive = source.status === "active" && source.mode !== "synthetic";
  const activeMode = source.status === "active" || source.status === "requesting" ? source.mode : "synthetic";

  const select = (mode: AudioSourceMode) => {
    if (mode === "synthetic") stopLiveAudio();
    else if (mode === "microphone") void startMicrophone();
    else void startTabAudio();
  };

  return (
    <div className={`flex flex-col gap-2 ${className}`}>
      <div className="flex flex-wrap items-center gap-3">
        <div
          role="radiogroup"
          aria-label="Audio source for previews"
          className="inline-flex rounded-xl border border-white/10 bg-white/[0.03] p-1"
        >
          {OPTIONS.map((opt) => {
            const active = activeMode === opt.mode;
            return (
              <button
                key={opt.mode}
                type="button"
                role="radio"
                aria-checked={active}
                title={opt.hint}
                onClick={() => select(opt.mode)}
                className={`rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ${
                  active ? "bg-white/10 text-white" : "text-text-secondary hover:text-white"
                }`}
              >
                {opt.label}
              </button>
            );
          })}
        </div>

        <div className="flex items-center gap-2 font-mono text-[11px] uppercase tracking-wider text-text-secondary/80">
          <span
            className={`h-1.5 w-1.5 rounded-full ${
              source.status === "requesting"
                ? "animate-pulse bg-warning"
                : isLive
                  ? "bg-success"
                  : "bg-white/25"
            }`}
            aria-hidden="true"
          />
          <span aria-live="polite">
            {source.status === "requesting"
              ? "Waiting for permission"
              : isLive
                ? source.mode === "microphone"
                  ? "Live: microphone"
                  : "Live: tab audio"
                : "Synthetic"}
          </span>
          <span className="text-text-secondary/50" aria-hidden="true">
            &middot;
          </span>
          <BpmReadout live={isLive} />
        </div>
      </div>

      {source.error && <Alert tone="danger">{source.error}</Alert>}

      {!compact && (
        <p className="text-xs text-text-secondary/70">
          Audio is analyzed in your browser and never uploaded. Live sources drive every preview on
          the page.
        </p>
      )}
    </div>
  );
}
