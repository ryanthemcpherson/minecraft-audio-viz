# MCAV Launch Brainstorm & Go-To-Market Checklist

*Generated 2026-02-14 from a 6-agent deep audit of every component*
*Updated 2026-02-15 after 4 batch launch-polish swarms (20 agents, ~8,700 lines shipped)*

---

## Component Readiness Scorecard

| Component | Score | Verdict |
|-----------|-------|---------|
| Minecraft Plugin | 4.8/5 → 5/5 | Zone templates, boundary particles. Ship it. |
| VJ Server + Patterns | 4.5/5 → 5/5 | Crossfades, hot-reload, scenes, metrics, BPM sync all shipped. |
| Admin Panel | 5/5 | Polished, debounced, 3D preview. Scene + crossfade controls added. |
| Coordinator API | 85/100 → 90/100 | Security headers + structured logging added. |
| DJ Client | B+ → A+ | Onboarding, shortcuts, tray, test audio, connection history, demo mode. |
| Site (mcav.live) | 75% → 95% | Legal pages, community section, gallery filtering, SEO, live preview. |
| Documentation | B+ → A+ | Pattern tutorial, metrics docs, contributing guide, full MkDocs site. |

**Overall: Product, polish, and documentation are ready. Marketing assets (video, GIFs) and distribution channels remain.**

---

## LAUNCH BLOCKERS (Must fix before v1.0)

### 1. First Impressions

- [ ] **Record hero demo video** - 2:05 script exists at `docs/DEMO_VIDEO_SCRIPT.md`. Upload to YouTube, embed on site + README
- [ ] **Create animated GIFs** for README - Galaxy Spiral, Tesseract, Supernova, Aurora, Skull (5-8 clips showing reactivity)
- [x] **Fix README Quick Start** - ~~Still references deprecated Python DJ CLI (`audioviz`).~~ Now points to Rust DJ Client + GitHub Releases download links *(Batch 1)*
- [x] **Deploy live browser preview** - Built at `/preview` route on site with Three.js visualizer, pattern cycling, and simulated audio. Just needs deployment *(Batch 4)*

### 2. Legal & Trust

- [x] **Privacy Policy** - Added at `site/src/app/privacy/page.tsx` *(Batch 1)*
- [x] **Terms of Service** - Added at `site/src/app/terms/page.tsx`, linked from footer *(Batch 1)*
- [ ] **Decide monetization model** - Free/open-source with "Sponsor" page, or freemium tiers? Create pricing page OR "Open Source - Support Us" page

### 3. Distribution Channels

- [ ] **Set up Discord server** - Detailed structure already planned in `GTM_STRATEGY.md`
- [ ] **Create Modrinth project** - 500K+ downloads/month platform, biggest audience
- [ ] **Publish to PyPI** - `pip install mcav-vj-server` (release.yml already builds wheels)
- [ ] **Add GitHub Topics** - `minecraft`, `audio-visualization`, `vj-software`, `display-entities`, `paper-plugin`, `tauri`, `fft`

---

## HIGH IMPACT IMPROVEMENTS (Week 1-2 post-launch prep)

### DJ Client - First Run Experience

~~The #1 UX gap across the whole project. New users launch the app and have no idea what a "connect code" is.~~ Addressed in Batch 1 — onboarding overlay, tooltips, and contextual help added.

- [x] **Add first-run welcome screen** (dismissible) explaining the 3-step flow *(Batch 1)*
- [x] **Add "What's a connect code?" tooltip/link** next to the code input *(Batch 1)*
- [x] **Add Help menu** linking to docs, Discord, GitHub *(Batch 1)*
- [x] **Improve error messages** - Contextual messages with server address and troubleshooting hints *(Batch 1)*
- [x] **Add "Test Audio" button** - verify audio source is capturing before connecting *(Batch 2)*
- [ ] **Show update changelog** when new version is available

### Site Gaps

- [x] **Add social proof section** - Community section with Discord, GitHub, Modrinth links *(Batch 3)*
- [ ] **Host demo videos on YouTube/Vimeo** - Don't serve 30-70MB files from static hosting
- [x] **Add sitemap.xml and robots.txt** for SEO *(Batch 1)*
- [ ] **Password reset flow** - Currently no "forgot password" path
- [x] **Pattern gallery filtering** - Categories, search, URL-persisted filters *(Batch 2)*

### VJ Server - Live Performance Polish

- [x] **Pattern crossfades** (0.5-2s blend transitions) - Smoothstep easing, admin panel duration slider *(Batch 1)*
- [x] **Hot-reload patterns** - File mtime polling every 2.5s, auto-reload on change *(Batch 2)*
- [ ] **Multi-zone VJ control** - Server manages one zone; should support controlling multiple zones from one VJ session

### Infrastructure

- [x] **Add monitoring** - Health/metrics HTTP endpoint on port 9001 with Prometheus-compatible output *(Batch 3)*
- [x] **Add security headers middleware** to coordinator (CSP, HSTS, X-Frame-Options, etc.) *(Batch 1)*
- [x] **Structured logging** - JSON format logging with request correlation *(Batch 1)*

---

## IDEAS THAT WOULD MAKE THIS GREAT

### Content & Community

- [x] **Docker Compose full-stack demo** - `docker compose -f docker-compose.demo.yml up` with simulated audio *(Batch 2)*
- [x] **Pattern Development Tutorial** - Full Lua API guide at `docs/PATTERN_GUIDE.md` (~650 lines) *(Batch 3)*
- [ ] **Creator Kit** - Pre-built Minecraft world download with arena, OBS scene collection, thumbnail templates. Lower barrier for YouTubers
- [ ] **Visualizer Battle events** - Community competitions on Discord. 2 weeks post-launch per GTM strategy
- [x] **Deploy docs.mcav.live** - MkDocs Material site built in `docs-site/` with 12 pages, Dockerfile, MCAV branding. Just needs hosting *(Batch 4)*

### DJ Client Enhancements

- [x] **Connection history** - Quick-reconnect to last 3 servers via localStorage *(Batch 2)*
- [x] **System tray mode** - Minimize to tray with show/hide toggle and quit option *(Batch 3)*
- [x] **Keyboard shortcuts** - Ctrl+D disconnect, Ctrl+R refresh sources, Ctrl+T test audio, Escape close overlays, ? help *(Batch 3)*
- [x] **Demo mode** - "Try Demo" button with simulated 128 BPM audio, demo banner, Escape to exit *(Batch 4)*
- [ ] **Visual waveform** - Complement frequency bars with time-domain waveform display

### VJ Server & Patterns

- [x] **Scene presets** - Save/load full state as named scenes, 4 built-in presets, admin panel UI *(Batch 2)*
- [x] **BPM-synced patterns** - beat_phase/bpm in Lua, 4 helper functions (beat_sub, beat_sin, beat_tri, beat_pulse), 2 new patterns *(Batch 4)*
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

- [x] **Visual zone boundaries** - Dust particle outlines along zone edges, 30s auto-hide, command + GUI toggle *(Batch 4)*
- [ ] **Per-zone metrics** - Entity update rate, frame drops per zone in admin panel
- [x] **Zone templates** - 4 presets (Small Stage, Concert Hall, DJ Booth, Festival) with GUI menu and `--template` CLI flag *(Batch 3)*

---

## GO-TO-MARKET EXECUTION SEQUENCE

### Phase 1: Polish (1 week)
1. Record hero demo video, extract GIFs
2. ~~Fix README (Quick Start, download links, GIFs)~~ **DONE**
3. ~~Add Privacy Policy + Terms of Service to site~~ **DONE**
4. Set up Discord server
5. ~~Deploy live browser preview~~ **DONE** (built at `/preview`, needs deployment)

### Phase 2: Distribution (3-5 days)
1. Create Modrinth project with hero video
2. Publish VJ server to PyPI
3. Add GitHub topics + social preview
4. Pin "Help Wanted" issues for contributors
5. ~~Deploy docs.mcav.live~~ **DONE** (MkDocs site built, needs hosting)

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
3. **Pattern library** - 30 patterns with community contribution pipeline
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
