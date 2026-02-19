interface FrequencyMeterProps {
  bands: number[];
  isBeat?: boolean;
  beatIntensity?: number;
  bpm?: number;
}

const BAND_NAMES = ['Bass', 'Low', 'Mid', 'High', 'Air'];
const BAND_COLORS = [
  'linear-gradient(to right, #ff9100, #ff5722)', // Bass - orange
  'linear-gradient(to right, #ffea00, #ffc107)', // Low - yellow
  'linear-gradient(to right, #00e676, #4caf50)', // Mid - green
  'linear-gradient(to right, #00b0ff, #2196f3)', // High - blue
  'linear-gradient(to right, #d500f9, #9c27b0)', // Air - magenta
];
const BAND_GLOW_COLORS = [
  'rgba(255, 145, 0, 0.6)',  // Bass
  'rgba(255, 234, 0, 0.5)',  // Low
  'rgba(0, 230, 118, 0.5)',  // Mid
  'rgba(0, 176, 255, 0.5)',  // High
  'rgba(213, 0, 249, 0.5)',  // Air
];

function FrequencyMeter({ bands, isBeat = false, beatIntensity = 0, bpm = 0 }: FrequencyMeterProps) {
  return (
    <div className={`frequency-meter ${isBeat ? 'meter-beat' : ''}`}>
      <div className="meter-header">
        <label className="input-label">Frequency Bands</label>
        {bpm > 0 && (
          <span className={`bpm-display ${isBeat ? 'bpm-pulse' : ''}`}>
            {Math.round(bpm)} BPM
          </span>
        )}
      </div>
      <div className="meter-bars">
        {bands.map((value, index) => {
          const percent = Math.round(value * 100);
          const glowIntensity = value > 0.6 ? (value - 0.6) * 2.5 : 0;
          const beatGlow = isBeat && index === 0 ? beatIntensity * 0.8 : 0;
          const totalGlow = Math.min(1, glowIntensity + beatGlow);
          return (
            <div key={index} className="meter-bar">
              <div className="meter-label">{BAND_NAMES[index]}</div>
              <div className="meter-track">
                <div
                  className="meter-fill"
                  style={{
                    width: `${percent}%`,
                    background: BAND_COLORS[index],
                    boxShadow: totalGlow > 0
                      ? `0 0 ${8 + totalGlow * 12}px ${BAND_GLOW_COLORS[index]}, inset 0 0 ${4 + totalGlow * 6}px rgba(255,255,255,${totalGlow * 0.3})`
                      : 'none',
                  }}
                />
              </div>
              <div className="meter-value">{percent}%</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

export default FrequencyMeter;
