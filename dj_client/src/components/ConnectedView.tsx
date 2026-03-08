import TopBar from './TopBar';
import PresetBar from './PresetBar';
import FrequencyMeter from './FrequencyMeter';
import StatusPanel from './StatusPanel';
import QueuePanel from './QueuePanel';
import StatusStrip from './StatusStrip';
import FrequencyStrip from './FrequencyStrip';
import AudioSourceSelect from './AudioSourceSelect';
import type { UseAuthReturn } from '../hooks/useAuth';
import type { UseConnectionReturn } from '../hooks/useConnection';
import type { UseAudioSourcesReturn } from '../hooks/useAudioSources';

interface ConnectedViewProps {
  auth: UseAuthReturn;
  connection: UseConnectionReturn;
  audioSources: UseAudioSourcesReturn;
  onSignIn: () => void;
}

export default function ConnectedView({
  auth,
  connection,
  audioSources,
  onSignIn,
}: ConnectedViewProps) {
  const handleSourceChange = (sourceId: string | null) => {
    void audioSources.handleSourceChange(sourceId, connection.status.connected);
  };

  return (
    <div className="dashboard connected">
      <TopBar
        djName={connection.djName}
        showName={connection.showName}
        isBeat={connection.isBeatForUI && connection.status.connected}
        isConnected={true}
        user={auth.user}
        isSignedIn={auth.isSignedIn}
        onSignOut={auth.signOut}
        onSignIn={onSignIn}
      />

      <div className="main-content">
        {/* Compact layout (<720px) */}
        <div className="compact-only">
          <StatusStrip
            connected={connection.status.connected}
            isActive={connection.status.is_active}
            showName={connection.showName}
            queuePosition={connection.status.queue_position}
            totalDjs={connection.status.total_djs}
            activeDjName={connection.status.active_dj_name}
            latencyMs={connection.status.latency_ms}
            mcConnected={connection.status.mc_connected}
            bpm={connection.audioRef.current.bpm}
          />
          <FrequencyStrip audioRef={connection.audioRef} />
          <PresetBar active={connection.activePreset} onChange={connection.handlePresetChange} />
          <QueuePanel roster={connection.roster} />
        </div>

        {/* Expanded layout (>720px) */}
        <div className="expanded-only">
          <div className="col-left">
            <FrequencyMeter audioRef={connection.audioRef} />
            <PresetBar active={connection.activePreset} onChange={connection.handlePresetChange} />
          </div>
          <div className="col-right">
            <StatusPanel status={connection.status} />
            <QueuePanel roster={connection.roster} />
          </div>
        </div>
      </div>

      <div className="bottom-bar">
        <AudioSourceSelect
          sources={audioSources.audioSources}
          value={audioSources.selectedSource}
          onChange={handleSourceChange}
          onRefresh={audioSources.loadAudioSources}
        />
        {connection.captureMode && connection.captureMode.mode === 'process_loopback' && (
          <span className="capture-info">{connection.captureMode.name}</span>
        )}
        <button
          className={`btn voice-toggle ${connection.voiceEnabled ? 'voice-on' : 'voice-off'}`}
          onClick={connection.handleToggleVoice}
          type="button"
        >
          {connection.voiceEnabled ? 'Mute Voice' : 'Voice Chat'}
        </button>
        <button className="btn btn-disconnect" onClick={connection.handleDisconnect}>
          Disconnect
        </button>
      </div>
    </div>
  );
}
