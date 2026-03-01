import type { MetadataRoute } from "next";

export default function robots(): MetadataRoute.Robots {
  return {
    rules: {
      userAgent: "*",
      allow: "/",
      disallow: [
        "/dashboard",
        "/onboarding",
        "/auth/",
        "/settings",
        "/admin",
        "/login",
        "/forgot-password",
        "/reset-password",
        "/verify-email",
      ],
    },
    sitemap: "https://mcav.live/sitemap.xml",
  };
}
