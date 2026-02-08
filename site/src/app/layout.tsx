import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import Navbar from "@/components/Navbar";

const inter = Inter({
  subsets: ["latin"],
  variable: "--font-inter",
});

export const metadata: Metadata = {
  icons: {
    icon: "/favicon.ico",
    apple: "/mcav.png",
  },
  title: "MCAV - Minecraft Audio Visualizer",
  description:
    "Real-time audio visualization in Minecraft. Capture system audio, process with FFT analysis, and render reactive 3D structures using Display Entities. No client mods required.",
  keywords: [
    "Minecraft",
    "audio visualizer",
    "display entities",
    "DJ",
    "VJ",
    "real-time",
    "FFT",
    "beat detection",
    "WASAPI",
  ],
  openGraph: {
    title: "MCAV - Turn Sound Into Worlds",
    description:
      "Real-time audio visualization in Minecraft. No client mods. Pure server-side magic.",
    type: "website",
    siteName: "MCAV",
  },
  twitter: {
    card: "summary_large_image",
    title: "MCAV - Turn Sound Into Worlds",
    description:
      "Real-time audio visualization in Minecraft. No client mods. Pure server-side magic.",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={inter.variable}>
      <body className="bg-bg-primary text-text-primary antialiased">
        <Navbar />
        <main>{children}</main>
      </body>
    </html>
  );
}
