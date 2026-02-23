import React from 'react';

interface RosterDJ {
  dj_id: string;
  dj_name: string;
  is_active: boolean;
  avatar_url: string | null;
  queue_position: number;
}

interface QueuePanelProps {
  roster: {
    djs: RosterDJ[];
    active_dj_id: string | null;
    your_position: number;
    rotation_interval_sec: number;
  } | null;
}

function QueuePanel({ roster }: QueuePanelProps) {
  if (!roster || roster.djs.length === 0) {
    return (
      <div className="queue-panel">
        <div className="queue-header">Queue</div>
        <div className="queue-empty">No other DJs connected</div>
      </div>
    );
  }

  const sorted = [...roster.djs].sort((a, b) => a.queue_position - b.queue_position);

  return (
    <div className="queue-panel">
      <div className="queue-header">Queue</div>
      {sorted.map(dj => (
        <div key={dj.dj_id} className={`queue-dj ${dj.is_active ? 'active' : ''}`}>
          <div className="queue-avatar">
            {dj.avatar_url
              ? <img src={dj.avatar_url} alt="" />
              : <span>{dj.dj_name.charAt(0).toUpperCase()}</span>
            }
          </div>
          <span className="queue-name">{dj.dj_name}</span>
          {dj.is_active && <span className="queue-live">LIVE</span>}
        </div>
      ))}
    </div>
  );
}

export default React.memo(QueuePanel);
