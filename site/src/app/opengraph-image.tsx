import { ImageResponse } from "next/og";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { PATTERN_COUNT } from "@/lib/patterns/meta";
import { RELEASE } from "@/lib/releaseStatus";

export const alt = "MCAV: your music, rendered live in Minecraft";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

const BARS = [0.35, 0.7, 0.5, 0.9, 0.6, 0.8, 0.45, 0.65, 0.3, 0.75, 0.55, 0.85];
const BAR_COLORS = ["#00CCFF", "#2fa9ff", "#5B6AFF", "#b07cff", "#FFAA00"];

/**
 * Social preview card. Generated at build time so it always matches the
 * current tagline, pattern count, and release baseline.
 */
export default async function OpenGraphImage() {
  let logo: string | null = null;
  try {
    const bytes = await readFile(join(process.cwd(), "public", "mcav.png"));
    logo = `data:image/png;base64,${bytes.toString("base64")}`;
  } catch (error) {
    console.error("[MCAV] OG image: could not read public/mcav.png", error);
  }

  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          justifyContent: "space-between",
          padding: 64,
          background: "#08090d",
          color: "#f5f5f5",
          fontFamily: "Inter, system-ui, sans-serif",
          position: "relative",
        }}
      >
        {/* Ambient glows */}
        <div
          style={{
            position: "absolute",
            top: -160,
            right: -120,
            width: 520,
            height: 520,
            borderRadius: 9999,
            background: "rgba(0, 204, 255, 0.16)",
            filter: "blur(120px)",
          }}
        />
        <div
          style={{
            position: "absolute",
            bottom: -200,
            left: 200,
            width: 520,
            height: 520,
            borderRadius: 9999,
            background: "rgba(91, 106, 255, 0.14)",
            filter: "blur(120px)",
          }}
        />

        {/* Header */}
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <div style={{ display: "flex", alignItems: "center", gap: 16 }}>
            {logo ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={logo} width={56} height={56} alt="" style={{ imageRendering: "pixelated" }} />
            ) : null}
            <span style={{ fontSize: 34, fontWeight: 700, letterSpacing: -1 }}>MCAV</span>
          </div>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 10,
              padding: "8px 16px",
              borderRadius: 9999,
              border: "1px solid rgba(255, 209, 102, 0.35)",
              background: "rgba(255, 209, 102, 0.10)",
              color: "#ffd166",
              fontSize: 18,
              letterSpacing: 2,
              textTransform: "uppercase",
            }}
          >
            <div style={{ width: 8, height: 8, borderRadius: 9999, background: "#ffd166" }} />
            Paper {RELEASE.minecraft}
          </div>
        </div>

        {/* Headline */}
        <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
          <div style={{ display: "flex", flexDirection: "column", fontSize: 84, fontWeight: 700, lineHeight: 1.02, letterSpacing: -3 }}>
            <span>Your music,</span>
            <span style={{ display: "flex" }}>
              <span
                style={{
                  backgroundImage: "linear-gradient(135deg, #00CCFF, #5B6AFF, #FFAA00)",
                  backgroundClip: "text",
                  color: "transparent",
                }}
              >
                rendered live
              </span>
              <span style={{ marginLeft: 22 }}>in Minecraft.</span>
            </span>
          </div>
          <div style={{ fontSize: 28, color: "#a1a1aa", maxWidth: 820, lineHeight: 1.35 }}>
            Real-time audio analysis driving 3D structures on a Paper server. Vanilla clients, no mods.
          </div>
        </div>

        {/* Footer: stats + EQ */}
        <div style={{ display: "flex", alignItems: "flex-end", justifyContent: "space-between" }}>
          <div style={{ display: "flex", gap: 36, fontSize: 20, color: "#a1a1aa", letterSpacing: 1 }}>
            <span>{PATTERN_COUNT} LUA PATTERNS</span>
            <span>21 MS ANALYSIS</span>
            <span>MULTI-DJ</span>
            <span>OPEN SOURCE</span>
          </div>
          <div style={{ display: "flex", alignItems: "flex-end", gap: 8, height: 90 }}>
            {BARS.map((h, i) => (
              <div
                key={i}
                style={{
                  width: 16,
                  height: Math.round(h * 90),
                  borderRadius: 4,
                  background: BAR_COLORS[i % BAR_COLORS.length],
                  opacity: 0.9,
                }}
              />
            ))}
          </div>
        </div>
      </div>
    ),
    { ...size },
  );
}
