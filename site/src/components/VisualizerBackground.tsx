"use client";

import { useRef, useMemo } from "react";
import { Canvas, useFrame } from "@react-three/fiber";
import * as THREE from "three";

// --- Constants ---
const BLOCKS_PER_RING = [48, 36, 24];
const RING_RADII = [4.5, 3.0, 1.8];
const BLOCK_SIZE = 0.18;
const BASE_HEIGHT = 0.08;

const COLOR_BLUE = new THREE.Color("#00D4FF");
const COLOR_PURPLE = new THREE.Color("#8B5CF6");
const COLOR_PINK = new THREE.Color("#FF006E");

const TEMP_OBJECT = new THREE.Object3D();
const TEMP_COLOR = new THREE.Color();

// --- Simulated audio generator ---
function getSimulatedAudio(
  time: number,
  index: number,
  ringIndex: number
): number {
  const angle = (index / BLOCKS_PER_RING[ringIndex]) * Math.PI * 2;

  // Multiple sine waves at different frequencies to simulate audio bands
  const bass = Math.sin(time * 1.2 + angle * 2) * 0.5 + 0.5;
  const mid = Math.sin(time * 2.4 + angle * 4 + 1.0) * 0.5 + 0.5;
  const treble = Math.sin(time * 3.8 + angle * 6 + 2.0) * 0.5 + 0.5;

  // Combine bands with different weights per ring
  const weights = [
    [0.7, 0.2, 0.1], // outer ring: bass-heavy
    [0.2, 0.6, 0.2], // middle ring: mid-heavy
    [0.1, 0.2, 0.7], // inner ring: treble-heavy
  ];
  const w = weights[ringIndex];
  const value = bass * w[0] + mid * w[1] + treble * w[2];

  // Add a slow "beat" pulse
  const beat = Math.pow(Math.max(0, Math.sin(time * 2.0)), 8) * 0.4;

  return Math.min(1, value * 0.8 + beat);
}

// --- Instanced ring of blocks ---
function BlockRing({
  ringIndex,
  count,
  radius,
}: {
  ringIndex: number;
  count: number;
  radius: number;
}) {
  const meshRef = useRef<THREE.InstancedMesh>(null);
  const prevHeights = useRef<Float32Array>(new Float32Array(count));

  // Pre-compute static angles
  const angles = useMemo(() => {
    const a = new Float32Array(count);
    for (let i = 0; i < count; i++) {
      a[i] = (i / count) * Math.PI * 2;
    }
    return a;
  }, [count]);

  // Initialize colors once
  const colorArray = useMemo(() => {
    const colors = new Float32Array(count * 3);
    for (let i = 0; i < count; i++) {
      const t = i / count;
      if (t < 0.33) {
        TEMP_COLOR.lerpColors(COLOR_BLUE, COLOR_PURPLE, t / 0.33);
      } else if (t < 0.66) {
        TEMP_COLOR.lerpColors(COLOR_PURPLE, COLOR_PINK, (t - 0.33) / 0.33);
      } else {
        TEMP_COLOR.lerpColors(COLOR_PINK, COLOR_BLUE, (t - 0.66) / 0.34);
      }
      colors[i * 3] = TEMP_COLOR.r;
      colors[i * 3 + 1] = TEMP_COLOR.g;
      colors[i * 3 + 2] = TEMP_COLOR.b;
    }
    return colors;
  }, [count]);

  useFrame(({ clock }) => {
    const mesh = meshRef.current;
    if (!mesh) return;

    const time = clock.getElapsedTime();

    for (let i = 0; i < count; i++) {
      const angle = angles[i];
      const audio = getSimulatedAudio(time, i, ringIndex);

      // Smooth the height changes
      const targetHeight = BASE_HEIGHT + audio * 1.2;
      const prev = prevHeights.current[i] || targetHeight;
      const smoothed = prev + (targetHeight - prev) * 0.12;
      prevHeights.current[i] = smoothed;

      const x = Math.cos(angle) * radius;
      const z = Math.sin(angle) * radius;

      TEMP_OBJECT.position.set(x, smoothed * 0.5, z);
      TEMP_OBJECT.scale.set(1, Math.max(0.1, smoothed / BLOCK_SIZE), 1);
      TEMP_OBJECT.rotation.y = angle;
      TEMP_OBJECT.updateMatrix();
      mesh.setMatrixAt(i, TEMP_OBJECT.matrix);

      // Modulate color brightness based on audio intensity
      const brightness = 0.3 + audio * 0.7;
      TEMP_COLOR.setRGB(
        colorArray[i * 3] * brightness,
        colorArray[i * 3 + 1] * brightness,
        colorArray[i * 3 + 2] * brightness
      );
      mesh.setColorAt(i, TEMP_COLOR);
    }

    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  });

  return (
    <instancedMesh ref={meshRef} args={[undefined, undefined, count]}>
      <boxGeometry args={[BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE]} />
      <meshStandardMaterial
        toneMapped={false}
        transparent
        opacity={0.85}
        roughness={0.3}
        metalness={0.1}
      />
    </instancedMesh>
  );
}

// --- Flat grid equalizer below the ring ---
const GRID_COLS = 20;
const GRID_ROWS = 8;
const GRID_TOTAL = GRID_COLS * GRID_ROWS;
const GRID_SPACING = 0.28;
const GRID_BLOCK = 0.14;

function EqualizerGrid() {
  const meshRef = useRef<THREE.InstancedMesh>(null);
  const prevHeights = useRef<Float32Array>(new Float32Array(GRID_TOTAL));

  const colorArray = useMemo(() => {
    const colors = new Float32Array(GRID_TOTAL * 3);
    for (let col = 0; col < GRID_COLS; col++) {
      const t = col / (GRID_COLS - 1);
      if (t < 0.5) {
        TEMP_COLOR.lerpColors(COLOR_BLUE, COLOR_PURPLE, t / 0.5);
      } else {
        TEMP_COLOR.lerpColors(COLOR_PURPLE, COLOR_PINK, (t - 0.5) / 0.5);
      }
      for (let row = 0; row < GRID_ROWS; row++) {
        const idx = col * GRID_ROWS + row;
        colors[idx * 3] = TEMP_COLOR.r;
        colors[idx * 3 + 1] = TEMP_COLOR.g;
        colors[idx * 3 + 2] = TEMP_COLOR.b;
      }
    }
    return colors;
  }, []);

  useFrame(({ clock }) => {
    const mesh = meshRef.current;
    if (!mesh) return;

    const time = clock.getElapsedTime();
    const offsetX = -(GRID_COLS - 1) * GRID_SPACING * 0.5;
    const offsetZ = -(GRID_ROWS - 1) * GRID_SPACING * 0.5;

    for (let col = 0; col < GRID_COLS; col++) {
      const freqT = col / (GRID_COLS - 1);
      const freq = 0.8 + freqT * 3.5;
      const colAudio =
        Math.sin(time * freq + col * 0.3) * 0.5 +
        0.5 +
        Math.pow(Math.max(0, Math.sin(time * 1.8 + col * 0.15)), 6) * 0.3;

      for (let row = 0; row < GRID_ROWS; row++) {
        const idx = col * GRID_ROWS + row;
        const rowFade = 1.0 - (row / (GRID_ROWS - 1)) * 0.6;
        const audio = colAudio * rowFade;

        const targetH = 0.04 + audio * 0.6;
        const prev = prevHeights.current[idx] || targetH;
        const smoothed = prev + (targetH - prev) * 0.1;
        prevHeights.current[idx] = smoothed;

        const x = offsetX + col * GRID_SPACING;
        const z = offsetZ + row * GRID_SPACING;

        TEMP_OBJECT.position.set(x, smoothed * 0.5 - 2.2, z + 3.0);
        TEMP_OBJECT.scale.set(1, Math.max(0.05, smoothed / GRID_BLOCK), 1);
        TEMP_OBJECT.updateMatrix();
        mesh.setMatrixAt(idx, TEMP_OBJECT.matrix);

        const brightness = 0.2 + audio * 0.8;
        TEMP_COLOR.setRGB(
          colorArray[idx * 3] * brightness,
          colorArray[idx * 3 + 1] * brightness,
          colorArray[idx * 3 + 2] * brightness
        );
        mesh.setColorAt(idx, TEMP_COLOR);
      }
    }

    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  });

  return (
    <instancedMesh ref={meshRef} args={[undefined, undefined, GRID_TOTAL]}>
      <boxGeometry args={[GRID_BLOCK, GRID_BLOCK, GRID_BLOCK]} />
      <meshStandardMaterial
        toneMapped={false}
        transparent
        opacity={0.7}
        roughness={0.4}
        metalness={0.1}
      />
    </instancedMesh>
  );
}

// --- Slowly rotating scene container ---
function Scene() {
  const groupRef = useRef<THREE.Group>(null);

  useFrame(({ clock }) => {
    if (groupRef.current) {
      groupRef.current.rotation.y = clock.getElapsedTime() * 0.08;
    }
  });

  return (
    <group ref={groupRef}>
      {RING_RADII.map((radius, i) => (
        <BlockRing
          key={i}
          ringIndex={i}
          count={BLOCKS_PER_RING[i]}
          radius={radius}
        />
      ))}
      <EqualizerGrid />
    </group>
  );
}

// --- Canvas wrapper ---
function VisualizerCanvas() {
  const dpr = useMemo(() => {
    if (typeof window === "undefined") return 1;
    return window.innerWidth < 768 ? 1 : Math.min(window.devicePixelRatio, 1.5);
  }, []);

  const isMobile = useMemo(() => {
    if (typeof window === "undefined") return false;
    return window.innerWidth < 768;
  }, []);

  return (
    <Canvas
      dpr={dpr}
      gl={{
        antialias: !isMobile,
        alpha: true,
        powerPreference: "low-power",
      }}
      camera={{
        position: [0, 5.5, 7],
        fov: 45,
        near: 0.1,
        far: 50,
      }}
      style={{
        position: "absolute",
        top: 0,
        left: 0,
        width: "100%",
        height: "100%",
      }}
      frameloop="always"
    >
      <ambientLight intensity={0.3} />
      <pointLight position={[5, 8, 5]} intensity={0.6} color="#00D4FF" />
      <pointLight position={[-5, 6, -3]} intensity={0.4} color="#8B5CF6" />
      <pointLight position={[0, 3, -5]} intensity={0.3} color="#FF006E" />
      <fog attach="fog" args={["#0a0a0a", 6, 18]} />
      <Scene />
    </Canvas>
  );
}

// --- Exported component (rendered client-side only) ---
export default function VisualizerBackground() {
  return (
    <div className="absolute inset-0 z-0" aria-hidden="true">
      <VisualizerCanvas />
    </div>
  );
}
