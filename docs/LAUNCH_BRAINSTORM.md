# MCAV Launch Brainstorm & Go-To-Market Checklist

*Generated 2026-02-14 from a 6-agent deep audit of every component*

---

## Component Readiness Scorecard

| Component | Score | Verdict |
|-----------|-------|---------|
| Minecraft Plugin | 4.8/5 | Ship it. Enterprise-grade. |
| VJ Server + Patterns | 4.5/5 | Ship it. Missing crossfades. |
| Admin Panel | 5/5 | Polished, debounced, 3D preview. |
| Coordinator API | 85/100 | Production-ready with caveats. |
| DJ Client | B+ | Technically excellent, UX needs onboarding. |
| Site (mcav.live) | 75% | Strong pages, missing legal/pricing/social proof. |
| Documentation | B+ | Great depth, weak first-impression materials. |

**Overall: The product is ready. The marketing is not.**

---

## LAUNCH BLOCKERS (Must fix before v1.0)

### 1. First Impressions

- [ ] **Record hero demo video** - 2:05 script exists at `docs/DEMO_VIDEO_SCRIPT.md`. Upload to YouTube, embed on site + README
- [ ] **Create animated GIFs** for README - Galaxy Spiral, Tesseract, Supernova, Aurora, Skull (5-8 clips showing reactivity)
- [ ] **Fix README Quick Start** - Still references deprecated Python DJ CLI (`audioviz`). Point to Rust DJ Client + GitHub Releases download links
- [ ] **Deploy live browser preview** at `preview.mcav.live` with simulated audio (the Three.js preview already works, just needs hosting)

### 2. Legal & Trust

- [ ] **Privacy Policy** - Required if collecting user data (accounts, Discord OAuth)
- [ ] **Terms of Service** - Required for SaaS platform
- [ ] **Decide monetization model** - Free/open-source with "Sponsor" page, or freemium tiers? Create pricing page OR "Open Source - Support Us" page

### 3. Distribution Channels

- [ ] **Set up Discord server** - Detailed structure already planned in `GTM_STRATEGY.md`
- [ ] **Create Modrinth project** - 500K+ downloads/month platform, biggest audience
- [ ] **Publish to PyPI** - `pip install mcav-vj-server` (release.yml already builds wheels)
- [ ] **Add GitHub Topics** - `minecraft`, `audio-visualization`, `vj-software`, `display-entities`, `paper-plugin`, `tauri`, `fft`

---

## HIGH IMPACT IMPROVEMENTS (Week 1-2 post-launch prep)

### DJ Client - First Run Experience

The #1 UX gap across the whole project. New users launch the app and have no idea what a "connect code" is.

- [ ] **Add first-run welcome screen** (dismissible) explaining the 3-step flow: get connect code from VJ operator -> select audio -> connect
- [ ] **Add "What's a connect code?" tooltip/link** next to the code input
- [ ] **Add Help menu** linking to docs, Discord, GitHub
- [ ] **Improve error messages** - "Connection timeout" -> "Can't reach server at X:9000. Check that the VJ server is running and your firewall allows the connection."
- [ ] **Add "Test Audio" button** - verify audio source is capturing before connecting
- [ ] **Show update changelog** when new version is available

### Site Gaps

- [ ] **Add social proof section** - Discord member count, server count, "Join the community"
- [ ] **Host demo videos on YouTube/Vimeo** - Don't serve 30-70MB files from static hosting
- [ ] **Add sitemap.xml and robots.txt** for SEO
- [ ] **Password reset flow** - Currently no "forgot password" path
- [ ] **Pattern gallery filtering** - Categories (Geometric, Cosmic, Organic), search, favorites

### VJ Server - Live Performance Polish

- [ ] **Pattern crossfades** (0.5-2s blend transitions) - Instant pattern switches feel jarring during live sets
- [ ] **Hot-reload patterns** - Watch `patterns/` directory, reload on file change without server restart. Critical for pattern development iteration
- [ ] **Multi-zone VJ control** - Server manages one zone; should support controlling multiple zones from one VJ session

### Infrastructure

- [ ] **Add monitoring** - Sentry or equivalent error tracking on coordinator
- [ ] **Add security headers middleware** to coordinator (worker already has CSP, HSTS, X-Frame-Options)
- [ ] **Structured logging** - JSON format for log aggregation

---

## IDEAS THAT WOULD MAKE THIS GREAT

### Content & Community

- [ ] **Docker Compose full-stack demo** - `docker compose up` gives you browser preview with simulated audio, zero install. Massive for first impressions
- [ ] **Pattern Development Tutorial** - "Write Your First MCAV Pattern in 10 Minutes." Enable community contributions, grow the pattern library
- [ ] **Creator Kit** - Pre-built Minecraft world download with arena, OBS scene collection, thumbnail templates. Lower barrier for YouTubers
- [ ] **Visualizer Battle events** - Community competitions on Discord. 2 weeks post-launch per GTM strategy
- [ ] **Deploy docs.mcav.live** - MkDocs Material site. Content already exists in markdown, just needs hosting

### DJ Client Enhancements

- [ ] **Connection history** - Quick-reconnect to last 3 servers
- [ ] **System tray mode** - Minimize to tray when DJing (app must stay open, might as well hide it)
- [ ] **Keyboard shortcuts** - Ctrl+D disconnect, Ctrl+R refresh sources
- [ ] **Demo mode** - Explore UI with simulated audio data, no server needed. Great for screenshots/tutorials
- [ ] **Visual waveform** - Complement frequency bars with time-domain waveform display

### VJ Server & Patterns

- [ ] **Scene presets** - Save/load full state (pattern + audio preset + zone config) as named scenes. One-click setups for different vibes
- [ ] **BPM-synced patterns** - Lock pattern rotations/animations to beat phase for tighter sync
- [ ] **Pattern preview thumbnails** in admin panel - 28 patterns in a flat grid is hard to browse without visuals
- [ ] **MIDI controller support** - Map hardware faders/buttons to pattern params. Professional VJ workflow
- [ ] **Session recording/replay** - Record a performance, replay it later. Great for content creation

### Platform & Distribution

- [ ] **Homebrew formula** - `brew install mcav` for macOS users
- [ ] **AUR package** - Arch Linux (overrepresented in developer demographic)
- [ ] **SpigotMC resource page** - Still the largest Paper/Spigot marketplace
- [ ] **bStats integration** - Track active server count for social proof
- [ ] **Pre-built world download** on Planet Minecraft - Showcase arena with zones pre-configured

### Minecraft Plugin

- [ ] **Visual zone boundaries** - Particle outlines to help with positioning zones in-world
- [ ] **Per-zone metrics** - Entity update rate, frame drops per zone in admin panel
- [ ] **Zone templates** - Pre-built zone configurations (small stage, concert hall, DJ booth)

---

## GO-TO-MARKET EXECUTION SEQUENCE

### Phase 1: Polish (1 week)
1. Record hero demo video, extract GIFs
2. Fix README (Quick Start, download links, GIFs)
3. Add Privacy Policy + Terms of Service to site
4. Set up Discord server
5. Deploy live browser preview

### Phase 2: Distribution (3-5 days)
1. Create Modrinth project with hero video
2. Publish VJ server to PyPI
3. Add GitHub topics + social preview
4. Pin "Help Wanted" issues for contributors
5. Deploy docs.mcav.live

### Phase 3: Launch Day
*Per existing GTM_STRATEGY.md:*
- Morning: Publish GitHub release v1.0.0
- Morning: Make YouTube video public, Modrinth listing live
- Afternoon: Post to r/Minecraft (8M users) with hero video
- Afternoon: Post to r/admincraft (100K users) with practical server owner angle
- Evening: Engage all comment threads for 2 hours

### Phase 4: Post-Launch (Weeks 2-4)
1. Dev blog: "Why I Built a Concert Venue in Minecraft"
2. Dev blog: "Real-Time FFT to Display Entities" (technical deep-dive)
3. Post to r/MinecraftCommands + r/musicproduction
4. First Visualizer Battle event on Discord
5. Creator outreach: 20 Tier 2 YouTubers (100K-500K subs)

### 30-Day Targets
- GitHub stars: 100
- Total downloads: 750
- Discord members: 25
- Community-submitted patterns: 5
- Hero video views: 5,000

---

## COMPETITIVE MOAT

What makes MCAV defensible:
1. **First-mover on Display Entities** - 18+ months ahead of any competitor
2. **Multi-DJ architecture** - Network effect (more DJs = more value per server)
3. **Pattern library** - 28 patterns with community contribution pipeline
4. **Cross-platform tooling** - Rust + Python + Java + TypeScript (high replication cost)
5. **Open source community** - MIT license attracts contributors who build the moat for you

**Biggest risk:** Well-funded team clones the concept.
**Mitigation:** Build community moat fast - contributors, pattern library, server deployments, Discord community.

---

## WHAT WE'RE NOT DOING (Conscious Omissions)

- **Code signing certificates** - Too expensive for current stage ($70-400/yr). Users accept SmartScreen warning for now
- **Mobile app** - Desktop-only DJ client is fine; DJs use laptops
- **Paid tier** - Launch as free/open-source, figure out monetization after community exists
- **Windows Store / Mac App Store** - Distribution overhead not worth it yet
- **Localization** - English only for launch

---

*This document is a living brainstorm. Prioritize ruthlessly - the product is ready, the launch sequence matters more than perfection.*
