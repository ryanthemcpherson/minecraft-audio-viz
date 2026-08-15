import { useState, useEffect, useCallback, useRef } from 'react';
import AuthModal from './components/AuthModal';
import ConnectedView from './components/ConnectedView';
import DisconnectedView from './components/DisconnectedView';
import { useAuth } from './hooks/useAuth';
import { useConnection } from './hooks/useConnection';
import { useAutoUpdate } from './hooks/useAutoUpdate';
import { useAudioSources } from './hooks/useAudioSources';

function App() {
  const auth = useAuth();
  const [showAuthModal, setShowAuthModal] = useState(false);
  const connection = useConnection(auth);
  const update = useAutoUpdate();
  const audioSources = useAudioSources();

  const [showWelcomeOverlay, setShowWelcomeOverlay] = useState(false);

  // Refs to avoid stale closures in the keyboard handler
  const connectionRef = useRef(connection);
  const audioSourcesRef = useRef(audioSources);
  const showAuthModalRef = useRef(showAuthModal);
  const showWelcomeOverlayRef = useRef(showWelcomeOverlay);

  useEffect(() => { connectionRef.current = connection; }, [connection]);
  useEffect(() => { audioSourcesRef.current = audioSources; }, [audioSources]);
  useEffect(() => { showAuthModalRef.current = showAuthModal; }, [showAuthModal]);
  useEffect(() => { showWelcomeOverlayRef.current = showWelcomeOverlay; }, [showWelcomeOverlay]);

  // Load audio sources and check onboarding on mount
  useEffect(() => {
    const onboardingComplete = localStorage.getItem('mcav.onboardingComplete');
    if (!onboardingComplete) {
      setShowWelcomeOverlay(true);
    }
    audioSources.loadAudioSources();
  }, []);

  // Persist selected audio source
  useEffect(() => {
    if (audioSources.selectedSource) {
      localStorage.setItem('mcav.audioSource', audioSources.selectedSource);
    }
  }, [audioSources.selectedSource]);

  // Auto-close auth modal when signed in
  useEffect(() => {
    if (auth.isSignedIn && showAuthModal) {
      setShowAuthModal(false);
    }
  }, [auth.isSignedIn, showAuthModal]);

  const handleDismissWelcome = useCallback(() => {
    localStorage.setItem('mcav.onboardingComplete', 'true');
    setShowWelcomeOverlay(false);
  }, []);

  // Keyboard shortcuts handler - uses refs to avoid stale closures
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      // Don't trigger shortcuts when typing in input fields
      const target = event.target as HTMLElement;
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA') {
        return;
      }

      const isMac = navigator.platform.toUpperCase().indexOf('MAC') >= 0;
      const modifierKey = isMac ? event.metaKey : event.ctrlKey;

      const conn = connectionRef.current;
      const sources = audioSourcesRef.current;

      // Ctrl/Cmd + D - Disconnect
      if (modifierKey && event.key === 'd') {
        event.preventDefault();
        if (conn.status.isConnected) {
          void conn.handleDisconnect();
        }
      }

      // Ctrl/Cmd + R - Refresh audio sources
      if (modifierKey && event.key === 'r') {
        event.preventDefault();
        void sources.loadAudioSources();
      }

      // Ctrl/Cmd + T - Toggle test audio
      if (modifierKey && event.key === 't') {
        event.preventDefault();
        if (sources.selectedSource && !conn.status.isConnected) {
          if (sources.isTestingAudio) {
            void sources.handleStopTest();
          } else {
            void sources.handleStartTest();
          }
        }
      }

      // Escape - Close auth modal, welcome overlay, or stop test audio
      if (event.key === 'Escape') {
        if (showAuthModalRef.current) {
          setShowAuthModal(false);
        } else if (showWelcomeOverlayRef.current) {
          handleDismissWelcome();
        } else if (sources.isTestingAudio) {
          void sources.handleStopTest();
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [handleDismissWelcome]);

  const handleConnect = () => {
    void connection.handleConnect(
      audioSources.selectedSource,
      audioSources.isTestingAudio,
      audioSources.handleStopTest,
    );
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

      {(update.availableUpdate && !update.dismissUpdateBanner) && (
        <div className="update-banner">
          <span>Update {update.availableUpdate.version} available</span>
          <button className="btn btn-link" onClick={update.installAvailableUpdate} disabled={update.isInstallingUpdate}>
            {update.isInstallingUpdate ? 'Installing...' : 'Update'}
          </button>
          <button className="btn-dismiss" onClick={() => update.setDismissUpdateBanner(true)}>&times;</button>
        </div>
      )}

      {auth.isSignedIn && auth.user && !auth.user.email_verified && (
        <div className="email-verify-banner">
          <span>Please verify your email. Check your inbox for a verification link.</span>
          {auth.verificationMessage ? (
            <span className="success-message">{auth.verificationMessage}</span>
          ) : (
            <button className="btn-link-inline" onClick={auth.resendVerification} type="button">
              Resend
            </button>
          )}
        </div>
      )}

      {!connection.status.isConnected ? (
        <DisconnectedView
          auth={auth}
          connection={connection}
          audioSources={audioSources}
          onSignIn={() => setShowAuthModal(true)}
          onConnect={handleConnect}
        />
      ) : (
        <ConnectedView
          auth={auth}
          connection={connection}
          audioSources={audioSources}
          onSignIn={() => setShowAuthModal(true)}
        />
      )}

      {showAuthModal && (
        <AuthModal auth={auth} onClose={() => setShowAuthModal(false)} />
      )}
    </div>
  );
}

export default App;
