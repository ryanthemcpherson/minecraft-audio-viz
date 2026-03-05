import { useRef, useEffect } from 'react';

interface AudioData {
  bands: number[];
  isBeat: boolean;
  bpm: number;
  beatIntensity: number;
}

interface FrequencyStripProps {
  audioRef: React.RefObject<AudioData>;
}

const BAND_LABELS = ['B', 'LM', 'M', 'HM', 'H'];
const BAND_COLORS = [
  [0, 204, 255],    // Bass: cyan
  [40, 160, 255],   // Low-mid: blue-cyan
  [91, 106, 255],   // Mid: indigo
  [160, 100, 255],  // High-mid: purple
  [255, 170, 0],    // High: amber
];

export default function FrequencyStrip({ audioRef }: FrequencyStripProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const smoothedRef = useRef<number[]>([0, 0, 0, 0, 0]);
  const animFrameRef = useRef<number>(0);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const draw = () => {
      const dpr = window.devicePixelRatio || 1;
      const rect = canvas.getBoundingClientRect();
      const w = rect.width * dpr;
      const h = rect.height * dpr;

      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w;
        canvas.height = h;
      }

      ctx.clearRect(0, 0, w, h);
      ctx.scale(dpr, dpr);
      const cw = rect.width;
      const ch = rect.height;

      const bands = audioRef.current.bands;
      const smoothed = smoothedRef.current;
      const isBeat = audioRef.current.isBeat;

      const barGap = 3;
      const barWidth = (cw - barGap * (bands.length - 1)) / bands.length;

      for (let i = 0; i < bands.length; i++) {
        const target = Math.min(1, bands[i]);
        smoothed[i] += (target - smoothed[i]) * 0.25;
        const val = smoothed[i];

        const x = i * (barWidth + barGap);
        const barH = val * (ch - 12);
        const y = ch - 12 - barH;

        const [r, g, b] = BAND_COLORS[i];

        // Bar fill with gradient
        const grad = ctx.createLinearGradient(x, y, x, ch - 12);
        grad.addColorStop(0, `rgba(${r}, ${g}, ${b}, 0.9)`);
        grad.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0.4)`);
        ctx.fillStyle = grad;
        ctx.beginPath();
        ctx.roundRect(x, y, barWidth, barH, 2);
        ctx.fill();

        // Glow when value > 0.5
        if (val > 0.5) {
          ctx.shadowColor = `rgba(${r}, ${g}, ${b}, ${(val - 0.5) * 0.8})`;
          ctx.shadowBlur = 8;
          ctx.fillStyle = `rgba(${r}, ${g}, ${b}, 0.3)`;
          ctx.fillRect(x, y, barWidth, barH);
          ctx.shadowBlur = 0;
        }

        // Beat flash: amber overlay on bass hit
        if (isBeat && i === 0) {
          ctx.fillStyle = 'rgba(255, 170, 0, 0.2)';
          ctx.fillRect(0, 0, cw, ch - 12);
        }

        // Label at bottom
        ctx.fillStyle = val > 0.3 ? `rgb(${r}, ${g}, ${b})` : '#6d87a1';
        ctx.font = `600 ${Math.max(8, Math.min(10, cw / 40))}px Inter, sans-serif`;
        ctx.textAlign = 'center';
        ctx.fillText(BAND_LABELS[i], x + barWidth / 2, ch - 1);
      }

      ctx.setTransform(1, 0, 0, 1, 0, 0);
      animFrameRef.current = requestAnimationFrame(draw);
    };

    animFrameRef.current = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(animFrameRef.current);
  }, [audioRef]);

  return (
    <canvas
      ref={canvasRef}
      className="frequency-strip"
      style={{ width: '100%', height: '48px' }}
    />
  );
}
