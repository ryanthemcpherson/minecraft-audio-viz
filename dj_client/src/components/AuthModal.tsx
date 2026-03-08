import { useState } from 'react';
import * as api from '../lib/api';
import type { UseAuthReturn } from '../hooks/useAuth';

type Tab = 'signin' | 'register' | 'forgot';

interface AuthModalProps {
  auth: UseAuthReturn;
  onClose: () => void;
}

export default function AuthModal({ auth, onClose }: AuthModalProps) {
  const [tab, setTab] = useState<Tab>('signin');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [forgotEmail, setForgotEmail] = useState('');
  const [forgotLoading, setForgotLoading] = useState(false);
  const [forgotMessage, setForgotMessage] = useState<string | null>(null);
  const [forgotError, setForgotError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      if (tab === 'signin') {
        await auth.login(email, password);
      } else if (tab === 'register') {
        await auth.register(email, password, displayName);
      }
      // Modal will auto-close via parent watching isSignedIn
    } catch (err) {
      console.error("Failed to submit auth form:", err);
    }
  };

  const handleForgotSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setForgotLoading(true);
    setForgotMessage(null);
    setForgotError(null);
    try {
      const { message } = await api.forgotPassword(forgotEmail);
      setForgotMessage(message);
    } catch (err) {
      setForgotError(err instanceof Error ? err.message : String(err));
    } finally {
      setForgotLoading(false);
    }
  };

  if (tab === 'forgot') {
    return (
      <div className="welcome-overlay" onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}>
        <div className="auth-modal">
          <button
            className="auth-back"
            onClick={() => { setTab('signin'); setForgotMessage(null); setForgotError(null); }}
            type="button"
          >
            &larr; Back to sign in
          </button>

          <h2 className="auth-heading">Reset Password</h2>
          <p className="auth-subtext">
            Enter your email and we'll send you a link to reset your password.
          </p>

          <form className="auth-form" onSubmit={handleForgotSubmit}>
            <label className="input-label">
              Email
              <input
                type="email"
                className="input"
                value={forgotEmail}
                onChange={(e) => setForgotEmail(e.target.value)}
                placeholder="you@example.com"
                required
                autoFocus
              />
            </label>

            {forgotError && <div className="error-message">{forgotError}</div>}
            {forgotMessage && <div className="success-message">{forgotMessage}</div>}

            <button
              className="btn btn-connect"
              type="submit"
              disabled={forgotLoading}
              style={{ width: '100%' }}
            >
              {forgotLoading ? 'Sending...' : 'Send Reset Link'}
            </button>
          </form>

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

          {tab === 'signin' && (
            <button
              className="btn-link-inline"
              onClick={() => { setTab('forgot'); setForgotEmail(email); }}
              type="button"
            >
              Forgot password?
            </button>
          )}

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
          className="btn btn-google"
          onClick={auth.signInWithGoogle}
          type="button"
          disabled={auth.isLoading}
        >
          <svg width="18" height="18" viewBox="0 0 48 48">
            <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/>
            <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/>
            <path fill="#FBBC05" d="M10.53 28.59a14.5 14.5 0 0 1 0-9.18l-7.98-6.19a24.0 24.0 0 0 0 0 21.56l7.98-6.19z"/>
            <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/>
          </svg>
          Continue with Google
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
