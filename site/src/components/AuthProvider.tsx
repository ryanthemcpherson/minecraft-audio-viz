"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import type { ReactNode } from "react";
import type { User, UserProfile } from "@/lib/auth";
import {
  clearStoredRefreshToken,
  fetchMe,
  getStoredRefreshToken,
  logout as logoutApi,
  refreshToken,
  storeRefreshToken,
} from "@/lib/auth";

/** Default token lifetime in seconds (matches coordinator's 30-min default). */
const TOKEN_LIFETIME_SECONDS = 30 * 60;
/** Refresh 5 minutes before the token expires. */
const REFRESH_MARGIN_MS = 5 * 60 * 1000;

// ---------------------------------------------------------------------------
// Context shape
// ---------------------------------------------------------------------------

interface AuthContextValue {
  user: User | null;
  accessToken: string | null;
  loading: boolean;
  /** Call after a successful login/register to set tokens + user. */
  setAuth: (accessToken: string, refreshTokenValue: string, user: User) => void;
  /** Log out and clear stored tokens. */
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue>({
  user: null,
  accessToken: null,
  loading: true,
  setAuth: () => {},
  logout: async () => {},
});

export function useAuth() {
  return useContext(AuthContext);
}

// ---------------------------------------------------------------------------
// Provider
// ---------------------------------------------------------------------------

export default function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [expiresIn, setExpiresIn] = useState<number>(TOKEN_LIFETIME_SECONDS);

  // On mount, try to restore session from refresh token
  useEffect(() => {
    const controller = new AbortController();
    const stored = getStoredRefreshToken();
    if (!stored) {
      queueMicrotask(() => setLoading(false));
      return;
    }

    refreshToken(stored)
      .then((res) => {
        if (controller.signal.aborted) return;
        setAccessToken(res.access_token);
        storeRefreshToken(res.refresh_token);
        if (res.expires_in) setExpiresIn(res.expires_in);
        return fetchMe(res.access_token);
      })
      .then((profile: UserProfile | undefined) => {
        if (controller.signal.aborted) return;
        if (profile) setUser(profile);
      })
      .catch((err) => {
        console.error("Failed to restore session from refresh token:", err);
        // Refresh failed — clear stale token
        clearStoredRefreshToken();
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });

    return () => controller.abort();
  }, []);

  // Periodically refresh the access token before it expires
  useEffect(() => {
    if (!accessToken) return;

    let retryTimer: ReturnType<typeof setTimeout> | undefined;

    const refreshMs = Math.max(
      (expiresIn * 1000) - REFRESH_MARGIN_MS,
      60_000 // at least 1 minute
    );

    const timer = setTimeout(() => {
      const stored = getStoredRefreshToken();
      if (!stored) return;

      refreshToken(stored)
        .then((res) => {
          setAccessToken(res.access_token);
          storeRefreshToken(res.refresh_token);
          if (res.expires_in) setExpiresIn(res.expires_in);
        })
        .catch((err) => {
          console.warn("[MCAV] Token refresh failed, retrying in 5s:", err);
          // Retry once after 5s before giving up
          retryTimer = setTimeout(() => {
            const retryToken = getStoredRefreshToken();
            if (!retryToken) return;
            refreshToken(retryToken)
              .then((res) => {
                setAccessToken(res.access_token);
                storeRefreshToken(res.refresh_token);
                if (res.expires_in) setExpiresIn(res.expires_in);
              })
              .catch((retryErr) => {
                console.error("[MCAV] Token refresh retry failed:", retryErr);
              });
          }, 5000);
        });
    }, refreshMs);

    return () => {
      clearTimeout(timer);
      if (retryTimer !== undefined) clearTimeout(retryTimer);
    };
  }, [accessToken, expiresIn]);

  const setAuth = useCallback(
    (newAccessToken: string, refreshTokenValue: string, newUser: User) => {
      setAccessToken(newAccessToken);
      storeRefreshToken(refreshTokenValue);
      setUser(newUser);
    },
    []
  );

  const logout = useCallback(async () => {
    const stored = getStoredRefreshToken();
    if (stored) {
      try {
        await logoutApi(stored);
      } catch (err) {
        console.error("Failed to revoke refresh token on logout:", err);
      }
    }
    setAccessToken(null);
    setUser(null);
    clearStoredRefreshToken();
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ user, accessToken, loading, setAuth, logout }),
    [user, accessToken, loading, setAuth, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
