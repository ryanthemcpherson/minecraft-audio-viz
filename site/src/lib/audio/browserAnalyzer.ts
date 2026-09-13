"use client";

import type { AudioState } from "@/lib/patterns/base";
import { BAND_COUNT, BandSmoother, BeatTracker, computeBandLevels } from "./bands";

const FFT_SIZE = 2048;

/**
 * Wraps a MediaStream in a Web Audio AnalyserNode and produces MCAV
 * AudioState frames. Sampling is cached per animation frame so any number of
 * previews can call sample() without recomputing the FFT bands.
 *
 * Audio never leaves the browser: nothing is recorded or uploaded.
 */
export class BrowserAudioAnalyzer {
  private readonly context: AudioContext;
  private readonly analyser: AnalyserNode;
  private readonly source: MediaStreamAudioSourceNode;
  private readonly stream: MediaStream;
  private readonly freqData: Uint8Array<ArrayBuffer>;
  private readonly rawLevels = new Float32Array(BAND_COUNT);
  private readonly smoother = new BandSmoother();
  private readonly beats = new BeatTracker();
  private lastSampleTime = -1;
  private lastState: AudioState;
  private frame = 0;
  private disposed = false;

  constructor(stream: MediaStream) {
    this.stream = stream;
    this.context = new AudioContext();
    this.analyser = this.context.createAnalyser();
    this.analyser.fftSize = FFT_SIZE;
    this.analyser.smoothingTimeConstant = 0.4;
    this.source = this.context.createMediaStreamSource(stream);
    this.source.connect(this.analyser);
    // Deliberately not connected to destination: analysis only, no playback.
    this.freqData = new Uint8Array(this.analyser.frequencyBinCount);
    this.lastState = {
      bands: new Array(BAND_COUNT).fill(0),
      amplitude: 0,
      isBeat: false,
      beatIntensity: 0,
      beatPhase: 0,
      bpm: 0,
      frame: 0,
    };

    // Stop everything if the user ends sharing from the browser UI.
    for (const track of stream.getAudioTracks()) {
      track.addEventListener("ended", () => this.dispose());
    }
  }

  /** Autoplay policy can leave the context suspended until a user gesture. */
  async resume(): Promise<void> {
    if (this.context.state === "suspended") await this.context.resume();
  }

  get isDisposed(): boolean {
    return this.disposed;
  }

  /**
   * Current audio state. `time` is the caller's clock in seconds; calls within
   * the same ~4 ms window return the cached frame.
   */
  sample(time: number): AudioState {
    if (this.disposed) return this.lastState;
    if (this.lastSampleTime >= 0 && Math.abs(time - this.lastSampleTime) < 0.004) {
      return this.lastState;
    }
    this.lastSampleTime = time;
    this.frame++;

    this.analyser.getByteFrequencyData(this.freqData);
    computeBandLevels(this.freqData, this.context.sampleRate, FFT_SIZE, this.rawLevels);
    const bands = this.smoother.update(this.rawLevels);
    const beat = this.beats.update(bands[0], time);

    let amplitude = 0;
    for (let i = 0; i < BAND_COUNT; i++) amplitude += bands[i];
    amplitude /= BAND_COUNT;

    this.lastState = {
      bands: Array.from(bands),
      amplitude,
      isBeat: beat.isBeat,
      beatIntensity: beat.beatIntensity,
      beatPhase: beat.beatPhase,
      bpm: beat.bpm,
      frame: this.frame,
    };
    return this.lastState;
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    try {
      this.source.disconnect();
    } catch (error) {
      console.error("[MCAV] Audio source disconnect failed:", error);
    }
    for (const track of this.stream.getTracks()) track.stop();
    this.context.close().catch((error) => {
      console.error("[MCAV] AudioContext close failed:", error);
    });
  }
}

/** Ask for the microphone. Throws a user-readable Error on failure. */
export async function requestMicrophoneStream(): Promise<MediaStream> {
  if (!navigator.mediaDevices?.getUserMedia) {
    throw new Error("This browser does not support microphone capture.");
  }
  try {
    return await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false,
      },
      video: false,
    });
  } catch (error) {
    throw new Error(describeMediaError(error, "microphone"));
  }
}

/**
 * Ask to share a browser tab with its audio. Chrome and Edge support tab
 * audio; the visitor has to tick "Share tab audio" in the picker.
 */
export async function requestTabAudioStream(): Promise<MediaStream> {
  if (!navigator.mediaDevices?.getDisplayMedia) {
    throw new Error("This browser does not support tab audio capture. Try Chrome or Edge.");
  }
  let stream: MediaStream;
  try {
    stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: true });
  } catch (error) {
    throw new Error(describeMediaError(error, "tab audio"));
  }
  // We only want the audio; drop the video track immediately.
  for (const track of stream.getVideoTracks()) {
    track.stop();
    stream.removeTrack(track);
  }
  if (stream.getAudioTracks().length === 0) {
    throw new Error('No audio was shared. Pick a tab and tick "Share tab audio" in the dialog.');
  }
  return stream;
}

function describeMediaError(error: unknown, what: string): string {
  const name = error instanceof DOMException ? error.name : "";
  switch (name) {
    case "NotAllowedError":
    case "SecurityError":
      return `Permission to use the ${what} was denied.`;
    case "NotFoundError":
      return `No ${what} device was found.`;
    case "NotReadableError":
      return `The ${what} is in use by another application.`;
    default:
      return error instanceof Error && error.message
        ? `Could not start ${what}: ${error.message}`
        : `Could not start ${what}.`;
  }
}
