import { useState, useEffect, useCallback } from 'react';
import { onOpenUrl } from '@tauri-apps/plugin-deep-link';
import { open } from '@tauri-apps/plugin-shell';
import * as api from '../lib/api';
import type { UserProfileResponse } from '../lib/api';

export interface UseAuthReturn {
  isLoading: boolean;
  isSignedIn: boolean;
  user: UserProfileResponse | null;
  error: string | null;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  signInWithDiscord: () => Promise<void>;
  signOut: () => Promise<void>;
  clearError: () => void;
}

export function useAuth(): UseAuthReturn {
  const [isLoading, setIsLoading] = useState(true);
  const [user, setUser] = useState<UserProfileResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const isSignedIn = user !== null;

  // Restore session on mount
  useEffect(() => {
    if (!api.hasStoredTokens()) {
      setIsLoading(false);
      return;
    }

    api
      .getProfile()
      .then(setUser)
      .catch(() => {
        api.clearTokens();
      })
      .finally(() => setIsLoading(false));
  }, []);

  // Listen for deep-link callback (Discord OAuth desktop flow)
  useEffect(() => {
    const unlisten = onOpenUrl(async (urls) => {
      for (const rawUrl of urls) {
        try {
          const parsed = new URL(rawUrl);
          const exchangeCode = parsed.searchParams.get('exchange_code');
          if (!exchangeCode) continue;

          setIsLoading(true);
          setError(null);
          await api.exchangeDesktopCode(exchangeCode);
          const profile = await api.getProfile();
          setUser(profile);
          break;
        } catch (e) {
          setError(e instanceof Error ? e.message : String(e));
        } finally {
          setIsLoading(false);
        }
      }
    });

    return () => {
      unlisten.then((fn) => fn()).catch(() => {});
    };
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    setIsLoading(true);
    setError(null);
    try {
      await api.login(email, password);
      const profile = await api.getProfile();
      setUser(profile);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      throw e;
    } finally {
      setIsLoading(false);
    }
  }, []);

  const register = useCallback(
    async (email: string, password: string, displayName: string) => {
      setIsLoading(true);
      setError(null);
      try {
        await api.register(email, password, displayName);
        const profile = await api.getProfile();
        setUser(profile);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        throw e;
      } finally {
        setIsLoading(false);
      }
    },
    [],
  );

  const signInWithDiscord = useCallback(async () => {
    setError(null);
    try {
      const { authorize_url } = await api.getDiscordAuthorizeUrl(true);
      await open(authorize_url);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  const signOut = useCallback(async () => {
    await api.logout();
    setUser(null);
    setError(null);
  }, []);

  const clearError = useCallback(() => setError(null), []);

  return {
    isLoading,
    isSignedIn,
    user,
    error,
    login,
    register,
    signInWithDiscord,
    signOut,
    clearError,
  };
}
