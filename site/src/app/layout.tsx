import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import Navbar from "@/components/Navbar";
import AuthProvider from "@/components/AuthProvider";

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
    "Real-time audio visualization in Minecraft. No client mods. Capture system audio, process with FFT analysis, and render reactive 3D structures using Display Entities.",
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
    title: "MCAV - Minecraft Audio Visualizer",
    description:
      "Real-time audio visualization in Minecraft. No client mods.",
    type: "website",
    siteName: "MCAV",
  },
  twitter: {
    card: "summary_large_image",
    title: "MCAV - Minecraft Audio Visualizer",
    description:
      "Real-time audio visualization in Minecraft. No client mods.",
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
        <AuthProvider>
          <Navbar />
          <main>{children}</main>
        </AuthProvider>
      </body>
    </html>
  );
}
