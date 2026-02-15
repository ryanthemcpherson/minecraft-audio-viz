import { useState } from 'react';
import type { UseAuthReturn } from '../hooks/useAuth';

type Tab = 'signin' | 'register';

interface AuthModalProps {
  auth: UseAuthReturn;
  onClose: () => void;
}

export default function AuthModal({ auth, onClose }: AuthModalProps) {
  const [tab, setTab] = useState<Tab>('signin');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      if (tab === 'signin') {
        await auth.login(email, password);
      } else {
        await auth.register(email, password, displayName);
      }
      // Modal will auto-close via parent watching isSignedIn
    } catch {
      // Error is shown via auth.error
    }
  };

  return (
    <div className="welcome-overlay" onClick={(e) => {
      if (e.target === e.currentTarget) onClose();
    }}>
      <div className="auth-modal">
        <div className="auth-tabs">
          <button
            className={`auth-tab ${tab === 'signin' ? 'active' : ''}`}
            onClick={() => { setTab('signin'); auth.clearError(); }}
            type="button"
          >
            Sign In
          </button>
          <button
            className={`auth-tab ${tab === 'register' ? 'active' : ''}`}
            onClick={() => { setTab('register'); auth.clearError(); }}
            type="button"
          >
            Create Account
          </button>
        </div>

        <form className="auth-form" onSubmit={handleSubmit}>
          {tab === 'register' && (
            <label className="input-label">
              Display Name
              <input
                type="text"
                className="input"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                placeholder="Your name"
                maxLength={100}
                required
                autoFocus
              />
            </label>
          )}

          <label className="input-label">
            Email
            <input
              type="email"
              className="input"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
              required
              autoFocus={tab === 'signin'}
            />
          </label>

          <label className="input-label">
            Password
            <input
              type="password"
              className="input"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder={tab === 'register' ? 'At least 8 characters' : 'Password'}
              minLength={tab === 'register' ? 8 : 1}
              required
            />
          </label>

          {auth.error && <div className="error-message">{auth.error}</div>}

          <button
            className="btn btn-connect"
            type="submit"
            disabled={auth.isLoading}
            style={{ width: '100%' }}
          >
            {auth.isLoading
              ? 'Please wait...'
              : tab === 'signin'
                ? 'Sign In'
                : 'Create Account'}
          </button>
        </form>

        <div className="auth-divider">
          <span>or</span>
        </div>

        <button
          className="btn btn-discord"
          onClick={auth.signInWithDiscord}
          type="button"
          disabled={auth.isLoading}
        >
          <svg width="20" height="15" viewBox="0 0 71 55" fill="currentColor">
            <path d="M60.1 4.9A58.5 58.5 0 0 0 45.4.2a.2.2 0 0 0-.2.1 40.8 40.8 0 0 0-1.8 3.7 54 54 0 0 0-16.2 0A37.3 37.3 0 0 0 25.4.3a.2.2 0 0 0-.2-.1A58.4 58.4 0 0 0 10.6 4.9a.2.2 0 0 0-.1.1C1.5 18.7-.9 32.2.3 45.5v.1a58.7 58.7 0 0 0 17.7 9a.2.2 0 0 0 .3-.1 42 42 0 0 0 3.6-5.9.2.2 0 0 0-.1-.3 38.7 38.7 0 0 1-5.5-2.6.2.2 0 0 1 0-.4l1.1-.9a.2.2 0 0 1 .2 0 41.9 41.9 0 0 0 35.6 0 .2.2 0 0 1 .2 0l1.1.9a.2.2 0 0 1 0 .4 36.4 36.4 0 0 1-5.5 2.6.2.2 0 0 0-.1.3 47.1 47.1 0 0 0 3.6 5.9.2.2 0 0 0 .3.1A58.5 58.5 0 0 0 70.5 45.6v-.1c1.4-15-2.3-28.4-9.8-40.1a.2.2 0 0 0-.1-.1ZM23.7 37.3c-3.5 0-6.3-3.2-6.3-7.1 0-3.9 2.8-7.1 6.3-7.1 3.6 0 6.4 3.2 6.3 7.1 0 3.9-2.8 7.1-6.3 7.1Zm23.3 0c-3.5 0-6.3-3.2-6.3-7.1 0-3.9 2.8-7.1 6.3-7.1 3.6 0 6.4 3.2 6.3 7.1 0 3.9-2.7 7.1-6.3 7.1Z" />
          </svg>
          Continue with Discord
        </button>

        <button
          className="btn btn-link auth-close"
          onClick={onClose}
          type="button"
        >
          Cancel
        </button>
      </div>
    </div>
  );
}
