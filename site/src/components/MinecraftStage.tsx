"use client";

import { useRef, useEffect, useMemo } from "react";
import * as THREE from "three";
import { getGrassTopTexture, getStoneTexture } from "@/lib/blockTextures";

const BLOCK_SIZE = 0.38;
const GRID_SPACING = 0.4;
const TEMP_OBJ = new THREE.Object3D();

interface MinecraftStageProps {
  /** Top layer grid size (e.g. 7 = 7×7 grass blocks) */
  size?: number;
  /** Number of tapered stone layers below grass */
  layers?: number;
  /** Y position of the top (grass) layer */
  yOffset?: number;
}

export default function MinecraftStage({
  size = 7,
  layers = 3,
  yOffset = -1.8,
}: MinecraftStageProps) {
  const grassRef = useRef<THREE.InstancedMesh>(null);
  const stoneRef = useRef<THREE.InstancedMesh>(null);

  const grassTex = useMemo(() => getGrassTopTexture(), []);
  const stoneTex = useMemo(() => getStoneTexture(), []);

  const { grassPositions, stonePositions } = useMemo(() => {
    const gPos: [number, number, number][] = [];
    const sPos: [number, number, number][] = [];

    // Top layer: grass
    const half = (size - 1) / 2;
    for (let x = 0; x < size; x++) {
      for (let z = 0; z < size; z++) {
        gPos.push([
          (x - half) * GRID_SPACING,
          yOffset,
          (z - half) * GRID_SPACING,
        ]);
      }
    }

    // Lower layers: stone, shrinking by 2 each layer
    for (let layer = 1; layer < layers; layer++) {
      const layerSize = Math.max(1, size - layer * 2);
      const layerHalf = (layerSize - 1) / 2;
      for (let x = 0; x < layerSize; x++) {
        for (let z = 0; z < layerSize; z++) {
          sPos.push([
            (x - layerHalf) * GRID_SPACING,
            yOffset - layer * GRID_SPACING,
            (z - layerHalf) * GRID_SPACING,
          ]);
        }
      }
    }

    return { grassPositions: gPos, stonePositions: sPos };
  }, [size, layers, yOffset]);

  useEffect(() => {
    const mesh = grassRef.current;
    if (!mesh) return;
    grassPositions.forEach(([x, y, z], i) => {
      TEMP_OBJ.position.set(x, y, z);
      TEMP_OBJ.scale.setScalar(1);
      TEMP_OBJ.rotation.set(0, 0, 0);
      TEMP_OBJ.updateMatrix();
      mesh.setMatrixAt(i, TEMP_OBJ.matrix);
    });
    mesh.instanceMatrix.needsUpdate = true;
  }, [grassPositions]);

  useEffect(() => {
    const mesh = stoneRef.current;
    if (!mesh || stonePositions.length === 0) return;
    stonePositions.forEach(([x, y, z], i) => {
      TEMP_OBJ.position.set(x, y, z);
      TEMP_OBJ.scale.setScalar(1);
      TEMP_OBJ.rotation.set(0, 0, 0);
      TEMP_OBJ.updateMatrix();
      mesh.setMatrixAt(i, TEMP_OBJ.matrix);
    });
    mesh.instanceMatrix.needsUpdate = true;
  }, [stonePositions]);

  return (
    <>
      <instancedMesh
        ref={grassRef}
        args={[undefined, undefined, grassPositions.length]}
      >
        <boxGeometry args={[BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE]} />
        <meshStandardMaterial map={grassTex} roughness={0.8} metalness={0} />
      </instancedMesh>

      {stonePositions.length > 0 && (
        <instancedMesh
          ref={stoneRef}
          args={[undefined, undefined, stonePositions.length]}
        >
          <boxGeometry args={[BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE]} />
          <meshStandardMaterial map={stoneTex} roughness={0.9} metalness={0} />
        </instancedMesh>
      )}
    </>
  );
}
