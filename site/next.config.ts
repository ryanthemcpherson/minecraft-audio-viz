import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Fengari (Lua-in-JS) uses require('os') and require('fs') at module
  // evaluation time when it detects `process` (injected by Turbopack).
  // These shims let the module evaluate without throwing in the browser.
  turbopack: {
    resolveAlias: {
      os: "./shims/os.js",
      fs: "./shims/fs.js",
    },
  },
  async rewrites() {
    const coordinatorUrl =
      process.env.COORDINATOR_API_URL || "https://api.mcav.live";
    return [
      {
        source: "/api/v1/:path*",
        destination: `${coordinatorUrl}/api/v1/:path*`,
      },
    ];
  },
  async headers() {
    return [
      {
        source: "/(.*)",
        headers: [
          { key: "X-Frame-Options", value: "DENY" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          {
            key: "Permissions-Policy",
            value: "camera=(), microphone=(), geolocation=()",
          },
          {
            key: "Strict-Transport-Security",
            value: "max-age=63072000; includeSubDomains; preload",
          },
          {
            key: "X-DNS-Prefetch-Control",
            value: "on",
          },
        ],
      },
    ];
  },
};

export default nextConfig;
