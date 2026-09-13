"use client";

import { useEffect, useState } from "react";
import {
  getLoadedMinecraftTextures,
  getProceduralTextures,
  loadMinecraftTextures,
  type MinecraftBlockTextures,
} from "./blockTextures";

/**
 * Returns the real Minecraft block textures once loaded, and the procedural
 * stand-ins until then. Materials swap seamlessly when the PNGs arrive.
 */
export function useMinecraftTextures(): MinecraftBlockTextures {
  const [textures, setTextures] = useState<MinecraftBlockTextures>(
    () => getLoadedMinecraftTextures() ?? getProceduralTextures(),
  );

  useEffect(() => {
    if (getLoadedMinecraftTextures()) return;
    let cancelled = false;
    loadMinecraftTextures()
      .then((loaded) => {
        if (!cancelled) setTextures(loaded);
      })
      .catch((error: unknown) => {
        console.error("[MCAV] Falling back to procedural block textures:", error);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return textures;
}
