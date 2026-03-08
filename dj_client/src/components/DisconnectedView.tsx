import TopBar from './TopBar';
import ConnectForm from './ConnectForm';
import type { UseAuthReturn } from '../hooks/useAuth';
import type { UseConnectionReturn } from '../hooks/useConnection';
import type { UseAudioSourcesReturn } from '../hooks/useAudioSources';

interface DisconnectedViewProps {
  auth: UseAuthReturn;
  connection: UseConnectionReturn;
  audioSources: UseAudioSourcesReturn;
  onSignIn: () => void;
  onConnect: () => void;
}

export default function DisconnectedView({
  auth,
  connection,
  audioSources,
  onSignIn,
  onConnect,
}: DisconnectedViewProps) {
  return (
    <div className="dashboard disconnected">
      <TopBar
        djName={connection.djName}
        onDjNameChange={connection.setDjName}
        showName={null}
        isBeat={false}
        isConnected={false}
        user={auth.user}
        isSignedIn={auth.isSignedIn}
        onSignOut={auth.signOut}
        onSignIn={onSignIn}
      />

      <ConnectForm
        connectCode={connection.connectCode}
        onConnectCodeChange={connection.setConnectCode}
        selectedSource={audioSources.selectedSource}
        onSourceChange={audioSources.setSelectedSource}
        audioSources={audioSources.audioSources}
        onRefreshSources={audioSources.loadAudioSources}
        directConnect={connection.directConnect}
        onDirectConnectChange={connection.setDirectConnect}
        serverHost={connection.serverHost}
        onServerHostChange={connection.setServerHost}
        serverPort={connection.serverPort}
        onServerPortChange={connection.setServerPort}
        error={connection.status.error}
        isConnecting={connection.isConnecting}
        djName={connection.djName}
        onConnect={onConnect}
      />
    </div>
  );
}
