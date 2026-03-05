const PRESETS = ['auto', 'edm', 'chill', 'rock', 'folk', 'hiphop', 'classical'];

const PRESET_COLORS: Record<string, string> = {
  auto: '#00CCFF',
  edm: '#5B6AFF',
  chill: '#2fe098',
  rock: '#ff6767',
  folk: '#FFAA00',
  hiphop: '#a070ff',
  classical: '#ffd166',
};

interface PresetBarProps {
  active: string;
  onChange: (name: string) => void;
}

export default function PresetBar({ active, onChange }: PresetBarProps) {
  return (
    <div className="preset-row">
      {PRESETS.map(name => (
        <button
          key={name}
          className={`preset-chip ${active === name ? 'active' : ''}`}
          onClick={() => onChange(name)}
          type="button"
        >
          <span className="preset-dot" style={{ background: PRESET_COLORS[name] }} />
          {name}
        </button>
      ))}
    </div>
  );
}

export { PRESETS };
