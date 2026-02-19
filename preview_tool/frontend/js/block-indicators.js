/**
 * BlockIndicatorSystem - Shows which blocks are activated by frequency bands
 * Creates a floor grid that lights up based on audio reactivity
 */

class BlockIndicatorSystem {
    constructor(scene, gridSize = 8) {
        this.scene = scene;
        this.gridSize = gridSize;
        this.indicators = [];
        this.baseY = -0.4;
        this.activeCount = 0;
        this.totalBlocks = gridSize * gridSize;

        // Band colors matching the visualizer (5 bands for ultra-low-latency)
        this.bandColors = [
            new THREE.Color(0xff9100),  // Bass - orange (includes kick)
            new THREE.Color(0xffea00),  // Low - yellow
            new THREE.Color(0x00e676),  // Mid - green
            new THREE.Color(0x00b0ff),  // High - blue
            new THREE.Color(0xd500f9)   // Air - magenta
        ];

        this.inactiveColor = new THREE.Color(0x1a1a25);
        this.beatPulse = 0;

        this.createGrid();
    }

    createGrid() {
        const spacing = 1.2;
        const offset = (this.gridSize - 1) * spacing / 2;

        // Create indicator cubes
        for (let x = 0; x < this.gridSize; x++) {
            for (let z = 0; z < this.gridSize; z++) {
                const geometry = new THREE.BoxGeometry(0.9, 0.15, 0.9);
                const material = new THREE.MeshStandardMaterial({
                    color: this.inactiveColor,
                    emissive: this.inactiveColor,
                    emissiveIntensity: 0.1,
                    roughness: 0.6,
                    metalness: 0.3
                });

                const cube = new THREE.Mesh(geometry, material);
                cube.position.set(
                    x * spacing - offset,
                    this.baseY,
                    z * spacing - offset
                );

                // Assign band based on position (diagonal gradient)
                const bandIndex = Math.floor(((x + z) / (this.gridSize * 2 - 2)) * 5);
                cube.userData.bandIndex = Math.min(4, bandIndex);
                cube.userData.gridX = x;
                cube.userData.gridZ = z;
                cube.userData.intensity = 0;
                cube.userData.targetIntensity = 0;

                this.scene.add(cube);
                this.indicators.push(cube);
            }
        }

        // Create border glow ring
        this.createBorderRing(offset, spacing);
    }

    createBorderRing(offset, spacing) {
        const ringRadius = (this.gridSize * spacing) / 2 + 0.5;
        const ringGeometry = new THREE.RingGeometry(ringRadius - 0.05, ringRadius, 64);
        const ringMaterial = new THREE.MeshBasicMaterial({
            color: 0x333340,
            side: THREE.DoubleSide,
            transparent: true,
            opacity: 0.5
        });

        this.borderRing = new THREE.Mesh(ringGeometry, ringMaterial);
        this.borderRing.rotation.x = -Math.PI / 2;
        this.borderRing.position.y = this.baseY - 0.05;
        this.scene.add(this.borderRing);
    }

    updateFromAudio(bands, beat, beatIntensity) {
        // Beat pulse effect
        if (beat) {
            this.beatPulse = 1.0;
        }
        this.beatPulse *= 0.9;

        this.activeCount = 0;

        // Update each indicator based on its assigned band
        this.indicators.forEach((cube, index) => {
            const bandIndex = cube.userData.bandIndex;
            const bandValue = bands[bandIndex] || 0;

            // Calculate target intensity
            const threshold = 0.15;
            const isActive = bandValue > threshold;

            if (isActive) {
                cube.userData.targetIntensity = bandValue;
                this.activeCount++;
            } else {
                cube.userData.targetIntensity = 0.05;
            }

            // Smooth interpolation
            const lerpSpeed = 0.2;
            cube.userData.intensity += (cube.userData.targetIntensity - cube.userData.intensity) * lerpSpeed;

            const intensity = cube.userData.intensity;
            const bandColor = this.bandColors[bandIndex];

            // Update material
            const glowIntensity = intensity * (1 + this.beatPulse * 0.5);

            cube.material.color.copy(bandColor).multiplyScalar(intensity * 0.5);
            cube.material.emissive.copy(bandColor);
            cube.material.emissiveIntensity = glowIntensity * 0.8;

            // Slight Y scale pulse
            cube.scale.y = 1 + intensity * 0.3 + this.beatPulse * 0.2;
            cube.position.y = this.baseY + (cube.scale.y - 1) * 0.075;
        });

        // Update border ring color on beat
        if (this.borderRing) {
            const pulseColor = new THREE.Color(0x333340);
            if (this.beatPulse > 0.1) {
                pulseColor.lerp(new THREE.Color(0x6366f1), this.beatPulse * 0.5);
            }
            this.borderRing.material.color.copy(pulseColor);
        }
    }

    getStats() {
        return {
            active: this.activeCount,
            total: this.totalBlocks
        };
    }

    setVisible(visible) {
        this.indicators.forEach(cube => cube.visible = visible);
        if (this.borderRing) this.borderRing.visible = visible;
    }

    dispose() {
        this.indicators.forEach(cube => {
            this.scene.remove(cube);
            cube.geometry.dispose();
            cube.material.dispose();
        });
        if (this.borderRing) {
            this.scene.remove(this.borderRing);
            this.borderRing.geometry.dispose();
            this.borderRing.material.dispose();
        }
    }
}

// Export for use in app.js
if (typeof window !== 'undefined') {
    window.BlockIndicatorSystem = BlockIndicatorSystem;
}
