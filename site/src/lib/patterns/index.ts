/**
 * Pattern Registry
 * Central index of all visualization patterns with metadata.
 */

import type { VisualizationPattern } from "./base";

// Original patterns
import {
  StackedTower,
  ExpandingSphere,
  DNAHelix,
  Supernova,
  FloatingPlatforms,
  AtomModel,
  Fountain,
  BreathingCube,
} from "./original";

// Epic patterns
import {
  Mushroom,
  Skull,
  SacredGeometry,
  Vortex,
  Pyramid,
  GalaxySpiral,
  LaserArray,
  WormholePortal,
} from "./epic";

// Cosmic / Geometric patterns
import {
  BlackHole,
  Nebula,
  Mandala,
  Tesseract,
  CrystalGrowth,
} from "./cosmic";

// Organic / Nature patterns
import { Aurora, OceanWaves, Fireflies } from "./organic";

// Spectrum analyzer patterns
import { SpectrumBars, SpectrumTubes, SpectrumCircle } from "./spectrum";

export interface PatternMeta {
  id: string;
  name: string;
  description: string;
  category: string;
  staticCamera: boolean;
  createPattern: () => VisualizationPattern;
}

type PatternClass = {
  new (config?: Record<string, unknown>): VisualizationPattern;
  patternName: string;
  description: string;
};

interface PatternEntry {
  id: string;
  cls: PatternClass;
  category: string;
  staticCamera?: boolean;
}

const REGISTRY: PatternEntry[] = [
  // Original (8)
  { id: "spectrum", cls: StackedTower, category: "Original" },
  { id: "ring", cls: ExpandingSphere, category: "Original" },
  { id: "wave", cls: DNAHelix, category: "Original" },
  { id: "explode", cls: Supernova, category: "Original" },
  { id: "columns", cls: FloatingPlatforms, category: "Original" },
  { id: "orbit", cls: AtomModel, category: "Original" },
  { id: "matrix", cls: Fountain, category: "Original" },
  { id: "heartbeat", cls: BreathingCube, category: "Original" },

  // Epic (8)
  { id: "mushroom", cls: Mushroom, category: "Epic" },
  { id: "skull", cls: Skull, category: "Epic" },
  { id: "sacred", cls: SacredGeometry, category: "Epic" },
  { id: "vortex", cls: Vortex, category: "Epic" },
  { id: "pyramid", cls: Pyramid, category: "Epic" },
  { id: "galaxy", cls: GalaxySpiral, category: "Epic" },
  { id: "laser", cls: LaserArray, category: "Epic" },
  { id: "wormhole", cls: WormholePortal, category: "Epic" },

  // Cosmic / Geometric (5)
  { id: "blackhole", cls: BlackHole, category: "Cosmic" },
  { id: "nebula", cls: Nebula, category: "Cosmic" },
  { id: "mandala", cls: Mandala, category: "Cosmic" },
  { id: "tesseract", cls: Tesseract, category: "Cosmic" },
  { id: "crystal", cls: CrystalGrowth, category: "Cosmic" },

  // Organic / Nature (3)
  { id: "aurora", cls: Aurora, category: "Organic" },
  { id: "ocean", cls: OceanWaves, category: "Organic" },
  { id: "fireflies", cls: Fireflies, category: "Organic" },

  // Spectrum Analyzers (3) — fixed camera, no rotation
  { id: "bars", cls: SpectrumBars, category: "Spectrum", staticCamera: true },
  { id: "tubes", cls: SpectrumTubes, category: "Spectrum", staticCamera: true },
  { id: "circle", cls: SpectrumCircle, category: "Spectrum", staticCamera: true },
];

export function listPatterns(): PatternMeta[] {
  return REGISTRY.map((entry) => ({
    id: entry.id,
    name: entry.cls.patternName,
    description: entry.cls.description,
    category: entry.category,
    staticCamera: entry.staticCamera ?? false,
    createPattern: () => new entry.cls(),
  }));
}

export function getPattern(name: string): VisualizationPattern {
  const entry = REGISTRY.find((e) => e.id === name);
  if (!entry) return new StackedTower();
  return new entry.cls();
}

export { type VisualizationPattern } from "./base";
