import React from 'react';
import type { ConnectionStatus } from '../types';

interface StatusPanelProps {
  status: ConnectionStatus;
}

function StatusPanel({ status }: StatusPanelProps) {
  return (
    <div className={`status-panel ${status.is_active ? 'active' : 'standby'}`}>
      <div className="status-indicator">
        <span className={`status-dot ${status.isConnected ? 'connected' : 'disconnected'}`} />
        <span className="status-text">
          {status.isConnected ? (status.is_active ? 'LIVE' : 'CONNECTED') : 'DISCONNECTED'}
        </span>
      </div>

      <div className="status-details">
        <div className="status-row">
          <span className="status-label">Latency:</span>
          <span className={`status-value ${status.latency_ms > 100 ? 'warning' : ''}`}>
            {status.latency_ms.toFixed(0)}ms
          </span>
        </div>

        <div className="status-row">
          <span className="status-label">Route:</span>
          <span className="status-value">
            {(status.route_mode || 'relay').toUpperCase()}
          </span>
        </div>

        <div className="status-row">
          <span className="status-label">Direct MC:</span>
          <span className={`status-value ${status.mc_connected ? '' : 'warning'}`}>
            {status.mc_connected ? 'CONNECTED' : 'DISCONNECTED'}
          </span>
        </div>

        {status.total_djs > 0 && (
          <div className="status-row">
            <span className="status-label">Queue:</span>
            <span className="status-value">
              {status.queue_position} of {status.total_djs} DJs
            </span>
          </div>
        )}

        {!status.is_active && status.active_dj_name && (
          <div className="status-row">
            <span className="status-label">Status:</span>
            <span className="status-value standby">
              STANDBY ({status.active_dj_name} is LIVE)
            </span>
          </div>
        )}

        {status.is_active && (
          <div className="status-row live-status">
            <span className="live-badge">
              <span className="live-dot" />
              YOU ARE LIVE
            </span>
          </div>
        )}
      </div>
    </div>
  );
}

export default React.memo(StatusPanel);
