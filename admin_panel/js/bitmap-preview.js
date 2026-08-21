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
        this.pendingPatterns = {};
        this.exactFrames = {};
        this.nextFrameId = 1;
        this.frameFreshnessMs = 750;
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
        pattern = this.pendingPatterns[zoneName] || pattern || 'bmp_plasma';

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
            frameImageData: null,
            renderedFrameId: 0,
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
        if (!zoneName || !pattern) return;
        this.pendingPatterns[zoneName] = pattern;
        const s = this.zones[zoneName];
        if (s) s.pattern = pattern;
    }

    /**
     * Store an exact frame received from the Minecraft renderer.
     * Returns false for malformed frames without disturbing prior state.
     */
    ingestFrame(message) {
        const frame = this._decodeFrame(message);
        if (!frame) return false;

        frame.id = this.nextFrameId++;
        frame.receivedAt = performance.now();
        this.exactFrames[frame.zone] = frame;
        return true;
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

        for (const [zoneName, s] of Object.entries(this.zones)) {
            if (!s.mesh.visible) continue;

            const exactFrame = this.exactFrames[zoneName];
            if (exactFrame && now - exactFrame.receivedAt <= this.frameFreshnessMs) {
                if (s.renderedFrameId !== exactFrame.id) {
                    this._renderExactFrame(s, exactFrame);
                    s.renderedFrameId = exactFrame.id;
                    s.texture.needsUpdate = true;
                }
                continue;
            }

            this._renderPattern(s, audioState);
            this._applyEffects(s);
            s.texture.needsUpdate = true;
        }
    }

    _decodeFrame(message) {
        if (!message || typeof message !== 'object' || typeof message.zone !== 'string'
            || message.zone.length === 0) {
            return null;
        }

        const width = message.width;
        const height = message.height;
        if (!Number.isSafeInteger(width) || width < 1
            || !Number.isSafeInteger(height) || height < 1) {
            return null;
        }

        const pixelCount = width * height;
        if (!Number.isSafeInteger(pixelCount) || pixelCount > 4_194_304) {
            return null;
        }

        const hasPixelArray = Array.isArray(message.pixel_array);
        const hasBase64 = typeof message.pixels === 'string';
        if (hasPixelArray === hasBase64) return null;

        const argbPixels = new Uint32Array(pixelCount);
        if (hasPixelArray) {
            if (message.pixel_array.length !== pixelCount) return null;
            for (let index = 0; index < pixelCount; index++) {
                const value = message.pixel_array[index];
                if (!Number.isInteger(value) || value < -2_147_483_648 || value > 4_294_967_295) {
                    return null;
                }
                argbPixels[index] = value >>> 0;
            }
        } else {
            let bytes;
            try {
                const encoded = message.pixels;
                if (encoded.length === 0 || encoded.length % 4 !== 0
                    || !/^[A-Za-z0-9+/]*={0,2}$/.test(encoded)) {
                    return null;
                }
                const binary = atob(encoded);
                if (binary.length !== pixelCount * 4) return null;
                bytes = new Uint8Array(binary.length);
                for (let index = 0; index < binary.length; index++) {
                    bytes[index] = binary.charCodeAt(index);
                }
            } catch (_) {
                return null;
            }

            const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
            for (let index = 0; index < pixelCount; index++) {
                argbPixels[index] = view.getUint32(index * 4, true);
            }
        }

        const rgba = new Uint8ClampedArray(pixelCount * 4);
        for (let index = 0; index < pixelCount; index++) {
            const argb = argbPixels[index];
            const offset = index * 4;
            rgba[offset] = (argb >>> 16) & 0xff;
            rgba[offset + 1] = (argb >>> 8) & 0xff;
            rgba[offset + 2] = argb & 0xff;
            rgba[offset + 3] = (argb >>> 24) & 0xff;
        }

        return { zone: message.zone, width, height, rgba };
    }

    _renderExactFrame(zoneState, frame) {
        if (zoneState.width !== frame.width || zoneState.height !== frame.height) {
            zoneState.width = frame.width;
            zoneState.height = frame.height;
            zoneState.canvas.width = frame.width;
            zoneState.canvas.height = frame.height;
            zoneState.frameImageData = null;
        }

        if (!zoneState.frameImageData) {
            zoneState.frameImageData = zoneState.ctx.createImageData(frame.width, frame.height);
        }
        zoneState.frameImageData.data.set(frame.rgba);
        zoneState.ctx.putImageData(zoneState.frameImageData, 0, 0);
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
