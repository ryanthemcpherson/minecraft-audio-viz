import ConnectCode from './ConnectCode';
import AudioSourceSelect from './AudioSourceSelect';
import { getTlsFingerprintFieldState } from '../lib/connectionProfile';

interface AudioSource {
  id: string;
  name: string;
  source_type: 'system_audio' | 'application' | 'input_device';
}

interface ConnectFormProps {
  connectCode: string;
  onConnectCodeChange: (code: string) => void;
  selectedSource: string | null;
  onSourceChange: (source: string | null) => void;
  audioSources: AudioSource[];
  onRefreshSources: () => void;
  directConnect: boolean;
  onDirectConnectChange: (checked: boolean) => void;
  serverHost: string;
  onServerHostChange: (host: string) => void;
  serverPort: number;
  onServerPortChange: (port: number) => void;
  tlsFingerprint: string;
  onTlsFingerprintChange: (fingerprint: string) => void;
  isTlsFingerprintValid: boolean;
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
  tlsFingerprint,
  onTlsFingerprintChange,
  isTlsFingerprintValid,
  error,
  isConnecting,
  djName,
  onConnect,
}: ConnectFormProps) {
  const tlsFingerprintFieldState = getTlsFingerprintFieldState(tlsFingerprint);

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

      <div className="field-group">
        <label className="field-label" htmlFor="tls-fingerprint">
          Server certificate SHA-256 fingerprint
        </label>
        <input
          id="tls-fingerprint"
          type="text"
          className="input input-sm"
          style={{ fontFamily: 'var(--font-mono)' }}
          value={tlsFingerprint}
          onChange={e => onTlsFingerprintChange(e.target.value)}
          placeholder="64 hexadecimal characters"
          maxLength={95}
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          aria-describedby={
            tlsFingerprintFieldState.describedBy
          }
          aria-invalid={tlsFingerprintFieldState.ariaInvalid}
        />
        <div id="tls-fingerprint-help" className="capture-mode-info">
          Safe to save; never share the server password. Required for a self-signed public endpoint
          such as wss://IP:25808. Leave blank to use normal platform certificate validation.
        </div>
        {!isTlsFingerprintValid && (
          <div id="tls-fingerprint-error" className="capture-mode-warning" role="alert">
            {tlsFingerprintFieldState.validationMessage}
          </div>
        )}
      </div>

      {error && <div className="error-message" role="alert">{error}</div>}

      <button
        className="btn btn-connect full-width"
        onClick={onConnect}
        disabled={
          isConnecting ||
          (!directConnect && connectCode.length !== 8) ||
          !djName.trim() ||
          !isTlsFingerprintValid
        }
      >
        {isConnecting ? 'Connecting...' : 'Connect'}
      </button>
    </div>
  );
}
