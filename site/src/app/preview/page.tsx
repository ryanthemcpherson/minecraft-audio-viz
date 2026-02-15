import type { Metadata } from "next";
import PreviewClient from "./PreviewClient";

export const metadata: Metadata = {
  title: "Live Preview | MCAV - Audio Visualizer for Minecraft",
  description:
    "Try MCAV in your browser. Full-screen Three.js visualizer with simulated audio, pattern switching, and audio presets.",
  openGraph: {
    title: "MCAV Live Preview - Audio Visualizer for Minecraft",
    description:
      "Try MCAV in your browser. Full-screen Three.js visualizer with simulated audio.",
    url: "https://mcav.live/preview",
  },
};

export default function PreviewPage() {
  return <PreviewClient />;
}
