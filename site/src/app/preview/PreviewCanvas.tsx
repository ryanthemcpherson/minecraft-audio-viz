"use client";

import { useRef, useMemo, useCallback } from "react";
import { Canvas } from "@react-three/fiber";
import type { RootState } from "@react-three/fiber";
import { EffectComposer, Bloom } from "@react-three/postprocessing";
import * as THREE from "three";
import PatternScene from "@/components/PatternScene";
import type { PatternMeta } from "@/lib/patterns";

interface PreviewCanvasProps {
  pattern: PatternMeta;
  preset: string;
}

export default function PreviewCanvas({ pattern, preset }: PreviewCanvasProps) {
  const glRef = useRef<THREE.WebGLRenderer | null>(null);

  const patternInstance = useMemo(() => pattern.createPattern(), [pattern]);

  // Phase offset based on preset to give each preset a different feel
  const phaseOffset = useMemo(() => {
    const offsets: Record<string, number> = {
      edm: 0,
      chill: 3.5,
      rock: 7.2,
      hiphop: 11.0,
      classical: 15.8,
    };
    return offsets[preset] ?? 0;
  }, [preset]);

  const handleCreated = useCallback((state: RootState) => {
    glRef.current = state.gl;
    state.gl.toneMapping = THREE.ACESFilmicToneMapping;
    state.gl.toneMappingExposure = 1.5;
  }, []);

  const dpr = useMemo(() => {
    if (typeof window === "undefined") return 1;
    return Math.min(window.devicePixelRatio, 2);
  }, []);

  return (
    <Canvas
      dpr={dpr}
      gl={{
        antialias: true,
        alpha: false,
        powerPreference: "high-performance",
      }}
      camera={{
        position: [0, 2, 5.5],
        fov: 50,
        near: 0.1,
        far: 40,
      }}
      style={{
        position: "absolute",
        top: 0,
        left: 0,
        width: "100%",
        height: "100%",
        background: "#000",
      }}
      frameloop="always"
      onCreated={handleCreated}
    >
      <PatternScene
        pattern={patternInstance}
        phaseOffset={phaseOffset}
        staticCamera={pattern.staticCamera}
      />
      <EffectComposer>
        <Bloom
          intensity={0.6}
          luminanceThreshold={0.5}
          luminanceSmoothing={0.4}
          mipmapBlur
        />
      </EffectComposer>
    </Canvas>
  );
}
