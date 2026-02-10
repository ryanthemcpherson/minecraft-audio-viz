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
  metadataBase: new URL("https://mcav.live"),
  icons: {
    icon: [
      { url: "/favicon.ico", sizes: "any" },
      { url: "/favicon-32x32.png", sizes: "32x32", type: "image/png" },
      { url: "/favicon-16x16.png", sizes: "16x16", type: "image/png" },
    ],
    apple: { url: "/apple-touch-icon.png", sizes: "180x180" },
  },
  manifest: "/site.webmanifest",
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
    url: "https://mcav.live",
    type: "website",
    siteName: "MCAV",
    images: [
      {
        url: "/og-image.jpg",
        width: 1200,
        height: 630,
        alt: "MCAV - Minecraft Audio Visualizer",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "MCAV - Minecraft Audio Visualizer",
    description:
      "Real-time audio visualization in Minecraft. No client mods.",
    images: ["/og-image.jpg"],
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
