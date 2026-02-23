import { useRef, useEffect } from 'react';

const BAND_NAMES = ['Bass', 'Low', 'Mid', 'High', 'Air'];
const BAND_COLORS = ['#ff9f43', '#ffd166', '#2fe098', '#43c5ff', '#c77dff'];

interface AudioData {
  bands: number[];
  isBeat: boolean;
  bpm: number;
  beatIntensity: number;
}

interface FrequencyMeterProps {
  audioRef: React.RefObject<AudioData>;
}

export default function FrequencyMeter({ audioRef }: FrequencyMeterProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animRef = useRef<number>(0);
  const smoothed = useRef<number[]>([0, 0, 0, 0, 0]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const draw = () => {
      const { bands, isBeat, bpm } = audioRef.current;
      const w = canvas.width;
      const h = canvas.height;
      const barHeight = Math.floor((h - 28) / 5);
      const barGap = 2;
      const labelWidth = 40;
      const valueWidth = 44;

      ctx.clearRect(0, 0, w, h);

      for (let i = 0; i < 5; i++) {
        const target = bands[i] ?? 0;
        smoothed.current[i] += (target - smoothed.current[i]) * 0.3;
        const val = smoothed.current[i];

        const y = i * (barHeight + barGap);
        const barW = val * (w - labelWidth - valueWidth - 8);

        // Bar background
        ctx.fillStyle = 'rgba(0, 0, 0, 0.3)';
        ctx.fillRect(labelWidth, y, w - labelWidth - valueWidth - 8, barHeight - barGap);

        // Bar fill
        ctx.fillStyle = BAND_COLORS[i];
        ctx.globalAlpha = 0.85;
        ctx.fillRect(labelWidth, y, Math.max(0, barW), barHeight - barGap);

        // Glow on high values
        if (val > 0.5) {
          ctx.globalAlpha = (val - 0.5) * 0.4;
          ctx.shadowColor = BAND_COLORS[i];
          ctx.shadowBlur = 8;
          ctx.fillRect(labelWidth, y, Math.max(0, barW), barHeight - barGap);
          ctx.shadowBlur = 0;
        }
        ctx.globalAlpha = 1;

        // Label
        ctx.fillStyle = '#a1a1aa';
        ctx.font = '11px Inter, sans-serif';
        ctx.textBaseline = 'middle';
        ctx.fillText(BAND_NAMES[i], 0, y + (barHeight - barGap) / 2);

        // Value
        ctx.fillStyle = '#a1a1aa';
        ctx.font = '11px "JetBrains Mono", monospace';
        ctx.textAlign = 'right';
        ctx.fillText(`${Math.round(val * 100)}%`, w, y + (barHeight - barGap) / 2);
        ctx.textAlign = 'left';
      }

      // BPM display
      const bpmY = 5 * (barHeight + barGap) + 4;
      ctx.fillStyle = isBeat ? '#ffaa00' : '#a1a1aa';
      ctx.font = 'bold 13px "JetBrains Mono", monospace';
      ctx.fillText(`${Math.round(bpm)} BPM`, 0, bpmY + 8);

      animRef.current = requestAnimationFrame(draw);
    };

    animRef.current = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(animRef.current);
  }, [audioRef]);

  return (
    <canvas
      ref={canvasRef}
      className="frequency-canvas"
      width={400}
      height={160}
    />
  );
}
