# MCAV Creative Vision

> Minecraft Audio Visualizer -- where sound becomes architecture and music becomes world.

This document is both an inspiration board and a practical feature backlog. Every idea here is grounded in the existing pattern engine (27 patterns, 5-band FFT, beat detection, crossfade transitions) and designed so a contributor can pick up any section and start building.

---

## 1. Killer Demo Scenarios

Five showcase scenarios designed to produce jaw-dropping demo videos. Each one is buildable with the current architecture plus targeted new work.

### 1.1 "The Drop"

**Concept:** A single continuous shot that follows a DJ buildup to a massive bass drop. The entire Minecraft world appears to hold its breath, then detonates.

**Setting:** Open plains at sunset. A single visualization zone centered on a raised obsidian platform, 40x40 blocks. Audience of player NPCs surrounding the stage.

**Music:** Melodic dubstep or future bass with a clear 16-bar buildup and a devastating drop.

**Pattern sequence:**
- Buildup (0:00-0:45): `aurora` with low entity count, gentle sway. Bands 3-4 (mids/highs) drive subtle shimmer. Entity scale stays below 0.3.
- Tension (0:45-1:00): Crossfade to `sacred` geometry, entities slowly contracting inward. Beat detection threshold raised so nothing triggers. The world goes quiet. Particle effects cut to zero.
- Drop (1:00): Instant switch to `explode` (Supernova) at max entity count (64+). Beat boost cranked to 2.0. `BassFlameEffect` and `BeatRingEffect` fire simultaneously. Entity scale jumps to max.
- Sustain (1:00-1:30): Crossfade from `explode` to `galaxy` spiral, maintaining high energy. `SpectrumDustEffect` fills the air. Entities maintain 1.5x beat boost.
- Outro (1:30-2:00): Slow crossfade to `nebula` with falling entity count. Particles fade. Peaceful.

**Camera:** Single dolly shot starting low behind the audience, slowly rising during buildup, then a fast snap-zoom into the center at the drop, followed by a spiraling orbit during sustain.

**What makes it special:** The contrast. Thirty seconds of near-silence and contraction make the explosion visceral. The Supernova pattern's physics-based outward blast combined with flame particles creates a moment that looks impossible inside Minecraft.

---

### 1.2 "Neon Cathedral"

**Concept:** An architectural visualization that builds itself from nothing over the course of a progressive house track. The structure grows upward like a cathedral being constructed in real time, with each musical phrase adding a new structural layer.

**Setting:** Flat void world (barrier floor, black concrete walls far away). Night time, no moon. The only light comes from the visualization itself. Render distance set high so the glow is visible from far away.

**Music:** Progressive house or melodic techno with layered arrangement -- each new instrument adds a visual layer.

**Pattern sequence:**
- Foundation (0:00-0:30): `bars` (SpectrumBars) in a cross pattern on the ground. Only bass band active, pulsing pillars that rise 2-3 blocks.
- Walls (0:30-1:00): Crossfade to `crystal` (CrystalGrowth) with growth rate tied to low-mid energy. Branches grow upward and outward, forming wall-like structures.
- Arches (1:00-1:30): Add a second zone running `tesseract`, positioned above the crystal zone. The rotating 4D geometry forms arch-like shapes overhead.
- Rose Window (1:30-2:00): Third zone with `mandala` positioned at the far end. The symmetric rotating pattern resembles a gothic rose window. High-frequency bands drive the intricate inner detail.
- Spire (2:00-2:30): Central zone switches to `vortex`, pulling entities upward into a spire shape. `SoulFireEffect` particles stream upward along the vortex axis.
- Completion (2:30-3:00): All zones running simultaneously. `AmbientMistEffect` fills the interior. Beat rings pulse outward from the mandala.

**Camera:** Slow walk-through, starting outside looking at the foundation, gradually entering the space as it builds, ending with a look straight up through the spire at the stars.

**What makes it special:** Multi-zone composition. Each zone is an independent visualization, but together they form coherent architecture. The building metaphor (foundation, walls, arches, window, spire) gives the video a narrative arc that mirrors the musical arrangement.

---

### 1.3 "Rave Cave"

**Concept:** A natural Minecraft cave system converted into an underground reactive nightclub. Stalactites pulse, lava pools throb with bass, and the entire cave breathes with the rhythm.

**Setting:** A large natural cave (amplified with WorldEdit to create a cavernous dome). Lava pools at the base. Glow lichen on walls. Multiple visualization zones placed at different elevations within the cave.

**Music:** Dark techno or drum and bass. Relentless four-on-the-floor kick pattern with evolving synth textures.

**Pattern sequence (multi-zone):**
- Zone 1 (ceiling): `aurora` mapped to the cave ceiling, hanging like luminous curtains between stalactites. Slow movement, high-frequency reactive.
- Zone 2 (center): `laser` (LaserArray) positioned in the middle of the cave, firing beams that sweep across the space. Beat-synced direction changes.
- Zone 3 (floor): `ocean` (OceanWaves) mapped flat, creating a reactive dance floor effect. Bass drives wave height.
- Zone 4 (alcove): `fireflies` scattered throughout, quantity surging on beats.
- Zone 5 (pillar): `vortex` wrapped around a central stalagmite, spinning faster with BPM.

**Camera:** POV of a player walking through the cave entrance and descending into the rave. Cut between wide establishing shots and close-up details of individual reactive elements. Final shot: looking back at the entrance from deep inside, the exit a small rectangle of light against the overwhelming visual chaos.

**What makes it special:** Environmental integration. The visualizations are not floating in empty space; they interact with existing Minecraft terrain. The cave provides natural occlusion, revealing different visual layers as the viewer moves through the space. It demonstrates that MCAV is not just a visualizer -- it is a world-building tool.

---

### 1.4 "Battle of the DJs"

**Concept:** Two DJs connected via the VJ server, each controlling their own visualization zone side by side. A competitive format where the audience (Minecraft players) can see both visualizations simultaneously and vote on which is more impressive.

**Setting:** A custom arena divided down the middle. Left half: blue-themed zone. Right half: red-themed zone. A shared scoreboard in the center. Spectator seating surrounds both zones.

**Music:** Each DJ plays their own track. The VJ server routes DJ-1's audio state to the left zone and DJ-2's audio state to the right zone. Both play simultaneously -- controlled audio bleed is part of the chaos.

**Pattern sequence:**
- DJ-1 might run: `galaxy` -> crossfade to `blackhole` -> `tesseract` -> `skull`
- DJ-2 might run: `mushroom` -> crossfade to `sacred` -> `crystal` -> `laser`
- Both DJs have full control of their zone's pattern, preset, entity count, and particle effects via the admin panel.
- A VJ oversees both and can trigger shared effects (fireworks, screen flash) at key moments.

**Camera:** Split-screen with occasional full-width cuts to whichever DJ is peaking. Overhead drone shots showing both zones simultaneously. Close-ups of audience reactions.

**What makes it special:** This is the multi-DJ architecture demonstrated as a competitive sport. It shows off the VJ server's ability to route multiple audio streams, the admin panel's per-zone control, and the visual diversity of the pattern library. No two battles would ever look the same.

---

### 1.5 "Pocket Concert"

**Concept:** A tiny, intimate visualization in a small room -- a single player's bedroom or a cozy 8x8 space. Proves that MCAV does not need a massive arena. The visualizer runs at low entity count (8-12) and the effect is delicate, personal, meditative.

**Setting:** A small decorated room: bookshelves, a crafting table, a bed, warm lighting from lanterns. The visualization occupies a 3x3 area on a desk or an item frame.

**Music:** Lo-fi hip-hop, ambient, or acoustic. Gentle beats, soft pads, minimal percussion.

**Pattern sequence:**
- Start: `fireflies` at entity count 8. Just a few gently floating lights that pulse with the kick.
- Transition: Slow crossfade (3 seconds) to `aurora` at entity count 10. Soft curtains of light ripple across the small space.
- Middle: `crystal` at entity count 12 with bass weight turned down and high-frequency sensitivity boosted. Delicate branching structures grow slowly.
- End: Crossfade back to `fireflies` at count 6. The lights dim. The room returns to lantern glow.

**Camera:** Static shot from one corner of the room, like a security camera or a cozy webcam stream. Minimal movement -- let the visualization breathe. Occasional slow zoom into the center of the pattern.

**What makes it special:** Counter-programming. While every other demo shouts, this one whispers. It demonstrates that the system scales down gracefully, that the patterns are beautiful even at minimal entity counts, and that audio visualization does not need to be overwhelming to be compelling. This is the demo that makes someone think "I want this in my world."

---

## 2. New Pattern Ideas

Fifteen new visualization patterns to extend the current library of 27. Each includes the class name, a description of the visual behavior, which frequency bands drive which parameters, and estimated implementation complexity.

### 2.1 AudioTerrain
**Registry key:** `terrain`

A flat grid of entities that deforms vertically to create a terrain surface. Each entity represents a point on a heightmap. Height is driven by the corresponding frequency band with spatial smoothing between neighbors.

- **Bass** (band 0): Drives broad, rolling hills at the edges of the grid
- **Low-mid** (band 1): Medium undulations in the mid-ring
- **Mid** (band 2): Central peak height
- **High-mid/High** (bands 3-4): Fine surface ripple across all points

The terrain rotates slowly on the Y axis. On beats, a shockwave ripple propagates outward from the center, temporarily raising all points. Uses `simple_noise()` for organic variation.

**Complexity:** Low-medium. Grid layout is straightforward; the interesting part is the neighbor-smoothed heightmap and the beat shockwave propagation.

---

### 2.2 CityScape
**Registry key:** `city`

A grid of rectangular towers (2-3 entities stacked per tower) that rise and fall like a reactive skyline. Each column maps to a frequency band. Towers on the perimeter are shorter (high freq), towers near center are taller (bass).

- **Bass**: Central towers surge upward, scale increases
- **Mid bands**: Ring of medium towers breathes in/out
- **High**: Edge towers flicker rapidly with small height changes
- On beats, all towers jump up simultaneously and slam back down

Windows can be simulated by alternating band assignments within a single tower, creating color striping. The city slowly rotates so all sides are visible.

**Complexity:** Low. Essentially a 2D grid of stacked bars with per-band height mapping. Good starter pattern for new contributors.

---

### 2.3 Jellyfish
**Registry key:** `jellyfish`

An organic pulsing creature with a dome (bell) and trailing tentacles. The bell contracts and expands rhythmically with the beat, and tentacles trail behind with physics-based sway.

- **Bass**: Bell contraction/expansion amplitude
- **Low-mid**: Tentacle sway intensity
- **Mid**: Bell rotation speed
- **High-mid/High**: Tentacle tip shimmer (small entities at tentacle ends flicker)

The bell is constructed like a flattened `ExpandingSphere` hemisphere. Tentacles are chains of entities hanging from the bell rim, with each link following the one above it with damped spring physics. On beats, the bell snaps shut and tentacles flare outward.

**Complexity:** Medium. Tentacle chain physics requires per-entity state tracking (position, velocity) similar to the `Fountain` pattern.

---

### 2.4 TreeOfLife
**Registry key:** `tree`

A branching tree structure that grows upward with sustained audio energy. Branches split at threshold amplitude levels. Leaves (small entities at branch tips) bloom when high frequencies are present.

- **Bass**: Trunk thickness and sway
- **Low-mid**: Primary branch growth rate
- **Mid**: Secondary branch growth rate
- **High-mid**: Leaf bloom (entities appear at branch tips)
- **High**: Leaf shimmer (scale oscillation on existing leaf entities)

Growth is persistent across frames: the tree does not reset each frame but accumulates structure over time. Silence causes leaves to fall (entities drift downward). A sustained bass note makes the trunk thicken and sway. Uses recursive branching with angle variation based on `simple_noise()`.

**Complexity:** High. Recursive branching with persistent state, growth accumulation, and leaf lifecycle management. Worth it for the visual payoff.

---

### 2.5 LorenzAttractor
**Registry key:** `lorenz`

Entities trace paths through a Lorenz strange attractor (the butterfly-shaped chaotic system). Each entity follows the differential equations with slightly different initial conditions, creating divergent trails that visualize sensitivity to initial conditions.

- **Bass**: Controls the Lorenz `sigma` parameter, affecting orbit width
- **Low-mid**: Controls the `rho` parameter, affecting attractor height
- **Mid**: Controls the `beta` parameter, affecting the waist between lobes
- **High**: Simulation speed (dt multiplier)
- On beats, all entities receive a position perturbation, causing dramatic divergence

The attractor is scaled and centered within the 0-1 normalized space.

**Complexity:** Medium. The math is well-documented. The challenge is scaling the attractor coordinates to fit the display space.

---

### 2.6 MengerSponge
**Registry key:** `menger`

A recursive Menger sponge fractal built from entities. The recursion depth breathes with audio energy: at low amplitude, only level 1 is visible (a cube with holes). As energy increases, level 2 subdivisions appear. At peak energy, level 3 detail emerges.

- **Bass**: Overall sponge scale (breathing)
- **Mid**: Recursion depth (continuous, not integer -- entities fade in/out as depth increases)
- **High**: Rotation speed on all three axes
- On beats, the sponge inverts briefly (holes become solid, solid becomes holes -- achieved by swapping visibility flags)

Uses the `BreathingCube`'s rotation infrastructure. Entity budget is managed by LOD.

**Complexity:** Medium-high.

---

### 2.7 LaserFan
**Registry key:** `laserfan`

A laser show simulation with multiple beam sources that sweep, cross, and form geometric patterns. Beams are represented as lines of tightly-spaced small entities.

- **Bass**: Beam sweep speed and range
- **Low-mid**: Number of active beams
- **Mid**: Beam convergence angle
- **High**: Beam flicker rate
- On beats, all beams snap to a starburst pattern then slowly resume sweeping

Differs from the existing `LaserArray` by focusing on smooth sweeping motion and geometric convergence rather than static beam placement.

**Complexity:** Low-medium.

---

### 2.8 Waterfall
**Registry key:** `waterfall`

Cascading particles flowing downward from a top edge, pooling at the bottom. Water volume and speed increase with bass energy. Mist rises from the impact point.

- **Bass**: Water volume (number of visible entities in the falling section)
- **Low-mid**: Fall speed
- **Mid**: Splash radius at the bottom
- **High-mid**: Mist height
- **High**: Surface ripple in the pool

Uses the same physics model as `Fountain` but inverted. On beats, the waterfall surges.

**Complexity:** Low-medium. Heavily reuses `Fountain` physics.

---

### 2.9 Heartbeat
**Registry key:** `pulse`

An EKG/heart monitor visualization. Entities trace the classic PQRST waveform of a heartbeat, with the waveform scrolling horizontally. The heart rate synchronizes to the detected BPM.

- **Bass**: QRS complex amplitude (the tall spike)
- **Low-mid**: P-wave and T-wave amplitude
- **Mid**: Baseline wander
- **High**: High-frequency noise on the trace
- On beats, the QRS complex fires (a sharp upward spike followed by a dip)

Entities are distributed along a horizontal line. Each entity's Y position is determined by where it falls on the scrolling PQRST waveform.

**Complexity:** Low. A 1D wave function mapped to entity Y positions.

---

### 2.10 NeuralNetwork
**Registry key:** `neural`

A network of nodes (larger entities) connected by edges (lines of small entities). Nodes fire when audio energy in their assigned band exceeds a threshold, and the firing propagates along connections with a delay.

- **Bass**: Input layer activation
- **Low-mid through High**: Each band activates a different column of nodes
- On beats, all nodes fire simultaneously and connections flash

Layout: 3-4 layers of nodes arranged in a feed-forward topology. Input layer (5 nodes, one per band) on the left, hidden layers in the middle, output layer on the right.

**Complexity:** Medium.

---

### 2.11 Kaleidoscope
**Registry key:** `kaleidoscope`

Symmetric rotating patterns with 4-fold, 6-fold, or 8-fold symmetry. A small number of seed entities move freely, and their positions are mirrored/rotated to fill the display with symmetric copies.

- **Bass**: Seed entity orbit radius
- **Low-mid**: Symmetry order (4/6/8, cycling through as energy changes)
- **Mid**: Seed movement speed
- **High**: Rotation speed of the entire pattern
- On beats, the symmetry order jumps and the pattern briefly fragments before re-stabilizing

For entity count N, the system uses N/symmetry_order seed entities and mirrors the rest.

**Complexity:** Low-medium. The visual impact is disproportionately high relative to the implementation effort.

---

### 2.12 AccretionDisk
**Registry key:** `accretion`

An accretion disk visualization (distinct from the existing `BlackHole` pattern). Entities orbit a central point in a flat disk, with orbital speed increasing closer to the center (Keplerian). Material spirals inward over time and jets shoot upward from the poles.

- **Bass**: Jet intensity (entities shooting up/down from center along Y axis)
- **Low-mid**: Disk thickness (vertical spread of orbiting entities)
- **Mid**: Accretion rate (how fast entities spiral inward)
- **High-mid/High**: Hot inner disk glow
- On beats, a jet eruption fires a burst of entities along the polar axis

Entity distribution: 70% in the disk, 15% in each jet.

**Complexity:** Medium. Jet physics reuses existing fountain code.

---

### 2.13 NorthernLights
**Registry key:** `northernlights`

Shimmering curtains of light that hang from the top of the display space, swaying with audio. Vertical curtains instead of horizontal sheets, with pronounced wave motion and color banding.

- **Bass**: Curtain sway amplitude (large, slow lateral movement)
- **Low-mid**: Curtain length (how far down the entities extend)
- **Mid**: Wave speed (how fast the curtain ripples propagate)
- **High-mid**: Brightness (entity scale)
- **High**: Shimmer rate (rapid small-scale oscillation)

Multiple curtain sheets at different Z depths, each with independent wave phases.

**Complexity:** Low.

---

### 2.14 PixelArt
**Registry key:** `pixel`

Retro 8-bit style reactive art. A grid of entities acts as a pixel display, rendering simple recognizable shapes (musical notes, speakers, headphones, waveforms) that animate with the beat.

- **Bass**: Current icon selection (cycles through a sprite sheet of 4-5 icons on strong beats)
- **Low-mid**: Icon scale/zoom
- **Mid**: Scanline effect speed
- **High**: Static/noise overlay intensity
- On beats, the entire grid flashes and the icon transitions (old icon scatters, new icon assembles)

Icons are stored as simple bitmap arrays (8x8 or 12x12). Transition uses scatter/reassemble animation similar to `Supernova`.

**Complexity:** Medium.

---

### 2.15 SoundGarden
**Registry key:** `garden`

A garden of flowers that bloom and sway with frequency content. Each flower occupies a small cluster of entities (stem + petals). Flowers grow over time with sustained audio and wilt during silence.

- **Bass**: Ground-level leaf/grass entity movement (wind sway)
- **Low-mid**: Stem growth rate and sway
- **Mid**: Petal bloom (entities at the top of stems spread outward in a circle)
- **High-mid**: Petal shimmer (scale oscillation)
- **High**: Seed/spore entities (tiny entities drifting upward from bloomed flowers)

The garden starts empty. As audio plays, stems grow upward. On beats, all flowers bounce and release spore entities.

**Complexity:** Medium-high. Lifecycle management (grow, bloom, wilt) requires per-flower state.

---

### Pattern Priority Matrix

| Pattern | Visual Impact | Code Complexity | Reuses Existing Code | Priority |
|---------|--------------|-----------------|---------------------|----------|
| AudioTerrain | High | Low-Med | Grid math only | P1 |
| CityScape | Medium | Low | SpectrumBars grid | P1 |
| Jellyfish | High | Medium | Fountain physics | P1 |
| TreeOfLife | Very High | High | New recursive system | P2 |
| LorenzAttractor | High | Medium | Minimal reuse | P2 |
| MengerSponge | High | Med-High | BreathingCube rotation | P2 |
| LaserFan | Medium | Low-Med | LaserArray geometry | P1 |
| Waterfall | Medium | Low-Med | Fountain physics | P1 |
| Heartbeat | Medium | Low | 1D wave math | P1 |
| NeuralNetwork | High | Medium | Node layout static | P2 |
| Kaleidoscope | Very High | Low-Med | Mirror math | P1 |
| AccretionDisk | High | Medium | Fountain jets | P2 |
| NorthernLights | Medium | Low | Aurora approach | P1 |
| PixelArt | High | Medium | Supernova scatter | P2 |
| SoundGarden | Very High | Med-High | New lifecycle system | P3 |

---

## 3. Interactive Experiences

Designs for immersive experiences where Minecraft players are inside the visualization, not just watching it.

### 3.1 Walk-Through Audio Tunnel

A long tunnel (50-100 blocks) where the walls, ceiling, and floor are all visualization zones. The player walks through a corridor of reactive light.

**Implementation:**
- 5 sequential zones along the tunnel length, each running a different pattern
- Zones crossfade into each other as the player walks (triggered by player position, not time)
- Zone 1 (entrance): `bars` on floor, minimal ceiling
- Zone 2: `aurora` on ceiling, `ocean` on floor
- Zone 3 (climax): `vortex` surrounding the player on all sides, `laser` beams crossing the path
- Zone 4: `nebula` filling the space, `fireflies` drifting
- Zone 5 (exit): `crystal` growing on walls, thinning out toward the exit

**New feature required:** Player-position-triggered zone activation. The plugin would need a proximity check that sends zone enable/disable messages based on player location. This is a lightweight addition to `ZoneManager`.

---

### 3.2 Reactive Dance Floor

A flat 16x16 grid of different colored blocks at ground level. When a player walks on a block, it lights up. When audio plays, the entire floor pulses in patterns. Player position influences the pattern.

**Implementation:**
- Map the `AudioTerrain` or `SpectrumBars` pattern to a floor plane
- Player position is injected into the audio state as a focal point: ripples emanate from where the player stands
- Multiple players create interference patterns (constructive/destructive wave interaction)

**New feature required:** Player position injection into the pattern engine. A new optional field `player_positions: List[Tuple[float, float]]` on `AudioState`.

---

### 3.3 Rhythm Game Integration

A beat-matching minigame where Display Entities serve as falling note highway targets.

**Implementation:**
- 5 lanes (one per band) where entities spawn at the top and fall toward a hit zone at the bottom
- Entities spawn on detected beats; band assignment determines lane
- Hit detection via pressure plate or interaction event
- Scoring: early, perfect, late, miss
- Visual feedback: successful hits trigger `BeatRingEffect`; misses cause entity shatter

**New feature required:** A rhythm game mode in the plugin with collision detection. Could be implemented as an alternative `RendererBackend`.

---

### 3.4 Audio Sculpt Mode

Players hold a "Sculpt Wand" and move it through the visualization zone. Entities near the wand are attracted to or repelled from it.

**Implementation:**
- Player wand position tracked and sent to the pattern engine as an attractor/repeller point
- Inverse-square force: close entities strongly affected, distant ones barely move
- Left-click attracts, right-click repels
- Audio-driven pattern continues underneath; sculpt forces add displacement
- Spring-back when sculpting stops

**New feature required:** A force injection system in the pattern base class. A new method `apply_external_forces(forces: List[ForcePoint])` that runs after `calculate_entities()`. Pattern-agnostic.

---

### 3.5 VR / First-Person Immersion Mode

The player at the center of the visualization zone, surrounded by entities on all sides.

**Implementation:**
- Visualization zone is a sphere centered on the player
- Look direction drives LOD (more detail in field of view)
- Stereo panning information drives left/right entity emphasis
- Close entities larger and more reactive; distant entities smaller

**New feature required:** Player look-direction injection (yaw/pitch) into the pattern engine via WebSocket.

---

## 4. Event Production Features

Professional features for using MCAV at live events, streams, and shows.

### 4.1 Cue Stacks and Show Sequencing

The existing `TimelineEngine` and `CueExecutor` provide the foundation. These extensions make it production-ready.

**Cue stack enhancements:**
- **Named cue stacks:** Multiple named stacks (intro, main, encore) that can be triggered independently
- **Conditional cues:** Cues that fire only if audio energy exceeds a threshold (wait for a natural beat before transitioning)
- **Loop cues:** A cue range that repeats N times or indefinitely (useful for ambient sections)
- **Follow cues:** Auto-advance after the current cue's duration expires, with optional delay
- **Manual override:** A "go" button that advances to the next cue regardless of timing

**Show file format example:**
```json
{
  "name": "Friday Night Show",
  "bpm_hint": 128,
  "stacks": {
    "intro": [
      {"time_ms": 0, "pattern": "aurora", "preset": "chill", "transition": "fade", "duration_ms": 2000},
      {"time_ms": 30000, "pattern": "crystal", "transition": "morph", "duration_ms": 3000}
    ],
    "main": [
      {"time_ms": 0, "pattern": "galaxy", "preset": "edm", "transition": "shatter"},
      {"time_ms": 60000, "pattern": "blackhole", "transition": "wipe"}
    ]
  }
}
```

---

### 4.2 Beat-Synced Transitions

Transitions that quantize to the detected beat grid so they always land on musically meaningful moments.

**Quantization modes:**
- **Next beat:** Transition starts on the very next detected beat
- **Next bar:** Transition starts on the next bar boundary (every 4 beats at detected BPM)
- **Next phrase:** Transition starts on the next 8-bar phrase boundary
- **Immediate:** No quantization, transition starts now (current behavior)

**Implementation:** The `CueExecutor` already tracks beat state. Add a `quantize` field to `Transition` that buffers the transition request until the quantization condition is met.

---

### 4.3 Transition Effects

Five new transition types beyond the current crossfade.

**Morph:** Both patterns run simultaneously. Entity positions are interpolated between old and new pattern positions using the easing function. Entities smoothly migrate from one formation to another.

**Shatter:** The old pattern's entities explode outward (using `Supernova` physics), then the new pattern's entities implode inward from scattered positions to their target locations.

**Wipe:** A plane sweeps across the display space. Entities behind the plane show the new pattern; entities ahead show the old pattern.

**Glitch:** Rapid random switching between old and new patterns at decreasing intervals. Includes random entity displacement during switches.

**Dissolve:** Random per-entity transition. Each entity independently switches from old to new pattern at a random time within the transition duration.

---

### 4.4 Multi-Zone Synchronized Shows

Coordinating multiple visualization zones to create coherent large-scale shows.

**Zone synchronization modes:**
- **Mirror:** Zone B mirrors Zone A's pattern and timing
- **Chase:** Zone B runs the same pattern as Zone A but with a fixed time offset (creates a wave across zones)
- **Complement:** Zone B automatically selects a pattern that contrasts Zone A (e.g., organic vs. geometric)
- **Independent:** Each zone runs its own pattern and timing (current behavior)

**Zone layout presets:**
- **Stage:** One large front zone + two smaller side zones
- **Surround:** Four zones forming a square around the audience
- **Tower:** Three zones stacked vertically
- **Corridor:** Five zones in a line (for the walk-through tunnel)

---

### 4.5 Audience Interaction

Systems for the audience to influence the show.

**In-game voting:**
- Players type `/vote <pattern_name>` to cast a vote
- After a configurable voting window (e.g., 30 seconds), the winning pattern is activated with a transition
- Results are displayed on a scoreboard or in chat
- VJ can veto or override if the winning pattern does not suit the current music

**Reaction particles:**
- Players can trigger personal particle effects by performing actions (jumping, crouching, attacking)
- These are distinct from the main visualization -- they are per-player flourishes

**Crowd energy meter:**
- The plugin tracks aggregate player activity (movement speed, jump frequency, chat rate) and computes a crowd energy value
- This value is sent to the pattern engine as an additional input
- High crowd energy could automatically increase beat sensitivity, entity count, or particle density
- Creates a feedback loop: the more excited the audience, the more intense the visualization

---

## 5. Art Direction Themes

Seven complete visual themes. Each theme defines a color mapping for the 5 frequency bands, recommended patterns, particle preferences, and environmental settings.

### 5.1 Cyberpunk

| Band | Frequency | Color | Hex |
|------|-----------|-------|-----|
| 0 | Bass | Deep hot pink | `#FF1493` |
| 1 | Low-mid | Electric violet | `#8B00FF` |
| 2 | Mid | Neon cyan | `#00BFFF` |
| 3 | High-mid | Warning orange | `#FF4500` |
| 4 | High | Pure white | `#FFFFFF` |

**Recommended patterns:** `laser`, `city` (CityScape), `neural` (NeuralNetwork), `tesseract`
**Particle effects:** `SpectrumDust` (pink/cyan), `BassFlame` (violet fire)
**Environment:** Night time. Rain. Black concrete ground. Obsidian structures. Fog close.
**Mood:** Aggressive, urban, electric. The city at 2 AM.

---

### 5.2 Vaporwave

| Band | Frequency | Color | Hex |
|------|-----------|-------|-----|
| 0 | Bass | Soft pink | `#FF71CE` |
| 1 | Low-mid | Pastel purple | `#B967FF` |
| 2 | Mid | Pastel teal | `#01CDFE` |
| 3 | High-mid | Soft yellow | `#FFFB96` |
| 4 | High | Mint green | `#05FFA1` |

**Recommended patterns:** `mandala`, `sacred`, `aurora`, `kaleidoscope`
**Particle effects:** `AmbientMist` (pink), `SpectrumDust` (teal/purple)
**Environment:** Sunset sky. Pink-stained glass structures. Water features. Smooth quartz.
**Mood:** Nostalgic, dreamy, soft. A sunset you half-remember.

---

### 5.3 Nature

| Band | Frequency | Color | Hex |
|------|-----------|-------|-----|
| 0 | Bass | Earth brown | `#8B4513` |
| 1 | Low-mid | Forest green | `#228B22` |
| 2 | Mid | Golden amber | `#FFD700` |
| 3 | High-mid | Sky blue | `#87CEEB` |
| 4 | High | Cloud white | `#FFFFFF` |

**Recommended patterns:** `tree` (TreeOfLife), `garden` (SoundGarden), `ocean`, `fireflies`, `aurora`
**Particle effects:** `AmbientMist` (green), `SpectrumDust` (gold)
**Environment:** Daytime. Flower forest or meadow biome. Flowing water. Gentle hills.
**Mood:** Organic, peaceful, alive. Growth over explosion. Breathing over pulsing.

---

### 5.4 Cosmic

| Band | Frequency | Color | Hex |
|------|-----------|-------|-----|
| 0 | Bass | Midnight blue | `#191970` |
| 1 | Low-mid | Deep indigo | `#4B0082` |
| 2 | Mid | Vivid violet | `#9400D3` |
| 3 | High-mid | Nebula teal | `#00CED1` |
| 4 | High | Star white | `#FFFAF0` |

**Recommended patterns:** `galaxy`, `blackhole`, `nebula`, `wormhole`, `lorenz` (LorenzAttractor), `accretion` (AccretionDisk)
**Particle effects:** `SoulFire` (blue), `SpectrumDust` (violet/white)
**Environment:** Void world or end dimension. Deep space skybox. Infinite darkness.
**Mood:** Vast, mysterious, awe-inspiring. Silence made visible.

---

### 5.5 Inferno

| Band | Frequency | Color | Hex |
|------|-----------|-------|-----|
| 0 | Bass | Blood red | `#8B0000` |
| 1 | Low-mid | Burning orange | `#FF4500` |
| 2 | Mid | Molten gold | `#FFD700` |
| 3 | High-mid | Ember | `#FF6347` |
| 4 | High | Heat white | `#FFFACD` |

**Recommended patterns:** `vortex`, `mushroom`, `skull`, `explode`, `fountain`
**Particle effects:** `BassFlame` (red/orange), `SoulFire` (gold), `BeatRing` (ember)
**Environment:** Nether dimension. Lava pools, magma blocks, blackstone. Red fog.
**Mood:** Aggressive, primal, dangerous. Music as combustion.

---

### 5.6 Arctic

| Band | Frequency | Color | Hex |
|------|-----------|-------|-----|
| 0 | Bass | Glacial grey | `#E0E0E0` |
| 1 | Low-mid | Powder blue | `#B0E0E6` |
| 2 | Mid | Ice blue | `#ADD8E6` |
| 3 | High-mid | Frost white | `#E0FFFF` |
| 4 | High | Pure white | `#FFFFFF` |

**Recommended patterns:** `crystal`, `northernlights` (NorthernLights), `aurora`, `menger` (MengerSponge), `tesseract`
**Particle effects:** `AmbientMist` (white/blue), `SpectrumDust` (ice blue)
**Environment:** Snowy tundra or ice spikes biome. Packed ice, blue ice. Snowfall. Low sun angle.
**Mood:** Clean, sharp, crystalline. Precise angles and facets.

---

### 5.7 Retro Arcade

| Band | Frequency | Color | Hex |
|------|-----------|-------|-----|
| 0 | Bass | Arcade red | `#FF0000` |
| 1 | Low-mid | Matrix green | `#00FF00` |
| 2 | Mid | Arcade yellow | `#FFFF00` |
| 3 | High-mid | CRT cyan | `#00FFFF` |
| 4 | High | Synthwave magenta | `#FF00FF` |

**Recommended patterns:** `pixel` (PixelArt), `bars`, `pulse` (Heartbeat), `city` (CityScape), `kaleidoscope`
**Particle effects:** `SpectrumDust` (multi-color), `HighFreqNote` (green)
**Environment:** Black concrete box. Redstone lamp borders. Sea lantern accents. Grid floor.
**Mood:** Playful, nostalgic, pixelated. Every beat is a high score.

---

## 6. Integration Possibilities

External service integrations that extend MCAV beyond a standalone system.

### 6.1 Twitch Chat Integration

**Twitch chat pattern voting:**
- Viewers type `!pattern <name>` to vote
- A Twitch bot (separate Python service using `twitchio`) tallies votes over a configurable window
- The winning pattern is sent to the VJ server via WebSocket
- Cooldown prevents constant pattern switching (minimum 30 seconds between votes)

**Twitch chat particle triggers:**
- `!hype` triggers a burst of `BeatRingEffect` in the visualization
- Subscriber/bit events trigger special particle effects
- Raid events trigger a `Supernova` burst
- Chat message rate modulates entity count: more chat activity = more entities

**Implementation path:** A standalone Python script (~200-300 lines) connecting to Twitch IRC and MCAV WebSocket simultaneously.

---

### 6.2 Spotify Currently-Playing Overlay

**Song info display:**
- VJ server queries the Spotify Web API for the currently playing track
- Track name, artist, and album art sent to the admin panel and preview tool
- Album art colors extracted to auto-select a matching art direction theme

**Automatic preset selection:**
- Spotify's audio features API provides energy, danceability, valence, and tempo
- These map to MCAV presets: high energy + high danceability = `edm`; low energy + high valence = `chill`
- Preset changes happen automatically on track change with a crossfade transition

**Implementation path:** Background thread in `vj_server.py` (~150-200 lines) plus Spotify OAuth.

---

### 6.3 Discord Bot Commands

Extends the existing `discord_bot/` module with audience interaction.

**New bot commands:**
- `/pattern <name>` -- Request a pattern change (subject to VJ approval)
- `/vote <pattern>` -- Vote for the next pattern
- `/nowplaying` -- Show current track, pattern, and preset info
- `/setlist` -- Display the current show's cue stack
- `/energy` -- Show a text-based energy meter

**Voice channel integration:**
- Bot captures audio from Discord voice channel and feeds it to the MCAV processor
- Multiple users create a collaborative audio stream
- Enables remote collaborative visualization sessions

---

### 6.4 MIDI Controller Mapping

Map physical MIDI controllers to MCAV parameters.

**Default mapping (generic 8-fader controller):**
| Fader | Parameter |
|-------|-----------|
| 1 | Bass sensitivity |
| 2 | Mid sensitivity |
| 3 | High sensitivity |
| 4 | Beat threshold |
| 5 | Entity count |
| 6 | Pattern speed |
| 7 | Transition duration |
| 8 | Master brightness (entity scale multiplier) |

**Pad mapping (8x8 grid like Launchpad):**
- Row 1: Pattern selection (one pad per pattern, up to 8 patterns per bank)
- Row 2: Preset selection
- Row 3: Transition type
- Row 4: Zone selection
- Rows 5-8: Direct cue triggers

**Implementation path:** Python bridge using `mido` or `python-rtmidi` (~300-400 lines). Mapping stored in JSON config.

---

### 6.5 OBS Scene Switching

Trigger OBS scene changes based on MCAV events using obs-websocket.

**Trigger mappings:**
- Pattern change -> OBS scene transition
- Beat drop -> OBS stinger transition
- Energy threshold -> OBS source visibility toggle
- Cue stack advance -> OBS scene advance (lockstep)

**Implementation path:** Bridge script for MCAV WebSocket + obs-websocket v5 (~150-250 lines).

---

### 6.6 Stream Deck Integration

Elgato Stream Deck buttons for physical show control.

**Button layout (15-key Stream Deck):**
| Row | Buttons |
|-----|---------|
| Top (5) | Pattern quick-select (most-used patterns with icons) |
| Middle (5) | Preset select, transition type, go-to-next-cue |
| Bottom (5) | Zone select, start/stop show, panic (reset all), blackout, brightness toggle |

**Implementation path:** Stream Deck plugin in JavaScript (~400-500 lines). Button icons show current state (active pattern highlighted, energy level meters).

---

## Appendix: Current Pattern Inventory

For reference, the 27 patterns currently implemented in `audio_processor/patterns.py`:

| Registry Key | Class Name | Category |
|-------------|-----------|----------|
| `spectrum` | StackedTower | Original |
| `ring` | ExpandingSphere | Original |
| `wave` | DNAHelix | Original |
| `explode` | Supernova | Original |
| `columns` | FloatingPlatforms | Original |
| `orbit` | AtomModel | Original |
| `matrix` | Fountain | Original |
| `heartbeat` | BreathingCube | Original |
| `mushroom` | Mushroom | Epic v1 |
| `skull` | Skull | Epic v1 |
| `sacred` | SacredGeometry | Epic v1 |
| `vortex` | Vortex | Epic v1 |
| `pyramid` | Pyramid | Epic v1 |
| `galaxy` | GalaxySpiral | Epic v1 |
| `laser` | LaserArray | Epic v1 |
| `mandala` | Mandala | Geometric v2 |
| `tesseract` | Tesseract | Geometric v2 |
| `crystal` | CrystalGrowth | Geometric v2 |
| `blackhole` | BlackHole | Cosmic v2 |
| `nebula` | Nebula | Cosmic v2 |
| `wormhole` | WormholePortal | Cosmic v2 |
| `aurora` | Aurora | Organic v2 |
| `ocean` | OceanWaves | Organic v2 |
| `fireflies` | Fireflies | Organic v2 |
| `bars` | SpectrumBars | Classic |
| `tubes` | SpectrumTubes | Classic |
| `circle` | SpectrumCircle | Classic |
