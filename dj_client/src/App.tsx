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
    queue_position: 0,
    total_djs: 0,
    active_dj_name: null,
    error: null,
  });
  const [isConnecting, setIsConnecting] = useState(false);

  // Load audio sources on mount
  useEffect(() => {
    loadAudioSources();
  }, []);

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
      if (sources.length > 0) {
        setSelectedSource(sources[0].id);
      }
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
        <h1>AudioViz DJ</h1>
        <BeatIndicator active={isBeat} />
      </header>

      <main className="app-main">
        {!status.connected ? (
          <>
            {/* Server Settings */}
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

            {/* Connect Code */}
            <section className="section">
              <ConnectCode
                value={connectCode}
                onChange={setConnectCode}
              />
              <div className="code-display">
                {formatCode(connectCode) || 'XXXX-XXXX'}
              </div>
            </section>

            {/* DJ Name */}
            <section className="section">
              <label className="input-label">
                Your Name
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

            {/* Audio Source */}
            <section className="section">
              <AudioSourceSelect
                sources={audioSources}
                value={selectedSource}
                onChange={setSelectedSource}
                onRefresh={loadAudioSources}
              />
            </section>

            {/* Error Display */}
            {status.error && (
              <div className="error-message">
                {status.error}
              </div>
            )}

            {/* Connect Buttons */}
            <div className="connect-buttons">
              <button
                className="btn btn-connect"
                onClick={handleConnect}
                disabled={isConnecting || connectCode.join('').length !== 8 || !djName.trim()}
              >
                {isConnecting ? 'Connecting...' : 'CONNECT'}
              </button>
              <button
                className="btn btn-quick-connect"
                onClick={handleQuickConnect}
                disabled={isConnecting || !djName.trim()}
              >
                {isConnecting ? 'Connecting...' : 'QUICK CONNECT'}
              </button>
            </div>
          </>
        ) : (
          <>
            {/* Frequency Meters */}
            <section className="section">
              <FrequencyMeter bands={bands} />
            </section>

            {/* Status Panel */}
            <section className="section">
              <StatusPanel status={status} />
            </section>

            {/* Disconnect Button */}
            <button
              className="btn btn-disconnect"
              onClick={handleDisconnect}
            >
              DISCONNECT
            </button>
          </>
        )}
      </main>
    </div>
  );
}

export default App;
