import { useState, useEffect, useRef } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
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
  serverHost: string;
  setServerHost: (host: string) => void;
  serverPort: number;
  setServerPort: (port: number) => void;
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
  const [serverHost, setServerHost] = useState(
    () => localStorage.getItem('mcav.serverHost') || '192.168.1.204',
  );
  const [serverPort, setServerPort] = useState(
    () => parseInt(localStorage.getItem('mcav.serverPort') || '9000', 10),
  );

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
    localStorage.setItem('mcav.serverHost', serverHost);
  }, [serverHost]);
  useEffect(() => {
    localStorage.setItem('mcav.serverPort', String(serverPort));
  }, [serverPort]);

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
      setStatus(event.payload);
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

      let connHost: string;
      let connPort: number;
      let djSessionId: string | null = null;

      if (directConnect) {
        connHost = serverHost;
        connPort = serverPort;
      } else {
        const idempotencyKey =
          typeof crypto !== 'undefined' && crypto.randomUUID
            ? crypto.randomUUID()
            : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const resolved = await api.resolveConnectCode(formattedCode, idempotencyKey);
        const wsUrl = new URL(resolved.websocket_url);
        connHost = wsUrl.hostname;
        connPort = parseInt(wsUrl.port, 10) || (wsUrl.protocol === 'wss:' ? 443 : 80);
        setShowName(resolved.show_name);
        djSessionId = resolved.dj_session_id ?? null;
      }

      await invoke('connect_with_code', {
        code: formattedCode,
        djName: djName.trim(),
        serverHost: connHost,
        serverPort: connPort,
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
          errorMessage = e.message;
        }
      } else if (
        errStr.includes('timeout') ||
        errStr.includes('timed out') ||
        errStr.includes('connection refused')
      ) {
        errorMessage = "Can't reach server. Check that the VJ server is running.";
      } else if (
        errStr.includes('auth') ||
        errStr.includes('invalid') ||
        errStr.includes('unauthorized')
      ) {
        errorMessage = 'Authentication failed. Ask your VJ operator for a new code.';
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
    serverHost,
    setServerHost,
    serverPort,
    setServerPort,
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
