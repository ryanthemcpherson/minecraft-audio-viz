import type { MetadataRoute } from "next";

export default function sitemap(): MetadataRoute.Sitemap {
  const base = "https://mcav.live";

  return [
    { url: base, lastModified: new Date(), changeFrequency: "weekly", priority: 1 },
    { url: `${base}/getting-started`, lastModified: new Date(), changeFrequency: "monthly", priority: 0.8 },
    { url: `${base}/patterns`, lastModified: new Date(), changeFrequency: "monthly", priority: 0.7 },
    { url: `${base}/login`, lastModified: new Date(), changeFrequency: "yearly", priority: 0.3 },
  ];
}
