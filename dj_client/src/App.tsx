import { useState, useEffect } from 'react';
import { invoke } from '@tauri-apps/api/core';
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

  // Poll audio levels when connected
  useEffect(() => {
    if (!status.connected) return;

    const interval = setInterval(async () => {
      try {
        const levels = await invoke<AudioLevels>('get_audio_levels');
        setBands(levels.bands);
        setIsBeat(levels.is_beat);

        const newStatus = await invoke<ConnectionStatus>('get_status');
        setStatus(newStatus);
      } catch (e) {
        console.error('Failed to get audio levels:', e);
      }
    }, 16); // ~60fps

    return () => clearInterval(interval);
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
