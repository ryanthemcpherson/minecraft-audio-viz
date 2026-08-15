export interface AudioSource {
  id: string;
  name: string;
  source_type: 'system_audio' | 'application' | 'input_device';
}

// ---------------------------------------------------------------------------
// Wire types — snake_case fields match the WebSocket / Tauri event protocol.
// These interfaces are intentionally snake_case because they represent the
// JSON payloads serialised by the Rust backend (serde) and the VJ server.
// Do NOT rename fields here; add camelCase application types below instead.
// ---------------------------------------------------------------------------

/** Wire format for connection status events from the Rust backend. */
export interface WireConnectionStatus {
  connected: boolean;
  is_active: boolean;
  latency_ms: number;
  route_mode: string;
  mc_connected: boolean;
  queue_position: number;
  total_djs: number;
  active_dj_name: string | null;
  error: string | null;
}

/** Wire format for voice status events from the Rust backend. */
export interface WireVoiceStatus {
  available: boolean;
  streaming: boolean;
  channel_type: string;
  connected_players: number;
}

// ---------------------------------------------------------------------------
// Application types — camelCase booleans for use throughout the UI layer.
// ---------------------------------------------------------------------------

export interface ConnectionStatus {
  isConnected: boolean;
  is_active: boolean;
  latency_ms: number;
  route_mode: string;
  mc_connected: boolean;
  queue_position: number;
  total_djs: number;
  active_dj_name: string | null;
  error: string | null;
}

export interface AudioLevels {
  bands: number[];
  peak: number;
  is_beat: boolean;
  beat_intensity: number;
  bpm: number;
}

export interface VoiceStatus {
  isAvailable: boolean;
  isStreaming: boolean;
  channel_type: string;
  connected_players: number;
}

export interface CaptureMode {
  mode: 'pending' | 'system_loopback' | 'process_loopback' | 'input_device';
  fallback_reason?: string;
  pid?: number;
  name?: string;
}

export interface RosterDJ {
  dj_id: string;
  dj_name: string;
  is_active: boolean;
  avatar_url: string | null;
  queue_position: number;
}

export interface RosterUpdate {
  djs: RosterDJ[];
  active_dj_id: string | null;
  your_position: number;
  rotation_interval_sec: number;
}

export interface AudioData {
  bands: number[];
  isBeat: boolean;
  bpm: number;
  beatIntensity: number;
}

// ---------------------------------------------------------------------------
// Mapping helpers — convert wire payloads to application types.
// ---------------------------------------------------------------------------

export function mapWireConnectionStatus(wire: WireConnectionStatus): ConnectionStatus {
  return {
    isConnected: wire.connected,
    is_active: wire.is_active,
    latency_ms: wire.latency_ms,
    route_mode: wire.route_mode,
    mc_connected: wire.mc_connected,
    queue_position: wire.queue_position,
    total_djs: wire.total_djs,
    active_dj_name: wire.active_dj_name,
    error: wire.error,
  };
}

export function mapWireVoiceStatus(wire: WireVoiceStatus): VoiceStatus {
  return {
    isAvailable: wire.available,
    isStreaming: wire.streaming,
    channel_type: wire.channel_type,
    connected_players: wire.connected_players,
  };
}

export const DEFAULT_CONNECTION_STATUS: ConnectionStatus = {
  isConnected: false,
  is_active: false,
  latency_ms: 0,
  route_mode: '',
  mc_connected: false,
  queue_position: 0,
  total_djs: 0,
  active_dj_name: null,
  error: null,
};

export const DEFAULT_VOICE_STATUS: VoiceStatus = {
  isAvailable: false,
  isStreaming: false,
  channel_type: 'static',
  connected_players: 0,
};

export const DEFAULT_AUDIO_DATA: AudioData = {
  bands: [0, 0, 0, 0, 0],
  isBeat: false,
  bpm: 0,
  beatIntensity: 0,
};
