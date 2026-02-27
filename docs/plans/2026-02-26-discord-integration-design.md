# Discord Integration Design

Date: 2026-02-26

## Goals

1. Set up the MCAV Discord server with proper channel structure for community, support, and announcements
2. Build a community bot (separate from the audio bot) for role management and welcome flows
3. Implement two-way role sync between mcav.live (coordinator) and Discord
4. Support multiple roles per user with union-based merge and explicit-only removals

## Roles

| Role | Self-assignable | Description |
|-|-|-|
| DJ | Yes (button) | Music creators / audio streamers |
| Server Owner | Yes (button) | Minecraft server admins using MCAV |
| VJ | Yes (button) | Visual pattern creators |
| Developer | Yes (button) | Contributors to the open source project |
| Beta Tester | Yes (button) | Early access to new features |
| Verified | No (auto) | Linked Discord to mcav.live account |

## Discord Server Channel Structure

### Category: WELCOME
- `#rules` — server rules (read-only)
- `#welcome` — welcome message + link to mcav.live (read-only)
- `#roles` — button-based self-assign for DJ, Server Owner, VJ, Developer, Beta Tester

### Category: GENERAL
- `#general` — community chat
- `#announcements` — release notes, updates (read-only)
- `#dev-progress` — development clips/screenshots
- `#showcase` — community shares visualizations
- `#support` — setup help
- `#ideas` — feature requests and suggestions

### Category: ROLE-GATED
- `#dj-lounge` — requires DJ or Verified
- `#server-owners` — requires Server Owner or Verified
- `#vj-lab` — requires VJ or Verified
- `#dev-chat` — requires Developer

### Category: VOICE
- `General Voice` — open to all
- `Live DJ` — for bot audio streaming

## Community Bot (`community_bot/`)

Standalone Python bot using `discord.py`, separate from the existing audio bot (`discord_bot/`).

### Features

1. **Role self-assign** — Posts an embed with buttons in `#roles`. Clicking toggles the role on/off. Verified is not self-assignable.
2. **Role sync with coordinator** — Webhook-driven, near-instant. Bot runs a lightweight aiohttp server alongside discord.py.
3. **Role removal sync** — Only explicit removals propagate. No automatic pruning.
4. **Welcome message** — Greets new members, links to `#roles` and mcav.live.

### Communication

Bot exposes an HTTP webhook endpoint. Coordinator POSTs role changes to it. Bot calls coordinator API to push Discord-side changes.

## Coordinator API Changes

### New DB Table: `user_roles`

| Column | Type | Description |
|-|-|-|
| id | UUID | Primary key |
| user_id | UUID FK | References users.id |
| role | Enum | dj, server_owner, vj, developer, beta_tester |
| source | Enum | discord, coordinator, both |
| created_at | DateTime | When role was assigned |

Replaces the single `user_type` string field. Migration preserves existing data.

### New/Modified Endpoints

- `GET /users/{user_id}/roles` — get user's roles
- `PUT /users/{user_id}/roles` — update roles (from mcav.live frontend)
- `POST /webhooks/discord/role-sync` — bot pushes Discord role changes (authed with shared secret)
- `POST /notify/discord/role-change` — coordinator notifies bot of mcav.live role changes

## Sync Flows

### Flow 1: Self-assign in Discord
1. User clicks "DJ" button in `#roles`
2. Bot assigns Discord role
3. Bot checks if user has linked mcav.live account (`GET /users/by-discord/{discord_id}`)
4. If linked: bot calls `POST /webhooks/discord/role-sync` with new role
5. Coordinator adds role to `user_roles`

### Flow 2: Update roles on mcav.live
1. User toggles "VJ" on profile
2. Coordinator saves to `user_roles`
3. Coordinator calls `POST /notify/discord/role-change` with discord_id and role set
4. Bot assigns/removes Discord role

### Flow 3: Link Discord account (first time)
1. User does Discord OAuth on mcav.live
2. Coordinator saves discord_id, calls bot webhook
3. Bot assigns Verified role + union of all roles from both sides
4. Bot pushes Discord-only roles back to coordinator

### Flow 4: Explicit role removal
1. User clicks active role button (toggle off) in Discord or removes on mcav.live
2. Webhook flows fire with role removed
3. Other side removes it too

## Sync Rules

- **Union merge**: roles only accumulate, never auto-removed
- **Explicit removals only**: user must intentionally remove a role for it to propagate
- **Verified is coordinator-only**: assigned automatically when Discord is linked, cannot be self-assigned

## Deployment

- Separate process alongside existing services
- Shares `.env` for coordinator URL, bot token, webhook secret
- Entry point: `python -m community_bot` or CLI `mcav-community-bot`
- Config: `MCAV_COMMUNITY_BOT_TOKEN`, `MCAV_COORDINATOR_URL`, `MCAV_WEBHOOK_SECRET`, `MCAV_DISCORD_GUILD_ID`
