export interface AudioSource {
  id: string;
  name: string;
  source_type: 'system_audio' | 'application' | 'input_device';
}

export interface ConnectionStatus {
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

export interface AudioLevels {
  bands: number[];
  peak: number;
  is_beat: boolean;
  beat_intensity: number;
  bpm: number;
}

export interface VoiceStatus {
  available: boolean;
  streaming: boolean;
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

export const DEFAULT_CONNECTION_STATUS: ConnectionStatus = {
  connected: false,
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
  available: false,
  streaming: false,
  channel_type: 'static',
  connected_players: 0,
};

export const DEFAULT_AUDIO_DATA: AudioData = {
  bands: [0, 0, 0, 0, 0],
  isBeat: false,
  bpm: 0,
  beatIntensity: 0,
};
