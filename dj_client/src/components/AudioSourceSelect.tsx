interface AudioSource {
  id: string;
  name: string;
  source_type: 'system_audio' | 'application' | 'input_device';
}

interface AudioSourceSelectProps {
  sources: AudioSource[];
  value: string | null;
  onChange: (id: string | null) => void;
  onRefresh: () => void;
  label?: string;
}

function AudioSourceSelect({ sources, value, onChange, onRefresh, label = 'Audio Source' }: AudioSourceSelectProps) {
  // Group sources by type
  const systemSources = sources.filter(s => s.source_type === 'system_audio');
  const appSources = sources.filter(s => s.source_type === 'application');
  const inputSources = sources.filter(s => s.source_type === 'input_device');

  return (
    <div className="audio-source-select">
      <div className="source-header">
        <label className="input-label">{label}</label>
        <button className="btn-icon" onClick={onRefresh} title="Refresh sources">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
            <path d="M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" />
          </svg>
        </button>
      </div>

      <select
        className="select"
        value={value || ''}
        onChange={e => onChange(e.target.value || null)}
      >
        {/* System Audio */}
        {systemSources.length > 0 && (
          <optgroup label="System">
            {systemSources.map(source => (
              <option key={source.id} value={source.id}>
                {source.name}
              </option>
            ))}
          </optgroup>
        )}

        {/* Applications */}
        {appSources.length > 0 && (
          <optgroup label="Applications">
            {appSources.map(source => (
              <option key={source.id} value={source.id}>
                {source.name}
              </option>
            ))}
          </optgroup>
        )}

        {/* Input Devices */}
        {inputSources.length > 0 && (
          <optgroup label="Input Devices">
            {inputSources.map(source => (
              <option key={source.id} value={source.id}>
                {source.name}
              </option>
            ))}
          </optgroup>
        )}
      </select>
    </div>
  );
}

export default AudioSourceSelect;
