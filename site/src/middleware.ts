import { NextRequest, NextResponse } from "next/server";

/**
 * Intercept the Discord OAuth callback before the page loads so password
 * managers (1Password, etc.) never see the /auth/callback?code=…&state=… URL.
 *
 * Flow:
 *   1. Discord redirects to /auth/callback?code=X&state=Y
 *   2. This middleware stashes code+state in a short-lived cookie
 *   3. Redirects to /login (clean URL that 1Password saves)
 *   4. The login page reads the cookie and exchanges the code
 */
export function middleware(request: NextRequest) {
  const { pathname, searchParams } = request.nextUrl;

  if (pathname === "/auth/callback") {
    const code = searchParams.get("code");
    const state = searchParams.get("state");
    const error = searchParams.get("error");
    const errorDesc = searchParams.get("error_description");

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
      });
    }

    return response;
  }
}

export const config = {
  matcher: "/auth/callback",
};
