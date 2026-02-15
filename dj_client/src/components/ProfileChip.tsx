import { useState, useRef, useEffect } from 'react';
import type { UserProfileResponse } from '../lib/api';

interface ProfileChipProps {
  user: UserProfileResponse;
  onSignOut: () => void;
}

function getInitials(name: string): string {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0])
    .join('')
    .toUpperCase();
}

export default function ProfileChip({ user, onSignOut }: ProfileChipProps) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  // Close dropdown on outside click
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  const avatarUrl = user.avatar_url;
  const displayName = user.display_name;

  return (
    <div className="profile-chip-wrapper" ref={ref}>
      <button
        className="profile-chip"
        onClick={() => setOpen((v) => !v)}
        type="button"
        title={displayName}
      >
        {avatarUrl ? (
          <img
            className="profile-chip-avatar"
            src={avatarUrl}
            alt=""
            width={26}
            height={26}
          />
        ) : (
          <span className="profile-chip-avatar profile-chip-initials">
            {getInitials(displayName)}
          </span>
        )}
        <span className="profile-chip-name">{displayName}</span>
      </button>

      {open && (
        <div className="profile-dropdown">
          <div className="profile-dropdown-header">
            <span className="profile-dropdown-name">{displayName}</span>
            {user.email && (
              <span className="profile-dropdown-email">{user.email}</span>
            )}
          </div>
          <button
            className="btn profile-dropdown-signout"
            onClick={() => {
              setOpen(false);
              onSignOut();
            }}
            type="button"
          >
            Sign Out
          </button>
        </div>
      )}
    </div>
  );
}
