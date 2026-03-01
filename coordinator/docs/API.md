# MCAV Coordinator API Reference

The coordinator exposes a REST API for DJ authentication, show management, organization management, and the unified dashboard.

## Interactive Docs

FastAPI auto-generates interactive API documentation:

- **Swagger UI**: `{base_url}/docs`
- **ReDoc**: `{base_url}/redoc`
- **OpenAPI JSON**: `{base_url}/openapi.json`

In development: <http://localhost:8090/docs>

## Authentication

Three authentication mechanisms are used depending on the endpoint:

| Mechanism | Header | Used by |
|-|-|-|
| User JWT | `Authorization: Bearer <access_token>` | Site/DJ client (most endpoints) |
| Server API key | `Authorization: Bearer <api_key>` | VJ servers (server/show management) |
| Webhook secret | `X-Webhook-Secret: <secret>` | Discord community bot |

User JWTs are obtained via `/api/v1/auth/register`, `/api/v1/auth/login`, or the OAuth flows and refreshed via `/api/v1/auth/refresh`.

Server API keys are generated during server registration and cannot be retrieved again.

## Endpoints

All `/api/v1` endpoints are prefixed below. Health and metrics endpoints have no prefix.

### Health & Metrics (public)

| Method | Path | Description |
|-|-|-|
| GET | `/health` | Service health check with active server/show counts |
| GET | `/metrics` | Prometheus-format counters and HTTP latency histograms |

### Auth (`/api/v1/auth`)

| Method | Path | Auth | Description |
|-|-|-|-|
| POST | `/auth/register` | none | Create account with email/password |
| POST | `/auth/login` | none | Login with email/password |
| GET | `/auth/discord` | none | Get Discord OAuth authorize URL |
| GET | `/auth/discord/callback` | none | Discord OAuth redirect handler |
| GET | `/auth/google` | none | Get Google OAuth authorize URL |
| GET | `/auth/google/callback` | none | Google OAuth redirect handler |
| POST | `/auth/exchange` | none | Exchange desktop OAuth code for tokens |
| GET | `/auth/desktop-poll/{poll_token}` | none | Poll for desktop OAuth completion |
| POST | `/auth/forgot-password` | none | Request password reset email |
| POST | `/auth/reset-password` | none | Reset password with token |
| POST | `/auth/verify-email` | none | Verify email with token |
| POST | `/auth/refresh` | none | Refresh access token |
| GET | `/auth/me` | user | Get current user profile |
| PATCH | `/auth/me` | user | Update current user account |
| POST | `/auth/change-password` | user | Change password |
| DELETE | `/auth/account` | user | Soft-delete (deactivate) account |
| POST | `/auth/logout` | user | Revoke a refresh token |
| POST | `/auth/resend-verification` | user | Resend email verification link |
| GET | `/auth/sessions` | user | List active sessions |
| DELETE | `/auth/sessions/{session_id}` | user | Revoke a specific session |
| POST | `/auth/admin/cleanup-tokens` | admin | Delete expired/revoked refresh tokens |

### Connect Codes (`/api/v1`)

| Method | Path | Auth | Description |
|-|-|-|-|
| GET | `/connect/{code}` | none | Resolve a connect code to server metadata |
| POST | `/connect/{code}/join` | none | Join a show and mint a DJ session token |
| POST | `/disconnect/{dj_session_id}` | none | Notify that a DJ has disconnected |

### Servers (`/api/v1`)

| Method | Path | Auth | Description |
|-|-|-|-|
| POST | `/servers/register` | none | Register a new VJ server |
| PUT | `/servers/{server_id}/heartbeat` | server | Update server heartbeat |

### Shows (`/api/v1`)

| Method | Path | Auth | Description |
|-|-|-|-|
| POST | `/shows` | server | Create a new show |
| GET | `/shows/{show_id}` | server | Get show details |
| DELETE | `/shows/{show_id}` | server | End an active show |

### Organizations (`/api/v1/orgs`)

| Method | Path | Auth | Description |
|-|-|-|-|
| POST | `/orgs` | user | Create a new organization |
| GET | `/orgs/by-slug/{slug}` | user (member) | Resolve org by slug |
| GET | `/orgs/{org_id}` | user (member) | Get organization details |
| PUT | `/orgs/{org_id}` | user (owner) | Update organization |
| POST | `/orgs/join` | user | Join org via invite code |
| POST | `/orgs/{org_id}/servers` | user (owner) | Link a server to the org |
| GET | `/orgs/{org_id}/servers` | user (member) | List org servers |
| POST | `/orgs/{org_id}/servers/register` | user (owner) | Register a new server for the org |
| DELETE | `/orgs/{org_id}/servers/{server_id}` | user (owner) | Unlink a server from the org |
| POST | `/orgs/{org_id}/invites` | user (owner) | Create an invite code |
| GET | `/orgs/{org_id}/invites` | user (owner) | List active invite codes |
| DELETE | `/orgs/{org_id}/invites/{invite_id}` | user (owner) | Deactivate an invite code |

### Onboarding (`/api/v1/onboarding`)

| Method | Path | Auth | Description |
|-|-|-|-|
| POST | `/onboarding/complete` | user | Complete onboarding with user type |
| POST | `/onboarding/skip` | user | Skip onboarding |
| POST | `/onboarding/reset` | user | Reset onboarding status |
| POST | `/onboarding/reset-full` | user | Full account reset (dev only) |

### DJ Profiles (`/api/v1/dj`)

| Method | Path | Auth | Description |
|-|-|-|-|
| POST | `/dj/profile` | user | Create DJ profile |
| GET | `/dj/profile` | user | Get own DJ profile |
| PUT | `/dj/profile` | user | Update own DJ profile |
| GET | `/dj/slug-check/{slug}` | none | Check if a slug is available |
| GET | `/dj/by-slug/{slug}` | none | Get public DJ profile by slug |
| GET | `/dj/{user_id}` | none | Get public DJ profile by user ID |

### Roles (`/api/v1/users`)

| Method | Path | Auth | Description |
|-|-|-|-|
| GET | `/users/me/roles` | user | Get current user's roles |
| PUT | `/users/me/roles` | user | Add roles (union merge) |
| DELETE | `/users/me/roles/{role}` | user | Remove a specific role |

### Dashboard (`/api/v1/dashboard`)

| Method | Path | Auth | Description |
|-|-|-|-|
| GET | `/dashboard/summary` | user | Role-specific dashboard data |
| GET | `/dashboard/unified` | user | Unified dashboard (all capabilities) |

### Tenants (`/api/v1/tenants`)

| Method | Path | Auth | Description |
|-|-|-|-|
| GET | `/tenants/resolve?slug=` | none | Resolve subdomain to org, servers, and active shows |

### Uploads (`/api/v1/uploads`)

| Method | Path | Auth | Description |
|-|-|-|-|
| POST | `/uploads/presigned-url` | user | Get presigned URL for image upload |

### Internal (`/api/v1/internal`)

| Method | Path | Auth | Description |
|-|-|-|-|
| GET | `/internal/dj-profile/{dj_session_id}` | server | Get DJ profile for a session |

### Admin (`/api/v1/admin`)

| Method | Path | Auth | Description |
|-|-|-|-|
| GET | `/admin/stats` | admin | Site-wide statistics |
| GET | `/admin/users` | admin | List all users (searchable) |
| PATCH | `/admin/users/{user_id}` | admin | Update user active/admin status |
| GET | `/admin/organizations` | admin | List all organizations |
| GET | `/admin/servers` | admin | List all servers |
| GET | `/admin/shows` | admin | List all shows (filterable by status) |

### Discord Webhooks (`/webhooks/discord`)

| Method | Path | Auth | Description |
|-|-|-|-|
| POST | `/webhooks/discord/role-sync` | webhook | Push role changes from Discord |
| DELETE | `/webhooks/discord/role-sync/{discord_id}/{role}` | webhook | Remove a role via Discord |
| GET | `/webhooks/discord/users/{discord_id}` | webhook | Look up user by Discord ID |

## Common Response Patterns

- **Pagination**: Most list endpoints accept `limit` (default 50) and `offset` (default 0) query parameters.
- **Errors**: Standard JSON `{"detail": "message"}` format.
- **Rate limiting**: Connect endpoints are rate-limited (10 req/IP/min). Returns 429 with `Retry-After` header.
- **Idempotency**: `POST /connect/{code}/join` supports `Idempotency-Key` header (5-minute TTL).
