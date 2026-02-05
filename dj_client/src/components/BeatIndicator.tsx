interface BeatIndicatorProps {
  active: boolean;
}

function BeatIndicator({ active }: BeatIndicatorProps) {
  return (
    <div className={`beat-indicator ${active ? 'active' : ''}`}>
      <div className="beat-ring" />
      <div className="beat-ring ring-2" />
    </div>
  );
}

export default BeatIndicator;
