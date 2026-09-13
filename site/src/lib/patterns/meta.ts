// AUTO-GENERATED from patterns/*.lua — DO NOT EDIT
// Run: node scripts/generate-patterns.mjs
// Metadata only (no Lua sources). Safe to import from server components.

export interface PatternMetaDef {
  id: string;
  name: string;
  description: string;
  category: string;
  staticCamera: boolean;
  startBlocks: number | null;
}

export const CATEGORY_ORDER = ["Original","Mainstage","Epic","Cosmic","Organic","Spectrum"] as const;

export const PATTERN_CATEGORIES: string[] = ["Original","Mainstage","Epic","Cosmic","Organic","Spectrum"];

export const PATTERN_COUNT = 41;

export const PATTERN_META: PatternMetaDef[] = [
  {
    "id": "bpm_pulse",
    "name": "BPM Pulse",
    "description": "Sphere of blocks that pulse in sync with the beat phase",
    "category": "Original",
    "staticCamera": false,
    "startBlocks": 64
  },
  {
    "id": "bpm_strobe",
    "name": "BPM Strobe",
    "description": "Ring of blocks with visibility and scale locked to beat subdivisions",
    "category": "Original",
    "staticCamera": false,
    "startBlocks": 32
  },
  {
    "id": "columns",
    "name": "Floating Platforms",
    "description": "6 levitating platforms - one per frequency",
    "category": "Original",
    "staticCamera": false,
    "startBlocks": 50
  },
  {
    "id": "explode",
    "name": "Supernova",
    "description": "Explosive burst on beats - 3D shockwave",
    "category": "Original",
    "staticCamera": false,
    "startBlocks": 64
  },
  {
    "id": "heartbeat",
    "name": "Breathing Cube",
    "description": "Rotating cube vertices - expands with beats",
    "category": "Original",
    "staticCamera": false,
    "startBlocks": 64
  },
  {
    "id": "matrix",
    "name": "Fountain",
    "description": "Upward spray with gravity arcs",
    "category": "Original",
    "staticCamera": false,
    "startBlocks": 64
  },
  {
    "id": "orbit",
    "name": "Atom Model",
    "description": "Nucleus + electrons on 3D orbital planes",
    "category": "Original",
    "staticCamera": false,
    "startBlocks": 40
  },
  {
    "id": "ring",
    "name": "Expanding Sphere",
    "description": "3D sphere that breathes and pulses",
    "category": "Original",
    "staticCamera": false,
    "startBlocks": 64
  },
  {
    "id": "spectrum",
    "name": "Stacked Tower",
    "description": "Spiraling vertical tower - blocks orbit and bounce",
    "category": "Original",
    "staticCamera": false,
    "startBlocks": 64
  },
  {
    "id": "wave",
    "name": "DNA Helix",
    "description": "Double helix spiral - rotates and stretches",
    "category": "Original",
    "staticCamera": false,
    "startBlocks": 90
  },
  {
    "id": "dropsequence",
    "name": "Drop Sequence",
    "description": "EDM build-up tension over 16 beats, then explosive drop — the tension-release cycle",
    "category": "Mainstage",
    "staticCamera": true,
    "startBlocks": 80
  },
  {
    "id": "laserfan",
    "name": "Laser Fan",
    "description": "Floor-origin laser beams sweep in synchronized arcs and freeze on beat",
    "category": "Mainstage",
    "staticCamera": true,
    "startBlocks": 96
  },
  {
    "id": "ledwall",
    "name": "LED Wall",
    "description": "Giant LED screen with spectrum bars, waveform, color wash, and beat geometry modes",
    "category": "Mainstage",
    "staticCamera": true,
    "startBlocks": 100
  },
  {
    "id": "movingheads",
    "name": "Moving Heads",
    "description": "Concert moving-head lights with sweeping beams and ballyhoo snap on beat",
    "category": "Mainstage",
    "staticCamera": true,
    "startBlocks": 96
  },
  {
    "id": "pyro",
    "name": "Pyro",
    "description": "Beat-triggered firework bursts with ballistic physics and gravity",
    "category": "Mainstage",
    "staticCamera": true,
    "startBlocks": 100
  },
  {
    "id": "shockwave",
    "name": "Shockwave",
    "description": "Expanding ring pulses radiate from center on each beat",
    "category": "Mainstage",
    "staticCamera": true,
    "startBlocks": 80
  },
  {
    "id": "strobe",
    "name": "Strobe Wall",
    "description": "Full-zone grid that flashes on/off in sync with beats — 4 strobe modes cycle automatically",
    "category": "Mainstage",
    "staticCamera": true,
    "startBlocks": 96
  },
  {
    "id": "crown",
    "name": "Crown",
    "description": "Floating royal crown with 5 frequency-reactive spikes and glowing jewels",
    "category": "Epic",
    "staticCamera": false,
    "startBlocks": 96
  },
  {
    "id": "dragon",
    "name": "Dragon",
    "description": "Fearsome dragon head with spread wings, reactive jaw, glowing eyes, and beat-driven wing flaps",
    "category": "Epic",
    "staticCamera": false,
    "startBlocks": 160
  },
  {
    "id": "fist",
    "name": "Raised Fist",
    "description": "Concert fist pumping on the beat with curled fingers and knuckle glow",
    "category": "Epic",
    "staticCamera": false,
    "startBlocks": 128
  },
  {
    "id": "galaxy",
    "name": "Galaxy",
    "description": "Spiral galaxy - cosmic visualization",
    "category": "Epic",
    "staticCamera": false,
    "startBlocks": 96
  },
  {
    "id": "laser",
    "name": "Laser Array",
    "description": "Laser beams shooting from center",
    "category": "Epic",
    "staticCamera": false,
    "startBlocks": 64
  },
  {
    "id": "mushroom",
    "name": "Mushroom",
    "description": "Psychedelic toadstool with spots, gills, and spores",
    "category": "Epic",
    "staticCamera": false,
    "startBlocks": 96
  },
  {
    "id": "phoenix",
    "name": "Phoenix",
    "description": "Fiery phoenix in flight with flapping wings and fire trail",
    "category": "Epic",
    "staticCamera": false,
    "startBlocks": 128
  },
  {
    "id": "pyramid",
    "name": "Pyramid",
    "description": "Egyptian pyramid - inverts on drops",
    "category": "Epic",
    "staticCamera": false,
    "startBlocks": 64
  },
  {
    "id": "sacred",
    "name": "Sacred Geometry",
    "description": "Morphing platonic solids - icosahedron",
    "category": "Epic",
    "staticCamera": false,
    "startBlocks": 96
  },
  {
    "id": "skull",
    "name": "Skull",
    "description": "Clean anatomical skull with animated jaw and glowing eyes",
    "category": "Epic",
    "staticCamera": false,
    "startBlocks": 160
  },
  {
    "id": "sword",
    "name": "Sword",
    "description": "Giant floating sword with energy pulses traveling up the blade on beat",
    "category": "Epic",
    "staticCamera": false,
    "startBlocks": 96
  },
  {
    "id": "vortex",
    "name": "Vortex",
    "description": "Swirling tunnel - spiral into infinity",
    "category": "Epic",
    "staticCamera": false,
    "startBlocks": 80
  },
  {
    "id": "wormhole",
    "name": "Wormhole Portal",
    "description": "Infinite tunnel - rings fly toward you",
    "category": "Epic",
    "staticCamera": false,
    "startBlocks": 108
  },
  {
    "id": "blackhole",
    "name": "Black Hole",
    "description": "Accretion disk with jets - gravity visualization",
    "category": "Cosmic",
    "staticCamera": false,
    "startBlocks": 96
  },
  {
    "id": "crystal",
    "name": "Crystal Growth",
    "description": "Fractal crystal with recursive branching",
    "category": "Cosmic",
    "staticCamera": false,
    "startBlocks": 80
  },
  {
    "id": "mandala",
    "name": "Mandala",
    "description": "Sacred geometry rings - frequency mapped",
    "category": "Cosmic",
    "staticCamera": false,
    "startBlocks": 60
  },
  {
    "id": "nebula",
    "name": "Nebula",
    "description": "Cosmic gas cloud with drifting particles",
    "category": "Cosmic",
    "staticCamera": false,
    "startBlocks": 104
  },
  {
    "id": "tesseract",
    "name": "Tesseract",
    "description": "4D hypercube rotating through dimensions",
    "category": "Cosmic",
    "staticCamera": false,
    "startBlocks": 96
  },
  {
    "id": "aurora",
    "name": "Aurora",
    "description": "Northern lights curtains - flowing waves",
    "category": "Organic",
    "staticCamera": false,
    "startBlocks": 64
  },
  {
    "id": "fireflies",
    "name": "Fireflies",
    "description": "Swarm of synchronized flashing lights",
    "category": "Organic",
    "staticCamera": false,
    "startBlocks": 40
  },
  {
    "id": "ocean",
    "name": "Ocean Waves",
    "description": "Water surface with splashes and ripples",
    "category": "Organic",
    "staticCamera": false,
    "startBlocks": 100
  },
  {
    "id": "bars",
    "name": "Spectrum Bars",
    "description": "Classic vertical frequency bars",
    "category": "Spectrum",
    "staticCamera": true,
    "startBlocks": 96
  },
  {
    "id": "circle",
    "name": "Spectrum Circle",
    "description": "Radial frequency bars in a circle",
    "category": "Spectrum",
    "staticCamera": true,
    "startBlocks": 100
  },
  {
    "id": "tubes",
    "name": "Spectrum Tubes",
    "description": "3D cylindrical frequency tubes",
    "category": "Spectrum",
    "staticCamera": true,
    "startBlocks": 120
  }
];
