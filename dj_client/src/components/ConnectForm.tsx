import ConnectCode from './ConnectCode';
import AudioSourceSelect from './AudioSourceSelect';

interface AudioSource {
  id: string;
  name: string;
  source_type: 'system_audio' | 'application' | 'input_device';
}

interface ConnectFormProps {
  connectCode: string;
  onConnectCodeChange: (code: string) => void;
  selectedSource: string;
  onSourceChange: (source: string) => void;
  audioSources: AudioSource[];
  onRefreshSources: () => void;
  directConnect: boolean;
  onDirectConnectChange: (checked: boolean) => void;
  serverHost: string;
  onServerHostChange: (host: string) => void;
  serverPort: number;
  onServerPortChange: (port: number) => void;
  error: string | null;
  isConnecting: boolean;
  djName: string;
  onConnect: () => void;
}

export default function ConnectForm({
  connectCode,
  onConnectCodeChange,
  selectedSource,
  onSourceChange,
  audioSources,
  onRefreshSources,
  directConnect,
  onDirectConnectChange,
  serverHost,
  onServerHostChange,
  serverPort,
  onServerPortChange,
  error,
  isConnecting,
  djName,
  onConnect,
}: ConnectFormProps) {
  return (
    <div className="connect-form">
      <div className="connect-row">
        <div className="field-group">
          <label className="field-label">Code</label>
          <ConnectCode value={connectCode} onChange={onConnectCodeChange} />
        </div>
        <div className="field-group">
          <label className="field-label">Audio</label>
          <AudioSourceSelect
            sources={audioSources}
            value={selectedSource}
            onChange={onSourceChange}
            onRefresh={onRefreshSources}
          />
        </div>
      </div>

      <label className="checkbox-label">
        <input type="checkbox" checked={directConnect} onChange={e => onDirectConnectChange(e.target.checked)} />
        Direct connect (self-hosted)
      </label>

      {directConnect && (
        <div className="direct-connect-row">
          <input
            type="text"
            className="input input-sm"
            value={serverHost}
            onChange={e => onServerHostChange(e.target.value)}
            placeholder="Host"
          />
          <input
            type="number"
            className="input input-sm input-port"
            value={serverPort}
            onChange={e => onServerPortChange(parseInt(e.target.value, 10) || 9000)}
            placeholder="Port"
          />
        </div>
      )}

      {error && <div className="error-message">{error}</div>}

      <button
        className="btn btn-connect full-width"
        onClick={onConnect}
        disabled={isConnecting || connectCode.length !== 8 || !djName.trim()}
      >
        {isConnecting ? 'Connecting...' : 'Connect'}
      </button>
    </div>
  );
}
