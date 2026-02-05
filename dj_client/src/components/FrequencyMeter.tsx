interface FrequencyMeterProps {
  bands: number[];
}

const BAND_NAMES = ['Bass', 'Low', 'Mid', 'High', 'Air'];
const BAND_COLORS = [
  'linear-gradient(to right, #ff9100, #ff5722)', // Bass - orange
  'linear-gradient(to right, #ffea00, #ffc107)', // Low - yellow
  'linear-gradient(to right, #00e676, #4caf50)', // Mid - green
  'linear-gradient(to right, #00b0ff, #2196f3)', // High - blue
  'linear-gradient(to right, #d500f9, #9c27b0)', // Air - magenta
];

function FrequencyMeter({ bands }: FrequencyMeterProps) {
  return (
    <div className="frequency-meter">
      <label className="input-label">Frequency Bands</label>
      <div className="meter-bars">
        {bands.map((value, index) => {
          const percent = Math.round(value * 100);
          return (
            <div key={index} className="meter-bar">
              <div className="meter-label">{BAND_NAMES[index]}</div>
              <div className="meter-track">
                <div
                  className="meter-fill"
                  style={{
                    width: `${percent}%`,
                    background: BAND_COLORS[index],
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
