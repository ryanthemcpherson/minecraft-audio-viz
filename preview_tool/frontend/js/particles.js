/**
 * ParticleSystem - Three.js particle effects matching Minecraft
 * Renders FLAME, SOUL_FIRE_FLAME, END_ROD, NOTE, and DUST particles
 */

class ParticleSystem {
    constructor(scene, maxParticles = 500) {
        this.scene = scene;
        this.maxParticles = maxParticles;
        this.particles = [];
        this.activeCount = 0;

        // Particle type configurations
        this.particleTypes = {
            FLAME: {
                color: [1.0, 0.5, 0.1],
                size: 0.12,
                lifetime: 0.8,
                velocity: { x: 0, y: 1.5, z: 0 },
                gravity: -0.5,
                fadeOut: true,
                spread: 0.3
            },
            SOUL_FIRE_FLAME: {
                color: [0.2, 0.8, 1.0],
                size: 0.12,
                lifetime: 1.2,
                velocity: { x: 0, y: 1.0, z: 0 },
                gravity: -0.3,
                fadeOut: true,
                spread: 0.25
            },
            END_ROD: {
                color: [1.0, 1.0, 0.9],
                size: 0.08,
                lifetime: 1.0,
                velocity: { x: 0, y: 0, z: 0 },
                gravity: 0,
                fadeOut: true,
                spread: 0,
                ring: true
            },
            NOTE: {
                color: [1.0, 0.3, 0.5],  // Will be randomized
                size: 0.15,
                lifetime: 1.5,
                velocity: { x: 0, y: 0.8, z: 0 },
                gravity: 0,
                fadeOut: true,
                spread: 0.5,
                rainbow: true
            },
            DUST: {
                color: [0.5, 0.5, 0.5],  // Will be set per-particle
                size: 0.06,
                lifetime: 2.0,
                velocity: { x: 0, y: 0.2, z: 0 },
                gravity: 0,
                fadeOut: true,
                spread: 0.8
            },
            LAVA: {
                color: [1.0, 0.3, 0.0],
                size: 0.1,
                lifetime: 0.6,
                velocity: { x: 0, y: 2.0, z: 0 },
                gravity: -3.0,
                fadeOut: true,
                spread: 0.4
            }
        };

        // Create geometry with buffer attributes
        this.geometry = new THREE.BufferGeometry();
        this.positions = new Float32Array(maxParticles * 3);
        this.colors = new Float32Array(maxParticles * 3);
        this.sizes = new Float32Array(maxParticles);
        this.alphas = new Float32Array(maxParticles);

        this.geometry.setAttribute('position', new THREE.BufferAttribute(this.positions, 3));
        this.geometry.setAttribute('color', new THREE.BufferAttribute(this.colors, 3));
        this.geometry.setAttribute('size', new THREE.BufferAttribute(this.sizes, 1));

        // Custom shader material for better particle rendering
        this.material = new THREE.ShaderMaterial({
            uniforms: {
                pointTexture: { value: this.createParticleTexture() }
            },
            vertexShader: `
                attribute float size;
                attribute vec3 color;
                varying vec3 vColor;
                varying float vAlpha;

                void main() {
                    vColor = color;
                    vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
                    gl_PointSize = size * (300.0 / -mvPosition.z);
                    gl_Position = projectionMatrix * mvPosition;
                }
            `,
            fragmentShader: `
                uniform sampler2D pointTexture;
                varying vec3 vColor;

                void main() {
                    vec4 texColor = texture2D(pointTexture, gl_PointCoord);
                    if (texColor.a < 0.1) discard;
                    gl_FragColor = vec4(vColor, texColor.a);
                }
            `,
            blending: THREE.AdditiveBlending,
            depthWrite: false,
            transparent: true,
            vertexColors: true
        });

        this.points = new THREE.Points(this.geometry, this.material);
        scene.add(this.points);

        // Initialize particle pool
        for (let i = 0; i < maxParticles; i++) {
            this.particles.push({
                active: false,
                x: 0, y: 0, z: 0,
                vx: 0, vy: 0, vz: 0,
                life: 0,
                maxLife: 1,
                size: 0.1,
                r: 1, g: 1, b: 1,
                gravity: 0,
                fadeOut: true,
                ringPhase: 0,
                ringSpeed: 0
            });
        }
    }

    createParticleTexture() {
        const canvas = document.createElement('canvas');
        canvas.width = 64;
        canvas.height = 64;
        const ctx = canvas.getContext('2d');

        // Create soft circular gradient
        const gradient = ctx.createRadialGradient(32, 32, 0, 32, 32, 32);
        gradient.addColorStop(0, 'rgba(255, 255, 255, 1)');
        gradient.addColorStop(0.3, 'rgba(255, 255, 255, 0.8)');
        gradient.addColorStop(0.6, 'rgba(255, 255, 255, 0.3)');
        gradient.addColorStop(1, 'rgba(255, 255, 255, 0)');

        ctx.fillStyle = gradient;
        ctx.fillRect(0, 0, 64, 64);

        const texture = new THREE.CanvasTexture(canvas);
        return texture;
    }

    spawn(type, x, y, z, count, customColor = null) {
        const config = this.particleTypes[type];
        if (!config) return;

        count = Math.min(count, 50); // Cap per-spawn

        for (let i = 0; i < count; i++) {
            const particle = this.getInactiveParticle();
            if (!particle) break;

            // Position with spread
            particle.x = x + (Math.random() - 0.5) * config.spread * 2;
            particle.y = y + (Math.random() - 0.5) * config.spread;
            particle.z = z + (Math.random() - 0.5) * config.spread * 2;

            // Velocity with randomization
            particle.vx = config.velocity.x + (Math.random() - 0.5) * 0.5;
            particle.vy = config.velocity.y + Math.random() * 0.5;
            particle.vz = config.velocity.z + (Math.random() - 0.5) * 0.5;

            // Ring expansion for END_ROD
            if (config.ring) {
                particle.ringPhase = Math.random() * Math.PI * 2;
                particle.ringSpeed = 2 + Math.random();
                particle.vy = 0.1;
            }

            // Lifetime
            particle.life = config.lifetime * (0.8 + Math.random() * 0.4);
            particle.maxLife = particle.life;

            // Size
            particle.size = config.size * (0.8 + Math.random() * 0.4);

            // Color
            if (customColor) {
                particle.r = customColor[0];
                particle.g = customColor[1];
                particle.b = customColor[2];
            } else if (config.rainbow) {
                // Rainbow for NOTE particles
                const hue = Math.random();
                const rgb = this.hslToRgb(hue, 1, 0.6);
                particle.r = rgb[0];
                particle.g = rgb[1];
                particle.b = rgb[2];
            } else {
                particle.r = config.color[0];
                particle.g = config.color[1];
                particle.b = config.color[2];
            }

            particle.gravity = config.gravity;
            particle.fadeOut = config.fadeOut;
            particle.active = true;
            this.activeCount++;
        }
    }

    spawnRing(type, x, y, z, radius, count) {
        const config = this.particleTypes[type];
        if (!config) return;

        count = Math.min(count, 50);

        for (let i = 0; i < count; i++) {
            const particle = this.getInactiveParticle();
            if (!particle) break;

            const angle = (i / count) * Math.PI * 2;
            particle.x = x + Math.cos(angle) * radius * 0.3;
            particle.y = y;
            particle.z = z + Math.sin(angle) * radius * 0.3;

            // Outward velocity
            particle.vx = Math.cos(angle) * 2;
            particle.vy = 0.3;
            particle.vz = Math.sin(angle) * 2;

            particle.life = config.lifetime;
            particle.maxLife = particle.life;
            particle.size = config.size;

            particle.r = config.color[0];
            particle.g = config.color[1];
            particle.b = config.color[2];

            particle.gravity = 0;
            particle.fadeOut = true;
            particle.active = true;
            this.activeCount++;
        }
    }

    getInactiveParticle() {
        for (const p of this.particles) {
            if (!p.active) return p;
        }
        return null;
    }

    update(dt) {
        let idx = 0;

        for (const p of this.particles) {
            if (!p.active) {
                // Hidden particle
                this.positions[idx * 3] = 0;
                this.positions[idx * 3 + 1] = -1000;
                this.positions[idx * 3 + 2] = 0;
                this.sizes[idx] = 0;
                idx++;
                continue;
            }

            // Update physics
            p.life -= dt;
            p.vy += p.gravity * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.z += p.vz * dt;

            // Ring expansion
            if (p.ringSpeed) {
                const radius = (p.maxLife - p.life) * p.ringSpeed;
                p.x += Math.cos(p.ringPhase) * 0.02;
                p.z += Math.sin(p.ringPhase) * 0.02;
            }

            // Update buffer
            this.positions[idx * 3] = p.x;
            this.positions[idx * 3 + 1] = p.y;
            this.positions[idx * 3 + 2] = p.z;

            // Fade out
            const alpha = p.fadeOut ? (p.life / p.maxLife) : 1;
            this.colors[idx * 3] = p.r * alpha;
            this.colors[idx * 3 + 1] = p.g * alpha;
            this.colors[idx * 3 + 2] = p.b * alpha;
            this.sizes[idx] = p.size * (0.5 + alpha * 0.5);

            // Check death
            if (p.life <= 0) {
                p.active = false;
                this.activeCount--;
            }

            idx++;
        }

        this.geometry.attributes.position.needsUpdate = true;
        this.geometry.attributes.color.needsUpdate = true;
        this.geometry.attributes.size.needsUpdate = true;
    }

    hslToRgb(h, s, l) {
        let r, g, b;
        if (s === 0) {
            r = g = b = l;
        } else {
            const hue2rgb = (p, q, t) => {
                if (t < 0) t += 1;
                if (t > 1) t -= 1;
                if (t < 1/6) return p + (q - p) * 6 * t;
                if (t < 1/2) return q;
                if (t < 2/3) return p + (q - p) * (2/3 - t) * 6;
                return p;
            };
            const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
            const p = 2 * l - q;
            r = hue2rgb(p, q, h + 1/3);
            g = hue2rgb(p, q, h);
            b = hue2rgb(p, q, h - 1/3);
        }
        return [r, g, b];
    }

    getActiveCount() {
        return this.activeCount;
    }

    clear() {
        for (const p of this.particles) {
            p.active = false;
        }
        this.activeCount = 0;
    }

    dispose() {
        this.scene.remove(this.points);
        this.geometry.dispose();
        this.material.dispose();
    }
}

// Band colors for spectrum dust particles (5 bands for ultra-low-latency)
const BAND_COLORS = {
    0: [1.0, 0.57, 0.0],    // Bass - #ff9100 (includes kick)
    1: [1.0, 0.92, 0.0],    // Low - #ffea00
    2: [0.0, 0.9, 0.46],    // Mid - #00e676
    3: [0.0, 0.69, 1.0],    // High - #00b0ff
    4: [0.84, 0.0, 0.98]    // Air - #d500f9
};

/**
 * ParticleEntityRenderer - Renders audio entities as particle clouds
 * instead of solid blocks, for Particle View mode.
 * Uses an instanced point system with pooled particles for zero-alloc updates.
 */
class ParticleEntityRenderer {
    constructor(scene, maxParticles = 1500) {
        this.scene = scene;
        this.maxParticles = maxParticles;
        this.particles = [];
        this.activeCount = 0;

        // Entity tracking: each entity gets a cluster of particles
        // that lerp toward the entity position
        this.entityClusters = [];  // Maps entity index -> cluster info
        this.CLUSTER_MIN = 3;
        this.CLUSTER_MAX = 8;

        // Beat state for burst effect
        this.beatActive = false;
        this.beatDecay = 0;  // 0..1, decays over time after beat

        // Create geometry with buffer attributes
        this.geometry = new THREE.BufferGeometry();
        this.positions = new Float32Array(maxParticles * 3);
        this.colors = new Float32Array(maxParticles * 3);
        this.sizes = new Float32Array(maxParticles);

        this.geometry.setAttribute('position', new THREE.BufferAttribute(this.positions, 3));
        this.geometry.setAttribute('color', new THREE.BufferAttribute(this.colors, 3));
        this.geometry.setAttribute('size', new THREE.BufferAttribute(this.sizes, 1));

        // Additive blending shader for glowy ethereal look
        this.material = new THREE.ShaderMaterial({
            uniforms: {
                pointTexture: { value: this._createGlowTexture() }
            },
            vertexShader: `
                attribute float size;
                attribute vec3 color;
                varying vec3 vColor;

                void main() {
                    vColor = color;
                    vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
                    gl_PointSize = size * (350.0 / -mvPosition.z);
                    gl_Position = projectionMatrix * mvPosition;
                }
            `,
            fragmentShader: `
                uniform sampler2D pointTexture;
                varying vec3 vColor;

                void main() {
                    vec4 texColor = texture2D(pointTexture, gl_PointCoord);
                    if (texColor.a < 0.05) discard;
                    // Additive glow: brighten core, soft edges
                    float core = texColor.a * texColor.a;
                    gl_FragColor = vec4(vColor * (0.6 + core * 0.4), texColor.a * 0.85);
                }
            `,
            blending: THREE.AdditiveBlending,
            depthWrite: false,
            transparent: true,
            vertexColors: true
        });

        this.points = new THREE.Points(this.geometry, this.material);
        this.points.frustumCulled = false;
        scene.add(this.points);

        // Initialize particle pool
        for (let i = 0; i < maxParticles; i++) {
            this.particles.push({
                active: false,
                // Current position
                x: 0, y: -1000, z: 0,
                // Target position (entity center)
                tx: 0, ty: 0, tz: 0,
                // Drift velocity (organic sway)
                dx: 0, dy: 0, dz: 0,
                // Visual
                r: 1, g: 1, b: 1,
                size: 0.1,
                baseSize: 0.1,
                alpha: 1,
                // Cluster identity
                entityIndex: -1,
                clusterOffset: 0,  // Offset angle within cluster
                clusterRadius: 0.2,
                // Trail fade
                trailAlpha: 1,
                // Lifetime for recycling stale particles
                life: 0
            });
        }

        // Glow overlay element (simple CSS bloom simulation)
        this.glowOverlay = null;
        this._createGlowOverlay();
    }

    _createGlowTexture() {
        const canvas = document.createElement('canvas');
        canvas.width = 64;
        canvas.height = 64;
        const ctx = canvas.getContext('2d');

        // Softer, more diffuse glow for entity particles
        const gradient = ctx.createRadialGradient(32, 32, 0, 32, 32, 32);
        gradient.addColorStop(0, 'rgba(255, 255, 255, 1)');
        gradient.addColorStop(0.15, 'rgba(255, 255, 255, 0.9)');
        gradient.addColorStop(0.4, 'rgba(255, 255, 255, 0.4)');
        gradient.addColorStop(0.7, 'rgba(255, 255, 255, 0.1)');
        gradient.addColorStop(1, 'rgba(255, 255, 255, 0)');

        ctx.fillStyle = gradient;
        ctx.fillRect(0, 0, 64, 64);

        return new THREE.CanvasTexture(canvas);
    }

    _createGlowOverlay() {
        this.glowOverlay = document.createElement('div');
        this.glowOverlay.className = 'particle-glow-overlay';
        this.glowOverlay.style.cssText = `
            position: absolute;
            inset: 0;
            pointer-events: none;
            background: radial-gradient(ellipse at center, rgba(99, 102, 241, 0.08) 0%, transparent 70%);
            opacity: 0;
            transition: opacity 0.3s ease;
            z-index: 1;
            mix-blend-mode: screen;
        `;
        const previewSection = document.querySelector('.preview-section');
        if (previewSection) {
            previewSection.appendChild(this.glowOverlay);
        }
    }

    /**
     * Set glow overlay visibility based on view mode.
     */
    setGlowVisible(visible) {
        if (this.glowOverlay) {
            this.glowOverlay.style.opacity = visible ? '1' : '0';
        }
    }

    /**
     * Notify of a beat event for burst effect.
     */
    onBeat(intensity) {
        this.beatActive = true;
        this.beatDecay = Math.min(1.0, intensity || 0.7);
    }

    /**
     * Update particle positions from entity data.
     * entities: array of {x, y, z, scale, band} in 0..1 normalized coords
     * bands: array of 5 band amplitudes
     * zoneSize/centerOffset: coordinate transform params
     */
    updateFromEntities(entities, bands, zoneSize, centerOffset) {
        if (!entities || entities.length === 0) {
            // Deactivate all particles if no entities
            this._deactivateAll();
            return;
        }

        // Determine cluster sizes based on band: bass=fewer/larger, high=more/smaller
        const bandClusterSizes = [3, 4, 5, 6, 8];  // bass..air
        const bandParticleSizes = [0.25, 0.2, 0.16, 0.12, 0.09];  // bass..air
        const bandClusterRadii = [0.5, 0.4, 0.35, 0.3, 0.25];

        let particleIdx = 0;

        // Mark all particles as potentially stale
        for (const p of this.particles) {
            if (p.active) p.life -= 1;
        }

        for (let ei = 0; ei < entities.length; ei++) {
            const entity = entities[ei];
            if (!entity) continue;

            const bandIdx = entity.band || 0;
            const bandValue = bands[bandIdx] || 0;

            // World position
            const wx = (entity.x * zoneSize) - centerOffset;
            const wy = entity.y * zoneSize;
            const wz = (entity.z * zoneSize) - centerOffset;

            const clusterSize = bandClusterSizes[bandIdx] || 5;
            const baseParticleSize = bandParticleSizes[bandIdx] || 0.15;
            const clusterRadius = bandClusterRadii[bandIdx] || 0.35;

            // Get band color as RGB floats
            const bandColor = BAND_COLORS[bandIdx] || [1, 1, 1];

            // Scale cluster radius by entity scale and band intensity
            const scaledRadius = clusterRadius * (entity.scale || 1) * (0.6 + bandValue * 0.6);

            // Beat burst: temporarily expand radius
            const burstMultiplier = 1.0 + this.beatDecay * 0.8;

            for (let ci = 0; ci < clusterSize; ci++) {
                if (particleIdx >= this.maxParticles) break;

                const p = this.particles[particleIdx];

                // Assign cluster offset angle if new or reassigned
                if (!p.active || p.entityIndex !== ei) {
                    p.clusterOffset = (ci / clusterSize) * Math.PI * 2 + Math.random() * 0.5;
                    p.dx = (Math.random() - 0.5) * 0.3;
                    p.dy = (Math.random() - 0.5) * 0.15 + 0.05;  // slight upward drift
                    p.dz = (Math.random() - 0.5) * 0.3;
                    // If newly activated, start near target
                    if (!p.active) {
                        p.x = wx + (Math.random() - 0.5) * 0.3;
                        p.y = wy + (Math.random() - 0.5) * 0.3;
                        p.z = wz + (Math.random() - 0.5) * 0.3;
                    }
                }

                p.entityIndex = ei;
                p.active = true;
                p.life = 3;  // Keep alive

                // Target: entity center + cluster offset
                const angle = p.clusterOffset + performance.now() * 0.0003 * (1 + bandIdx * 0.2);
                const effectiveRadius = scaledRadius * burstMultiplier;
                p.tx = wx + Math.cos(angle) * effectiveRadius;
                p.ty = wy + Math.sin(angle * 0.7) * effectiveRadius * 0.5;
                p.tz = wz + Math.sin(angle) * effectiveRadius;

                // Color: band color with intensity modulation
                const brightness = 0.5 + bandValue * 0.5;
                // Beat flash: briefly whiten
                const beatWhiten = this.beatDecay * 0.3;
                p.r = Math.min(1, bandColor[0] * brightness + beatWhiten);
                p.g = Math.min(1, bandColor[1] * brightness + beatWhiten);
                p.b = Math.min(1, bandColor[2] * brightness + beatWhiten);

                // Size: base size scaled by entity scale, intensity, and beat
                p.baseSize = baseParticleSize * (entity.scale || 1) * (0.7 + bandValue * 0.5);
                p.size = p.baseSize * (1.0 + this.beatDecay * 0.4);

                // Alpha based on band intensity
                p.alpha = 0.5 + bandValue * 0.5;

                particleIdx++;
            }
        }

        // Deactivate remaining particles
        for (let i = particleIdx; i < this.maxParticles; i++) {
            const p = this.particles[i];
            if (p.active && p.life <= 0) {
                p.active = false;
            }
        }
    }

    _deactivateAll() {
        for (const p of this.particles) {
            p.active = false;
        }
        this.activeCount = 0;
    }

    /**
     * Per-frame update: physics, lerp, buffer write.
     */
    update(dt) {
        // Decay beat effect
        if (this.beatDecay > 0) {
            this.beatDecay = Math.max(0, this.beatDecay - dt * 4.0);
        }

        // Update glow overlay intensity based on beat
        if (this.glowOverlay) {
            const glowIntensity = 0.5 + this.beatDecay * 0.5;
            this.glowOverlay.style.background = `radial-gradient(ellipse at center, rgba(99, 102, 241, ${0.06 + this.beatDecay * 0.12}) 0%, transparent 70%)`;
        }

        let active = 0;
        const lerpSpeed = 6.0;  // Smooth but responsive
        const driftScale = 0.4;

        for (let i = 0; i < this.maxParticles; i++) {
            const p = this.particles[i];

            if (!p.active) {
                this.positions[i * 3] = 0;
                this.positions[i * 3 + 1] = -1000;
                this.positions[i * 3 + 2] = 0;
                this.sizes[i] = 0;
                continue;
            }

            active++;

            // Organic sway: slowly rotate drift direction
            p.dx += (Math.random() - 0.5) * 0.1 * dt;
            p.dy += (Math.random() - 0.5) * 0.05 * dt;
            p.dz += (Math.random() - 0.5) * 0.1 * dt;
            // Dampen drift
            p.dx *= 0.98;
            p.dy *= 0.98;
            p.dz *= 0.98;

            // Lerp toward target position + drift
            const targetX = p.tx + p.dx * driftScale;
            const targetY = p.ty + p.dy * driftScale + 0.02;  // Slight upward bias
            const targetZ = p.tz + p.dz * driftScale;

            const lerpFactor = 1.0 - Math.exp(-lerpSpeed * dt);
            p.x += (targetX - p.x) * lerpFactor;
            p.y += (targetY - p.y) * lerpFactor;
            p.z += (targetZ - p.z) * lerpFactor;

            // Write to buffer
            this.positions[i * 3] = p.x;
            this.positions[i * 3 + 1] = p.y;
            this.positions[i * 3 + 2] = p.z;

            // Color with alpha fade
            const a = p.alpha;
            this.colors[i * 3] = p.r * a;
            this.colors[i * 3 + 1] = p.g * a;
            this.colors[i * 3 + 2] = p.b * a;

            this.sizes[i] = p.size;
        }

        this.activeCount = active;

        this.geometry.attributes.position.needsUpdate = true;
        this.geometry.attributes.color.needsUpdate = true;
        this.geometry.attributes.size.needsUpdate = true;
    }

    getActiveCount() {
        return this.activeCount;
    }

    setVisible(visible) {
        this.points.visible = visible;
        this.setGlowVisible(visible);
    }

    clear() {
        this._deactivateAll();
    }

    dispose() {
        this.scene.remove(this.points);
        this.geometry.dispose();
        this.material.dispose();
        if (this.glowOverlay && this.glowOverlay.parentNode) {
            this.glowOverlay.parentNode.removeChild(this.glowOverlay);
        }
    }
}

// Export for use in app.js
if (typeof window !== 'undefined') {
    window.ParticleSystem = ParticleSystem;
    window.ParticleEntityRenderer = ParticleEntityRenderer;
    window.BAND_COLORS = BAND_COLORS;
}
