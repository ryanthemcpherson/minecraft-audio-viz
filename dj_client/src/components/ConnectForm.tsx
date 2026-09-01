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
  selectedSource: string | null;
  onSourceChange: (source: string | null) => void;
  audioSources: AudioSource[];
  onRefreshSources: () => void;
  directConnect: boolean;
  onDirectConnectChange: (checked: boolean) => void;
  serverUrl: string;
  onServerUrlChange: (url: string) => void;
  certificateFingerprint: string;
  onCertificateFingerprintChange: (fingerprint: string) => void;
  inviteLink: string;
  onInviteLinkChange: (invite: string) => void;
  onImportInvite: () => void;
  inviteExpiresAt: number | null;
  pinReplacementRequired: boolean;
  pinReplacementConfirmed: boolean;
  onPinReplacementConfirmedChange: (confirmed: boolean) => void;
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
  serverUrl,
  onServerUrlChange,
  certificateFingerprint,
  onCertificateFingerprintChange,
  inviteLink,
  onInviteLinkChange,
  onImportInvite,
  inviteExpiresAt,
  pinReplacementRequired,
  pinReplacementConfirmed,
  onPinReplacementConfirmedChange,
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

      <div className="invite-import">
        <label className="field-label" htmlFor="invite-link">Administrator invite</label>
        <div className="invite-import-row">
          <input
            id="invite-link"
            type="text"
            className="input input-sm"
            value={inviteLink}
            onChange={event => onInviteLinkChange(event.target.value)}
            placeholder="mcav://connect?invite=..."
            autoComplete="off"
            spellCheck={false}
          />
          <button
            className="btn btn-secondary"
            type="button"
            onClick={onImportInvite}
            disabled={!inviteLink.trim()}
          >
            Import
          </button>
        </div>
      </div>

      <label className="checkbox-label">
        <input type="checkbox" checked={directConnect} onChange={e => onDirectConnectChange(e.target.checked)} />
        Direct connect (self-hosted)
      </label>

      {directConnect && (
        <div className="direct-connect-fields">
          <label className="field-label" htmlFor="server-url">Secure server URL</label>
          <input
            id="server-url"
            type="url"
            className="input input-sm"
            value={serverUrl}
            onChange={event => onServerUrlChange(event.target.value)}
            placeholder="wss://server.example:8080/ws/dj"
            autoComplete="url"
            spellCheck={false}
          />
          <label className="field-label" htmlFor="certificate-fingerprint">
            Certificate SHA-256 <span className="field-optional">(self-signed servers)</span>
          </label>
          <input
            id="certificate-fingerprint"
            type="text"
            className="input input-sm fingerprint-input"
            value={certificateFingerprint}
            onChange={event => onCertificateFingerprintChange(event.target.value)}
            placeholder="64 hexadecimal characters"
            autoComplete="off"
            spellCheck={false}
          />
          {inviteExpiresAt && (
            <p
              className={
                inviteExpiresAt * 1000 - Date.now() < 300_000
                  ? 'connection-note connection-note-warning'
                  : 'connection-note'
              }
              role="status"
            >
              Invite expires {new Date(inviteExpiresAt * 1000).toLocaleString()}.
            </p>
          )}
          {pinReplacementRequired && (
            <label className="pin-replacement-warning">
              <input
                type="checkbox"
                checked={pinReplacementConfirmed}
                onChange={event => onPinReplacementConfirmedChange(event.target.checked)}
              />
              I verified this certificate trust change with the server administrator.
            </label>
          )}
        </div>
      )}

      {error && <div className="error-message" role="alert">{error}</div>}

      <button
        className="btn btn-connect full-width"
        onClick={onConnect}
        disabled={
          isConnecting ||
          connectCode.length !== 8 ||
          !djName.trim() ||
          (directConnect && !serverUrl.trim()) ||
          (pinReplacementRequired && !pinReplacementConfirmed)
        }
      >
        {isConnecting ? 'Connecting...' : 'Connect'}
      </button>
    </div>
  );
}
