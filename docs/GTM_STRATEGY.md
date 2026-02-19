# MCAV Go-to-Market Strategy

> Minecraft Audio Visualizer -- Real-time audio visualization in Minecraft using Display Entities, with professional DJ/VJ control tools.

**Last updated:** 2026-02-07
**Status:** Pre-launch planning

---

## Table of Contents

1. [Target Audiences](#1-target-audiences)
2. [Distribution Channels](#2-distribution-channels)
3. [Content Marketing Plan](#3-content-marketing-plan)
4. [Launch Timeline](#4-launch-timeline)
5. [Competitive Landscape](#5-competitive-landscape)
6. [Monetization Strategy](#6-monetization-strategy)
7. [Community Building](#7-community-building)
8. [KPIs and Success Metrics](#8-kpis-and-success-metrics)

---

## 1. Target Audiences

### Primary Audiences

#### Server Administrators
- **Profile:** Operators of entertainment, event, or community Minecraft servers running Paper/Spigot 1.21.1+.
- **Pain point:** Want to offer unique, engaging experiences to retain players but are limited to static builds, note blocks, or client-side mods.
- **What they need:** A drop-in plugin JAR with minimal configuration, clear documentation, and stable performance that does not degrade server TPS.
- **Messaging angle:** "One JAR, zero client mods. Drag, drop, and your server has a concert venue."
- **Action items:**
  - Provide a 5-minute quick-start guide specifically for server admins.
  - Include a ``config.yml`` with sensible defaults and inline comments.
  - Publish benchmark data showing TPS impact under typical and heavy load.

#### Content Creators (YouTubers / Streamers)
- **Profile:** Minecraft content creators (50K-5M subscribers) looking for novel video concepts. Minecraft remains one of the largest gaming categories on YouTube and Twitch.
- **Pain point:** Constant pressure for fresh content. Audio-reactive builds are visually spectacular and algorithmically favored (high watch time, high engagement).
- **What they need:** A visually impressive setup that works in 15 minutes, with a good "reveal moment" for videos.
- **Messaging angle:** "Turn any song into a Minecraft light show. Your viewers have never seen this."
- **Action items:**
  - Create a "Creator Kit" with pre-configured world download, recommended OBS scenes, and suggested video structure.
  - Build a one-command setup script that gets creators from zero to demo in under 5 minutes.
  - Identify 20 mid-tier creators (100K-500K subs) for outreach; they are more responsive than mega-creators and still move the needle.

#### EDM / Virtual Event Organizers
- **Profile:** People who produce virtual concerts, music festivals in Minecraft, or branded entertainment events (e.g., corporate parties, charity streams).
- **Pain point:** Existing Minecraft concert setups require extensive manual command-block programming or client-side mods that fragment the audience.
- **What they need:** Multi-DJ support, remote VJ control, reliable performance for 50-200 concurrent viewers, and the ability to synchronize visuals across zones.
- **Messaging angle:** "Professional-grade virtual concert production. Multiple DJs, real-time control, zero audience setup."
- **Action items:**
  - Write a dedicated "Event Production Guide" covering network architecture, VJ server setup, and multi-zone configuration.
  - Offer a free 30-minute consultation call for the first 10 event organizers who reach out.
  - Document a reference architecture for a 100-player event server.

### Secondary Audiences

#### Educators
- **Profile:** CS teachers, creative coding instructors, music technology educators.
- **Pain point:** Engaging students with abstract concepts like FFT, signal processing, and real-time systems.
- **What they need:** Well-documented code, clear architecture diagrams, and lesson-plan-friendly modularity.
- **Messaging angle:** "Teach FFT with something students actually care about."
- **Action items:**
  - Write a "How It Works" educational guide walking through the audio pipeline from WASAPI capture to Minecraft rendering.
  - Tag code with ``# EDUCATIONAL NOTE:`` comments in key algorithmic sections.
  - Create a simplified "student edition" configuration that exposes fewer knobs but makes the signal flow obvious.

#### Minecraft Build / Technical Communities
- **Profile:** Redstone engineers, technical Minecraft players, and datapack developers who push the boundaries of what Minecraft can do.
- **Pain point:** Always looking for the next impressive technical achievement. Display Entities are still underexplored.
- **What they need:** Extensibility, pattern API documentation, and the ability to create and share custom patterns.
- **Messaging angle:** "The most advanced use of Display Entities you have seen. And you can extend it."
- **Action items:**
  - Publish pattern API documentation with examples.
  - Create a "Pattern Cookbook" showing how to build 5 patterns of increasing complexity.
  - Set up a community pattern gallery on the project website or Discord.

---

## 2. Distribution Channels

### P0 -- Must-Have for Launch

These channels must be live before the v1.0 launch announcement.

| Channel | Artifact | Notes |
|---------|----------|-------|
| **GitHub Releases** | Plugin JAR, Python sdist/wheel, Rust binaries | Primary source of truth. Use GitHub Actions for automated releases with checksums. |
| **Modrinth** | Plugin JAR | Fastest-growing MC distribution platform. Supports Paper plugins. Tag with `utility`, `management`. Modrinth has strong SEO and discovery. |
| **SpigotMC** | Plugin JAR | Still the largest Paper/Spigot plugin marketplace. Required for credibility. Write a polished resource page with screenshots, GIFs, and a demo video embed. |
| **PyPI** | `mcav` Python package | `pip install mcav` is the lowest-friction path for the audio processor. Ensure the CLI entry points (`audioviz`, `audioviz-vj`) work out of the box. |

**Action items:**
- [ ] Set up GitHub Actions workflow for multi-platform release builds (JAR, Python wheel, Rust binaries for Windows/macOS/Linux).
- [ ] Create Modrinth project page with banner image, feature list, and dependency info.
- [ ] Create SpigotMC resource page. Follow their formatting conventions (BBCode, not Markdown).
- [ ] Verify `pyproject.toml` metadata is complete (description, classifiers, URLs, license).
- [ ] Write install instructions for each channel in the main README.

### P1 -- Shortly After Launch (Weeks 7-10)

| Channel | Artifact | Notes |
|---------|----------|-------|
| **Hangar** | Plugin JAR | PaperMC's official plugin repository. Smaller audience but highly targeted (Paper server admins). |
| **CurseForge** | Plugin JAR | Still significant traffic. Many server admins check CurseForge by habit. |
| **Homebrew** | CLI formula | `brew install mcav` for macOS users. Write a Homebrew tap (`homebrew-mcav`). |
| **AUR** | PKGBUILD | Arch Linux users are overrepresented in developer and tinkerer demographics. |

**Action items:**
- [ ] Submit Hangar project after Hangar approval process (may require PaperMC team review).
- [ ] Create CurseForge project. Note: CurseForge approval can take 1-2 weeks; submit early.
- [ ] Write and test Homebrew formula. Host in a `homebrew-mcav` tap repository.
- [ ] Write AUR PKGBUILD and submit. Maintain or find an AUR maintainer.

### P2 -- Growth Phase (Months 2-4)

| Channel | Artifact | Notes |
|---------|----------|-------|
| **crates.io** | `mcav-dj-client` Rust crate | For developers who want to build custom DJ clients or integrate the protocol. |
| **Docker Hub** | Docker image | `docker run mcav/vj-server` for quick VJ server deployment. Valuable for event organizers. |
| **npm** | Protocol types package | If TypeScript types for the WebSocket protocol would benefit third-party integrations. |

**Action items:**
- [ ] Clean up the Rust DJ client crate for public consumption (API docs, examples, `README.md` in crate root).
- [ ] Create a Dockerfile for the VJ server with health checks and volume mounts for configuration.
- [ ] Evaluate whether a protocol types npm package has enough demand to justify maintenance.

---

## 3. Content Marketing Plan

### Hero Demo Video (Priority: Critical)

**Format:** 2-3 minutes, cinematic, high production value.
**Concept -- "The Drop":**
1. Open on a dark Minecraft world. Silence. A single player walks toward an empty arena.
2. Soft ambient music begins. Subtle particle effects drift through the air.
3. Music builds. Display Entities begin forming patterns -- slow, geometric, mesmerizing.
4. The drop hits. Full audio-reactive explosion of color, movement, and particles. Multiple patterns cycling. Camera swooping through the visualization.
5. Pull back to reveal the full scale. Cut to the admin panel showing real-time controls. Cut to the 3D browser preview.
6. End card: "MCAV -- Audio Visualization for Minecraft. Free. Open Source. No Client Mods."

**Action items:**
- [ ] Scout or build a dedicated demo world with a purpose-built arena (amphitheater shape works well for framing).
- [ ] Select 2-3 tracks with clear build-drop structure (royalty-free: check Epidemic Sound, Artlist, or NCS).
- [ ] Record with ReplayMod or camera account for smooth cinematic shots.
- [ ] Edit with quick cuts synchronized to the beat. Show the admin panel and browser preview as picture-in-picture overlays, not separate segments.
- [ ] Target release: 3 days before v1.0 launch.
- [ ] Upload to YouTube. Cross-post to Twitter/X, Reddit, and Discord.

### Short-Form Content (TikTok / YouTube Shorts / Instagram Reels)

Produce 10-15 clips before launch, schedule 2-3 per week.

| Clip Concept | Duration | Hook |
|-------------|----------|------|
| Single pattern showcase (e.g., "Helix" pattern) | 15s | "This is what [pattern name] looks like in Minecraft" |
| Before/after (silent arena vs. music playing) | 20s | "What happens when you plug audio into Minecraft" |
| Speed comparison (60 BPM vs. 180 BPM) | 15s | "Slow song vs. drum and bass" |
| Admin panel demo | 30s | "Controlling a Minecraft concert in real-time" |
| Pattern switching montage | 20s | Set to fast-paced music, rapid cuts between patterns |
| "How it works" explainer | 30s | "Your PC's audio -> FFT -> WebSocket -> Minecraft" |
| Multi-DJ mode | 25s | "Two DJs, one Minecraft server" |

**Action items:**
- [ ] Record all clips in a single session to maintain visual consistency.
- [ ] Use consistent branding: MCAV watermark in corner, consistent font for text overlays.
- [ ] Add captions (most short-form is watched muted).
- [ ] Use trending audio where possible on TikTok (even if just as background with the MC audio overlaid).

### Reddit Strategy

Reddit is high-leverage for this project because the target audiences are concentrated in specific subreddits.

| Subreddit | Approach | Timing |
|-----------|----------|--------|
| **r/Minecraft** (8M+) | Post hero video as a "I built a real-time audio visualizer" showcase. Use the `Builds` flair. | Launch day |
| **r/MinecraftCommands** (200K+) | Technical deep-dive post: "How we use Display Entities for 60fps audio visualization." Show the entity batching system. | Launch day +2 |
| **r/admincraft** (100K+) | Practical post: "Free plugin for audio-reactive visualizations, no client mods." Focus on installation, performance, and server impact. | Launch day +1 |
| **r/dataisbeautiful** (20M+) | Crosspost the hero video or a spectrograph comparison. Frame as data visualization. | Launch week |
| **r/musicproduction** (1M+) | Post about the DJ/VJ control interface. Frame as "we built a VJ tool that outputs to Minecraft." | Launch day +3 |
| **r/Python** (1M+) | Technical post about the audio processing pipeline. Code snippets, architecture discussion. | Week after launch |
| **r/rust** (300K+) | Post about the Tauri-based DJ client if it is polished enough. Rust community appreciates real-world Tauri projects. | P1 phase |

**Rules of engagement:**
- Never post to more than 2 subreddits on the same day (looks spammy).
- Engage genuinely in comments for at least 2 hours after posting.
- Do not use marketing language. Be a developer sharing a project.
- If a post gains traction, do NOT edit it to add links. Respond to top comments instead.

### Dev Blog / Build Log Series

Publish on GitHub Discussions, dev.to, or a simple blog. 4-6 posts covering:

1. **"Why I Built a Concert Venue in Minecraft"** -- Origin story, motivation, early prototypes.
2. **"Real-Time FFT in Python: Lessons from Audio Processing"** -- Technical deep-dive on the audio pipeline.
3. **"Display Entities at Scale: Batching, Pooling, and Performance"** -- Minecraft plugin architecture.
4. **"Building a DJ Control Panel with Vanilla JS"** -- Admin panel design decisions.
5. **"Multi-DJ Architecture: How We Handle Concurrent Audio Sources"** -- VJ server design.
6. **"From Prototype to v1.0: What We Learned"** -- Retrospective.

**Action items:**
- [ ] Write posts 1 and 2 before launch (use as teaser content).
- [ ] Publish remaining posts on a weekly cadence after launch.
- [ ] Cross-post to Hacker News for post 2 or 3 (HN likes technical deep-dives).

### Creator Collaborations

**Tier 1 targets (aspirational, 1M+ subs):**
- Technical Minecraft creators (Mumbo Jumbo, ilmango, SciCraft members) -- the "how does this work" angle.
- Music + gaming crossover creators -- the "virtual concert" angle.

**Tier 2 targets (realistic, 100K-500K subs):**
- Smaller technical MC creators who cover new plugins and datapacks.
- Server showcase channels.
- Music production YouTubers who also play games.

**Outreach approach:**
- Do NOT cold-DM with "please feature our plugin."
- Instead: create a short (60s) custom demo using their server's aesthetic or their music. Send it with a brief note: "Made this with your [song/build] using our open-source tool. Thought you might find it interesting. No ask, just sharing."
- If they respond positively, offer early access, a pre-configured world download, and technical support.

**Action items:**
- [ ] Build a list of 20 Tier 2 creators with contact info.
- [ ] Record 5 personalized demo clips.
- [ ] Begin outreach 2 weeks before v1.0 launch.

### Conference Talks

**Target events:**
- PaperMC community calls or Discord events.
- PyCon (poster session or lightning talk: "Real-Time Audio Processing in Python").
- Local Minecraft meetups or gaming events.
- Music technology conferences (if the project gains enough traction).

**Action items:**
- [ ] Submit a PyCon lightning talk proposal for the next cycle.
- [ ] Reach out to PaperMC community organizers about presenting at a community event.

---

## 4. Launch Timeline

### Weeks 1-2: Beta Preparation

**Goal:** Ensure the project is polished, documented, and demo-ready.

| Task | Owner | Status |
|------|-------|--------|
| Complete all P0 documentation (README, quick-start, config reference) | -- | Not started |
| Record and edit hero demo video | -- | Not started |
| Record 10+ short-form clips | -- | Not started |
| Run internal bug bounty: invite 5-10 trusted testers from MC server admin communities | -- | Not started |
| Set up GitHub Actions for automated release builds | -- | Not started |
| Create Modrinth and SpigotMC project pages (draft, not published) | -- | Not started |
| Verify PyPI package installs cleanly on fresh systems (test in Docker) | -- | Not started |
| Write Reddit posts (drafts) | -- | Not started |
| Write dev blog posts 1 and 2 | -- | Not started |
| Set up Discord server with channel structure (see Section 7) | -- | Not started |
| Create project logo, banner, and consistent branding assets | -- | Not started |
| Prepare a pre-configured demo world download | -- | Not started |

### Week 3: Soft Launch

**Goal:** Get the project in front of early adopters and collect feedback before the full launch.

| Day | Action |
|-----|--------|
| Mon | Publish GitHub release (tag as `v1.0.0-rc.1`). Announce on personal Twitter/X. |
| Tue | Post dev blog #1 ("Why I Built a Concert Venue in Minecraft"). |
| Wed | Post to r/admincraft (practical, server-admin-focused). Open Discord invite to beta testers. |
| Thu | Begin posting short-form clips (2 per day for the rest of the week). |
| Fri | Post to r/MinecraftCommands (technical angle). |

**Action items:**
- [ ] Monitor GitHub Issues daily. Respond to all issues within 24 hours.
- [ ] Track which features/bugs beta testers report most frequently.
- [ ] Collect 3-5 quotes or testimonials from beta testers for the launch.

### Weeks 4-5: Feedback Cycle

**Goal:** Iterate on beta feedback. Fix critical bugs. Polish based on real-world usage.

| Task | Details |
|------|---------|
| Triage and fix critical bugs | Anything that prevents install or basic operation. |
| Update documentation based on common questions | If 3+ people ask the same question, it is a docs problem. |
| Refine demo video based on feedback | If testers say "the coolest part is X," make sure X is prominent. |
| Continue short-form content cadence | 2-3 clips per week. |
| Post dev blog #2 | "Real-Time FFT in Python" -- drives traffic from technical audiences. |
| Finalize creator outreach clips | Personalized demos for Tier 2 creators. |
| Prepare v1.0 release notes | Comprehensive changelog. |

### Week 6: v1.0 Launch

**Goal:** Maximum visibility across all channels in a coordinated 48-hour push.

| Day | Action |
|-----|--------|
| Mon (T-2) | Upload hero video to YouTube (unlisted). Share with creators under embargo. |
| Wed (Launch) AM | Publish GitHub release `v1.0.0`. Publish Modrinth and SpigotMC listings. Publish PyPI package. Make YouTube video public. |
| Wed (Launch) PM | Post to r/Minecraft with hero video. Post to Twitter/X. Send Discord announcement. |
| Thu (Launch +1) | Post to r/admincraft. Post to r/dataisbeautiful. Engage in all comment threads. |
| Fri (Launch +2) | Post to r/MinecraftCommands. Begin creator outreach emails. |
| Following week | Post to r/musicproduction, r/Python. Submit to Hacker News. Post dev blog #3. |

**Action items:**
- [ ] Prepare all Reddit posts in advance. Have them reviewed by someone outside the project for clarity.
- [ ] Schedule social media posts using a scheduler (Buffer, Typefully, or similar).
- [ ] Have a "war room" plan: dedicate launch day to monitoring and responding to feedback across all channels.

---

## 5. Competitive Landscape

### Current State of Audio Visualization in Minecraft

Audio visualization in Minecraft is an extremely underserved niche. There is no established, widely-used solution. This is both an opportunity (greenfield market) and a challenge (need to educate the audience that this is possible).

### Existing Approaches

| Approach | Description | Limitations |
|----------|-------------|-------------|
| **Command block note sequences** | Manually programmed note blocks and command blocks triggered in sequence. | Not reactive to real audio. Requires hours of manual programming per song. No dynamic response. |
| **Redstone music machines** | Complex redstone circuits that play pre-programmed note sequences. | Playback only, not visualization. Cannot respond to external audio. Massive builds for simple songs. |
| **Client-side mods** (e.g., custom Fabric/Forge mods) | Mods installed on each player's client that render visualizations locally. | Require every viewer to install the mod. Fragmented experience. High barrier to entry for audiences. Not suitable for events with casual attendees. |
| **External overlay tools** | OBS overlays or stream overlays that show visualizations outside Minecraft. | Not in-world. Breaks immersion. Only visible to stream viewers, not in-game players. |
| **Datapack-based approaches** | Server-side datapacks that use particles or armor stands. | Limited to particle effects (no Display Entity support in datapacks). Armor stands are far less performant than Display Entities. No real-time audio input. |

### MCAV Differentiators

| Differentiator | Details |
|----------------|---------|
| **Server-side only** | No client mods required. Any vanilla Minecraft client can see the visualizations. This is the single biggest advantage for events and content creation. |
| **Real-time audio reactivity** | Actual FFT analysis of live audio, not pre-programmed sequences. Responds to any music in real time. |
| **Display Entities** | Uses Minecraft's native Display Entity system (added in 1.19.4) for smooth, GPU-accelerated rendering. Far more performant and visually rich than armor stands or particles alone. |
| **Professional DJ/VJ tools** | Admin panel, multi-DJ support, pattern switching, preset management. No other MC visualization tool offers production-grade control. |
| **Multi-platform audio capture** | WASAPI capture on Windows with application-specific audio isolation. Not limited to microphone input. |
| **Extensible pattern system** | Python-based pattern API. New visualization patterns can be added without modifying the core plugin. |
| **3D browser preview** | Three.js preview allows pattern design and testing without a running Minecraft server. |
| **Desktop DJ client** | Rust/Tauri native application for dedicated DJ setups. |

### Competitive Risks

- **Mojang/Microsoft could build native audio visualization.** Unlikely in the near term, but Display Entity improvements could shift the landscape. Mitigation: stay ahead on features and community.
- **A well-funded team could build a competing plugin.** Possible, but the intersection of audio processing expertise and Minecraft plugin development is small. Mitigation: build community moat through contributor ecosystem and pattern library.
- **Client-side mod solutions could become easier to install.** Fabric/Quilt modloaders are getting more user-friendly. Mitigation: server-side-only will always be simpler for events and servers with casual players.

---

## 6. Monetization Strategy

### Philosophy

The core MCAV system is free and open source (MIT or similar license). Monetization focuses on convenience, services, and professional support. The open-source core is the growth engine; premium offerings serve users who need more than DIY.

### P0 -- Available at Launch

#### GitHub Sponsors
- Set up GitHub Sponsors with tiers:
  - **$5/month ("Supporter"):** Name in README, Sponsor badge in Discord.
  - **$15/month ("Patron"):** Early access to new patterns, vote on feature priorities.
  - **$50/month ("Producer"):** 1 hour of monthly support/consulting, priority bug fixes.
- **Action items:**
  - [ ] Set up GitHub Sponsors profile with clear value propositions per tier.
  - [ ] Add sponsor badges to README and Discord.

#### Event Production Consulting
- Offer paid consulting for organizations running large-scale virtual events in Minecraft.
- Rate: $100-200/hour for setup, configuration, and live event support.
- Target: corporate events, charity streams, music festival tie-ins.
- **Action items:**
  - [ ] Create a "Hire Us" page on the project website or GitHub wiki.
  - [ ] Document 1-2 case studies from beta events.

### P1 -- Post-Launch (Months 2-4)

#### Hosted VJ Server (SaaS)
- A managed VJ server that event organizers can connect to without self-hosting.
- Pricing: $20/event (up to 4 hours) or $50/month (unlimited events).
- Value proposition: No server setup, no Python installation, no port forwarding. Just connect your DJ client and your Minecraft server.
- **Action items:**
  - [ ] Evaluate hosting costs (a VJ server is lightweight; a small VPS should suffice).
  - [ ] Build a simple web dashboard for SaaS customers (event scheduling, connection credentials).
  - [ ] Write terms of service.

### P2 -- Growth Phase (Months 4-8)

#### Premium Pattern Packs
- Curated sets of 5-10 professionally designed visualization patterns.
- Pricing: $5-10 per pack.
- Examples: "Festival Pack" (large-scale arena patterns), "Chill Pack" (ambient, slow-moving patterns), "Retro Pack" (pixel art and 8-bit aesthetics).
- **Important:** Community-submitted patterns remain free. Premium packs are professionally designed, tested, and documented.

#### Custom Visualization Commissions
- Build custom patterns for specific songs, events, or brands.
- Pricing: $200-500 per custom pattern depending on complexity.
- Target: Event organizers, brands doing Minecraft activations, content creators wanting unique visuals.

#### Revenue Expectations (Year 1)

| Source | Estimated Annual Revenue |
|--------|------------------------|
| GitHub Sponsors | $500-2,000 |
| Event consulting | $2,000-5,000 |
| Hosted VJ server | $1,000-3,000 |
| Premium pattern packs | $500-1,500 |
| Custom commissions | $1,000-3,000 |
| **Total** | **$5,000-14,500** |

These are conservative estimates. Revenue is not the primary goal in year 1; adoption and community growth are. Revenue validates demand and funds infrastructure.

---

## 7. Community Building

### Discord Server Structure

Create a Discord server as the primary community hub.

```
MCAV Discord Server
|
+-- INFORMATION
|   +-- #welcome-and-rules
|   +-- #announcements
|   +-- #roadmap
|   +-- #faq
|
+-- SUPPORT
|   +-- #installation-help
|   +-- #plugin-support
|   +-- #audio-processor-support
|   +-- #dj-client-support
|
+-- COMMUNITY
|   +-- #general
|   +-- #showcase (post your setups, videos, screenshots)
|   +-- #pattern-sharing (share custom patterns)
|   +-- #event-planning
|   +-- #off-topic
|
+-- DEVELOPMENT
|   +-- #contributing
|   +-- #bug-reports
|   +-- #feature-requests
|   +-- #dev-chat
|
+-- VOICE
|   +-- General Voice
|   +-- Event Planning
|   +-- Live Demo
```

**Action items:**
- [ ] Create the Discord server and configure roles (Admin, Contributor, Supporter, Member).
- [ ] Write a welcoming #rules channel that sets a collaborative, respectful tone.
- [ ] Set up a bot for role assignment (e.g., reaction roles for "Server Admin," "Content Creator," "Developer").
- [ ] Create a #showcase channel with a pinned template for submissions (screenshot/video + pattern used + music genre + server specs).

### Contributor Program

**Goal:** Lower the barrier for community contributions, especially pattern submissions.

- **Pattern submission process:**
  1. Contributor writes a pattern extending `VisualizationPattern` in Python.
  2. Submits as a GitHub PR with a 15-second demo clip.
  3. Core team reviews for code quality, performance, and visual appeal.
  4. If accepted, pattern is included in the next release with credit in the changelog and README.

- **Other contribution paths:**
  - Documentation improvements and translations.
  - Bug reports with reproduction steps.
  - Performance profiling and optimization PRs.
  - Admin panel UI improvements.
  - Browser preview enhancements.

- **Recognition:**
  - Contributors get a Discord role and are listed in a CONTRIBUTORS.md file.
  - Top contributors are invited to a private #core-contributors channel.
  - Patterns are named by their creators (e.g., "Aurora" by @username).

**Action items:**
- [ ] Write a CONTRIBUTING.md with clear guidelines for each contribution type.
- [ ] Create a pattern submission PR template on GitHub.
- [ ] Set up a "Contributor" Discord role that is manually assigned on first merged PR.

### Monthly Community Events

| Event | Format | Frequency |
|-------|--------|-----------|
| **Visualizer Battle** | Two participants set up visualizations to the same track. Community votes on the best one via Discord poll. | Monthly |
| **Themed Challenge** | A theme is announced (e.g., "ambient," "retro," "chaos"). Participants have 2 weeks to create a pattern or setup. | Monthly |
| **Live Demo Night** | A community member hosts a live Minecraft session showcasing their setup. Others can join and watch. | Bi-weekly |
| **Dev Q&A** | Core team answers questions about architecture, upcoming features, and contribution opportunities. | Monthly |

**Action items:**
- [ ] Schedule the first Visualizer Battle for 2 weeks after v1.0 launch.
- [ ] Create an #events channel with a pinned schedule.
- [ ] Set up a simple voting system (Discord polls or a lightweight web form).

### Documentation-First Culture

- Every feature ships with documentation or it does not ship.
- User-facing documentation lives in `docs/` and is published to a documentation site (GitHub Pages or similar).
- API documentation is generated from docstrings and type hints.
- Common questions from Discord #support channels are turned into FAQ entries within 48 hours.

**Action items:**
- [ ] Set up a documentation site (MkDocs with Material theme or similar).
- [ ] Write a documentation style guide (tone: direct, practical, no jargon without explanation).
- [ ] Assign a rotating "docs duty" where a team member reviews and updates docs weekly.

---

## 8. KPIs and Success Metrics

### 30-Day Targets (Post v1.0 Launch)

| Metric | Target | How to Measure |
|--------|--------|----------------|
| GitHub stars | 100 | GitHub repository insights |
| Modrinth downloads | 500 | Modrinth project analytics |
| Total downloads (all platforms) | 750 | Sum of Modrinth + SpigotMC + PyPI + GitHub Releases |
| Discord members | 25 | Discord server member count |
| Community-submitted patterns | 5 | GitHub PRs tagged `pattern` |
| GitHub Issues filed (non-spam) | 15 | GitHub Issues (indicates real usage) |
| Hero video views | 5,000 | YouTube analytics |
| Reddit post upvotes (combined) | 500 | Reddit post scores |

### 90-Day Targets

| Metric | Target | How to Measure |
|--------|--------|----------------|
| GitHub stars | 500 | GitHub repository insights |
| Total downloads (all platforms) | 5,000 | Sum across all distribution channels |
| Discord members | 100 | Discord server member count |
| Combined video views (all platforms) | 50,000 | YouTube + TikTok + Shorts analytics |
| Content creator partnerships | 3 | Creators who have featured MCAV in a video |
| Community events hosted | 1 | Discord event completion |
| Contributor PRs merged | 10 | GitHub PR history |
| Active servers running MCAV | 20 | Self-reported in Discord or estimated from bStats (if integrated) |
| PyPI weekly downloads | 50 | PyPI download stats (pypistats.org) |
| Documentation site monthly visitors | 500 | Analytics on docs site |

### Tracking Infrastructure

- **GitHub:** Use repository Insights for stars, traffic, clones, and referrers.
- **Modrinth / SpigotMC:** Built-in download analytics.
- **PyPI:** Use pypistats.org or the PyPI API.
- **YouTube:** YouTube Studio analytics.
- **Discord:** Server Insights (available at 500+ members) or manual tracking.
- **bStats:** Consider integrating bStats into the Minecraft plugin for anonymous server count tracking (standard practice for MC plugins, opt-out by default).
- **Reddit:** Manual tracking of post performance.

**Action items:**
- [ ] Set up a simple spreadsheet or dashboard to track all KPIs weekly.
- [ ] Integrate bStats into the Minecraft plugin (with opt-out and privacy disclosure).
- [ ] Review KPIs at the end of each month and adjust strategy based on what is working.

### Leading Indicators to Watch

These are early signals that predict whether the project is gaining traction:

- **GitHub clone count trending up week-over-week.** Indicates growing awareness.
- **Discord #installation-help activity.** Means people are actually trying to install it (good) and having trouble (address quickly).
- **Ratio of GitHub stars to downloads.** If stars >> downloads, people like the idea but find installation too hard. If downloads >> stars, the tool is useful but not viral enough.
- **Repeat visitors to documentation site.** Indicates users are going deeper, not just bouncing.
- **#showcase posts in Discord.** The single best indicator of engaged users. If people are posting their setups voluntarily, the product is working.

---

## Appendix: Pre-Launch Checklist

A consolidated checklist of all action items that must be completed before launch.

- [ ] README is polished, concise, and has clear install instructions for all platforms.
- [ ] Quick-start guide exists and has been tested by someone who has never used the project.
- [ ] Hero demo video is recorded, edited, and uploaded (unlisted until launch).
- [ ] 10+ short-form clips are recorded and scheduled.
- [ ] GitHub Actions CI/CD pipeline builds and publishes releases automatically.
- [ ] Modrinth project page is drafted.
- [ ] SpigotMC resource page is drafted.
- [ ] PyPI package installs cleanly on a fresh system.
- [ ] Discord server is set up with all channels, roles, and welcome content.
- [ ] Reddit posts are drafted for r/Minecraft, r/admincraft, and r/MinecraftCommands.
- [ ] Dev blog posts 1 and 2 are written.
- [ ] Creator outreach list is built (20 Tier 2 creators).
- [ ] 5 personalized demo clips for creator outreach are recorded.
- [ ] CONTRIBUTING.md is written with pattern submission guidelines.
- [ ] GitHub Sponsors profile is configured.
- [ ] Pre-configured demo world download is built and tested.
- [ ] All KPI tracking is set up (spreadsheet or dashboard).
- [ ] bStats integration is added to the Minecraft plugin.
- [ ] Launch-day schedule is written and shared with anyone helping.
