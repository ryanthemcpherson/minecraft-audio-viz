import { useState, useEffect, useRef } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { check, type Update } from '@tauri-apps/plugin-updater';
import ConnectCode from './components/ConnectCode';
import AudioSourceSelect from './components/AudioSourceSelect';
import ConnectForm from './components/ConnectForm';
import TopBar from './components/TopBar';
import PresetBar, { PRESETS } from './components/PresetBar';
import FrequencyMeter from './components/FrequencyMeter';
import StatusPanel from './components/StatusPanel';
import QueuePanel from './components/QueuePanel';
import StatusStrip from './components/StatusStrip';
import FrequencyStrip from './components/FrequencyStrip';
import AuthModal from './components/AuthModal';
import { useAuth } from './hooks/useAuth';
import * as api from './lib/api';

interface AudioSource {
  id: string;
  name: string;
  source_type: 'system_audio' | 'application' | 'input_device';
}

interface ConnectionStatus {
  connected: boolean;
  is_active: boolean;
  latency_ms: number;
  route_mode: string;
  mc_connected: boolean;
  queue_position: number;
  total_djs: number;
  active_dj_name: string | null;
  error: string | null;
}

interface AudioLevels {
  bands: number[];
  peak: number;
  is_beat: boolean;
  beat_intensity: number;
  bpm: number;
}

interface VoiceStatus {
  available: boolean;
  streaming: boolean;
  channel_type: string;
  connected_players: number;
}

interface CaptureMode {
  mode: 'pending' | 'system_loopback' | 'process_loopback' | 'input_device';
  fallback_reason?: string;
  pid?: number;
  name?: string;
}

interface RosterDJ {
  dj_id: string;
  dj_name: string;
  is_active: boolean;
  avatar_url: string | null;
  queue_position: number;
}

interface RosterUpdate {
  djs: RosterDJ[];
  active_dj_id: string | null;
  your_position: number;
  rotation_interval_sec: number;
}

function App() {
  // Auth state
  const auth = useAuth();
  const [showAuthModal, setShowAuthModal] = useState(false);
  const lastAutoFilledName = useRef<string | null>(null);

  // Connection state
  const [djName, setDjName] = useState('');
  const [connectCode, setConnectCode] = useState('');
  const [showName, setShowName] = useState<string | null>(null);
  const [directConnect, setDirectConnect] = useState(() => localStorage.getItem('mcav.directConnect') === 'true');
  const [serverHost, setServerHost] = useState(() => localStorage.getItem('mcav.serverHost') || '192.168.1.204');
  const [serverPort, setServerPort] = useState(() => parseInt(localStorage.getItem('mcav.serverPort') || '9000', 10));

  // Audio state
  const [audioSources, setAudioSources] = useState<AudioSource[]>([]);
  const [selectedSource, setSelectedSource] = useState<string | null>(null);
  const audioRef = useRef({ bands: [0, 0, 0, 0, 0], isBeat: false, bpm: 0, beatIntensity: 0 });
  const [isBeatForUI, setIsBeatForUI] = useState(false);

  // Audio preset state
  const [activePreset, setActivePreset] = useState(() => localStorage.getItem('mcav.preset') || 'auto');

  // Voice streaming state
  const [voiceEnabled, setVoiceEnabled] = useState(false);
  const [_voiceStatus, setVoiceStatus] = useState<VoiceStatus>({
    available: false,
    streaming: false,
    channel_type: 'static',
    connected_players: 0,
  });

  // Status
  const [status, setStatus] = useState<ConnectionStatus>({
    connected: false,
    is_active: false,
    latency_ms: 0,
    route_mode: '',
    mc_connected: false,
    queue_position: 0,
    total_djs: 0,
    active_dj_name: null,
    error: null,
  });
  const [isConnecting, setIsConnecting] = useState(false);
  const [availableUpdate, setAvailableUpdate] = useState<Update | null>(null);
  const [_isCheckingUpdate, setIsCheckingUpdate] = useState(false);
  const [isInstallingUpdate, setIsInstallingUpdate] = useState(false);
  const [_updateMessage, setUpdateMessage] = useState<string | null>(null);
  const [_updateError, setUpdateError] = useState<string | null>(null);
  const [_updateProgress, setUpdateProgress] = useState<number | null>(null);
  const [dismissUpdateBanner, setDismissUpdateBanner] = useState(false);
  const [showWelcomeOverlay, setShowWelcomeOverlay] = useState(false);

  // Capture mode state
  const [captureMode, setCaptureMode] = useState<CaptureMode | null>(null);

  // DJ roster state
  const [roster, setRoster] = useState<RosterUpdate | null>(null);

  // Test audio state
  const [isTestingAudio, setIsTestingAudio] = useState(false);
  const [_testBands, setTestBands] = useState<number[]>([0, 0, 0, 0, 0]);
  const testIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const testTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // (showShortcutsHelp removed — keyboard shortcuts still work, visual panel removed)

  // Keyboard shortcuts handler
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      // Don't trigger shortcuts when typing in input fields
      const target = event.target as HTMLElement;
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA') {
        return;
      }

      const isMac = navigator.platform.toUpperCase().indexOf('MAC') >= 0;
      const modifierKey = isMac ? event.metaKey : event.ctrlKey;

      // Ctrl/Cmd + D - Disconnect
      if (modifierKey && event.key === 'd') {
        event.preventDefault();
        if (status.connected) {
          void handleDisconnect();
        }
      }

      // Ctrl/Cmd + R - Refresh audio sources
      if (modifierKey && event.key === 'r') {
        event.preventDefault();
        void loadAudioSources();
      }

      // Ctrl/Cmd + T - Toggle test audio
      if (modifierKey && event.key === 't') {
        event.preventDefault();
        if (selectedSource && !status.connected) {
          if (isTestingAudio) {
            void handleStopTest();
          } else {
            void handleStartTest();
          }
        }
      }

      // Escape - Close auth modal, welcome overlay, or stop test audio
      if (event.key === 'Escape') {
        if (showAuthModal) {
          setShowAuthModal(false);
        } else if (showWelcomeOverlay) {
          handleDismissWelcome();
        } else if (isTestingAudio) {
          void handleStopTest();
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [status.connected, showWelcomeOverlay, showAuthModal, isTestingAudio, selectedSource]);


  // Auto-close auth modal when signed in
  useEffect(() => {
    if (auth.isSignedIn && showAuthModal) {
      setShowAuthModal(false);
    }
  }, [auth.isSignedIn, showAuthModal]);

  // Auto-fill DJ name from profile when signed in
  useEffect(() => {
    if (!auth.isSignedIn || !auth.user?.dj_profile?.dj_name) return;
    const profileDjName = auth.user.dj_profile.dj_name;
    // Only auto-fill if the field is empty or still holds the previous auto-filled value
    if (!djName || djName === lastAutoFilledName.current) {
      setDjName(profileDjName);
      lastAutoFilledName.current = profileDjName;
    }
  }, [auth.isSignedIn, auth.user?.dj_profile?.dj_name]);

  // Restore last-used settings and load audio sources on mount.
  useEffect(() => {
    const storedName = localStorage.getItem('mcav.djName');
    const onboardingComplete = localStorage.getItem('mcav.onboardingComplete');

    if (storedName) {
      setDjName(storedName);
    }

    // Show welcome overlay if first run
    if (!onboardingComplete) {
      setShowWelcomeOverlay(true);
    }

    loadAudioSources();
    checkForUpdates(false);
  }, []);

  useEffect(() => {
    const interval = setInterval(() => {
      void checkForUpdates(false);
    }, 1000 * 60 * 60 * 6); // every 6 hours

    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    localStorage.setItem('mcav.djName', djName);
  }, [djName]);

  useEffect(() => {
    if (selectedSource) {
      localStorage.setItem('mcav.audioSource', selectedSource);
    }
  }, [selectedSource]);

  // Persist direct connect settings
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
  // Without this, a brief disconnect removes all listeners and the UI stays
  // stuck on DISCONNECTED even after the bridge task auto-reconnects.
  useEffect(() => {
    const unlisten = listen<ConnectionStatus>('dj-status', (event) => {
      setStatus(event.payload);
    });
    return () => { unlisten.then((fn) => fn()).catch(() => {}); };
  }, []);

  // Listen for audio levels, voice, and preset events only while connected
  useEffect(() => {
    if (!status.connected) return;

    const unlisteners: Promise<UnlistenFn>[] = [];

    unlisteners.push(
      listen<AudioLevels>('audio-levels', (event) => {
        // Only update React state for beat indicator (~2-4 times/sec)
        if (event.payload.is_beat !== audioRef.current.isBeat) {
          setIsBeatForUI(event.payload.is_beat);
        }
        audioRef.current.bands = event.payload.bands;
        audioRef.current.isBeat = event.payload.is_beat;
        audioRef.current.bpm = event.payload.bpm;
        audioRef.current.beatIntensity = event.payload.beat_intensity;
      })
    );

    unlisteners.push(
      listen<VoiceStatus>('voice-status', (event) => {
        setVoiceStatus(event.payload);
      })
    );

    unlisteners.push(
      listen<string>('preset-changed', (event) => {
        setActivePreset(event.payload);
      })
    );

    unlisteners.push(
      listen<CaptureMode>('capture-mode', (event) => {
        setCaptureMode(event.payload);
      })
    );

    unlisteners.push(
      listen<RosterUpdate>('dj-roster', (event) => {
        setRoster(event.payload);
      })
    );

    return () => {
      unlisteners.forEach((p) => p.then((unlisten) => unlisten()).catch(() => {}));
    };
  }, [status.connected]);

  const loadAudioSources = async () => {
    try {
      const sources = await invoke<AudioSource[]>('list_audio_sources');
      setAudioSources(sources);
      if (sources.length === 0) {
        setSelectedSource(null);
        return;
      }

      const selectedStillExists = selectedSource
        ? sources.some(source => source.id === selectedSource)
        : false;
      if (selectedStillExists) {
        return;
      }

      const savedSourceId = localStorage.getItem('mcav.audioSource');
      const savedSourceExists = savedSourceId
        ? sources.some(source => source.id === savedSourceId)
        : false;
      setSelectedSource(savedSourceExists ? savedSourceId : sources[0].id);
    } catch (e) {
      console.error('Failed to load audio sources:', e);
    }
  };

  const checkForUpdates = async (manual: boolean) => {
    setIsCheckingUpdate(true);
    if (manual) {
      setUpdateError(null);
    }
    if (manual) {
      setUpdateMessage('Checking for updates...');
    }

    try {
      const update = await check();
      setAvailableUpdate(update);
      setUpdateProgress(null);

      if (update) {
        setDismissUpdateBanner(false);
        setUpdateMessage(`Update ${update.version} is available.`);
      } else if (manual) {
        setUpdateMessage('You are on the latest version.');
      } else {
        setUpdateMessage(null);
      }
    } catch (e) {
      const errStr = String(e);
      const isAcl = errStr.includes('not allowed by ACL');
      console.error(`[updater] Update check failed: ${errStr}`);
      if (manual) {
        setUpdateError(isAcl ? 'Updates are not available in this build.' : `Update check failed: ${errStr}`);
      } else {
        setUpdateMessage(null);
      }
    } finally {
      setIsCheckingUpdate(false);
    }
  };

  const installAvailableUpdate = async () => {
    if (!availableUpdate) return;

    setIsInstallingUpdate(true);
    setUpdateError(null);
    setUpdateProgress(0);
    setUpdateMessage(`Downloading v${availableUpdate.version}...`);

    let downloadedBytes = 0;
    let totalBytes = 0;
    try {
      await availableUpdate.downloadAndInstall(event => {
        if (event.event === 'Started') {
          totalBytes = event.data.contentLength ?? 0;
          downloadedBytes = 0;
          setUpdateProgress(0);
          return;
        }

        if (event.event === 'Progress') {
          downloadedBytes += event.data.chunkLength;
          if (totalBytes > 0) {
            const pct = Math.min(100, Math.round((downloadedBytes / totalBytes) * 100));
            setUpdateProgress(pct);
          } else {
            setUpdateProgress(null);
          }
          return;
        }

        if (event.event === 'Finished') {
          setUpdateProgress(100);
        }
      });

      setAvailableUpdate(null);
      setUpdateMessage('Update installed. Please restart the DJ app.');
      setDismissUpdateBanner(false);
    } catch (e) {
      setUpdateError(`Update install failed: ${String(e)}`);
    } finally {
      setIsInstallingUpdate(false);
    }
  };

  const handleConnect = async () => {
    const code = connectCode;
    if (code.length !== 8 || !djName.trim()) {
      return;
    }

    setIsConnecting(true);
    setStatus(prev => ({ ...prev, error: null }));
    try {
      // Stop test audio if running
      if (isTestingAudio) {
        await handleStopTest();
      }

      // Format code as XXXX-XXXX
      const formattedCode = `${code.slice(0, 4)}-${code.slice(4, 8)}`;

      let connHost: string;
      let connPort: number;
      let coordinatorToken: string | null = null;

      if (directConnect) {
        // Direct connection — use user-provided server host/port
        connHost = serverHost;
        connPort = serverPort;
      } else {
        // Resolve connect code through the coordinator API
        const idempotencyKey = typeof crypto !== 'undefined' && crypto.randomUUID
          ? crypto.randomUUID()
          : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const resolved = await api.resolveConnectCode(formattedCode, idempotencyKey);
        const wsUrl = new URL(resolved.websocket_url);
        connHost = wsUrl.hostname;
        connPort = parseInt(wsUrl.port, 10) || (wsUrl.protocol === 'wss:' ? 443 : 80);
        setShowName(resolved.show_name);
        coordinatorToken = resolved.token ?? null;
      }

      await invoke('connect_with_code', {
        code: formattedCode,
        djName: djName.trim(),
        serverHost: connHost,
        serverPort: connPort,
        blockPalette: auth?.user?.dj_profile?.block_palette ?? null,
        coordinatorToken,
      });

      // Start audio capture
      if (selectedSource) {
        await invoke('start_capture', { sourceId: selectedSource });
      }

      setStatus(prev => ({ ...prev, connected: true }));
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
      } else if (errStr.includes('timeout') || errStr.includes('timed out') || errStr.includes('connection refused')) {
        errorMessage = "Can't reach server. Check that the VJ server is running.";
      } else if (errStr.includes('auth') || errStr.includes('invalid') || errStr.includes('unauthorized')) {
        errorMessage = 'Authentication failed. Ask your VJ operator for a new code.';
      }

      setStatus(prev => ({ ...prev, error: errorMessage }));
    } finally {
      setIsConnecting(false);
    }
  };

  const handleDismissWelcome = () => {
    localStorage.setItem('mcav.onboardingComplete', 'true');
    setShowWelcomeOverlay(false);
  };

  const handleDisconnect = async () => {
    try {
      await invoke('disconnect');
      setStatus({
        connected: false,
        is_active: false,
        latency_ms: 0,
        route_mode: '',
        mc_connected: false,
        queue_position: 0,
        total_djs: 0,
        active_dj_name: null,
        error: null,
      });
      setShowName(null);
      audioRef.current = { bands: [0, 0, 0, 0, 0], isBeat: false, bpm: 0, beatIntensity: 0 };
      setIsBeatForUI(false);
      setCaptureMode(null);
      setRoster(null);
      setVoiceEnabled(false);
      setVoiceStatus({ available: false, streaming: false, channel_type: 'static', connected_players: 0 });
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

  const handleStartTest = async () => {
    if (!selectedSource) return;

    try {
      setIsTestingAudio(true);
      await invoke('start_capture', { sourceId: selectedSource });

      // Poll audio levels for 10 seconds
      testIntervalRef.current = setInterval(async () => {
        try {
          const levels = await invoke<AudioLevels>('get_audio_levels');
          setTestBands(levels.bands);
        } catch (err) {
          console.error('Failed to get audio levels:', err);
        }
      }, 50);

      // Auto-stop after 10 seconds
      testTimeoutRef.current = setTimeout(() => {
        if (testIntervalRef.current) clearInterval(testIntervalRef.current);
        testIntervalRef.current = null;
        testTimeoutRef.current = null;
        void handleStopTest();
      }, 10000);
    } catch (err) {
      console.error('Failed to start test audio:', err);
      setIsTestingAudio(false);
    }
  };

  const handleStopTest = async () => {
    if (testIntervalRef.current) {
      clearInterval(testIntervalRef.current);
      testIntervalRef.current = null;
    }
    if (testTimeoutRef.current) {
      clearTimeout(testTimeoutRef.current);
      testTimeoutRef.current = null;
    }
    try {
      await invoke('stop_capture');
      setIsTestingAudio(false);
      setTestBands([0, 0, 0, 0, 0]);
    } catch (err) {
      console.error('Failed to stop test audio:', err);
    }
  };

  const handleSourceChange = async (sourceId: string | null) => {
    setSelectedSource(sourceId);
    if (sourceId && status.connected) {
      try {
        await invoke('change_audio_source', { sourceId });
      } catch (e) {
        console.error('Failed to change audio source:', e);
      }
    }
  };

  return (
    <div className="app">
      {showWelcomeOverlay && (
        <div className="welcome-overlay">
          <div className="welcome-modal">
            <h2>Welcome to MCAV DJ Client</h2>
            <p className="welcome-subtitle">Stream your audio to Minecraft visualizations</p>

            <div className="welcome-steps">
              <div className="welcome-step">
                <div className="step-number">1</div>
                <div className="step-content">
                  <h3>Get a connect code</h3>
                  <p>Request a code from your VJ operator or server admin</p>
                </div>
              </div>

              <div className="welcome-step">
                <div className="step-number">2</div>
                <div className="step-content">
                  <h3>Select audio source</h3>
                  <p>Choose which audio to stream: system audio, specific app, or microphone</p>
                </div>
              </div>

              <div className="welcome-step">
                <div className="step-number">3</div>
                <div className="step-content">
                  <h3>Connect and go live</h3>
                  <p>Hit connect and start streaming to Minecraft</p>
                </div>
              </div>
            </div>

            <div className="welcome-actions">
              <button className="btn btn-connect" onClick={handleDismissWelcome} type="button">
                Get Started
              </button>
              <a
                className="btn btn-link"
                href="https://github.com/ryanthemcpherson/minecraft-audio-viz#quick-start"
                target="_blank"
                rel="noopener noreferrer"
              >
                Learn More
              </a>
            </div>
          </div>
        </div>
      )}

      {(availableUpdate && !dismissUpdateBanner) && (
        <div className="update-banner">
          <span>Update {availableUpdate.version} available</span>
          <button className="btn btn-link" onClick={installAvailableUpdate} disabled={isInstallingUpdate}>
            {isInstallingUpdate ? 'Installing...' : 'Update'}
          </button>
          <button className="btn-dismiss" onClick={() => setDismissUpdateBanner(true)}>&times;</button>
        </div>
      )}

      {auth.isSignedIn && auth.user && !auth.user.email_verified && (
        <div className="email-verify-banner">
          <span>Please verify your email. Check your inbox for a verification link.</span>
          {auth.verificationMessage ? (
            <span className="success-message">{auth.verificationMessage}</span>
          ) : (
            <button className="btn-link-inline" onClick={auth.resendVerification} type="button">
              Resend
            </button>
          )}
        </div>
      )}

      {!status.connected ? (
        <div className="dashboard disconnected">
          <TopBar djName={djName} onDjNameChange={setDjName} showName={null} isBeat={false} isConnected={false} user={auth.user} isSignedIn={auth.isSignedIn} onSignOut={auth.signOut} onSignIn={() => setShowAuthModal(true)} />

          <ConnectForm
            connectCode={connectCode}
            onConnectCodeChange={setConnectCode}
            selectedSource={selectedSource}
            onSourceChange={setSelectedSource}
            audioSources={audioSources}
            onRefreshSources={loadAudioSources}
            directConnect={directConnect}
            onDirectConnectChange={setDirectConnect}
            serverHost={serverHost}
            onServerHostChange={setServerHost}
            serverPort={serverPort}
            onServerPortChange={setServerPort}
            error={status.error}
            isConnecting={isConnecting}
            djName={djName}
            onConnect={handleConnect}
          />
        </div>
      ) : (
        <div className="dashboard connected">
          <TopBar djName={djName} showName={showName} isBeat={isBeatForUI && status.connected} isConnected={true} user={auth.user} isSignedIn={auth.isSignedIn} onSignOut={auth.signOut} onSignIn={() => setShowAuthModal(true)} />

          <div className="main-content">
            {/* Compact layout (<720px) */}
            <div className="compact-only">
              <StatusStrip
                connected={status.connected}
                isActive={status.is_active}
                showName={showName}
                queuePosition={status.queue_position}
                totalDjs={status.total_djs}
                activeDjName={status.active_dj_name}
                latencyMs={status.latency_ms}
                mcConnected={status.mc_connected}
                bpm={audioRef.current.bpm}
              />
              <FrequencyStrip audioRef={audioRef} />
              <PresetBar active={activePreset} onChange={handlePresetChange} />
              <QueuePanel roster={roster} />
            </div>

            {/* Expanded layout (>720px) */}
            <div className="expanded-only">
              <div className="col-left">
                <FrequencyMeter audioRef={audioRef} />
                <PresetBar active={activePreset} onChange={handlePresetChange} />
              </div>
              <div className="col-right">
                <StatusPanel status={status} />
                <QueuePanel roster={roster} />
              </div>
            </div>
          </div>

          <div className="bottom-bar">
            <AudioSourceSelect
              sources={audioSources}
              value={selectedSource}
              onChange={handleSourceChange}
              onRefresh={loadAudioSources}
            />
            {captureMode && captureMode.mode === 'process_loopback' && (
              <span className="capture-info">{captureMode.name}</span>
            )}
            <button
              className={`btn voice-toggle ${voiceEnabled ? 'voice-on' : 'voice-off'}`}
              onClick={handleToggleVoice}
              type="button"
            >
              {voiceEnabled ? 'Voice Off' : 'Voice'}
            </button>
            <button className="btn btn-disconnect" onClick={handleDisconnect}>
              Disconnect
            </button>
          </div>
        </div>
      )}

      {showAuthModal && (
        <AuthModal auth={auth} onClose={() => setShowAuthModal(false)} />
      )}
    </div>
  );
}

export default App;
