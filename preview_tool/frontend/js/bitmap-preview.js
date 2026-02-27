/**
 * BitmapPreview — Client-side bitmap LED wall rendering for the 3D preview.
 *
 * Renders JS implementations of the Minecraft bitmap patterns (plasma, spectrum,
 * waveform, etc.) as CanvasTexture planes in zone groups, driven by the same
 * audio data that flows to the Minecraft plugin.
 */
class BitmapPreview {
    constructor() {
        /** @type {Object<string, BitmapZoneState>} */
        this.zones = {};
        this.time = 0;
        this.effects = {
            brightness: 1.0,
            blackout: false,
            frozen: false,
            washR: 0, washG: 0, washB: 0, washOpacity: 0,
        };
    }

    /**
     * Activate bitmap rendering for a zone.
     * Creates a CanvasTexture plane in the zone group.
     */
    activate(zoneName, width, height, pattern, zoneGroup) {
        this.deactivate(zoneName);

        width = width || 16;
        height = height || 12;
        pattern = pattern || 'bmp_plasma';

        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext('2d');
        ctx.fillStyle = '#000';
        ctx.fillRect(0, 0, width, height);

        const texture = new THREE.CanvasTexture(canvas);
        texture.minFilter = THREE.NearestFilter;
        texture.magFilter = THREE.NearestFilter;

        const sx = zoneGroup.sizeX;
        const sy = zoneGroup.sizeY;
        const sz = zoneGroup.sizeZ;

        const planeGeo = new THREE.PlaneGeometry(sx, sy);
        const planeMat = new THREE.MeshBasicMaterial({
            map: texture,
            side: THREE.DoubleSide,
        });
        const mesh = new THREE.Mesh(planeGeo, planeMat);
        // Position at center of zone depth, centered on x/y
        mesh.position.set(sx / 2, sy / 2, sz * 0.5);
        mesh.visible = false; // Hidden until LED Wall view mode is selected

        zoneGroup.group.add(mesh);

        this.zones[zoneName] = {
            width, height, pattern,
            canvas, ctx, texture, mesh, zoneGroup,
        };
    }

    deactivate(zoneName) {
        const s = this.zones[zoneName];
        if (!s) return;
        if (s.mesh && s.mesh.parent) s.mesh.parent.remove(s.mesh);
        if (s.mesh) { s.mesh.geometry.dispose(); s.mesh.material.dispose(); }
        if (s.texture) s.texture.dispose();
        delete this.zones[zoneName];
    }

    isActive(zoneName) { return !!this.zones[zoneName]; }

    setPattern(zoneName, pattern) {
        const s = this.zones[zoneName];
        if (s) s.pattern = pattern;
    }

    setVisible(visible) {
        for (const s of Object.values(this.zones)) {
            s.mesh.visible = visible;
        }
    }

    setZoneVisible(zoneName, visible) {
        const s = this.zones[zoneName];
        if (s) s.mesh.visible = visible;
    }

    /** Called every animation frame. */
    update(dt, audioState) {
        if (this.effects.frozen) return;
        this.time += dt;

        const now = performance.now();
        for (const s of Object.values(this.zones)) {
            if (!s.mesh.visible) continue;
            // Skip local rendering if server frames arrived within last 2 seconds
            if (s._serverFrameTime && (now - s._serverFrameTime) < 2000) continue;
            this._renderPattern(s, audioState);
            this._applyEffects(s);
            s.texture.needsUpdate = true;
        }
    }

    /**
     * Apply a server-rendered bitmap frame directly to the canvas.
     * Decodes base64 little-endian ARGB int array to RGBA ImageData.
     */
    applyServerFrame(zoneName, base64Pixels, width, height) {
        const s = this.zones[zoneName];
        if (!s || !s.mesh.visible) return;

        // Resize canvas if dimensions changed
        if (s.canvas.width !== width || s.canvas.height !== height) {
            s.canvas.width = width;
            s.canvas.height = height;
            s.width = width;
            s.height = height;
        }

        // Decode base64 to byte array
        const binaryStr = atob(base64Pixels);
        const bytes = new Uint8Array(binaryStr.length);
        for (let i = 0; i < binaryStr.length; i++) {
            bytes[i] = binaryStr.charCodeAt(i);
        }

        // Convert LE ARGB int array to RGBA ImageData
        const pixelCount = width * height;
        const img = s.ctx.createImageData(width, height);
        const d = img.data;
        const view = new DataView(bytes.buffer);

        for (let i = 0; i < pixelCount; i++) {
            const argb = view.getInt32(i * 4, true); // little-endian
            const a = (argb >>> 24) & 0xFF;
            const r = (argb >>> 16) & 0xFF;
            const g = (argb >>> 8) & 0xFF;
            const b = argb & 0xFF;
            const j = i << 2;
            d[j] = r;
            d[j + 1] = g;
            d[j + 2] = b;
            d[j + 3] = a || 255; // Default to opaque if alpha is 0
        }

        s.ctx.putImageData(img, 0, 0);
        this._applyEffects(s);
        s.texture.needsUpdate = true;

        // Mark as receiving server frames (skip local rendering for this zone)
        s._serverFrameTime = performance.now();
    }

    // ────────────────── Pattern Dispatch ──────────────────

    _renderPattern(s, audio) {
        switch (s.pattern) {
            case 'bmp_spectrum':    return this._spectrum(s, audio);
            case 'bmp_waveform':    return this._waveform(s, audio);
            case 'bmp_vumeter':     return this._vuMeter(s, audio);
            case 'bmp_spectrogram': return this._spectrogram(s, audio);
            default:                return this._plasma(s, audio);
        }
    }

    // ────────────────── Pattern: Plasma ──────────────────

    _plasma(s, audio) {
        const { ctx, width, height } = s;
        const t = this.time;
        const bass = (audio.bands && audio.bands[0]) || 0;
        const mid = (audio.bands && audio.bands[2]) || 0;

        const img = ctx.createImageData(width, height);
        const d = img.data;

        for (let y = 0; y < height; y++) {
            const ny = y / height;
            for (let x = 0; x < width; x++) {
                const nx = x / width;
                const v1 = Math.sin(nx * 6.28 + t * 2.0 + bass * 3.0);
                const v2 = Math.sin(ny * 4.71 + t * 1.5);
                const v3 = Math.sin((nx + ny) * 5.0 + t * 1.2 + mid * 2.0);
                const v4 = Math.sin(Math.sqrt((nx - 0.5) ** 2 + (ny - 0.5) ** 2) * 8.0 - t * 3.0);
                const v = (v1 + v2 + v3 + v4) * 0.25;

                const i = (y * width + x) << 2;
                d[i]     = (Math.sin(v * 3.14159) * 0.5 + 0.5) * 255 | 0;
                d[i + 1] = (Math.sin(v * 3.14159 + 2.094) * 0.5 + 0.5) * 255 | 0;
                d[i + 2] = (Math.sin(v * 3.14159 + 4.189) * 0.5 + 0.5) * 255 | 0;
                d[i + 3] = 255;
            }
        }
        ctx.putImageData(img, 0, 0);
    }

    // ────────────────── Pattern: Spectrum Bars ──────────────────

    _spectrum(s, audio) {
        const { ctx, width, height } = s;
        const bands = (audio.bands || [0, 0, 0, 0, 0]);

        ctx.fillStyle = '#000';
        ctx.fillRect(0, 0, width, height);

        const colors = ['#ff9100', '#ffea00', '#00e676', '#00b0ff', '#d500f9'];
        const barW = width / bands.length;

        for (let i = 0; i < bands.length; i++) {
            const barH = Math.max(1, Math.round(bands[i] * height));
            const x = Math.round(i * barW);
            const w = Math.max(1, Math.round(barW) - 1);

            ctx.fillStyle = colors[i % 5];
            ctx.fillRect(x, height - barH, w, barH);

            // Peak line
            if (barH < height) {
                ctx.fillStyle = '#fff';
                ctx.fillRect(x, height - barH - 1, w, 1);
            }
        }
    }

    // ────────────────── Pattern: Waveform ──────────────────

    _waveform(s, audio) {
        const { ctx, width, height } = s;
        const t = this.time;
        const amp = audio.amplitude || 0;
        const bass = (audio.bands && audio.bands[0]) || 0;

        ctx.fillStyle = 'rgba(0, 0, 10, 0.4)';
        ctx.fillRect(0, 0, width, height);

        const midY = height / 2;

        for (let x = 0; x < width; x++) {
            const nx = x / width;
            const wave = Math.sin(nx * 12.57 + t * 5) * amp * height * 0.3;
            const wave2 = Math.sin(nx * 6.28 + t * 3) * bass * height * 0.2;
            const y = Math.round(midY + wave + wave2);

            if (y >= 0 && y < height) {
                // Core pixel
                ctx.fillStyle = '#00ccff';
                ctx.fillRect(x, y, 1, 1);
                // Glow
                ctx.fillStyle = 'rgba(0, 204, 255, 0.3)';
                if (y > 0) ctx.fillRect(x, y - 1, 1, 1);
                if (y < height - 1) ctx.fillRect(x, y + 1, 1, 1);
            }
        }
    }

    // ────────────────── Pattern: VU Meter ──────────────────

    _vuMeter(s, audio) {
        const { ctx, width, height } = s;
        const bands = (audio.bands || [0, 0, 0, 0, 0]);

        ctx.fillStyle = '#000';
        ctx.fillRect(0, 0, width, height);

        const barW = Math.max(1, Math.floor(width / bands.length) - 1);

        for (let i = 0; i < bands.length; i++) {
            const level = bands[i];
            const x = Math.round(i * (barW + 1));
            const totalH = Math.round(level * height);

            for (let row = 0; row < totalH; row++) {
                const ratio = row / height;
                const r = ratio > 0.85 ? 255 : ratio > 0.6 ? 255 : 0;
                const g = ratio > 0.85 ? 0 : 255;
                ctx.fillStyle = `rgb(${r},${g},0)`;
                ctx.fillRect(x, height - 1 - row, barW, 1);
            }
        }
    }

    // ────────────────── Pattern: Spectrogram ──────────────────

    _spectrogram(s, audio) {
        const { ctx, width, height } = s;
        const bands = (audio.bands || [0, 0, 0, 0, 0]);

        // Scroll left by 1 pixel
        const img = ctx.getImageData(1, 0, width - 1, height);
        ctx.putImageData(img, 0, 0);

        // Draw new column on right edge
        const bandH = height / bands.length;
        for (let i = 0; i < bands.length; i++) {
            const v = bands[i];
            const r = Math.min(255, Math.round(v * 510));
            const g = Math.min(255, Math.round(v * 153));
            const b = Math.round((1 - v) * 80);
            ctx.fillStyle = `rgb(${r},${g},${b})`;
            const y = height - Math.round((i + 1) * bandH);
            ctx.fillRect(width - 1, y, 1, Math.ceil(bandH));
        }
    }

    // ────────────────── Effects ──────────────────

    _applyEffects(s) {
        const { ctx, width, height } = s;
        const fx = this.effects;

        if (fx.blackout) {
            ctx.fillStyle = '#000';
            ctx.fillRect(0, 0, width, height);
            return;
        }

        if (fx.washOpacity > 0) {
            ctx.fillStyle = `rgba(${fx.washR},${fx.washG},${fx.washB},${fx.washOpacity})`;
            ctx.fillRect(0, 0, width, height);
        }

        if (fx.brightness < 1.0) {
            ctx.fillStyle = `rgba(0,0,0,${1.0 - fx.brightness})`;
            ctx.fillRect(0, 0, width, height);
        }
    }
}
