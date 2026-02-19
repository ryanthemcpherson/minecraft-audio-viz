# MCAV Demo Video Script

**Title:** MCAV -- Turn Music Into Minecraft
**Target length:** 2:00-2:30
**Tone:** Tech demo meets creative showcase. Confident, clear, a little awe.
**Target audience:** Minecraft server owners, content creators, music enthusiasts, event organizers

---

## 1. HOOK (0:00 - 0:08)

[MUSIC] Heavy electronic buildup, muted at first, swelling over 4 seconds into a bass drop.

[VISUAL] Black screen. A single Minecraft block flickers into existence in a dark void world. Then another. Then dozens. On the bass drop, cut to a wide shot of a fully active Galaxy Spiral visualization -- 64 display entities orbiting, pulsing, and scaling in perfect sync with the kick drum. The camera orbits slowly around it.

**NARRATION:**
"What if your music could build worlds?"

---

## 2. WHAT IS MCAV (0:08 - 0:28)

[MUSIC] Track continues at moderate energy. Clean melodic section.

[VISUAL] Quick montage -- 1.5 seconds each -- cycling through 6-8 different patterns: Spectrum Bars pumping on a kick, DNA Helix rotating to a synth lead, Aurora shimmering on ambient pads, Black Hole warping on a bass hit, Sacred Geometry spinning to hi-hats, Supernova exploding on a drop. Each clip is in a different Minecraft environment (plains, void world, cave, rooftop build).

**NARRATION:**
"MCAV is a real-time audio visualizer built for Minecraft. It captures audio from any source -- Spotify, YouTube, your DAW -- runs it through FFT analysis and beat detection, and renders reactive 3D visualizations inside your server using Display Entities. No client mods. No resource packs. Just pure server-side rendering that every player can see."

---

## 3. LIVE DEMO WALKTHROUGH (0:28 - 1:25)

### 3a. Starting Up (0:28 - 0:40)

[MUSIC] Track dips to a quieter bridge section.

[VISUAL] Screen recording of a terminal. The user types `audioviz --app spotify --preview` and presses Enter. The CLI output scrolls: "Capturing audio from Spotify... FFT analyzer ready... WebSocket connected... Preview server at http://localhost:8080." Cut to Spotify playing a track in the background, with the terminal's compact spectrograph showing live frequency bars in the console.

**NARRATION:**
"Getting started takes one command. Point MCAV at your audio source and it connects automatically. Spotify, Chrome, a live DJ set -- anything playing on your system."

### 3b. Browser Preview (0:40 - 0:55)

[MUSIC] Track builds back up. Mid-energy electronic.

[VISUAL] Cut to the Three.js browser preview in fullscreen. The camera auto-orbits around a Tesseract pattern. The 4D hypercube rotates and breathes with the bass. Frequency band meters pulse in the corner of the UI. A beat indicator flashes on every kick. The user clicks a pattern dropdown and switches to Crystal Growth -- the transition crossfades smoothly over 2 seconds as crystal branches extend outward. Then switches to Vortex -- entities spiral into a tunnel formation.

**NARRATION:**
"The built-in 3D preview gives you a real-time look at exactly what players will see in Minecraft. Twenty-seven patterns -- from spectrum bars to galaxy spirals, tesseracts, and black holes. Switch between them live. Crossfade transitions keep the visuals smooth."

### 3c. Inside Minecraft (0:55 - 1:15)

[MUSIC] Track hits a high-energy chorus. Driving kick and bass.

[VISUAL] Cut to Minecraft. First-person view on a server. The player is standing on an elevated platform looking at a visualization zone. A Galaxy Spiral pattern is active -- glowing display entities orbit in a spiral formation, scaling up with every bass hit and contracting on the release. Beat-reactive particle effects burst outward on every kick drum. The player opens the in-game GUI menu with `/mcav menu` -- it shows pattern selection, preset controls, zone management. The player switches the pattern to Laser Array. Beams of display entities sweep across the space in concert-style formation. Cut to a wide cinematic shot of the same visualization from a distance, showing the full scale of the setup against the Minecraft landscape.

**NARRATION:**
"And here it is inside Minecraft. Display Entities move, scale, and react to every frequency band in real time. Bass drives the big movements. Highs add shimmer and detail. Beat detection fires particle effects on every kick -- and the whole system runs at sub-twenty-millisecond latency. It feels instant."

### 3d. Admin Control Panel (1:15 - 1:25)

[MUSIC] Track continues at high energy.

[VISUAL] Cut to the Admin Panel in a browser. The VJ-style control surface is visible: frequency band meters bouncing, a beat indicator flashing, pattern selector, preset dropdown (switching from "auto" to "edm" -- the beat response visibly tightens), entity count slider, zone controls. The user adjusts the bass sensitivity slider and the visualization in the embedded preview responds immediately. Quick shot of the DJ Client app showing a connect code (BEAT-7K3M) and audio source selection.

**NARRATION:**
"The admin panel gives you full VJ-style control. Swap patterns, tune frequency sensitivity per band, dial in presets for EDM, hip-hop, classical -- whatever fits the music. And for live events, the DJ Client lets remote performers connect with a simple code."

---

## 4. KEY FEATURES HIGHLIGHT REEL (1:25 - 1:52)

[MUSIC] Track transitions to a montage-friendly section -- quick cuts on every other beat.

[VISUAL] Rapid-fire feature cards. Each feature gets 3-4 seconds with a bold text overlay on the left and a supporting visual on the right.

**Card 1 -- "27 Visualization Patterns"**
[VISUAL] Quick 0.5-second clips of 8 different patterns cycling rapidly: Bars, Galaxy, Mandala, Wormhole, Ocean Waves, Skull, Pyramid, Fireflies. Each in a distinct Minecraft setting.

**Card 2 -- "Zero Client Mods"**
[VISUAL] A vanilla Minecraft client connecting to a server. No mod loader, no resource pack prompt. The player spawns and immediately sees the visualization running. Text overlay: "Paper/Spigot 1.21.1+ -- Display Entities, server-side."

**Card 3 -- "Multi-DJ Support"**
[VISUAL] The architecture diagram from the README animates on screen: two DJ nodes connecting to a central VJ Server, which routes to Minecraft and browser viewers. Then cut to the Admin Panel showing a DJ queue with two connected DJs and a "Go Live" button.

**Card 4 -- "6 Audio Presets"**
[VISUAL] Side-by-side comparison. The same track plays while the preset switches from "chill" (smooth, gentle movements) to "edm" (punchy, aggressive response) to "classical" (fluid, delicate). The difference in beat response and entity behavior is visually obvious.

**Card 5 -- "Works With Any Audio"**
[VISUAL] Quick cuts of different audio sources: Spotify window, YouTube in Chrome, Ableton Live, a Discord voice call. Each with MCAV running and reacting to the audio.

**Card 6 -- "Timeline Shows"**
[VISUAL] A show file loaded in the admin panel with timed cues. The timeline plays: pattern automatically transitions from Aurora to Crystal Growth to Supernova on cue, perfectly timed to the music.

**NARRATION:**
"Twenty-seven patterns. Zero client mods needed. Multi-DJ support for live events. Six genre-tuned presets. Works with any audio source on your system. And a timeline system for pre-programmed shows."

---

## 5. CALL TO ACTION (1:52 - 2:05)

[MUSIC] Track reaches a final melodic resolution. Energy settles to a satisfying close.

[VISUAL] Return to the wide cinematic Minecraft shot from earlier, but now from a higher angle -- a slow, sweeping aerial view of the visualization running against a sunset. The MCAV logo fades in center-screen. Below it: the GitHub URL and the text "Open Source -- MIT License." A terminal appears briefly at the bottom showing the three-line quick start:

```
git clone https://github.com/ryanthemcpherson/minecraft-audio-viz
pip install -e .
audioviz --app spotify --preview
```

**NARRATION:**
"MCAV is open source and free. Three commands to install. One command to run. Clone it, point it at your music, and turn sound into something your players can see. Link in the description."

[VISUAL] Logo holds for 2 seconds. Fade to black.

---

## Production Notes

### Music Selection
- Use a royalty-free electronic track with a clear structure: quiet intro, buildup, drop, sustain, outro. The track needs a prominent kick drum for beat detection to look impressive on camera.
- Recommended: melodic dubstep or future bass with a tempo around 128-140 BPM. The "edm" preset is tuned for this range.

### Recording Tips
- Use Minecraft's cinematic camera (F6) or a replay mod for smooth camera movements.
- Record the browser preview and Minecraft simultaneously to show parity between them.
- Set render distance to 16+ chunks for wide shots.
- Use a void world or flat world with minimal distractions for the main visualization shots.
- Night time in Minecraft makes glowing display entities more visually striking.
- Entity count of 32-64 produces the most visually impressive results for video.

### Pattern Showcase Priority
These patterns are the most visually impressive on camera and should get the most screen time:
1. Galaxy Spiral -- instantly recognizable, smooth motion, scales well
2. Tesseract -- unique 4D geometry, eye-catching rotation
3. Crystal Growth -- organic feel, satisfying branching animation
4. Supernova -- explosive, great for drop moments
5. Black Hole -- gravitational warping effect is visually unique
6. Laser Array -- concert association, immediately understood
7. Aurora -- beautiful ambient movement, great for calm sections
8. Vortex -- tunnel effect is immersive from first-person view

### Timing Reference
| Section | Start | End | Duration |
|---------|-------|-----|----------|
| Hook | 0:00 | 0:08 | 8s |
| What is MCAV | 0:08 | 0:28 | 20s |
| Demo: Starting up | 0:28 | 0:40 | 12s |
| Demo: Browser preview | 0:40 | 0:55 | 15s |
| Demo: Minecraft | 0:55 | 1:15 | 20s |
| Demo: Admin panel | 1:15 | 1:25 | 10s |
| Feature highlight reel | 1:25 | 1:52 | 27s |
| Call to action | 1:52 | 2:05 | 13s |
| **Total** | | | **~2:05** |

