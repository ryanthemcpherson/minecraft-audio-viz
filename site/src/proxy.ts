import { NextRequest, NextResponse } from "next/server";

/**
 * Detect a desktop OAuth state by peeking at the JWT payload.
 * Desktop flows must reach the /auth/callback page component so it can
 * redirect the browser to the coordinator for a mcav:// deep-link.
 */
function isDesktopOAuthState(state: string): boolean {
  try {
    let b64 = state.split(".")[1];
    // JWT uses base64url; convert to standard base64 for atob()
    b64 = b64.replace(/-/g, "+").replace(/_/g, "/");
    while (b64.length % 4) b64 += "=";
    const payload = JSON.parse(atob(b64));
    return !!payload.desktop;
  } catch {
    return false;
  }
}

/**
 * Intercept the Discord OAuth callback before the page loads so password
 * managers (1Password, etc.) never see the /auth/callback?code=…&state=… URL.
 *
 * Web flow:
 *   1. Discord redirects to /auth/callback?code=X&state=Y
 *   2. This middleware stashes code+state in a short-lived cookie
 *   3. Redirects to /login (clean URL that 1Password saves)
 *   4. The login page reads the cookie and exchanges the code
 *
 * Desktop flow (DJ client):
 *   The state JWT contains desktop:true. We let the request through to the
 *   /auth/callback page, which redirects the browser to the coordinator.
 *   The coordinator returns HTML with a mcav:// deep-link back to the app.
 */
export function proxy(request: NextRequest) {
  const { pathname, searchParams } = request.nextUrl;

  if (pathname === "/auth/callback") {
    const code = searchParams.get("code");
    const state = searchParams.get("state");
    const error = searchParams.get("error");
    const errorDesc = searchParams.get("error_description");

    // Desktop OAuth flow — let the page component handle the deep-link redirect
    if (!error && state && isDesktopOAuthState(state)) {
      return NextResponse.next();
    }

    const url = request.nextUrl.clone();
    url.pathname = "/login";
    url.search = "";

    const response = NextResponse.redirect(url);

    if (error) {
      // OAuth error — pass it via cookie so login page can display it.
      // httpOnly is false because client JS needs to read the value on
      // the login page. This is mitigated by the 60-second TTL and the
      // fact that the error string is not a secret.
      response.cookies.set("mcav_oauth_error", errorDesc || error, {
        path: "/",
        maxAge: 60,
        httpOnly: false,
        sameSite: "lax",
        secure: process.env.NODE_ENV === "production",
      });
    } else if (code && state) {
      // Success — pass code and state via cookie.
      // httpOnly is false because client JS on /login must read the
      // code+state to exchange it for tokens. Mitigated by 60-second
      // TTL and the code being single-use (consumed by the OAuth
      // provider on first exchange).
      response.cookies.set("mcav_oauth_code", `${code}:${state}`, {
        path: "/",
        maxAge: 60,
        httpOnly: false,
        sameSite: "lax",
        secure: process.env.NODE_ENV === "production",
      });
    }

    return response;
  }
}

export const config = {
  matcher: "/auth/callback",
};
