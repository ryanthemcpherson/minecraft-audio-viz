import { useEffect, useState } from 'react';
import {
  getTlsFingerprintFieldState,
  loadTlsFingerprint,
  normalizeTlsFingerprint,
  saveTlsFingerprint,
  type FingerprintStorage,
} from '../lib/connectionProfile';

interface FingerprintProfileState {
  value: string;
  userEdited: boolean;
}

export function useTlsFingerprintProfile(
  storage: FingerprintStorage = localStorage,
) {
  const [profile, setProfile] = useState<FingerprintProfileState>(() => ({
    value: loadTlsFingerprint(storage),
    userEdited: false,
  }));
  const fieldState = getTlsFingerprintFieldState(profile.value);

  useEffect(() => {
    if (profile.userEdited) {
      saveTlsFingerprint(storage, fieldState.normalizedValue);
    }
  }, [fieldState.normalizedValue, profile.userEdited, storage]);

  const setTlsFingerprint = (fingerprint: string) => {
    setProfile({
      value: normalizeTlsFingerprint(fingerprint),
      userEdited: true,
    });
  };

  return {
    tlsFingerprint: fieldState.normalizedValue,
    setTlsFingerprint,
    normalizedTlsFingerprint: fieldState.normalizedValue,
    isTlsFingerprintValid: fieldState.isValid,
  };
}
