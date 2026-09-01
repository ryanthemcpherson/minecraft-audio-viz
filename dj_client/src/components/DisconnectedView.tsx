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
        serverUrl={connection.serverUrl}
        onServerUrlChange={connection.setServerUrl}
        certificateFingerprint={connection.certificateFingerprint}
        onCertificateFingerprintChange={connection.setCertificateFingerprint}
        inviteLink={connection.inviteLink}
        onInviteLinkChange={connection.setInviteLink}
        onImportInvite={connection.importInvite}
        inviteExpiresAt={connection.inviteExpiresAt}
        pinReplacementRequired={connection.pinReplacementRequired}
        pinReplacementConfirmed={connection.pinReplacementConfirmed}
        onPinReplacementConfirmedChange={connection.setPinReplacementConfirmed}
        error={connection.status.error}
        isConnecting={connection.isConnecting}
        djName={connection.djName}
        onConnect={onConnect}
      />
    </div>
  );
}
