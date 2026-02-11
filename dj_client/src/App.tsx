import { useState, useEffect } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { check, type Update } from '@tauri-apps/plugin-updater';
import ConnectCode from './components/ConnectCode';
import AudioSourceSelect from './components/AudioSourceSelect';
import FrequencyMeter from './components/FrequencyMeter';
import StatusPanel from './components/StatusPanel';
import BeatIndicator from './components/BeatIndicator';

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

const MCAV_LOGO_DATA_URI =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA8AAAAKCAYAAABrGwT5AAAA3ElEQVR4AWyRvQ3CMBCFLx4jBQ0FyhppaNiAggFgAApKCgaAASjYgIYma0QUNBQZA3PfiTOOlUgv9+79OJYSZOJpmiaWmIhJKufhtm2lRO77QVbGyMOYqjGECXJf94gZIBgs73CyMPzx3MhldZTdfC193yPZDiFf13W0LyNQnH32FoSjbe8HRiqxc6CJ+kplil6Cqye+U9Ib2iFwPGBlv5aXCC4XV2E/v27k7EZe9HxQUnVdZ6by0aSF5hMOyA/DUNmX1bQDEAGBEugO8or/f9alcngon+79pg6RLwAAAP//ucby7wAAAAZJREFUAwBHiZkQ43EK0gAAAABJRU5ErkJggg==';

function App() {
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

  // Restore last-used settings and load audio sources on mount.
  useEffect(() => {
    const storedName = localStorage.getItem('mcav.djName');
    const storedHost = localStorage.getItem('mcav.serverHost');
    const storedPort = localStorage.getItem('mcav.serverPort');

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

    return () => {
      unlisteners.forEach((p) => p.then((unlisten) => unlisten()));
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
      const message = `Update check failed: ${String(e)}`;
      if (manual) {
        setUpdateError(message);
      } else {
        setUpdateMessage(null);
      }
      console.error(`[updater] ${message}`);
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
    } catch (e) {
      setStatus(prev => ({ ...prev, error: String(e) }));
    } finally {
      setIsConnecting(false);
    }
  };

  const handleQuickConnect = async () => {
    if (!djName.trim()) return;

    setIsConnecting(true);
    try {
      await invoke('connect_direct', {
        djName: djName.trim(),
        serverHost,
        serverPort,
      });

      // Start audio capture
      if (selectedSource) {
        await invoke('start_capture', { sourceId: selectedSource });
      }

      setStatus(prev => ({ ...prev, connected: true }));
    } catch (e) {
      setStatus(prev => ({ ...prev, error: String(e) }));
    } finally {
      setIsConnecting(false);
    }
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
    } catch (e) {
      console.error('Disconnect error:', e);
    }
  };

  const formatCode = (code: string[]) => {
    const str = code.join('');
    if (str.length <= 4) return str;
    return `${str.slice(0, 4)}-${str.slice(4)}`;
  };

  return (
    <div className="app">
      <header className="app-header">
        <div className="brand">
          <img className="brand-logo" src={MCAV_LOGO_DATA_URI} alt="MCAV logo" />
          <div className="brand-copy">
            <p className="brand-kicker">MCAV</p>
            <h1>DJ Client</h1>
          </div>
        </div>
        <BeatIndicator active={isBeat && status.connected} />
      </header>

      <main className="app-main">
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

        {!status.connected ? (
          <>
            <section className="section hero-section">
              <h2>Go live in under 10 seconds</h2>
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
              </section>
            )}

            <section className="section">
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
              <ConnectCode
                value={connectCode}
                onChange={setConnectCode}
                label="Step 2 - Connect Code"
              />
              <div className="code-display">
                {formatCode(connectCode) || 'XXXX-XXXX'}
              </div>
            </section>

            <section className="section">
              <AudioSourceSelect
                sources={audioSources}
                value={selectedSource}
                onChange={setSelectedSource}
                onRefresh={loadAudioSources}
                label="Step 3 - Audio Source"
              />
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
                {isConnecting ? 'Connecting...' : 'Connect With Code'}
              </button>
              <button
                className="btn btn-quick-connect"
                onClick={handleQuickConnect}
                disabled={isConnecting || !djName.trim()}
              >
                {isConnecting ? 'Connecting...' : 'Quick Connect (No Code)'}
              </button>
            </div>
          </>
        ) : (
          <>
            <section className="section">
              <FrequencyMeter bands={bands} />
            </section>

            <section className="section">
              <StatusPanel status={status} />
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
    </div>
  );
}

export default App;
