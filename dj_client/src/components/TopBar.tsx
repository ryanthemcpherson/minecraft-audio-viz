import BeatIndicator from './BeatIndicator';
import ProfileChip from './ProfileChip';

interface TopBarProps {
  djName: string;
  onDjNameChange?: (name: string) => void;
  showName: string | null;
  isBeat: boolean;
  isConnected: boolean;
  user: any | null;
  isSignedIn: boolean;
  onSignOut: () => void;
  onSignIn: () => void;
}

export default function TopBar({
  djName, onDjNameChange, showName, isBeat, isConnected,
  user, isSignedIn, onSignOut, onSignIn,
}: TopBarProps) {
  return (
    <div className="top-bar">
      {onDjNameChange ? (
        <input
          type="text"
          className="input dj-name-input"
          value={djName}
          onChange={e => onDjNameChange(e.target.value)}
          placeholder="DJ Name"
          maxLength={32}
        />
      ) : (
        <span className="dj-label">{djName}</span>
      )}
      {showName && <span className="show-label">{showName}</span>}
      <div className="top-bar-right">
        {isSignedIn && user ? (
          <ProfileChip user={user} onSignOut={onSignOut} />
        ) : !isConnected ? (
          <button className="btn-signin" onClick={onSignIn} type="button">
            Sign In
          </button>
        ) : null}
        {isConnected && <BeatIndicator active={isBeat} />}
      </div>
    </div>
  );
}
