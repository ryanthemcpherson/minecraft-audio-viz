import { useState, useEffect, useRef, useCallback } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { getCurrent, onOpenUrl } from '@tauri-apps/plugin-deep-link';
import type { UseAuthReturn } from './useAuth';
import { PRESETS } from '../components/PresetBar';
import * as api from '../lib/api';
import type {
  ConnectionStatus,
  AudioLevels,
  VoiceStatus,
  CaptureMode,
  RosterUpdate,
  AudioData,
} from '../types';

const CONNECT_CODE_PATTERN = /^[A-Z]{4}-[A-HJ-KM-NP-Z2-9]{4}$/u;
const FINGERPRINT_PATTERN = /^[0-9A-F]{64}$/u;
const MAX_SERVER_URL_BYTES = 2048;
const MAX_INVITE_JSON_BYTES = 4096;
const MAX_ENCODED_INVITE_BYTES = Math.ceil(MAX_INVITE_JSON_BYTES / 3) * 4;
const MAX_INVITE_TTL_SECONDS = 86_400;
const MAX_CLOCK_SKEW_SECONDS = 300;

export interface ServerProfile {
  serverUrl: string;
  certificateSha256: string | null;
}

export interface ParsedDjInvite extends ServerProfile {
  connectCode: string;
  expiresAt: number;
}

function utf8ByteLength(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

export function normalizeDjServerUrl(value: string): string {
  if (typeof value !== 'string' || utf8ByteLength(value) > MAX_SERVER_URL_BYTES) {
    throw new TypeError('Server URL is invalid or too long');
  }

  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new TypeError('Server URL is invalid');
  }
  if (
    parsed.protocol !== 'wss:' ||
    !parsed.hostname ||
    parsed.username ||
    parsed.password ||
    parsed.pathname !== '/ws/dj' ||
    parsed.search ||
    parsed.hash
  ) {
    throw new TypeError('Server URL must be a credential-free wss:// URL ending in /ws/dj');
  }
  return parsed.toString();
}

function normalizeResolvedServerUrl(value: string): string {
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new TypeError('The coordinator returned an invalid server URL');
  }
  if (parsed.pathname === '' || parsed.pathname === '/') {
    parsed.pathname = '/ws/dj';
  }
  // Legacy coordinator entries may still be ws:// during local development;
  // the Rust backend independently rejects them in release builds.
  if (parsed.protocol === 'ws:') {
    return parsed.toString();
  }
  return normalizeDjServerUrl(parsed.toString());
}

export function normalizeConnectionFingerprint(value: string): string {
  const compact = value.replace(/[\s:-]/gu, '').toUpperCase();
  if (!FINGERPRINT_PATTERN.test(compact)) {
    throw new TypeError('Certificate fingerprint must contain exactly 64 hexadecimal characters');
  }
  return compact;
}

export function formatConnectionFingerprint(value: string): string {
  const normalized = normalizeConnectionFingerprint(value);
  return normalized.match(/.{2}/gu)?.join(' ') ?? normalized;
}

function decodeInvitePayload(payload: string): unknown {
  if (!/^[A-Za-z0-9_-]+$/u.test(payload)) {
    throw new TypeError('Invite payload is not valid base64url');
  }
  if (payload.length > MAX_ENCODED_INVITE_BYTES) {
    throw new TypeError('Invite payload is too large');
  }
  try {
    const padding = '='.repeat((4 - (payload.length % 4)) % 4);
    const binary = atob(payload.replaceAll('-', '+').replaceAll('_', '/') + padding);
    const bytes = Uint8Array.from(binary, character => character.charCodeAt(0));
    if (bytes.byteLength > MAX_INVITE_JSON_BYTES) {
      throw new TypeError('Invite payload is too large');
    }
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch (error) {
    if (error instanceof TypeError && /too large/u.test(error.message)) throw error;
    throw new TypeError('Invite payload is malformed');
  }
}

export function parseDjInviteLink(
  value: string,
  nowSeconds = Math.floor(Date.now() / 1000),
): ParsedDjInvite {
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new TypeError('Invite link is invalid');
  }
  if (
    parsed.protocol !== 'mcav:' ||
    parsed.hostname !== 'connect' ||
    parsed.pathname !== '' ||
    parsed.username ||
    parsed.password ||
    parsed.hash
  ) {
    throw new TypeError('Invite link is invalid');
  }
  const inviteValues = parsed.searchParams.getAll('invite');
  if (
    inviteValues.length !== 1 ||
    [...parsed.searchParams.keys()].some(key => key !== 'invite')
  ) {
    throw new TypeError('Invite link payload is missing');
  }

  const decoded = decodeInvitePayload(inviteValues[0]);
  if (!decoded || typeof decoded !== 'object' || Array.isArray(decoded)) {
    throw new TypeError('Invite payload is invalid');
  }
  const invite = decoded as Record<string, unknown>;
  const allowedFields = new Set([
    'schema_version',
    'server_url',
    'connect_code',
    'certificate_sha256',
    'expires_at',
  ]);
  const unknownField = Object.keys(invite).find(key => !allowedFields.has(key));
  if (unknownField) throw new TypeError('Invite contains an unknown field');
  if (invite.schema_version !== 1) throw new TypeError('Invite version is unsupported');
  if (typeof invite.server_url !== 'string') throw new TypeError('Invite server URL is invalid');
  if (
    typeof invite.connect_code !== 'string' ||
    !CONNECT_CODE_PATTERN.test(invite.connect_code)
  ) {
    throw new TypeError('Invite connect code is invalid');
  }
  if (!Number.isInteger(invite.expires_at) || Number(invite.expires_at) <= nowSeconds) {
    throw new TypeError('Invite has expired');
  }
  if (Number(invite.expires_at) > nowSeconds + MAX_INVITE_TTL_SECONDS + MAX_CLOCK_SKEW_SECONDS) {
    throw new TypeError('Invite expiration is invalid');
  }
  if (
    invite.certificate_sha256 !== undefined &&
    (typeof invite.certificate_sha256 !== 'string' ||
      !FINGERPRINT_PATTERN.test(invite.certificate_sha256))
  ) {
    throw new TypeError('Invite certificate fingerprint is invalid');
  }

  return {
    serverUrl: normalizeDjServerUrl(invite.server_url),
    connectCode: invite.connect_code.replace('-', ''),
    certificateSha256: (invite.certificate_sha256 as string | undefined) ?? null,
    expiresAt: Number(invite.expires_at),
  };
}

export function requiresPinReplacementConfirmation(
  saved: ServerProfile | null,
  next: ServerProfile,
): boolean {
  return Boolean(
    saved &&
      saved.serverUrl === next.serverUrl &&
      saved.certificateSha256 &&
      saved.certificateSha256 !== next.certificateSha256,
  );
}

export function classifyConnectionError(value: unknown): string {
  const error = String(value);
  if (error.includes('CERTIFICATE_PIN_MISMATCH')) {
    return 'The server certificate changed. Verify a new fingerprint with the server administrator.';
  }
  if (error.includes('CERTIFICATE_UNTRUSTED')) {
    return 'The server certificate is not trusted. Import an administrator invite or enter its fingerprint.';
  }
  if (error.includes('CERTIFICATE_EXPIRED')) {
    return 'The server certificate has expired. Ask the server administrator to renew it.';
  }
  if (error.includes('CERTIFICATE_HOSTNAME_MISMATCH')) {
    return 'The certificate does not match this server name. Check the invite URL.';
  }
  if (error.includes('CONNECTION_TIMEOUT') || /timed? out|connection refused/iu.test(error)) {
    return "Can't reach server. Check that the VJ server is running and reachable.";
  }
  if (error.includes('AUTHENTICATION_FAILED') || /unauthorized|auth/iu.test(error)) {
    return 'Authentication failed. Ask your VJ operator for a new code.';
  }
  if (error.includes('RECONNECT_REQUIRES_NEW_CODE')) {
    return 'The secure session ended. Import a fresh administrator invite to reconnect.';
  }
  if (error.includes('SERVER_PROFILE_STORE_FAILED')) {
    return "Connected securely, but couldn't save this server's trust settings. Check app storage access and try again.";
  }
  if (error.includes('INVALID_SERVER_PROFILE')) {
    return 'The server URL or certificate fingerprint is invalid.';
  }
  return 'Connection failed. Check the server details and try again.';
}
import {
  DEFAULT_CONNECTION_STATUS,
  DEFAULT_VOICE_STATUS,
  DEFAULT_AUDIO_DATA,
} from '../types';

export interface UseConnectionReturn {
  // Connection state
  djName: string;
  setDjName: (name: string) => void;
  connectCode: string;
  setConnectCode: (code: string) => void;
  showName: string | null;
  directConnect: boolean;
  setDirectConnect: (value: boolean) => void;
  serverUrl: string;
  setServerUrl: (url: string) => void;
  certificateFingerprint: string;
  setCertificateFingerprint: (fingerprint: string) => void;
  inviteLink: string;
  setInviteLink: (invite: string) => void;
  importInvite: () => void;
  inviteExpiresAt: number | null;
  pinReplacementRequired: boolean;
  pinReplacementConfirmed: boolean;
  setPinReplacementConfirmed: (confirmed: boolean) => void;
  status: ConnectionStatus;
  isConnecting: boolean;

  // Audio state
  audioRef: React.RefObject<AudioData>;
  isBeatForUI: boolean;
  activePreset: string;
  setActivePreset: (preset: string) => void;

  // Voice state
  voiceEnabled: boolean;

  // Capture & roster
  captureMode: CaptureMode | null;
  roster: RosterUpdate | null;

  // Handlers
  handleConnect: (
    selectedSource: string | null,
    isTestingAudio: boolean,
    handleStopTest: () => Promise<void>,
  ) => Promise<void>;
  handleDisconnect: () => Promise<void>;
  handlePresetChange: (name: string) => Promise<void>;
  handleToggleVoice: () => Promise<void>;
}

export function useConnection(auth: UseAuthReturn): UseConnectionReturn {
  const lastAutoFilledName = useRef<string | null>(null);

  // Connection state
  const [djName, setDjName] = useState('');
  const [connectCode, setConnectCode] = useState('');
  const [showName, setShowName] = useState<string | null>(null);
  const [directConnect, setDirectConnect] = useState(
    () => localStorage.getItem('mcav.directConnect') === 'true',
  );
  const [serverUrl, setServerUrl] = useState(
    () => localStorage.getItem('mcav.serverUrl') || 'wss://192.168.1.204:8080/ws/dj',
  );
  const [certificateFingerprint, setCertificateFingerprint] = useState('');
  const [inviteLink, setInviteLink] = useState('');
  const [inviteExpiresAt, setInviteExpiresAt] = useState<number | null>(null);
  const [savedServerProfile, setSavedServerProfile] = useState<ServerProfile | null>(null);
  const [pinReplacementConfirmed, setPinReplacementConfirmed] = useState(false);

  // Audio state
  const audioRef = useRef<AudioData>(DEFAULT_AUDIO_DATA);
  const [isBeatForUI, setIsBeatForUI] = useState(false);
  const [activePreset, setActivePreset] = useState(
    () => localStorage.getItem('mcav.preset') || 'auto',
  );

  // Voice streaming state
  const [voiceEnabled, setVoiceEnabled] = useState(false);
  const [_voiceStatus, setVoiceStatus] = useState<VoiceStatus>(DEFAULT_VOICE_STATUS);

  // Status
  const [status, setStatus] = useState<ConnectionStatus>(DEFAULT_CONNECTION_STATUS);
  const [isConnecting, setIsConnecting] = useState(false);

  // Capture mode state
  const [captureMode, setCaptureMode] = useState<CaptureMode | null>(null);

  // DJ roster state
  const [roster, setRoster] = useState<RosterUpdate | null>(null);

  // Auto-fill DJ name from profile when signed in
  useEffect(() => {
    if (!auth.isSignedIn || !auth.user?.dj_profile?.dj_name) return;
    const profileDjName = auth.user.dj_profile.dj_name;
    if (!djName || djName === lastAutoFilledName.current) {
      setDjName(profileDjName);
      lastAutoFilledName.current = profileDjName;
    }
  }, [auth.isSignedIn, auth.user?.dj_profile?.dj_name]);

  // Restore last-used DJ name on mount
  useEffect(() => {
    const storedName = localStorage.getItem('mcav.djName');
    if (storedName) {
      setDjName(storedName);
    }
  }, []);

  // Persist state to localStorage
  useEffect(() => {
    localStorage.setItem('mcav.djName', djName);
  }, [djName]);

  useEffect(() => {
    localStorage.setItem('mcav.directConnect', String(directConnect));
  }, [directConnect]);
  useEffect(() => {
    localStorage.setItem('mcav.serverUrl', serverUrl);
  }, [serverUrl]);

  const applyInvite = useCallback((link: string) => {
    try {
      const invite = parseDjInviteLink(link);
      setDirectConnect(true);
      setServerUrl(invite.serverUrl);
      setConnectCode(invite.connectCode);
      setCertificateFingerprint(invite.certificateSha256 ?? '');
      setInviteExpiresAt(invite.expiresAt);
      setInviteLink('');
      setPinReplacementConfirmed(false);
      setStatus(prev => ({ ...prev, error: null }));
    } catch (error) {
      setStatus(prev => ({
        ...prev,
        error: error instanceof Error ? error.message : 'Invite link is invalid',
      }));
    }
  }, []);

  const importInvite = useCallback(() => applyInvite(inviteLink.trim()), [applyInvite, inviteLink]);

  useEffect(() => {
    let disposed = false;
    const unlistenPromise = onOpenUrl(urls => {
      const invite = urls.find(url => url.startsWith('mcav://'));
      if (invite) applyInvite(invite);
    }).catch(() => undefined);
    getCurrent()
      .then(urls => {
        if (disposed || !urls) return;
        const invite = urls.find(url => url.startsWith('mcav://'));
        if (invite) applyInvite(invite);
      })
      .catch(() => {});
    return () => {
      disposed = true;
      unlistenPromise.then(unlisten => unlisten?.()).catch(() => {});
    };
  }, [applyInvite]);

  useEffect(() => {
    setPinReplacementConfirmed(false);
    if (!directConnect) {
      setSavedServerProfile(null);
      return;
    }
    let normalizedUrl: string;
    try {
      normalizedUrl = normalizeDjServerUrl(serverUrl);
    } catch {
      setSavedServerProfile(null);
      return;
    }
    let disposed = false;
    invoke<ServerProfile | null>('get_saved_server_profile', { serverUrl: normalizedUrl })
      .then(profile => {
        if (!disposed) {
          setSavedServerProfile(profile);
          if (profile?.certificateSha256) {
            setCertificateFingerprint(current => current || profile.certificateSha256 || '');
          }
        }
      })
      .catch(() => {
        if (!disposed) setSavedServerProfile(null);
      });
    return () => {
      disposed = true;
    };
  }, [directConnect, serverUrl]);

  let nextServerProfile: ServerProfile | null = null;
  try {
    nextServerProfile = {
      serverUrl: normalizeDjServerUrl(serverUrl),
      certificateSha256: certificateFingerprint
        ? normalizeConnectionFingerprint(certificateFingerprint)
        : null,
    };
  } catch {
    nextServerProfile = null;
  }
  const pinReplacementRequired = Boolean(
    nextServerProfile &&
      requiresPinReplacementConfirmation(savedServerProfile, nextServerProfile),
  );
  const updateServerUrl = useCallback((url: string) => {
    setServerUrl(url);
    setCertificateFingerprint('');
    setInviteExpiresAt(null);
    setPinReplacementConfirmed(false);
  }, []);

  // Persist preset selection
  useEffect(() => {
    localStorage.setItem('mcav.preset', activePreset);
  }, [activePreset]);

  // Restore preset on mount (send to backend once capture is ready)
  useEffect(() => {
    const saved = localStorage.getItem('mcav.preset');
    if (saved && PRESETS.includes(saved)) {
      invoke('set_preset', { name: saved }).catch(() => {});
    }
  }, []);

  // Always listen for dj-status so reconnection events reach the frontend.
  useEffect(() => {
    const unlisten = listen<ConnectionStatus>('dj-status', (event) => {
      setStatus({
        ...event.payload,
        error: event.payload.error
          ? classifyConnectionError(event.payload.error)
          : null,
      });
    });
    return () => {
      unlisten.then((fn) => fn()).catch(() => {});
    };
  }, []);

  // Listen for audio levels, voice, and preset events only while connected
  useEffect(() => {
    if (!status.connected) return;

    const unlisteners: Promise<UnlistenFn>[] = [];

    unlisteners.push(
      listen<AudioLevels>('audio-levels', (event) => {
        if (event.payload.is_beat !== audioRef.current.isBeat) {
          setIsBeatForUI(event.payload.is_beat);
        }
        audioRef.current.bands = event.payload.bands;
        audioRef.current.isBeat = event.payload.is_beat;
        audioRef.current.bpm = event.payload.bpm;
        audioRef.current.beatIntensity = event.payload.beat_intensity;
      }),
    );

    unlisteners.push(
      listen<VoiceStatus>('voice-status', (event) => {
        setVoiceStatus(event.payload);
      }),
    );

    unlisteners.push(
      listen<string>('preset-changed', (event) => {
        setActivePreset(event.payload);
      }),
    );

    unlisteners.push(
      listen<CaptureMode>('capture-mode', (event) => {
        setCaptureMode(event.payload);
      }),
    );

    unlisteners.push(
      listen<RosterUpdate>('dj-roster', (event) => {
        setRoster(event.payload);
      }),
    );

    return () => {
      unlisteners.forEach((p) => p.then((unlisten) => unlisten()).catch(() => {}));
    };
  }, [status.connected]);

  const handleConnect = async (
    selectedSource: string | null,
    isTestingAudio: boolean,
    handleStopTest: () => Promise<void>,
  ) => {
    const code = connectCode;
    if (code.length !== 8 || !djName.trim()) {
      return;
    }

    setIsConnecting(true);
    setStatus((prev) => ({ ...prev, error: null }));
    try {
      // Stop test audio if running
      if (isTestingAudio) {
        await handleStopTest();
      }

      // Format code as XXXX-XXXX
      const formattedCode = `${code.slice(0, 4)}-${code.slice(4, 8)}`;

      let connectionServerUrl: string;
      let connectionCertificateSha256: string | null = null;
      let djSessionId: string | null = null;

      if (directConnect) {
        connectionServerUrl = normalizeDjServerUrl(serverUrl);
        connectionCertificateSha256 = certificateFingerprint
          ? normalizeConnectionFingerprint(certificateFingerprint)
          : null;
        if (pinReplacementRequired && !pinReplacementConfirmed) {
          throw new TypeError(
            'The saved certificate fingerprint changed. Confirm the replacement before connecting.',
          );
        }
      } else {
        const idempotencyKey =
          typeof crypto !== 'undefined' && crypto.randomUUID
            ? crypto.randomUUID()
            : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const resolved = await api.resolveConnectCode(formattedCode, idempotencyKey);
        connectionServerUrl = normalizeResolvedServerUrl(resolved.websocket_url);
        setShowName(resolved.show_name);
        djSessionId = resolved.dj_session_id ?? null;
      }

      await invoke('connect_with_code', {
        code: formattedCode,
        djName: djName.trim(),
        serverUrl: connectionServerUrl,
        certificateSha256: connectionCertificateSha256,
        blockPalette: auth?.user?.dj_profile?.block_palette ?? null,
        djSessionId,
      });

      // Start audio capture
      if (selectedSource) {
        await invoke('start_capture', { sourceId: selectedSource });
      }

      setStatus((prev) => ({ ...prev, connected: true }));
    } catch (e) {
      const errStr = String(e);
      let errorMessage = errStr;

      if (e instanceof api.ApiError) {
        if (e.status === 404) {
          errorMessage = 'Connect code not found. Check the code and try again.';
        } else if (e.status === 409) {
          errorMessage = 'Show is full — maximum DJ limit reached.';
        } else if (e.status === 503) {
          errorMessage = 'Server is currently offline. Try again later.';
        } else {
          errorMessage = 'The coordinator could not complete the connection request. Try again later.';
        }
      } else {
        errorMessage = classifyConnectionError(e);
      }

      setStatus((prev) => ({ ...prev, error: errorMessage }));
    } finally {
      setIsConnecting(false);
    }
  };

  const handleDisconnect = async () => {
    try {
      await invoke('disconnect');
      setStatus(DEFAULT_CONNECTION_STATUS);
      setShowName(null);
      audioRef.current = { ...DEFAULT_AUDIO_DATA };
      setIsBeatForUI(false);
      setCaptureMode(null);
      setRoster(null);
      setVoiceEnabled(false);
      setVoiceStatus(DEFAULT_VOICE_STATUS);
    } catch (e) {
      console.error('Disconnect error:', e);
    }
  };

  const handlePresetChange = async (name: string) => {
    setActivePreset(name);
    try {
      await invoke('set_preset', { name });
    } catch (e) {
      console.error('Preset change error:', e);
    }
  };

  const handleToggleVoice = async () => {
    try {
      const newEnabled = !voiceEnabled;
      await invoke('set_voice_streaming', { enabled: newEnabled });
      setVoiceEnabled(newEnabled);
    } catch (e) {
      console.error('Voice toggle error:', e);
    }
  };

  return {
    djName,
    setDjName,
    connectCode,
    setConnectCode,
    showName,
    directConnect,
    setDirectConnect,
    serverUrl,
    setServerUrl: updateServerUrl,
    certificateFingerprint,
    setCertificateFingerprint,
    inviteLink,
    setInviteLink,
    importInvite,
    inviteExpiresAt,
    pinReplacementRequired,
    pinReplacementConfirmed,
    setPinReplacementConfirmed,
    status,
    isConnecting,
    audioRef,
    isBeatForUI,
    activePreset,
    setActivePreset,
    voiceEnabled,
    captureMode,
    roster,
    handleConnect,
    handleDisconnect,
    handlePresetChange,
    handleToggleVoice,
  };
}
