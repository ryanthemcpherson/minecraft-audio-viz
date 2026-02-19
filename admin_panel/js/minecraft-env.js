/**
 * MinecraftEnvironment — Block-based stage platform
 * Creates a floating island of grass/dirt/stone blocks as background
 * for the 3D preview, replacing the flat dark ground plane.
 * Uses InstancedMesh for performance (~5-6 draw calls total).
 */
class MinecraftEnvironment {
    constructor(scene, textureManager) {
        this._scene = scene;
        this._texMgr = textureManager;
        this._group = new THREE.Group();
        this._group.name = 'minecraft-environment';
        this._meshes = [];
    }

    /** Build the stage platform and add to scene */
    build() {
        const layout = this._generateLayout();
        this._buildInstancedMeshes(layout);
        this._scene.add(this._group);
    }

    /** Toggle visibility */
    setVisible(visible) {
        this._group.visible = visible;
    }

    /** Remove from scene and dispose resources */
    dispose() {
        this._scene.remove(this._group);
        for (const mesh of this._meshes) {
            mesh.geometry.dispose();
            if (Array.isArray(mesh.material)) {
                mesh.material.forEach(m => m.dispose());
            } else {
                mesh.material.dispose();
            }
        }
        this._meshes = [];
    }

    // ── Layout generation ─────────────────────────────────────────────

    _generateLayout() {
        // Platform dimensions: 15x15 centered at origin
        const HALF = 7;
        const blocks = { grass_side: [], grass_top: [], dirt: [], stone: [], log: [] };
        const rng = this._seededRng(12345);

        for (let x = -HALF; x <= HALF; x++) {
            for (let z = -HALF; z <= HALF; z++) {
                const dist = Math.max(Math.abs(x), Math.abs(z));
                const isEdge = dist === HALF;
                const isCorner = Math.abs(x) === HALF && Math.abs(z) === HALF;

                // Edge erosion: randomly skip some edge blocks
                if (isEdge && !isCorner && rng() < 0.35) continue;

                // Top layer: grass (y = -0.5)
                blocks.grass_side.push({ x, y: -0.5, z });
                blocks.grass_top.push({ x, y: 0.001, z });

                // Second layer: dirt (y = -1.5)
                blocks.dirt.push({ x, y: -1.5, z });

                // Bottom layers: stone
                // Deeper at center (more layers closer to center)
                blocks.stone.push({ x, y: -2.5, z });
                if (dist < HALF - 1) {
                    blocks.stone.push({ x, y: -3.5, z });
                }
                if (dist < HALF - 3) {
                    blocks.stone.push({ x, y: -4.5, z });
                }

                // Corner pillars: oak logs, 2 blocks tall at 4 corners
                if (isCorner) {
                    blocks.log.push({ x, y: 0.5, z });
                    blocks.log.push({ x, y: 1.5, z });
                }
            }
        }

        return blocks;
    }

    _buildInstancedMeshes(layout) {
        const blockGeo = new THREE.BoxGeometry(1, 1, 1);

        // Grass sides: use side material on all faces of a cube (good enough for instanced)
        if (layout.grass_side.length > 0) {
            const mat = this._texMgr.getEnvironmentMaterial('GRASS_BLOCK', 'side')
                || new THREE.MeshStandardMaterial({ color: 0x6b8b45, roughness: 0.85 });
            const mesh = new THREE.InstancedMesh(blockGeo, mat, layout.grass_side.length);
            this._setInstances(mesh, layout.grass_side);
            mesh.receiveShadow = true;
            this._group.add(mesh);
            this._meshes.push(mesh);
        }

        // Grass top overlay: thin plane on top of grass blocks
        if (layout.grass_top.length > 0) {
            const topGeo = new THREE.PlaneGeometry(1, 1);
            const topMat = this._texMgr.getEnvironmentMaterial('GRASS_BLOCK', 'top')
                || new THREE.MeshStandardMaterial({ color: 0x5d9c3a, roughness: 0.85 });
            const topMesh = new THREE.InstancedMesh(topGeo, topMat, layout.grass_top.length);

            const dummy = new THREE.Object3D();
            for (let i = 0; i < layout.grass_top.length; i++) {
                const b = layout.grass_top[i];
                dummy.position.set(b.x, b.y, b.z);
                dummy.rotation.set(-Math.PI / 2, 0, 0);
                dummy.scale.set(1, 1, 1);
                dummy.updateMatrix();
                topMesh.setMatrixAt(i, dummy.matrix);
            }
            topMesh.instanceMatrix.needsUpdate = true;
            topMesh.receiveShadow = true;
            this._group.add(topMesh);
            this._meshes.push(topMesh);
        }

        // Dirt
        if (layout.dirt.length > 0) {
            const mat = this._texMgr.getEnvironmentMaterial('DIRT', 'front')
                || new THREE.MeshStandardMaterial({ color: 0x8b6745, roughness: 0.9 });
            const mesh = new THREE.InstancedMesh(blockGeo, mat, layout.dirt.length);
            this._setInstances(mesh, layout.dirt);
            mesh.receiveShadow = true;
            this._group.add(mesh);
            this._meshes.push(mesh);
        }

        // Stone
        if (layout.stone.length > 0) {
            const mat = this._texMgr.getEnvironmentMaterial('STONE', 'front')
                || new THREE.MeshStandardMaterial({ color: 0x7f7f7f, roughness: 0.9 });
            const mesh = new THREE.InstancedMesh(blockGeo, mat, layout.stone.length);
            this._setInstances(mesh, layout.stone);
            mesh.receiveShadow = true;
            this._group.add(mesh);
            this._meshes.push(mesh);
        }

        // Oak logs (corner pillars)
        if (layout.log.length > 0) {
            const mat = this._texMgr.getEnvironmentMaterial('OAK_LOG', 'side')
                || new THREE.MeshStandardMaterial({ color: 0x5c3d1e, roughness: 0.9 });
            const mesh = new THREE.InstancedMesh(blockGeo, mat, layout.log.length);
            this._setInstances(mesh, layout.log);
            mesh.castShadow = true;
            mesh.receiveShadow = true;
            this._group.add(mesh);
            this._meshes.push(mesh);
        }
    }

    _setInstances(mesh, positions) {
        const dummy = new THREE.Object3D();
        for (let i = 0; i < positions.length; i++) {
            const p = positions[i];
            dummy.position.set(p.x, p.y, p.z);
            dummy.scale.set(1, 1, 1);
            dummy.updateMatrix();
            mesh.setMatrixAt(i, dummy.matrix);
        }
        mesh.instanceMatrix.needsUpdate = true;
    }

    _seededRng(seed) {
        let s = seed;
        return () => {
            s = (s * 16807 + 0) % 2147483647;
            return (s - 1) / 2147483646;
        };
    }
}
