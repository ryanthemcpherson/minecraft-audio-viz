import type { Metadata } from "next";
import { Inter, Space_Grotesk, JetBrains_Mono } from "next/font/google";
import "./globals.css";
import Navbar from "@/components/Navbar";
import AuthProvider from "@/components/AuthProvider";
import NowPlaying from "@/components/NowPlaying";

const inter = Inter({
  subsets: ["latin"],
  variable: "--font-inter",
  display: "swap",
});

const spaceGrotesk = Space_Grotesk({
  subsets: ["latin"],
  variable: "--font-space-grotesk",
  display: "swap",
});

const jetbrainsMono = JetBrains_Mono({
  subsets: ["latin"],
  variable: "--font-jetbrains-mono",
  display: "swap",
});

const fontVariables = `${inter.variable} ${spaceGrotesk.variable} ${jetbrainsMono.variable}`;

export const metadata: Metadata = {
  metadataBase: new URL("https://mcav.live"),
  icons: {
    icon: [
      { url: "/favicon.ico", sizes: "48x48" },
      { url: "/favicon-32x32.png", sizes: "32x32", type: "image/png" },
      { url: "/favicon-16x16.png", sizes: "16x16", type: "image/png" },
      { url: "/icon-192.png", sizes: "192x192", type: "image/png" },
    ],
    apple: { url: "/apple-touch-icon.png", sizes: "180x180" },
  },
  manifest: "/site.webmanifest",
  other: {
    "theme-color": "#08090d",
  },
  title: {
    default: "MCAV - Minecraft Audio Visualizer",
    template: "%s - MCAV",
  },
  description:
    "Live audio-reactive visuals inside Minecraft. MCAV captures your system audio, analyzes it in real time, and drives 3D structures on a Paper server. No client mods.",
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
  // Social images come from src/app/opengraph-image.tsx (generated at build time).
  openGraph: {
    title: "MCAV - Minecraft Audio Visualizer",
    description: "Your music, rendered live in Minecraft. Vanilla clients, no mods.",
    url: "https://mcav.live",
    type: "website",
    siteName: "MCAV",
  },
  twitter: {
    card: "summary_large_image",
    title: "MCAV - Minecraft Audio Visualizer",
    description: "Your music, rendered live in Minecraft. Vanilla clients, no mods.",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={fontVariables}>
      <body className="bg-bg-primary text-text-primary antialiased">
        <a
          href="#main-content"
          className="sr-only focus:not-sr-only focus:fixed focus:top-4 focus:left-4 focus:z-[100] focus:rounded-lg focus:bg-disc-cyan focus:px-4 focus:py-2 focus:text-sm focus:font-semibold focus:text-white"
        >
          Skip to content
        </a>
        <script
          type="application/ld+json"
          dangerouslySetInnerHTML={{
            __html: JSON.stringify({
              "@context": "https://schema.org",
              "@type": "SoftwareApplication",
              name: "MCAV",
              alternateName: "Minecraft Audio Visualizer",
              applicationCategory: "MultimediaApplication",
              operatingSystem: "Windows, macOS, Linux",
              url: "https://mcav.live",
              description:
                "Real-time audio visualization in Minecraft using Display Entities. Capture system audio, process with FFT analysis, and render reactive 3D structures.",
              offers: {
                "@type": "Offer",
                price: "0",
                priceCurrency: "USD",
              },
            }),
          }}
        />
        <AuthProvider>
          <Navbar />
          <main id="main-content">{children}</main>
          {process.env.NEXT_PUBLIC_VJ_WS_URL && (
            <NowPlaying wsUrl={process.env.NEXT_PUBLIC_VJ_WS_URL} />
          )}
        </AuthProvider>
      </body>
    </html>
  );
}
