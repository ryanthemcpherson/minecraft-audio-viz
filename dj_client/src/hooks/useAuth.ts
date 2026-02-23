import { useState, useEffect, useCallback } from 'react';
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
  signInWithGoogle: () => Promise<void>;
  resendVerification: () => Promise<void>;
  verificationMessage: string | null;
  signOut: () => Promise<void>;
  clearError: () => void;
}

export function useAuth(): UseAuthReturn {
  const [isLoading, setIsLoading] = useState(true);
  const [user, setUser] = useState<UserProfileResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [verificationMessage, setVerificationMessage] = useState<string | null>(null);

  const isSignedIn = user !== null;

  // Initialize token store, then restore session
  useEffect(() => {
    api.initTokenStore().then(() => {
      if (!api.hasTokens()) {
        setIsLoading(false);
        return;
      }

      api
        .getProfile()
        .then(setUser)
        .catch(() => {
          void api.clearTokens();
        })
        .finally(() => setIsLoading(false));
    }).catch(() => {
      setIsLoading(false);
    });
  }, []);

  // Periodic token refresh
  useEffect(() => {
    if (!isSignedIn) return;
    const interval = setInterval(async () => {
      if (api.isTokenExpiringSoon()) {
        try {
          await api.refreshTokens();
        } catch {
          // Silent — will retry next interval
        }
      }
    }, 30_000);
    return () => clearInterval(interval);
  }, [isSignedIn]);

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
    setIsLoading(true);
    try {
      const { authorize_url, poll_token } = await api.getDiscordAuthorizeUrl(true);
      await open(authorize_url);
      if (!poll_token) {
        setError('Server did not return poll token');
        setIsLoading(false);
        return;
      }
      const maxAttempts = 150; // 5 minutes at 2s intervals
      for (let i = 0; i < maxAttempts; i++) {
        await new Promise(r => setTimeout(r, 2000));
        const result = await api.pollDesktopAuth(poll_token);
        if (result.status === 'complete' && result.exchange_code) {
          await api.exchangeDesktopCode(result.exchange_code);
          const profile = await api.getProfile();
          setUser(profile);
          return;
        }
        if (result.status === 'expired') break;
      }
      setError('Sign-in timed out. Please try again.');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setIsLoading(false);
    }
  }, []);

  const signInWithGoogle = useCallback(async () => {
    setError(null);
    setIsLoading(true);
    try {
      const { authorize_url, poll_token } = await api.getGoogleAuthorizeUrl(true);
      await open(authorize_url);
      if (!poll_token) {
        setError('Server did not return poll token');
        setIsLoading(false);
        return;
      }
      const maxAttempts = 150; // 5 minutes at 2s intervals
      for (let i = 0; i < maxAttempts; i++) {
        await new Promise(r => setTimeout(r, 2000));
        const result = await api.pollDesktopAuth(poll_token);
        if (result.status === 'complete' && result.exchange_code) {
          await api.exchangeDesktopCode(result.exchange_code);
          const profile = await api.getProfile();
          setUser(profile);
          return;
        }
        if (result.status === 'expired') break;
      }
      setError('Sign-in timed out. Please try again.');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setIsLoading(false);
    }
  }, []);

  const resendVerification = useCallback(async () => {
    setVerificationMessage(null);
    try {
      const { message } = await api.resendVerification();
      setVerificationMessage(message);
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
    signInWithGoogle,
    resendVerification,
    verificationMessage,
    signOut,
    clearError,
  };
}
