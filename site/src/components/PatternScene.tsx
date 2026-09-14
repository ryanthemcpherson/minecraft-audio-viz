"use client";

import { useRef, useEffect, useMemo } from "react";
import { useFrame } from "@react-three/fiber";
import { EffectComposer, Bloom } from "@react-three/postprocessing";
import * as THREE from "three";
import type { EntityData } from "@/lib/patterns/base";
import type { PatternInstance } from "@/lib/patterns";
import { sampleAudio } from "@/lib/audio/audioSource";
import { useMinecraftTextures } from "@/lib/useMinecraftTextures";
import { BAND_BLOCKS } from "@/lib/blockTextures";
import MinecraftStage from "./MinecraftStage";

const BLOCK_SIZE = 0.22;
const STAGE_Y = -1.8;
const STAGE_BLOCK = 0.38;
const FLOOR_Y = STAGE_Y + STAGE_BLOCK / 2; // top surface of grass
const WORLD_SCALE = 6; // normalized 0..1 pattern space to world units
/** Radius (normalized units) we try to keep the pattern's extent within. */
const FIT_TARGET_RADIUS = 0.42;
const FIT_MIN = 0.55;
const FIT_MAX = 1.5;
const BAND_COUNT = BAND_BLOCKS.length;
const TEMP_OBJECT = new THREE.Object3D();
const TEMP_COLOR = new THREE.Color();
const HIDDEN_MATRIX = new THREE.Matrix4().makeScale(0, 0, 0);

export type PreviewQuality = "low" | "high";

interface PatternSceneProps {
  pattern: PatternInstance;
  phaseOffset: number;
  staticCamera?: boolean;
  /** "high" adds bloom post-processing. Use for a single showcase canvas only. */
  quality?: PreviewQuality;
}

export default function PatternScene({
  pattern,
  phaseOffset,
  staticCamera = false,
  quality = "low",
}: PatternSceneProps) {
  // One instanced mesh per band so each band renders its own concrete block,
  // exactly like the in-game materials.
  const meshRefs = useRef<(THREE.InstancedMesh | null)[]>([]);
  const groupRef = useRef<THREE.Group>(null);
  const keyLightRef = useRef<THREE.DirectionalLight>(null);
  const skyLightRef = useRef<THREE.HemisphereLight>(null);
  const beatRingRef = useRef<THREE.Mesh>(null);
  const beatRingMaterialRef = useRef<THREE.MeshBasicMaterial>(null);
  const maxCount = pattern.config.entityCount;
  const prevPositions = useRef<Float32Array | null>(null);
  const smoothCenter = useRef({ x: 0.5, y: 0.35, z: 0.5 });
  const frameCount = useRef(0);
  const fitScale = useRef(1);
  const radiusHold = useRef(FIT_TARGET_RADIUS);
  const beatGlow = useRef(0);
  /** Per-band cursor into each mesh's instance list, reused every frame. */
  const bandCursors = useRef(new Int32Array(BAND_COUNT));

  const textures = useMinecraftTextures();

  const bandMaterials = useMemo(
    () =>
      textures.bands.map(
        (map) => new THREE.MeshStandardMaterial({ map, toneMapped: false, roughness: 0.95, metalness: 0 }),
      ),
    [textures],
  );

  useEffect(() => {
    return () => bandMaterials.forEach((m) => m.dispose());
  }, [bandMaterials]);

  // Pre-allocate position tracking
  useEffect(() => {
    prevPositions.current = new Float32Array(maxCount * 3);
  }, [maxCount]);

  useFrame(({ clock }, delta) => {
    const meshes = meshRefs.current;
    if (meshes.length < BAND_COUNT || meshes.some((m) => !m)) return;

    const time = clock.getElapsedTime();
    // Clamp delta to avoid huge jumps when tab is backgrounded
    const dt = Math.min(delta, 0.1);

    // Synthetic track or live microphone/tab audio, whichever is selected
    const audio = sampleAudio(time, phaseOffset);

    // Update pattern internal time
    pattern.update(dt);

    // Calculate entity positions from the pattern
    const entities: EntityData[] = pattern.calculateEntities(audio, dt);

    const prev = prevPositions.current;
    if (!prev) return;

    const sc = smoothCenter.current;

    if (staticCamera) {
      // Fixed center for spectrum-style patterns. No jiggle.
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

    // Auto-fit: track the pattern's peak extent (with slow decay) so wide
    // patterns shrink to fit and tight ones fill the frame, without pumping
    // on every beat.
    let maxRadiusSq = 0;
    for (let i = 0; i < entities.length && i < maxCount; i++) {
      const e = entities[i];
      if (!e.visible) continue;
      const dx = e.x - sc.x;
      const dy = e.y - sc.y;
      const dz = e.z - sc.z;
      const r2 = dx * dx + dy * dy + dz * dz;
      if (r2 > maxRadiusSq) maxRadiusSq = r2;
    }
    const radius = Math.sqrt(maxRadiusSq);
    radiusHold.current = Math.max(radius, radiusHold.current * Math.pow(0.35, dt)); // ~1 s memory
    const targetFit = Math.min(
      FIT_MAX,
      Math.max(FIT_MIN, FIT_TARGET_RADIUS / Math.max(radiusHold.current, 0.05)),
    );
    const fitLerp = frameCount.current < 10 ? 0.5 : 1 - Math.pow(0.02, dt);
    fitScale.current += (targetFit - fitScale.current) * fitLerp * 0.5;
    const scale3d = WORLD_SCALE * fitScale.current;

    // Beat flash envelope
    beatGlow.current = audio.isBeat
      ? Math.max(beatGlow.current, 0.6 + audio.beatIntensity * 0.4)
      : beatGlow.current * Math.pow(0.02, dt);
    const glow = beatGlow.current;

    const cursors = bandCursors.current;
    cursors.fill(0);

    for (let i = 0; i < maxCount; i++) {
      if (i >= entities.length || !entities[i].visible) continue;
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

      // Clamp so blocks don't clip through the grass floor
      const s = Math.max(0.05, e.scale * 2.5);
      const halfBlock = BLOCK_SIZE * s * 0.5;
      const clampedY = Math.max(y, FLOOR_Y + halfBlock);

      const band = Math.min(Math.max(0, e.band | 0), BAND_COUNT - 1);
      const mesh = meshes[band]!;
      const slot = cursors[band]++;

      TEMP_OBJECT.position.set(x, clampedY, z);
      TEMP_OBJECT.scale.set(s, s, s);
      TEMP_OBJECT.updateMatrix();
      mesh.setMatrixAt(slot, TEMP_OBJECT.matrix);

      // The block keeps its real concrete color; brightness rises with scale
      // and on beats so bloom picks the active blocks out.
      const brightness = 0.95 + e.scale * 1.0 + glow * 0.5;
      TEMP_COLOR.setScalar(brightness);
      mesh.setColorAt(slot, TEMP_COLOR);
    }

    // Hide unused instances on every band mesh
    for (let b = 0; b < BAND_COUNT; b++) {
      const mesh = meshes[b]!;
      for (let slot = cursors[b]; slot < maxCount; slot++) {
        mesh.setMatrixAt(slot, HIDDEN_MATRIX);
      }
      mesh.count = maxCount;
      mesh.instanceMatrix.needsUpdate = true;
      if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    }

    // Lights and the floor ring flash on the beat
    if (keyLightRef.current) keyLightRef.current.intensity = 1.1 + glow * 0.6;
    if (skyLightRef.current) skyLightRef.current.intensity = 0.95 + glow * 0.35;
    if (beatRingRef.current && beatRingMaterialRef.current) {
      const ringScale = 1 + (1 - glow) * 0.35;
      beatRingRef.current.scale.set(ringScale, ringScale, 1);
      beatRingMaterialRef.current.opacity = glow * 0.45;
    }

    // Slow auto-rotate (skip for static camera patterns)
    if (groupRef.current && !staticCamera) {
      groupRef.current.rotation.y = time * 0.15 + Math.sin(time * 0.3) * 0.1;
    }
  });

  return (
    <>
      {/*
        Minecraft-style lighting: a sky/ground hemisphere gives the vertical
        face shading the game has (tops bright, sides mid, bottoms dark) and a
        soft white key adds a little directionality. No colored lights, so the
        block textures keep their real colors.
      */}
      <hemisphereLight ref={skyLightRef} args={["#dbe9ff", "#4a3f2a", 0.95]} />
      <directionalLight ref={keyLightRef} position={[3, 6, 2]} intensity={1.1} color="#fff6e6" />
      <fog attach="fog" args={["#050505", 8, 25]} />

      <group ref={groupRef}>
        {bandMaterials.map((material, band) => (
          <instancedMesh
            key={BAND_BLOCKS[band]}
            ref={(node) => {
              meshRefs.current[band] = node;
            }}
            args={[undefined, undefined, maxCount]}
            material={material}
            frustumCulled={false}
          >
            <boxGeometry args={[BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE]} />
          </instancedMesh>
        ))}

        {/* Beat ring on the stage floor */}
        <mesh ref={beatRingRef} rotation-x={-Math.PI / 2} position={[0, FLOOR_Y + 0.012, 0]}>
          <ringGeometry args={[1.35, 1.6, 64]} />
          <meshBasicMaterial
            ref={beatRingMaterialRef}
            color="#00CCFF"
            transparent
            opacity={0}
            depthWrite={false}
            toneMapped={false}
          />
        </mesh>

        <MinecraftStage size={7} layers={3} yOffset={STAGE_Y} />
      </group>

      {quality === "high" && (
        <EffectComposer>
          <Bloom intensity={0.6} luminanceThreshold={0.85} luminanceSmoothing={0.2} mipmapBlur />
        </EffectComposer>
      )}
    </>
  );
}
