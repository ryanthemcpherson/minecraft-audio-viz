interface StatusStripProps {
  connected: boolean;
  isActive: boolean;
  showName: string | null;
  queuePosition: number;
  totalDjs: number;
  activeDjName: string | null;
  latencyMs: number;
  mcConnected: boolean;
  bpm: number;
}

export default function StatusStrip({
  connected, isActive, showName, queuePosition, totalDjs,
  activeDjName, latencyMs, mcConnected, bpm,
}: StatusStripProps) {
  if (!connected) return null;

  return (
    <div className={`status-strip ${isActive ? 'status-strip--live' : ''}`}>
      <div className="status-strip-row">
        <span className={`status-strip-dot ${connected ? 'connected' : 'disconnected'}`} />
        {isActive ? (
          <span className="status-strip-badge">LIVE</span>
        ) : (
          <span className="status-strip-standby">
            Standby{activeDjName ? ` · ${activeDjName} is live` : ''}
          </span>
        )}
        {showName && <span className="status-strip-show">{showName}</span>}
      </div>
      <div className="status-strip-row status-strip-meta">
        <span>Queue: {queuePosition}/{totalDjs}</span>
        {bpm > 0 && <span className="status-strip-bpm">{Math.round(bpm)} BPM</span>}
        <span className="status-strip-latency">{Math.round(latencyMs)}ms</span>
        {mcConnected && <span className="status-strip-mc">MC</span>}
      </div>
    </div>
  );
}
