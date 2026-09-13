"use client";

import { useSyncExternalStore } from "react";
import type { AudioState } from "@/lib/patterns/base";
import { generateAudioState } from "@/lib/audioSim";
import {
  BrowserAudioAnalyzer,
  requestMicrophoneStream,
  requestTabAudioStream,
} from "./browserAnalyzer";

export type AudioSourceMode = "synthetic" | "microphone" | "tab";
export type AudioSourceStatus = "idle" | "requesting" | "active" | "error";

export interface AudioSourceSnapshot {
  mode: AudioSourceMode;
  status: AudioSourceStatus;
  error: string | null;
}

/**
 * Page-wide audio source shared by every pattern preview and meter.
 *
 * Defaults to the synthetic 128 BPM track. When the visitor enables the
 * microphone or tab audio, sampleAudio() switches to live analysis and every
 * consumer follows without re-mounting.
 */
let snapshot: AudioSourceSnapshot = { mode: "synthetic", status: "idle", error: null };
let analyzer: BrowserAudioAnalyzer | null = null;
const listeners = new Set<() => void>();

function emit(next: Partial<AudioSourceSnapshot>): void {
  snapshot = { ...snapshot, ...next };
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function getSnapshot(): AudioSourceSnapshot {
  return snapshot;
}

const SERVER_SNAPSHOT: AudioSourceSnapshot = { mode: "synthetic", status: "idle", error: null };
function getServerSnapshot(): AudioSourceSnapshot {
  return SERVER_SNAPSHOT;
}

/** React hook for the current source state. */
export function useAudioSource(): AudioSourceSnapshot {
  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
}

/**
 * Audio state for the current frame. Live sources ignore `phaseOffset`
 * (every preview should react to the same real music); the synthetic source
 * uses it so cards do not pulse in lockstep.
 */
export function sampleAudio(time: number, phaseOffset: number = 0): AudioState {
  if (analyzer && !analyzer.isDisposed && snapshot.status === "active") {
    return analyzer.sample(time);
  }
  return generateAudioState(time, phaseOffset);
}

/** True when a live source is currently feeding the previews. */
export function isLiveAudio(): boolean {
  return snapshot.status === "active" && snapshot.mode !== "synthetic";
}

function tearDown(): void {
  analyzer?.dispose();
  analyzer = null;
}

/** Return to the synthetic track and release any capture. */
export function stopLiveAudio(): void {
  tearDown();
  emit({ mode: "synthetic", status: "idle", error: null });
}

async function startLive(mode: Exclude<AudioSourceMode, "synthetic">): Promise<void> {
  tearDown();
  emit({ mode, status: "requesting", error: null });
  try {
    const stream = mode === "microphone" ? await requestMicrophoneStream() : await requestTabAudioStream();
    const next = new BrowserAudioAnalyzer(stream);
    await next.resume();
    analyzer = next;
    // If the visitor stops sharing from the browser chrome, fall back cleanly.
    for (const track of stream.getAudioTracks()) {
      track.addEventListener("ended", () => {
        if (analyzer === next) {
          analyzer = null;
          emit({ mode: "synthetic", status: "idle", error: null });
        }
      });
    }
    emit({ mode, status: "active", error: null });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error(`[MCAV] Could not start ${mode} audio:`, error);
    emit({ mode: "synthetic", status: "error", error: message });
  }
}

export function startMicrophone(): Promise<void> {
  return startLive("microphone");
}

export function startTabAudio(): Promise<void> {
  return startLive("tab");
}
