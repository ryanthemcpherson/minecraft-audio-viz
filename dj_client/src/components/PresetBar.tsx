const PRESETS = ['auto', 'edm', 'chill', 'rock', 'folk', 'hiphop', 'classical'];

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
        >{name}</button>
      ))}
    </div>
  );
}

export { PRESETS };
