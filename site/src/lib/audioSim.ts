/**
 * Synthetic Audio Simulator
 * Generates fake AudioState data with rhythmic beats and frequency sweeps.
 * Each gallery card uses a phase offset so they don't all pulse in sync.
 */

import type { AudioState } from "./patterns/base";

const BPM = 128;
const BEAT_INTERVAL = 60 / BPM; // seconds per beat

export function generateAudioState(
  time: number,
  phaseOffset: number = 0
): AudioState {
  const t = time + phaseOffset;
  const frame = Math.floor(t * 60);

  // Bass (40-250Hz) - strong rhythmic pulse
  const bassPulse = Math.pow(Math.max(0, Math.sin(t * Math.PI * 2 / BEAT_INTERVAL)), 4);
  const bassWave = Math.sin(t * 1.2) * 0.3 + 0.3;
  const bass = Math.min(1, bassPulse * 0.6 + bassWave);

  // Low-mid (250-500Hz) - syncopated bounce
  const lowMid = Math.sin(t * 2.1 + 0.5) * 0.3 + 0.35 + bassPulse * 0.25;

  // Mid (500-2000Hz) - melodic sweep
  const mid = Math.sin(t * 3.0 + 1.2) * 0.35 + 0.4 +
    Math.sin(t * 0.7) * 0.15;

  // High-mid (2-6kHz) - shimmering harmonics
  const highMid = Math.sin(t * 4.5 + 2.0) * 0.3 + 0.3 +
    Math.sin(t * 1.8 + 0.8) * 0.2;

  // High (6-20kHz) - crisp hi-hats
  const hihatPattern = Math.pow(Math.max(0, Math.sin(t * Math.PI * 4 / BEAT_INTERVAL)), 6);
  const high = hihatPattern * 0.5 + Math.sin(t * 5.5 + 3.0) * 0.2 + 0.15;

  const bands = [
    Math.min(1, Math.max(0, bass)),
    Math.min(1, Math.max(0, lowMid)),
    Math.min(1, Math.max(0, mid)),
    Math.min(1, Math.max(0, highMid)),
    Math.min(1, Math.max(0, high)),
  ];

  const amplitude = bands.reduce((a, b) => a + b, 0) / 5;

  // Beat detection - trigger on strong bass peaks
  const beatPhase = (t % BEAT_INTERVAL) / BEAT_INTERVAL;
  const isBeat = beatPhase < 0.05 && bass > 0.5;
  const beatIntensity = isBeat ? 0.6 + bass * 0.4 : 0;

  return {
    bands,
    amplitude,
    isBeat,
    beatIntensity,
    frame,
  };
}
