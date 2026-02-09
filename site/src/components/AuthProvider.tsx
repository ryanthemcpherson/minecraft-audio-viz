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

  // On mount, try to restore session from refresh token
  useEffect(() => {
    const stored = getStoredRefreshToken();
    if (!stored) {
      setLoading(false);
      return;
    }

    refreshToken(stored)
      .then((res) => {
        setAccessToken(res.access_token);
        storeRefreshToken(res.refresh_token);
        return fetchMe(res.access_token);
      })
      .then((profile: UserProfile) => {
        setUser(profile);
      })
      .catch(() => {
        // Refresh failed — clear stale token
        clearStoredRefreshToken();
      })
      .finally(() => setLoading(false));
  }, []);

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
      } catch {
        // Best-effort revocation
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
