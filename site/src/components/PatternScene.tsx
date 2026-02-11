"use client";

import { useRef, useEffect } from "react";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";
import type { VisualizationPattern, EntityData } from "@/lib/patterns/base";
import { generateAudioState } from "@/lib/audioSim";

const BLOCK_SIZE = 0.22;
const TEMP_OBJECT = new THREE.Object3D();
const TEMP_COLOR = new THREE.Color();

// Band colors: orange, yellow, green, blue, magenta (matching the main visualizer)
const BAND_COLORS = [
  new THREE.Color(0xff6d00),
  new THREE.Color(0xffd600),
  new THREE.Color(0x00e676),
  new THREE.Color(0x00b0ff),
  new THREE.Color(0xe040fb),
];

interface PatternSceneProps {
  pattern: VisualizationPattern;
  phaseOffset: number;
  staticCamera?: boolean;
}

export default function PatternScene({ pattern, phaseOffset, staticCamera = false }: PatternSceneProps) {
  const meshRef = useRef<THREE.InstancedMesh>(null);
  const groupRef = useRef<THREE.Group>(null);
  const maxCount = pattern.config.entityCount;
  const prevPositions = useRef<Float32Array | null>(null);
  const smoothCenter = useRef({ x: 0.5, y: 0.35, z: 0.5 });
  const frameCount = useRef(0);

  // Pre-allocate position tracking
  useEffect(() => {
    prevPositions.current = new Float32Array(maxCount * 3);
  }, [maxCount]);

  useFrame(({ clock }) => {
    const mesh = meshRef.current;
    if (!mesh) return;

    const time = clock.getElapsedTime();

    // Generate simulated audio for this card
    const audio = generateAudioState(time, phaseOffset);

    // Update pattern internal time
    pattern.update(0.016);

    // Calculate entity positions from the pattern
    const entities: EntityData[] = pattern.calculateEntities(audio);

    // Map entities to 3D positions — 0-1 range to world space
    const scale3d = 6;
    const prev = prevPositions.current!;

    const sc = smoothCenter.current;

    if (staticCamera) {
      // Fixed center for spectrum-style patterns — no jiggle
      sc.x = 0.5;
      sc.y = 0.35;
      sc.z = 0.5;
    } else {
      // Dynamic centroid tracking for 3D patterns
      let cx = 0, cy = 0, cz = 0, visCount = 0;
      for (let i = 0; i < entities.length; i++) {
        if (i < maxCount && entities[i].visible) {
          cx += entities[i].x;
          cy += entities[i].y;
          cz += entities[i].z;
          visCount++;
        }
      }
      if (visCount > 0) {
        cx /= visCount;
        cy /= visCount;
        cz /= visCount;
      } else {
        cx = 0.5;
        cy = 0.5;
        cz = 0.5;
      }

      // Snap quickly on the first few frames, then smooth
      frameCount.current++;
      const centerLerp = frameCount.current < 10 ? 0.5 : 0.08;
      sc.x += (cx - sc.x) * centerLerp;
      sc.y += (cy - sc.y) * centerLerp;
      sc.z += (cz - sc.z) * centerLerp;
    }

    for (let i = 0; i < maxCount; i++) {
      if (i < entities.length && entities[i].visible) {
        const e = entities[i];

        // Map normalized 0-1 coords to 3D space, centered on the centroid
        const targetX = (e.x - sc.x) * scale3d;
        const targetY = (e.y - sc.y) * scale3d;
        const targetZ = (e.z - sc.z) * scale3d;

        // Lerp for smooth movement
        const pi = i * 3;
        const px = prev[pi] || targetX;
        const py = prev[pi + 1] || targetY;
        const pz = prev[pi + 2] || targetZ;

        const lerpFactor = 0.22;
        const x = px + (targetX - px) * lerpFactor;
        const y = py + (targetY - py) * lerpFactor;
        const z = pz + (targetZ - pz) * lerpFactor;

        prev[pi] = x;
        prev[pi + 1] = y;
        prev[pi + 2] = z;

        TEMP_OBJECT.position.set(x, y, z);
        const s = Math.max(0.05, e.scale * 2.5);
        TEMP_OBJECT.scale.set(s, s, s);
        TEMP_OBJECT.updateMatrix();
        mesh.setMatrixAt(i, TEMP_OBJECT.matrix);

        // Color by band
        const bandColor = BAND_COLORS[Math.min(e.band, 4)];
        const brightness = 0.6 + e.scale * 2.5;
        TEMP_COLOR.copy(bandColor).multiplyScalar(brightness);
        mesh.setColorAt(i, TEMP_COLOR);
      } else {
        // Hide unused entities
        TEMP_OBJECT.position.set(0, -10, 0);
        TEMP_OBJECT.scale.set(0, 0, 0);
        TEMP_OBJECT.updateMatrix();
        mesh.setMatrixAt(i, TEMP_OBJECT.matrix);

        TEMP_COLOR.setRGB(0, 0, 0);
        mesh.setColorAt(i, TEMP_COLOR);
      }
    }

    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;

    // Slow auto-rotate (skip for static camera patterns)
    if (groupRef.current && !staticCamera) {
      groupRef.current.rotation.y = time * 0.15 + Math.sin(time * 0.3) * 0.1;
    }
  });

  return (
    <>
      <ambientLight intensity={0.15} />
      <pointLight position={[3, 4, 3]} intensity={1.2} distance={12} color="#00D4FF" />
      <pointLight position={[-3, 3, -2]} intensity={0.8} distance={12} color="#8B5CF6" />
      <pointLight position={[0, -2, 3]} intensity={0.5} distance={10} color="#FF006E" />
      <fog attach="fog" args={["#050505", 6, 20]} />

      <group ref={groupRef}>
        <instancedMesh ref={meshRef} args={[undefined, undefined, maxCount]}>
          <boxGeometry args={[BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE]} />
          <meshStandardMaterial
            toneMapped={false}
            transparent
            opacity={0.9}
            roughness={0.15}
            metalness={0.4}
          />
        </instancedMesh>
      </group>
    </>
  );
}
