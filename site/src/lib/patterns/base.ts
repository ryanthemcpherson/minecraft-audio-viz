/**
 * Pattern Engine - Base Types
 * Shared types for the Lua-backed pattern system.
 */

export interface EntityData {
  id: string;
  x: number;
  y: number;
  z: number;
  scale: number;
  band: number;
  visible: boolean;
}

export interface AudioState {
  bands: number[]; // 5 frequency bands (bass, low-mid, mid, high-mid, high)
  amplitude: number; // Overall amplitude 0-1
  isBeat: boolean; // Beat detected this frame
  beatIntensity: number; // Beat strength 0-1
  frame: number; // Frame counter
}

export interface PatternConfig {
  entityCount: number;
  zoneSize: number;
  beatBoost: number;
  baseScale: number;
  maxScale: number;
}

export const DEFAULT_CONFIG: PatternConfig = {
  entityCount: 32,
  zoneSize: 10,
  beatBoost: 1.5,
  baseScale: 0.2,
  maxScale: 1.0,
};
