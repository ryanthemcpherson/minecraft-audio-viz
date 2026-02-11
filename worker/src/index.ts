/**
 * Cloudflare Worker: Wildcard DNS tenant router for *.mcav.live
 *
 * Extracts the subdomain from the Host header and:
 * 1. No subdomain or "www" → proxy to main site
 * 2. "api" → pass through to coordinator
 * 3. Any other subdomain → resolve tenant via coordinator API, serve landing page
 */

interface Env {
  COORDINATOR_API: string;
  SITE_ORIGIN: string;
}

interface TenantOrg {
  id: string;
  name: string;
  slug: string;
  description: string | null;
  avatar_url: string | null;
}

interface TenantServer {
  id: string;
  name: string;
  is_active: boolean;
  show_count: number;
}

interface TenantShow {
  id: string;
  name: string;
  connect_code: string | null;
  current_djs: number;
  max_djs: number;
  server_name: string;
}

interface TenantData {
  org: TenantOrg;
  servers: TenantServer[];
  active_shows: TenantShow[];
}

function extractSubdomain(host: string): string | null {
  const hostname = host.split(":")[0];
  if (hostname.endsWith(".mcav.live")) {
    const sub = hostname.replace(".mcav.live", "");
    return sub || null;
  }
  return null;
}

function escapeHtml(str: string): string {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function renderLandingPage(data: TenantData): string {
  const showCards = data.active_shows
    .map(
      (show) => `
      <div class="card">
        <div class="card-header">
          <h3>${escapeHtml(show.name)}</h3>
          <span class="badge">${show.current_djs}/${show.max_djs} DJs</span>
        </div>
        <p class="server-name">${escapeHtml(show.server_name)}</p>
        ${
          show.connect_code
            ? `<a href="https://mcav.live/connect/${encodeURIComponent(show.connect_code)}" class="btn">
                Join: ${escapeHtml(show.connect_code)}
              </a>`
            : '<span class="closed">No connect code</span>'
        }
      </div>`
    )
    .join("");

  const noShows =
    data.active_shows.length === 0
      ? '<p class="empty">No active shows right now. Check back later!</p>'
      : "";

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(data.org.name)} – MCAV</title>
  <style>
    *, *::before, *::after { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      background: #0a0a0a;
      color: #f5f5f5;
      font-family: Inter, system-ui, -apple-system, sans-serif;
      min-height: 100vh;
    }
    .container { max-width: 720px; margin: 0 auto; padding: 3rem 1.5rem; }
    .header { text-align: center; margin-bottom: 3rem; }
    .header h1 { font-size: 2.5rem; font-weight: 700; margin-bottom: 0.5rem; }
    .header p { color: #a1a1aa; font-size: 1.1rem; line-height: 1.6; }
    .powered { color: #52525b; font-size: 0.85rem; margin-top: 0.75rem; }
    .powered a { color: #00D4FF; text-decoration: none; }
    .powered a:hover { text-decoration: underline; }
    h2 { font-size: 1.3rem; font-weight: 600; margin-bottom: 1rem; color: #e4e4e7; }
    .card {
      background: rgba(255,255,255,0.03);
      border: 1px solid rgba(255,255,255,0.06);
      border-radius: 12px;
      padding: 1.25rem 1.5rem;
      margin-bottom: 1rem;
    }
    .card-header { display: flex; align-items: center; justify-content: space-between; }
    .card h3 { font-size: 1.1rem; font-weight: 600; }
    .badge {
      font-size: 0.8rem;
      padding: 0.25rem 0.75rem;
      border-radius: 9999px;
      background: rgba(0,212,255,0.1);
      color: #00D4FF;
    }
    .server-name { color: #71717a; font-size: 0.85rem; margin: 0.5rem 0 1rem; }
    .btn {
      display: inline-block;
      background: linear-gradient(135deg, #00D4FF, #7B2FFF);
      color: #fff;
      text-decoration: none;
      font-weight: 600;
      font-size: 0.9rem;
      padding: 0.6rem 1.5rem;
      border-radius: 8px;
      transition: opacity 0.2s;
    }
    .btn:hover { opacity: 0.85; }
    .closed { color: #52525b; font-size: 0.85rem; }
    .empty { color: #71717a; text-align: center; padding: 2rem; }
    .footer {
      text-align: center;
      margin-top: 3rem;
      padding-top: 2rem;
      border-top: 1px solid rgba(255,255,255,0.06);
      color: #52525b;
      font-size: 0.85rem;
    }
    .footer a { color: #00D4FF; text-decoration: none; }
    .footer a:hover { text-decoration: underline; }
  </style>
</head>
<body>
  <div class="container">
    <div class="header">
      <h1>${escapeHtml(data.org.name)}</h1>
      ${data.org.description ? `<p>${escapeHtml(data.org.description)}</p>` : ""}
      <p class="powered">Powered by <a href="https://mcav.live">MCAV</a></p>
    </div>

    <section>
      <h2>Active Shows</h2>
      ${noShows}
      ${showCards}
    </section>

    <div class="footer">
      <a href="https://mcav.live">mcav.live</a>
    </div>
  </div>
</body>
</html>`;
}

function renderNotFoundPage(slug: string): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Not Found – MCAV</title>
  <style>
    *, *::before, *::after { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      background: #0a0a0a;
      color: #f5f5f5;
      font-family: Inter, system-ui, -apple-system, sans-serif;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
    }
    .c { text-align: center; }
    h1 { font-size: 2rem; margin-bottom: 1rem; }
    p { color: #a1a1aa; margin-bottom: 1.5rem; }
    a { color: #00D4FF; text-decoration: none; }
    a:hover { text-decoration: underline; }
  </style>
</head>
<body>
  <div class="c">
    <h1>Not Found</h1>
    <p>&ldquo;${escapeHtml(slug)}&rdquo; is not a registered MCAV server.</p>
    <a href="https://mcav.live">Go to mcav.live</a>
  </div>
</body>
</html>`;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const host = url.hostname;
    const subdomain = extractSubdomain(host);

    // No subdomain or reserved → proxy to main site
    if (!subdomain || subdomain === "www") {
      return fetch(
        new Request(env.SITE_ORIGIN + url.pathname + url.search, request)
      );
    }

    // API subdomain → pass through (already routed by DNS)
    if (subdomain === "api") {
      // Prevent forwarding loops: if our own header is present, bail out
      if (request.headers.get("X-MCAV-Worker")) {
        return new Response("Loop detected", { status: 508 });
      }
      const fwdRequest = new Request(request);
      fwdRequest.headers.set("X-MCAV-Worker", "1");
      return fetch(fwdRequest);
    }

    // Tenant resolution
    try {
      const tenantResp = await fetch(
        `${env.COORDINATOR_API}/api/v1/tenants/resolve?slug=${encodeURIComponent(subdomain)}`,
        { headers: { Accept: "application/json" } }
      );

      if (!tenantResp.ok) {
        if (tenantResp.status === 404) {
          return new Response(renderNotFoundPage(subdomain), {
            status: 404,
            headers: { "Content-Type": "text/html; charset=utf-8" },
          });
        }
        return new Response("Service unavailable", { status: 502 });
      }

      const data: TenantData = await tenantResp.json();
      return new Response(renderLandingPage(data), {
        status: 200,
        headers: {
          "Content-Type": "text/html; charset=utf-8",
          "Cache-Control": "public, max-age=60, s-maxage=60",
          "X-Frame-Options": "DENY",
          "X-Content-Type-Options": "nosniff",
          "Strict-Transport-Security": "max-age=31536000",
        },
      });
    } catch {
      return new Response("Service unavailable", { status: 502 });
    }
  },
};
