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

// Export for use in admin-app.js
if (typeof window !== 'undefined') {
    window.ParticleSystem = ParticleSystem;
    window.BAND_COLORS = BAND_COLORS;
}
