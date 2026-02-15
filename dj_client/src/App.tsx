import { useState, useEffect, useRef } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { check, type Update } from '@tauri-apps/plugin-updater';
import ConnectCode from './components/ConnectCode';
import AudioSourceSelect from './components/AudioSourceSelect';
import FrequencyMeter from './components/FrequencyMeter';
import StatusPanel from './components/StatusPanel';
import BeatIndicator from './components/BeatIndicator';
import AuthModal from './components/AuthModal';
import ProfileChip from './components/ProfileChip';
import { useAuth } from './hooks/useAuth';

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

interface ConnectionHistoryEntry {
  host: string;
  port: number;
  djName: string;
  timestamp: number;
}

interface VoiceStatus {
  available: boolean;
  streaming: boolean;
  channel_type: string;
  connected_players: number;
}

const MCAV_LOGO_DATA_URI =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA8AAAAKCAYAAABrGwT5AAAA3ElEQVR4AWyRvQ3CMBCFLx4jBQ0FyhppaNiAggFgAApKCgaAASjYgIYma0QUNBQZA3PfiTOOlUgv9+79OJYSZOJpmiaWmIhJKufhtm2lRO77QVbGyMOYqjGECXJf94gZIBgs73CyMPzx3MhldZTdfC193yPZDiFf13W0LyNQnH32FoSjbe8HRiqxc6CJ+kplil6Cqye+U9Ib2iFwPGBlv5aXCC4XV2E/v27k7EZe9HxQUnVdZ6by0aSF5hMOyA/DUNmX1bQDEAGBEugO8or/f9alcngon+79pg6RLwAAAP//ucby7wAAAAZJREFUAwBHiZkQ43EK0gAAAABJRU5ErkJggg==';

function App() {
  // Auth state
  const auth = useAuth();
  const [showAuthModal, setShowAuthModal] = useState(false);
  const lastAutoFilledName = useRef<string | null>(null);

  // Connection state
  const [serverHost, setServerHost] = useState('192.168.1.204');
  const [serverPort, setServerPort] = useState(9000);
  const [djName, setDjName] = useState('');
  const [connectCode, setConnectCode] = useState(['', '', '', '', '', '', '', '']);

  // Audio state
  const [audioSources, setAudioSources] = useState<AudioSource[]>([]);
  const [selectedSource, setSelectedSource] = useState<string | null>(null);
  const [bands, setBands] = useState<number[]>([0, 0, 0, 0, 0]);
  const [isBeat, setIsBeat] = useState(false);

  // Audio preset state
  const [activePreset, setActivePreset] = useState(() => localStorage.getItem('mcav.preset') || 'auto');
  const PRESETS = ['auto', 'edm', 'chill', 'rock', 'hiphop', 'classical'];

  // Voice streaming state
  const [voiceEnabled, setVoiceEnabled] = useState(false);
  const [voiceStatus, setVoiceStatus] = useState<VoiceStatus>({
    available: false,
    streaming: false,
    channel_type: 'static',
    connected_players: 0,
  });
  const [voiceChannelType, setVoiceChannelType] = useState('static');
  const [voiceDistance, setVoiceDistance] = useState(100);

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
  const [showServerSettings, setShowServerSettings] = useState(false);
  const [availableUpdate, setAvailableUpdate] = useState<Update | null>(null);
  const [isCheckingUpdate, setIsCheckingUpdate] = useState(false);
  const [isInstallingUpdate, setIsInstallingUpdate] = useState(false);
  const [updateMessage, setUpdateMessage] = useState<string | null>(null);
  const [updateError, setUpdateError] = useState<string | null>(null);
  const [updateProgress, setUpdateProgress] = useState<number | null>(null);
  const [dismissUpdateBanner, setDismissUpdateBanner] = useState(false);
  const [showWelcomeOverlay, setShowWelcomeOverlay] = useState(false);

  // Demo mode state
  const [isDemoMode, setIsDemoMode] = useState(false);

  // Test audio state
  const [isTestingAudio, setIsTestingAudio] = useState(false);
  const [testBands, setTestBands] = useState<number[]>([0, 0, 0, 0, 0]);

  // Connection history state
  const [connectionHistory, setConnectionHistory] = useState<ConnectionHistoryEntry[]>([]);

  // Keyboard shortcuts state
  const [showShortcutsHelp, setShowShortcutsHelp] = useState(false);

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

      // Escape - Close auth modal, welcome overlay, stop demo, or stop test audio
      if (event.key === 'Escape') {
        if (showAuthModal) {
          setShowAuthModal(false);
        } else if (showWelcomeOverlay) {
          handleDismissWelcome();
        } else if (isDemoMode) {
          void handleStopDemo();
        } else if (isTestingAudio) {
          void handleStopTest();
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [status.connected, showWelcomeOverlay, showAuthModal, isTestingAudio, isDemoMode, selectedSource]);

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
    const storedHost = localStorage.getItem('mcav.serverHost');
    const storedPort = localStorage.getItem('mcav.serverPort');
    const onboardingComplete = localStorage.getItem('mcav.onboardingComplete');

    if (storedName) {
      setDjName(storedName);
    }
    if (storedHost) {
      setServerHost(storedHost);
    }
    if (storedPort) {
      const parsedPort = parseInt(storedPort, 10);
      if (!Number.isNaN(parsedPort)) {
        setServerPort(parsedPort);
      }
    }

    // Show welcome overlay if first run
    if (!onboardingComplete) {
      setShowWelcomeOverlay(true);
    }

    loadAudioSources();
    checkForUpdates(false);

    // Load connection history
    const storedHistory = localStorage.getItem('mcav.connectionHistory');
    if (storedHistory) {
      try {
        const parsed = JSON.parse(storedHistory);
        setConnectionHistory(Array.isArray(parsed) ? parsed : []);
      } catch {
        setConnectionHistory([]);
      }
    }
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
    localStorage.setItem('mcav.serverHost', serverHost);
  }, [serverHost]);

  useEffect(() => {
    localStorage.setItem('mcav.serverPort', String(serverPort));
  }, [serverPort]);

  useEffect(() => {
    if (selectedSource) {
      localStorage.setItem('mcav.audioSource', selectedSource);
    }
  }, [selectedSource]);

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

  // Listen for simulated audio levels during demo mode
  useEffect(() => {
    if (!isDemoMode) return;

    const unlisten = listen<AudioLevels>('audio-levels', (event) => {
      setBands(event.payload.bands);
      setIsBeat(event.payload.is_beat);
    });

    return () => {
      unlisten.then((fn_) => fn_()).catch(() => {});
    };
  }, [isDemoMode]);

  // Listen for audio levels and status events pushed from the backend
  useEffect(() => {
    if (!status.connected) return;

    const unlisteners: Promise<UnlistenFn>[] = [];

    unlisteners.push(
      listen<AudioLevels>('audio-levels', (event) => {
        setBands(event.payload.bands);
        setIsBeat(event.payload.is_beat);
      })
    );

    unlisteners.push(
      listen<ConnectionStatus>('dj-status', (event) => {
        setStatus(event.payload);
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
    const code = connectCode.join('');
    if (code.length !== 8 || !djName.trim()) {
      return;
    }

    setIsConnecting(true);
    try {
      // Stop demo mode if running
      if (isDemoMode) {
        await handleStopDemo();
      }

      // Stop test audio if running
      if (isTestingAudio) {
        await handleStopTest();
      }

      // Format code as XXXX-XXXX
      const formattedCode = `${code.slice(0, 4)}-${code.slice(4, 8)}`;

      await invoke('connect_with_code', {
        code: formattedCode,
        djName: djName.trim(),
        serverHost,
        serverPort,
      });

      // Start audio capture
      if (selectedSource) {
        await invoke('start_capture', { sourceId: selectedSource });
      }

      setStatus(prev => ({ ...prev, connected: true }));

      // Save to connection history
      saveToConnectionHistory(serverHost, serverPort, djName.trim());
    } catch (e) {
      const errStr = String(e);
      let errorMessage = errStr;

      // Better error messages
      if (errStr.includes('timeout') || errStr.includes('timed out') || errStr.includes('connection refused')) {
        errorMessage = "Can't reach server. Check that the VJ server is running and your firewall allows connections on this port.";
      } else if (errStr.includes('auth') || errStr.includes('invalid') || errStr.includes('unauthorized')) {
        errorMessage = 'Invalid connect code. Ask your VJ operator for a new code.';
      } else if (errStr.includes('connect')) {
        errorMessage = 'Connection lost. Attempting to reconnect...';
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
      setBands([0, 0, 0, 0, 0]);
      setIsBeat(false);
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

  const handleVoiceChannelType = async (type_: string) => {
    setVoiceChannelType(type_);
    try {
      await invoke('set_voice_config', { channelType: type_, distance: voiceDistance });
    } catch (e) {
      console.error('Voice config error:', e);
    }
  };

  const handleVoiceDistance = async (distance: number) => {
    setVoiceDistance(distance);
    try {
      await invoke('set_voice_config', { channelType: voiceChannelType, distance });
    } catch (e) {
      console.error('Voice config error:', e);
    }
  };

  const formatCode = (code: string[]) => {
    const str = code.join('');
    if (str.length <= 4) return str;
    return `${str.slice(0, 4)}-${str.slice(4)}`;
  };

  const saveToConnectionHistory = (host: string, port: number, name: string) => {
    const newEntry: ConnectionHistoryEntry = {
      host,
      port,
      djName: name,
      timestamp: Date.now(),
    };

    // Deduplicate by host+port, keep most recent
    const filtered = connectionHistory.filter(
      (entry) => !(entry.host === host && entry.port === port)
    );

    const updated = [newEntry, ...filtered].slice(0, 3);
    setConnectionHistory(updated);
    localStorage.setItem('mcav.connectionHistory', JSON.stringify(updated));
  };

  const loadHistoryEntry = (entry: ConnectionHistoryEntry) => {
    setServerHost(entry.host);
    setServerPort(entry.port);
    setDjName(entry.djName);
  };

  const handleStartDemo = async () => {
    try {
      await invoke('start_demo');
      setIsDemoMode(true);
    } catch (e) {
      console.error('Failed to start demo:', e);
    }
  };

  const handleStopDemo = async () => {
    try {
      await invoke('stop_demo');
      setIsDemoMode(false);
      setBands([0, 0, 0, 0, 0]);
      setIsBeat(false);
    } catch (e) {
      console.error('Failed to stop demo:', e);
    }
  };

  const handleStartTest = async () => {
    if (!selectedSource) return;

    try {
      setIsTestingAudio(true);
      await invoke('start_capture', { sourceId: selectedSource });

      // Poll audio levels for 10 seconds
      const interval = setInterval(async () => {
        try {
          const levels = await invoke<AudioLevels>('get_audio_levels');
          setTestBands(levels.bands);
        } catch (err) {
          console.error('Failed to get audio levels:', err);
        }
      }, 50);

      // Auto-stop after 10 seconds
      setTimeout(() => {
        clearInterval(interval);
        void handleStopTest();
      }, 10000);
    } catch (err) {
      console.error('Failed to start test audio:', err);
      setIsTestingAudio(false);
    }
  };

  const handleStopTest = async () => {
    try {
      await invoke('stop_capture');
      setIsTestingAudio(false);
      setTestBands([0, 0, 0, 0, 0]);
    } catch (err) {
      console.error('Failed to stop test audio:', err);
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

      <header className="app-header">
        <div className="brand">
          <img className="brand-logo" src={MCAV_LOGO_DATA_URI} alt="MCAV logo" />
          <div className="brand-copy">
            <p className="brand-kicker">MCAV</p>
            <h1>DJ Client</h1>
          </div>
        </div>
        <div className="header-actions">
          {auth.isSignedIn && auth.user ? (
            <ProfileChip user={auth.user} onSignOut={auth.signOut} />
          ) : (
            <button
              className="btn-signin"
              onClick={() => setShowAuthModal(true)}
              type="button"
            >
              Sign In
            </button>
          )}
          <button
            className="help-link"
            onClick={() => setShowShortcutsHelp(prev => !prev)}
            title="Keyboard Shortcuts"
            type="button"
            style={{ background: 'none', border: 'none', cursor: 'pointer', padding: '4px', display: 'flex', alignItems: 'center' }}
          >
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
              <rect x="3" y="6" width="14" height="9" rx="1" stroke="currentColor" strokeWidth="1.5"/>
              <rect x="5" y="8" width="2" height="2" fill="currentColor"/>
              <rect x="8" y="8" width="2" height="2" fill="currentColor"/>
              <rect x="11" y="8" width="2" height="2" fill="currentColor"/>
              <rect x="5" y="11" width="2" height="2" fill="currentColor"/>
              <rect x="8" y="11" width="5" height="2" fill="currentColor"/>
              <rect x="14" y="11" width="2" height="2" fill="currentColor"/>
            </svg>
          </button>
          <a
            className="help-link"
            href="https://github.com/ryanthemcpherson/minecraft-audio-viz#quick-start"
            target="_blank"
            rel="noopener noreferrer"
            title="Help & Documentation"
          >
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
              <circle cx="10" cy="10" r="8.5" stroke="currentColor" strokeWidth="1.5"/>
              <path d="M10 14v-1m0-4c0-.55.2-1.02.59-1.41C10.98 7.2 11.45 7 12 7c.55 0 1.02.2 1.41.59.39.39.59.86.59 1.41 0 .28-.07.54-.2.78-.14.24-.32.45-.55.63l-.77.6c-.24.19-.43.4-.57.63-.14.24-.21.5-.21.78" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
            </svg>
          </a>
          <BeatIndicator active={isBeat && (status.connected || isDemoMode)} />
        </div>
      </header>

      {showShortcutsHelp && (
        <div className="shortcuts-help">
          <h3 style={{ marginTop: 0, marginBottom: '12px', fontSize: '14px' }}>Keyboard Shortcuts</h3>
          <div style={{ display: 'grid', gap: '8px', fontSize: '13px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span style={{ opacity: 0.7 }}>Disconnect</span>
              <kbd>{navigator.platform.toUpperCase().indexOf('MAC') >= 0 ? 'Cmd' : 'Ctrl'} + D</kbd>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span style={{ opacity: 0.7 }}>Refresh audio sources</span>
              <kbd>{navigator.platform.toUpperCase().indexOf('MAC') >= 0 ? 'Cmd' : 'Ctrl'} + R</kbd>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span style={{ opacity: 0.7 }}>Toggle test audio</span>
              <kbd>{navigator.platform.toUpperCase().indexOf('MAC') >= 0 ? 'Cmd' : 'Ctrl'} + T</kbd>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span style={{ opacity: 0.7 }}>Close overlay / Exit demo / Stop test</span>
              <kbd>Esc</kbd>
            </div>
          </div>
        </div>
      )}

      <main className="app-main">
        {isDemoMode && (
          <div className="demo-banner">
            <span className="demo-banner-label">DEMO MODE</span>
            <span className="demo-banner-text">Simulated audio -- no server connected</span>
            <button className="btn btn-demo-exit" onClick={handleStopDemo} type="button">
              Exit Demo
            </button>
          </div>
        )}

        {(availableUpdate && !dismissUpdateBanner) || isCheckingUpdate || updateMessage || updateError ? (
          <section className="section update-section">
            <div className="update-header">
              <h2>App Updates</h2>
              <button
                className="btn btn-link"
                onClick={() => checkForUpdates(true)}
                type="button"
                disabled={isCheckingUpdate || isInstallingUpdate}
              >
                {isCheckingUpdate ? 'Checking...' : 'Check now'}
              </button>
            </div>

            {availableUpdate && !dismissUpdateBanner ? (
              <>
                <p className="update-text">
                  Version {availableUpdate.version} is ready to install.
                </p>
                {updateProgress !== null ? (
                  <p className="update-text">Download progress: {updateProgress}%</p>
                ) : null}
                <div className="update-actions">
                  <button
                    className="btn btn-connect"
                    onClick={installAvailableUpdate}
                    disabled={isInstallingUpdate || isCheckingUpdate}
                    type="button"
                  >
                    {isInstallingUpdate ? 'Installing...' : 'Update now'}
                  </button>
                  <button
                    className="btn btn-quick-connect"
                    onClick={() => setDismissUpdateBanner(true)}
                    disabled={isInstallingUpdate}
                    type="button"
                  >
                    Later
                  </button>
                </div>
              </>
            ) : null}

            {updateMessage ? <p className="update-text">{updateMessage}</p> : null}
            {updateError ? <div className="error-message">{updateError}</div> : null}
          </section>
        ) : null}

        {isDemoMode ? (
          <>
            <section className="section">
              <FrequencyMeter bands={bands} />
            </section>

            <section className="section preset-section">
              <span className="input-label" style={{ marginBottom: '6px' }}>Audio Preset (visual only)</span>
              <div className="preset-buttons">
                {PRESETS.map(name => (
                  <button
                    key={name}
                    className={`btn btn-preset ${activePreset === name ? 'active' : ''}`}
                    onClick={() => setActivePreset(name)}
                    type="button"
                  >
                    {name}
                  </button>
                ))}
              </div>
            </section>

            <button
              className="btn btn-disconnect"
              onClick={handleStopDemo}
            >
              Exit Demo Mode
            </button>
          </>
        ) : !status.connected ? (
          <>
            <section className="section hero-section">
              <p>Enter your DJ name, paste your connect code, pick audio, then connect.</p>
              <button
                className="btn btn-link"
                onClick={() => setShowServerSettings(prev => !prev)}
                type="button"
              >
                {showServerSettings ? 'Hide server settings' : 'Server settings'}
              </button>
            </section>

            {showServerSettings && (
              <section className="section server-section">
                <label className="input-label">
                  Server
                  <div className="server-inputs">
                    <input
                      type="text"
                      value={serverHost}
                      onChange={e => setServerHost(e.target.value)}
                      placeholder="hostname"
                      className="input server-host"
                    />
                    <span className="server-separator">:</span>
                    <input
                      type="number"
                      value={serverPort}
                      onChange={e => setServerPort(parseInt(e.target.value) || 9000)}
                      placeholder="port"
                      className="input server-port"
                    />
                  </div>
                </label>

                {connectionHistory.length > 0 && (
                  <div className="connection-history">
                    <span className="history-label">Recent Connections</span>
                    <div className="history-list">
                      {connectionHistory.map((entry, idx) => (
                        <button
                          key={idx}
                          className="btn btn-history"
                          onClick={() => loadHistoryEntry(entry)}
                          type="button"
                        >
                          <span className="history-name">{entry.djName}</span>
                          <span className="history-server">{entry.host}:{entry.port}</span>
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </section>
            )}

            <section className="section">
              {auth.isSignedIn && auth.user?.dj_profile && (
                <div className="profile-card">
                  {auth.user.avatar_url ? (
                    <img className="profile-card-avatar" src={auth.user.avatar_url} alt="" />
                  ) : (
                    <span className="profile-card-avatar-initials">
                      {auth.user.display_name.split(/\s+/).slice(0, 2).map(w => w[0]).join('').toUpperCase()}
                    </span>
                  )}
                  <div className="profile-card-inner">
                    <span className="profile-card-name">{auth.user.dj_profile.dj_name}</span>
                    <span className="profile-card-sub">Signed in as {auth.user.display_name}</span>
                  </div>
                </div>
              )}
              <label className="input-label">
                Step 1 - DJ Name
                <input
                  type="text"
                  value={djName}
                  onChange={e => setDjName(e.target.value)}
                  placeholder="DJ Name"
                  className="input"
                  maxLength={32}
                />
              </label>
            </section>

            <section className="section">
              <div className="label-with-help">
                <span className="input-label">Step 2 - Connect Code</span>
                <span className="help-text">Get this from your VJ operator or server admin</span>
              </div>
              <ConnectCode
                value={connectCode}
                onChange={setConnectCode}
              />
              <div className="code-display">
                {formatCode(connectCode) || 'XXXX-XXXX'}
              </div>
            </section>

            <section className="section">
              <div className="label-with-help">
                <span className="input-label">Step 3 - Audio Source</span>
                <span className="help-text">System: all PC audio, Application: specific app, Input Device: microphone/line-in</span>
              </div>
              {audioSources.length === 0 ? (
                <div className="empty-state">
                  <p>No audio sources found. Make sure your audio devices are connected and enabled.</p>
                  <button className="btn btn-link" onClick={loadAudioSources} type="button">
                    Refresh
                  </button>
                </div>
              ) : (
                <>
                  <div className="audio-source-row">
                    <AudioSourceSelect
                      sources={audioSources}
                      value={selectedSource}
                      onChange={setSelectedSource}
                      onRefresh={loadAudioSources}
                    />
                    <button
                      className={`btn btn-test ${isTestingAudio ? 'testing' : ''}`}
                      onClick={isTestingAudio ? handleStopTest : handleStartTest}
                      disabled={!selectedSource}
                      type="button"
                    >
                      {isTestingAudio ? 'Stop Test' : 'Test'}
                    </button>
                  </div>
                  {isTestingAudio && (
                    <div className="test-meter">
                      {testBands.map((level, i) => (
                        <div key={i} className="test-bar">
                          <div
                            className="test-fill"
                            style={{
                              width: `${Math.min(100, level * 100)}%`,
                              background: `hsl(${180 + i * 40}, 70%, 50%)`,
                            }}
                          />
                        </div>
                      ))}
                    </div>
                  )}
                </>
              )}
            </section>

            {status.error && (
              <div className="error-message">
                {status.error}
              </div>
            )}

            <div className="connect-buttons">
              <button
                className="btn btn-connect"
                onClick={handleConnect}
                disabled={isConnecting || connectCode.join('').length !== 8 || !djName.trim()}
              >
                {isConnecting ? 'Connecting...' : 'Connect'}
              </button>
              <button
                className="btn btn-demo"
                onClick={handleStartDemo}
                type="button"
              >
                Try Demo
              </button>
            </div>
          </>
        ) : (
          <>
            <section className="section">
              <FrequencyMeter bands={bands} />
            </section>

            <section className="section preset-section">
              <span className="input-label" style={{ marginBottom: '6px' }}>Audio Preset</span>
              <div className="preset-buttons">
                {PRESETS.map(name => (
                  <button
                    key={name}
                    className={`btn btn-preset ${activePreset === name ? 'active' : ''}`}
                    onClick={() => handlePresetChange(name)}
                    type="button"
                  >
                    {name}
                  </button>
                ))}
              </div>
            </section>

            <section className="section">
              <StatusPanel status={status} />
            </section>

            <section className="section voice-section">
              <div className="voice-header">
                <div className="voice-title">
                  <span className="input-label" style={{ marginBottom: 0 }}>Voice Streaming</span>
                  {voiceStatus.streaming && (
                    <span className="voice-live-badge">STREAMING</span>
                  )}
                </div>
                <button
                  className={`btn voice-toggle ${voiceEnabled ? 'voice-on' : 'voice-off'}`}
                  onClick={handleToggleVoice}
                  type="button"
                >
                  {voiceEnabled ? 'Stop' : 'Stream Audio'}
                </button>
              </div>

              {voiceEnabled && (
                <div className="voice-controls">
                  <div className="voice-row">
                    <span className="status-label">Channel:</span>
                    <div className="voice-channel-buttons">
                      <button
                        className={`btn btn-channel ${voiceChannelType === 'static' ? 'active' : ''}`}
                        onClick={() => handleVoiceChannelType('static')}
                        type="button"
                      >
                        Static
                      </button>
                      <button
                        className={`btn btn-channel ${voiceChannelType === 'locational' ? 'active' : ''}`}
                        onClick={() => handleVoiceChannelType('locational')}
                        type="button"
                      >
                        Locational
                      </button>
                    </div>
                  </div>

                  {voiceChannelType === 'locational' && (
                    <div className="voice-row">
                      <span className="status-label">Distance:</span>
                      <div className="voice-distance">
                        <input
                          type="range"
                          min={10}
                          max={500}
                          step={10}
                          value={voiceDistance}
                          onChange={e => handleVoiceDistance(Number(e.target.value))}
                          className="voice-slider"
                        />
                        <span className="status-value">{voiceDistance}m</span>
                      </div>
                    </div>
                  )}

                  {voiceStatus.connected_players > 0 && (
                    <div className="voice-row">
                      <span className="status-label">Players:</span>
                      <span className="status-value">{voiceStatus.connected_players}</span>
                    </div>
                  )}
                </div>
              )}
            </section>

            <button
              className="btn btn-disconnect"
              onClick={handleDisconnect}
            >
              Disconnect
            </button>
          </>
        )}
      </main>

      {showAuthModal && (
        <AuthModal auth={auth} onClose={() => setShowAuthModal(false)} />
      )}
    </div>
  );
}

export default App;
