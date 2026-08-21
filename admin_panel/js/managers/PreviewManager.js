/**
 * PreviewManager - 3D Three.js preview for audio visualization
 * Manages scene, camera, renderer, block entities, multi-zone layout,
 * stage blocks, bitmap preview, and particle effects.
 */

export class PreviewManager {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;

        // 3D Preview state
        this._initialized = false;
        this._stripCollapsed = localStorage.getItem('mcav-preview-collapsed') === 'true';
        this._presentationMode = 'hidden';
        this._scene = null;
        this._camera = null;
        this._renderer = null;
        this._blocks = [];
        this._particleSystem = null;
        this._blockIndicators = null;
        this._failed = false;
        this._autoRotate = false;
        this._showGrid = false;
        this._animationId = null;
        this._lastFrameTime = 0;
        this._fps = 60;
        this._frameCount = 0;
        this._lastFpsUpdate = 0;
        this._lastBeatTime = 0;
        this._particleEffects = {
            enabled: true,
            bassFlame: true,
            soulFire: true,
            beatRing: true,
            notes: false,
            dust: false
        };
        this._threeLoadInFlight = false;

        // Multi-zone preview state
        this._zoneGroups = {};
        this._stageCenter = { x: 0, y: 0, z: 0 };
        this._stageBounds = null;
        this._stageMode = false;

        // Preview config
        this._config = {
            blockSize: 0.8,
            zoneSize: 10,
            centerOffset: 5,
            colors: [
                0xff9100,  // bass - orange
                0xffea00,  // low - yellow
                0x00e676,  // mid - green
                0x00b0ff,  // high - blue
                0xd500f9   // air - magenta
            ]
        };

        // Stage blocks state
        this._stageBlocksGroup = null;
        this._stageBlockData = null;
        this._stageBlocksScanned = false;
        this._stageGround = null;

        // Bitmap preview
        this._bitmapPreview = null;
        this._pendingBitmapFrames = new Map();

        // Texture manager
        this._textureManager = null;

        // Pre-created materials
        this._bandColorMaterials = null;

        // Reusable spherical
        this._spherical = null;
    }

    // === Accessors for AdminApp compat ===

    get previewInitialized() { return this._initialized; }
    get previewFailed() { return this._failed; }
    get previewStripCollapsed() { return this._stripCollapsed; }
    get previewStageMode() { return this._stageMode; }
    get previewZoneGroups() { return this._zoneGroups; }
    get previewConfig() { return this._config; }
    get bitmapPreview() { return this._bitmapPreview; }
    get stageBlocksScanned() { return this._stageBlocksScanned; }
    set stageBlocksScanned(v) { this._stageBlocksScanned = v; }

    // === Script Loading ===

    _loadThreeScript(src) {
        return new Promise((resolve, reject) => {
            const existing = Array.from(document.scripts).find(s => s.src && s.src.includes(src));
            if (existing && typeof THREE !== 'undefined') {
                resolve(true);
                return;
            }

            const script = document.createElement('script');
            script.src = src;
            script.async = true;
            script.onload = () => resolve(true);
            script.onerror = () => reject(new Error(`Failed to load ${src}`));
            document.head.appendChild(script);
        });
    }

    async _ensureThreeLoaded() {
        if (typeof THREE !== 'undefined') return true;
        if (this._threeLoadInFlight) return false;
        this._threeLoadInFlight = true;
        try {
            await this._loadThreeScript('js/vendor/three-r128.min.js');
            if (typeof THREE !== 'undefined') return true;
        } catch (_) {
            // fall through to CDN
        }

        try {
            await this._loadThreeScript('https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js');
            return typeof THREE !== 'undefined';
        } catch (_) {
            return false;
        } finally {
            this._threeLoadInFlight = false;
        }
    }

    // === Strip / Init ===

    setPresentationMode(workspace) {
        const supportsPreview = workspace === 'live' || workspace === 'visuals' || workspace === 'zones';
        const slot = supportsPreview
            ? document.querySelector(`[data-preview-slot="${workspace}"]`)
            : null;
        const visible = Boolean(slot);
        this._presentationMode = workspace === 'live' && visible
            ? 'live'
            : (visible ? 'compact' : 'hidden');
        const strip = this.elements.previewStrip;

        if (slot && strip?.parentElement !== slot) {
            slot.append(strip);
        }

        if (this._canAnimate()) {
            this.startAnimation();
            requestAnimationFrame(() => this._onResize());
        } else {
            this.stopAnimation();
        }
    }

    initPreviewStrip() {
        const strip = document.getElementById('preview-strip');
        const collapseBtn = document.getElementById('preview-strip-collapse');
        if (!strip) return;

        if (this._stripCollapsed) {
            strip.classList.add('collapsed');
            if (collapseBtn) {
                collapseBtn.querySelector('.collapse-arrow').textContent = '\u25B2';
            }
        }

        if (collapseBtn) {
            collapseBtn.addEventListener('click', () => {
                this._stripCollapsed = !this._stripCollapsed;
                strip.classList.toggle('collapsed', this._stripCollapsed);
                collapseBtn.querySelector('.collapse-arrow').textContent =
                    this._stripCollapsed ? '\u25B2' : '\u25BC';
                localStorage.setItem('mcav-preview-collapsed', String(this._stripCollapsed));

                if (this._stripCollapsed) {
                    this.stopAnimation();
                } else if (this._canAnimate()) {
                    this._onResize();
                    this.startAnimation();
                }
            });
        }

        const body = strip.querySelector('.preview-strip-body');
        if (body) {
            body.addEventListener('transitionend', () => {
                if (!this._stripCollapsed) {
                    this._onResize();
                }
            });
        }
    }

    async initPreview() {
        if (this._initialized || this._failed) return;

        const canvas = document.getElementById('preview-canvas');
        const wrapper = canvas?.parentElement;
        if (!canvas || !wrapper) {
            console.warn('[Preview] Canvas not found');
            return;
        }

        if (typeof THREE === 'undefined') {
            console.warn('[Preview] Three.js not loaded');
            const loaded = await this._ensureThreeLoaded();
            if (!loaded || typeof THREE === 'undefined') {
                this._failed = true;
                this.app.ui.showToast('Three.js failed to load; 3D Preview disabled', 'warning');
                return;
            }
        }

        try {
            this._scene = new THREE.Scene();
            this._scene.background = new THREE.Color(0x0a0a0f);
            this._scene.fog = new THREE.Fog(0x0a0a0f, 20, 50);

            this._spherical = new THREE.Spherical();

            const width = Math.max(1, wrapper.clientWidth);
            const height = Math.max(1, wrapper.clientHeight);
            const aspect = width / height;
            this._camera = new THREE.PerspectiveCamera(60, aspect, 0.1, 100);
            this._camera.position.set(12, 10, 12);
            this._camera.lookAt(0, 2, 0);

            this._renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
            this._renderer.setSize(width, height);
            this._renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
            this._renderer.shadowMap.enabled = true;

            const ambientLight = new THREE.AmbientLight(0x404040, 0.5);
            this._scene.add(ambientLight);

            const directionalLight = new THREE.DirectionalLight(0xffffff, 0.8);
            directionalLight.position.set(10, 20, 10);
            directionalLight.castShadow = true;
            directionalLight.shadow.mapSize.width = 2048;
            directionalLight.shadow.mapSize.height = 2048;
            this._scene.add(directionalLight);

            const pointLight = new THREE.PointLight(0x6366f1, 0.5, 20);
            pointLight.position.set(0, 5, 0);
            this._scene.add(pointLight);

            if (typeof BlockTextureManager !== 'undefined') {
                this._textureManager = new BlockTextureManager();
                this._textureManager.preload();
            }

            this._bandColorMaterials = this._config.colors.map(color =>
                new THREE.MeshStandardMaterial({
                    color: color,
                    roughness: 0.3,
                    metalness: 0.2,
                    emissive: new THREE.Color(color),
                    emissiveIntensity: 0.2
                })
            );

            this._createBlocks(16);

            if (typeof ParticleSystem !== 'undefined') {
                this._particleSystem = new ParticleSystem(this._scene, 500);
            }

            if (typeof BlockIndicatorSystem !== 'undefined') {
                this._blockIndicators = new BlockIndicatorSystem(this._scene, 8);
                this._blockIndicators.setVisible(false);
            }

            if (typeof BitmapPreview !== 'undefined') {
                this._bitmapPreview = new BitmapPreview();
                for (const frame of this._pendingBitmapFrames.values()) {
                    this._bitmapPreview.ingestFrame(frame);
                }
                this._pendingBitmapFrames.clear();
            }

            this._setupControls();
            this._setupMouseControls();

            this._boundOnResize = () => this._onResize();
            window.addEventListener('resize', this._boundOnResize);

            this._initialized = true;
            this._lastFrameTime = performance.now();

            if ((this.state.allZones || []).length > 0) {
                this.rebuildZoneLayout();
            }
        } catch (error) {
            this._failed = true;
            this._initialized = false;
            this._renderer = null;
            this._scene = null;
            this._camera = null;
            console.error('[Preview] Initialization failed', error);
            this.app.ui.showToast('3D Preview initialization failed; disabled for this session', 'warning');
        }
    }

    // === Block Creation ===

    _createBlock(index) {
        const config = this._config;
        const geometry = new THREE.BoxGeometry(config.blockSize, config.blockSize, config.blockSize);
        const bandIndex = index % 5;
        const material = new THREE.MeshStandardMaterial({
            color: config.colors[bandIndex],
            roughness: 0.3,
            metalness: 0.2,
            emissive: config.colors[bandIndex],
            emissiveIntensity: 0.2
        });

        const block = new THREE.Mesh(geometry, material);
        block.castShadow = true;
        block.receiveShadow = true;

        block.userData.bandIndex = bandIndex;
        block.userData.targetX = 0;
        block.userData.targetY = 0;
        block.userData.targetZ = 0;
        block.userData.targetScale = 1;

        return block;
    }

    _createBlocks(count) {
        for (let i = 0; i < count; i++) {
            const block = this._createBlock(i);
            block.position.set(0, 0.5, 0);
            this._scene.add(block);
            this._blocks.push(block);
        }
    }

    _ensureBlockCount(count) {
        while (this._blocks.length < count) {
            const block = this._createBlock(this._blocks.length);
            block.position.set(0, 0.5, 0);
            this._scene.add(block);
            this._blocks.push(block);
        }

        while (this._blocks.length > count) {
            const block = this._blocks.pop();
            this._scene.remove(block);
            block.geometry.dispose();
            block.material.dispose();
        }
    }

    // === Multi-Zone Methods ===

    _computeStageLayout() {
        const zones = this.state.allZones || [];
        if (zones.length === 0) {
            this._stageCenter = { x: 0, y: 0, z: 0 };
            this._stageBounds = null;
            return;
        }

        let minX = Infinity, minY = Infinity, minZ = Infinity;
        let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;

        zones.forEach(zone => {
            const ox = zone.origin?.x || 0;
            const oy = zone.origin?.y || 0;
            const oz = zone.origin?.z || 0;
            const sx = zone.size?.x || 10;
            const sy = zone.size?.y || 10;
            const sz = zone.size?.z || 10;

            minX = Math.min(minX, ox);
            minY = Math.min(minY, oy);
            minZ = Math.min(minZ, oz);
            maxX = Math.max(maxX, ox + sx);
            maxY = Math.max(maxY, oy + sy);
            maxZ = Math.max(maxZ, oz + sz);
        });

        const mainZone = zones.find(z => {
            const role = (z.stage_role || z.name || '').toLowerCase();
            return role.includes('main') || role.includes('center');
        });
        if (mainZone) {
            const mox = mainZone.origin?.x || 0;
            const moy = mainZone.origin?.y || 0;
            const moz = mainZone.origin?.z || 0;
            const msx = mainZone.size?.x || 10;
            const msy = mainZone.size?.y || 10;
            const msz = mainZone.size?.z || 10;
            this._stageCenter = {
                x: mox + msx / 2,
                y: moy + msy / 2,
                z: moz + msz / 2
            };
        } else {
            this._stageCenter = {
                x: (minX + maxX) / 2,
                y: (minY + maxY) / 2,
                z: (minZ + maxZ) / 2
            };
        }
        this._stageBounds = { minX, minY, minZ, maxX, maxY, maxZ };
    }

    _getZoneWireframeColor(zone) {
        const role = (zone.stage_role || zone.name || '').toLowerCase();
        if (role.includes('main') || role.includes('center')) return 0x00D4FF;
        if (role.includes('left') || role.includes('wing_l')) return 0xFF9100;
        if (role.includes('right') || role.includes('wing_r')) return 0x00E676;
        if (role.includes('sky') || role.includes('ceiling')) return 0xD500F9;
        if (role.includes('audience') || role.includes('floor')) return 0xFFD700;
        return 0x4488AA;
    }

    _ensureZoneGroup(zoneName) {
        if (this._zoneGroups[zoneName]) return this._zoneGroups[zoneName];

        const zones = this.state.allZones || [];
        const zone = zones.find(z => z.name === zoneName);
        if (!zone) return null;

        const group = new THREE.Group();
        group.name = `zone-${zoneName}`;

        const ox = (zone.origin?.x || 0) - this._stageCenter.x;
        const oy = (zone.origin?.y || 0) - this._stageCenter.y;
        const oz = (zone.origin?.z || 0) - this._stageCenter.z;
        const sx = zone.size?.x || 10;
        const sy = zone.size?.y || 10;
        const sz = zone.size?.z || 10;

        group.position.set(ox, oy, oz);
        group.rotation.y = -(zone.rotation || 0) * (Math.PI / 180);

        const boxGeo = new THREE.BoxGeometry(sx, sy, sz);
        const edgesGeo = new THREE.EdgesGeometry(boxGeo);
        const wireColor = this._getZoneWireframeColor(zone);
        const lineMat = new THREE.LineBasicMaterial({ color: wireColor, opacity: 0.35, transparent: true });
        const wireframe = new THREE.LineSegments(edgesGeo, lineMat);
        wireframe.position.set(sx / 2, sy / 2, sz / 2);
        group.add(wireframe);
        boxGeo.dispose();

        this._scene.add(group);

        const zoneGroup = {
            group,
            blocks: [],
            wireframe,
            sizeX: sx,
            sizeY: sy,
            sizeZ: sz,
            zone
        };
        this._zoneGroups[zoneName] = zoneGroup;
        return zoneGroup;
    }

    _ensureZoneBlockCount(zoneGroup, count) {
        while (zoneGroup.blocks.length < count) {
            const block = this._createBlock(zoneGroup.blocks.length);
            block.position.set(0, 0, 0);
            block.visible = true;
            zoneGroup.group.add(block);
            zoneGroup.blocks.push(block);
        }
        for (let i = 0; i < zoneGroup.blocks.length; i++) {
            zoneGroup.blocks[i].visible = i < count;
        }
    }

    _updateBlockMaterial(block, entity, bands, config) {
        const rawBand = Number.isFinite(entity.band) ? entity.band : 0;
        const bandIndex = Math.max(0, Math.min(4, Math.round(rawBand)));
        block.userData.bandIndex = bandIndex;

        const entityMaterial = entity.material || '';
        if (this._textureManager && entityMaterial && entityMaterial !== block.userData.currentMaterial) {
            const texMat = this._textureManager.getMaterial(entityMaterial);
            if (texMat) {
                block.material.dispose();
                block.material = texMat.clone();
                block.userData.currentMaterial = entityMaterial;
            }
        } else if (!entityMaterial && block.userData.currentMaterial) {
            block.material.dispose();
            if (this._bandColorMaterials) {
                block.material = this._bandColorMaterials[bandIndex].clone();
            }
            block.userData.currentMaterial = '';
        } else if (!entityMaterial && !block.userData.currentMaterial) {
            block.material.color.setHex(config.colors[bandIndex]);
            block.material.emissive.setHex(config.colors[bandIndex]);
        }

        const bandValue = Number.isFinite(bands[bandIndex]) ? bands[bandIndex] : 0;
        block.material.emissiveIntensity = 0.3 + bandValue * 1.0;
    }

    rebuildZoneLayout() {
        if (!this._initialized || !this._scene) return;

        this._disposeStageBlocks();

        for (const [name, zg] of Object.entries(this._zoneGroups)) {
            if (this._bitmapPreview) this._bitmapPreview.deactivate(name);
            zg.blocks.forEach(block => {
                block.geometry.dispose();
                if (block.material && block.material !== this._bandColorMaterials?.[block.userData.bandIndex]) {
                    block.material.dispose();
                }
            });
            zg.wireframe.geometry.dispose();
            zg.wireframe.material.dispose();
            this._scene.remove(zg.group);
        }
        this._zoneGroups = {};

        this._computeStageLayout();

        const zones = this.state.allZones || [];
        const hasMultipleZones = zones.length > 1 && zones.some(z => z.origin);

        const scanBtn = document.getElementById('preview-scan-stage');

        if (hasMultipleZones) {
            this._stageMode = true;
            if (this._blockIndicators) {
                this._blockIndicators.setVisible(false);
            }
            zones.forEach(z => this._ensureZoneGroup(z.name));

            this._frameStage();

            if (scanBtn) scanBtn.style.display = '';

            if (!this._stageBlocksScanned && this.state.selectedStage
                && this.ws && this.ws.isConnected
                && this.state.minecraftConnected) {
                this._stageBlocksScanned = true;
                this.scanStageBlocks();
            }
        } else {
            this._stageMode = false;
            if (this._blockIndicators) {
                this._blockIndicators.setVisible(this._showGrid);
            }
            if (scanBtn) scanBtn.style.display = 'none';
            if (this._stageGround) {
                this._scene.remove(this._stageGround);
                this._stageGround.geometry.dispose();
                this._stageGround.material.dispose();
                this._stageGround = null;
            }
        }

        this.syncBitmapZones();
    }

    /** Synchronize bitmap planes from the latest server-authoritative zone state. */
    syncBitmapZones() {
        const initializedZones = this.state.bitmap.initializedZones || new Set();
        for (const zoneName of this._pendingBitmapFrames?.keys() || []) {
            if (!initializedZones.has(zoneName)) this.purgeBitmapZone(zoneName);
        }

        if (!this._bitmapPreview) return;

        const retainedZones = new Set([
            ...Object.keys(this._bitmapPreview.zones),
            ...Object.keys(this._bitmapPreview.exactFrames),
            ...Object.keys(this._bitmapPreview.frameBuffers),
            ...Object.keys(this._bitmapPreview.pendingPatterns),
        ]);

        for (const zoneName of retainedZones) {
            if (!initializedZones.has(zoneName)) this.purgeBitmapZone(zoneName);
        }

        if (!this._initialized || !this._scene) return;

        for (const zoneName of initializedZones) {
            const zoneGroup = this._zoneGroups[zoneName] || this._ensureZoneGroup(zoneName);
            if (!zoneGroup) continue;

            const zoneState = this.state.bitmap.zones?.[zoneName] || {};
            const patternEntry = this.state.zonePatterns?.[zoneName];
            const pattern = zoneState.pattern
                || (patternEntry && typeof patternEntry === 'object' ? patternEntry.pattern : patternEntry)
                || this.state.bitmap.activePattern
                || 'bmp_plasma';
            const width = zoneState.width || this.state.bitmap.width || 16;
            const height = zoneState.height || this.state.bitmap.height || 12;
            const activeZone = this._bitmapPreview.zones[zoneName];

            if (!activeZone || activeZone.width !== width || activeZone.height !== height) {
                this._bitmapPreview.activate(zoneName, width, height, pattern, zoneGroup);
            } else {
                this._bitmapPreview.setPattern(zoneName, pattern);
            }
            this._bitmapPreview.setZoneVisible(zoneName, true);
        }

        const zones = this.state.allZones || [];
        if (zones.length <= 1 && initializedZones.size > 0) {
            this._blocks.forEach(block => { block.visible = false; });
        }
    }

    /** Permanently release bitmap resources for a removed or block-mode zone. */
    purgeBitmapZone(zoneName) {
        this._bitmapPreview?.purgeZone(zoneName);
        this._pendingBitmapFrames?.delete(zoneName);
    }

    /** Purge retained bitmap data after an authoritative zone inventory update. */
    reconcileBitmapZoneInventory(knownZoneNames) {
        const retainedZones = new Set(this._pendingBitmapFrames?.keys() || []);
        if (this._bitmapPreview) {
            Object.keys(this._bitmapPreview.zones).forEach(name => retainedZones.add(name));
            Object.keys(this._bitmapPreview.exactFrames).forEach(name => retainedZones.add(name));
            Object.keys(this._bitmapPreview.frameBuffers).forEach(name => retainedZones.add(name));
            Object.keys(this._bitmapPreview.pendingPatterns).forEach(name => retainedZones.add(name));
        }
        for (const zoneName of retainedZones) {
            if (!knownZoneNames.has(zoneName)) this.purgeBitmapZone(zoneName);
        }
    }

    handleBitmapFrame(data) {
        if (this._bitmapPreview) {
            return this._bitmapPreview.ingestFrame(data);
        }
        if (data && typeof data.zone === 'string' && data.zone) {
            if (!this._pendingBitmapFrames) this._pendingBitmapFrames = new Map();
            this._pendingBitmapFrames.set(data.zone, data);
        }
        return false;
    }

    _frameStage() {
        if (!this._camera || !this._stageBounds) return;

        const bounds = this._stageBounds;
        const spanX = bounds.maxX - bounds.minX;
        const spanY = bounds.maxY - bounds.minY;
        const spanZ = bounds.maxZ - bounds.minZ;
        const maxSpan = Math.max(spanX, spanY, spanZ);

        const distance = maxSpan * 1.2;
        const angle = Math.PI / 4;

        this._camera.position.set(
            distance * Math.sin(angle),
            distance * 0.6,
            distance * Math.cos(angle)
        );
        this._camera.lookAt(0, 0, 0);

        this._camera.far = Math.max(100, distance * 4);
        this._camera.updateProjectionMatrix();

        if (this._scene.fog) {
            this._scene.fog.far = Math.max(50, distance * 3);
        }
    }

    // === Stage Block Scanning ===

    scanStageBlocks() {
        if (!this.state.selectedStage) {
            this.app.ui.showToast('No stage selected', 'warning');
            return;
        }
        if (!this.ws || !this.ws.isConnected) {
            this.app.ui.showToast('Not connected', 'error');
            return;
        }
        this.ws.send({
            type: 'scan_stage_blocks',
            stage: this.state.selectedStage
        });
        this.app.ui.showToast('Scanning stage blocks...', 'info');
    }

    requestParityCheck() {
        if (!this.ws || !this.ws.isConnected) {
            this.app.ui.showToast('Not connected', 'error');
            return;
        }
        if (!this.state.minecraftConnected) {
            this.app.ui.showToast('Minecraft not connected', 'error');
            return;
        }
        this.ws.send({ type: 'request_parity_check' });
        this.app.ui.showToast('Running parity check...', 'info');
    }

    handleParityCheckResult(data) {
        if (data.error) {
            this.app.ui.showToast(`Parity check failed: ${data.error}`, 'error');
            return;
        }

        const zones = data.zones || {};
        const zoneNames = Object.keys(zones);
        if (zoneNames.length === 0) {
            this.app.ui.showToast('Parity check: no zones found', 'warning');
            return;
        }

        if (data.ok) {
            this.app.ui.showToast(`Parity check: all ${zoneNames.length} zones OK`, 'success');
            return;
        }

        const issues = [];
        for (const [name, info] of Object.entries(zones)) {
            if (info.ok) continue;
            const mismatches = (info.mismatches || []).join('; ');
            const repaired = (info.repaired || []).join('; ');
            let line = `${name}: ${mismatches}`;
            if (repaired) line += ` [repaired: ${repaired}]`;
            issues.push(line);
        }

        const okCount = zoneNames.filter(zoneName => zones[zoneName].ok).length;
        const message = `Parity: ${okCount}/${zoneNames.length} OK\n${issues.join('\n')}`;
        this.app.ui.showToast(message, issues.some(issue => issue.includes('failed')) ? 'error' : 'warning', 8000);
    }

    handleStageBlocks(data) {
        if (data.error) {
            this.app.ui.showToast(data.error, 'error');
            return;
        }
        this._stageBlockData = data;
        this._renderStageBlocks(data);
        this.app.ui.showToast(`Scanned ${data.blocks.length} blocks`, 'success');
    }

    _renderStageBlocks(data) {
        if (!this._initialized || !this._scene) return;

        this._disposeStageBlocks();
        this._stageBlockData = data;
        this._stageBlocksScanned = true;

        const { palette, blocks } = data;
        if (!palette || !blocks || blocks.length === 0) return;

        this._stageBlocksGroup = new THREE.Group();
        this._stageBlocksGroup.name = 'stage-blocks';

        const center = this._stageCenter || { x: 0, y: 0, z: 0 };

        const blocksByMaterial = new Map();
        for (const [x, y, z, palIdx] of blocks) {
            if (!blocksByMaterial.has(palIdx)) {
                blocksByMaterial.set(palIdx, []);
            }
            blocksByMaterial.get(palIdx).push({ x, y, z });
        }

        const boxGeo = new THREE.BoxGeometry(1, 1, 1);

        for (const [palIdx, positions] of blocksByMaterial) {
            const materialName = palette[palIdx];

            let material = null;
            if (this._textureManager) {
                material = this._textureManager.getEnvironmentMaterial(materialName, 'side');
            }
            if (!material) {
                material = BlockTextureManager.getBlockColor(materialName);
            }

            const mesh = new THREE.InstancedMesh(boxGeo, material, positions.length);
            mesh.receiveShadow = true;

            const matrix = new THREE.Matrix4();
            for (let i = 0; i < positions.length; i++) {
                const p = positions[i];
                matrix.makeTranslation(
                    p.x + 0.5 - center.x,
                    p.y + 0.5 - center.y,
                    p.z + 0.5 - center.z
                );
                mesh.setMatrixAt(i, matrix);
            }
            mesh.instanceMatrix.needsUpdate = true;

            this._stageBlocksGroup.add(mesh);
        }

        this._scene.add(this._stageBlocksGroup);
    }

    _disposeStageBlocks() {
        if (this._stageBlocksGroup) {
            this._stageBlocksGroup.traverse(child => {
                if (child.isMesh) {
                    child.geometry.dispose();
                    if (child.material) child.material.dispose();
                }
            });
            this._scene.remove(this._stageBlocksGroup);
            this._stageBlocksGroup = null;
        }
        this._stageBlockData = null;
        this._stageBlocksScanned = false;
    }

    // === Controls ===

    _setupControls() {
        const resetBtn = document.getElementById('preview-reset-camera');
        if (resetBtn) {
            resetBtn.addEventListener('click', () => this._resetCamera());
        }

        const rotateChk = document.getElementById('preview-auto-rotate');
        if (rotateChk) {
            rotateChk.addEventListener('change', (e) => {
                this._autoRotate = e.target.checked;
            });
        }

        const gridChk = document.getElementById('preview-show-grid');
        if (gridChk) {
            gridChk.addEventListener('change', (e) => {
                this._showGrid = e.target.checked;
                if (this._blockIndicators) {
                    this._blockIndicators.setVisible(this._showGrid);
                }
            });
        }

        const scanBtn = document.getElementById('preview-scan-stage');
        if (scanBtn) {
            scanBtn.addEventListener('click', () => this.scanStageBlocks());
        }

        const parityBtn = document.getElementById('parity-check-btn');
        if (parityBtn) {
            parityBtn.addEventListener('click', () => this.requestParityCheck());
        }

        const particlesChk = document.getElementById('preview-particles-enabled');
        if (particlesChk) {
            particlesChk.addEventListener('change', (e) => {
                this._particleEffects.enabled = e.target.checked;
            });
        }

        const effectMap = {
            'preview-effect-bass-flame': 'bassFlame',
            'preview-effect-soul-fire': 'soulFire',
            'preview-effect-beat-ring': 'beatRing',
            'preview-effect-notes': 'notes',
            'preview-effect-dust': 'dust'
        };

        Object.entries(effectMap).forEach(([id, prop]) => {
            const el = document.getElementById(id);
            if (el) {
                el.addEventListener('change', (e) => {
                    this._particleEffects[prop] = e.target.checked;
                });
            }
        });
    }

    _setupMouseControls() {
        const canvas = document.getElementById('preview-canvas');
        if (!canvas) return;

        let isDragging = false;
        let previousMousePosition = { x: 0, y: 0 };

        canvas.addEventListener('mousedown', (e) => {
            isDragging = true;
            previousMousePosition = { x: e.clientX, y: e.clientY };
        });

        canvas.addEventListener('mousemove', (e) => {
            if (!isDragging || !this._camera) return;

            const deltaX = e.clientX - previousMousePosition.x;
            const deltaY = e.clientY - previousMousePosition.y;

            const spherical = this._spherical;
            spherical.setFromVector3(this._camera.position);
            spherical.theta -= deltaX * 0.01;
            spherical.phi = Math.max(0.1, Math.min(Math.PI - 0.1, spherical.phi + deltaY * 0.01));
            this._camera.position.setFromSpherical(spherical);
            const lookY = this._stageMode ? 0 : 2;
            this._camera.lookAt(0, lookY, 0);

            previousMousePosition = { x: e.clientX, y: e.clientY };
        });

        canvas.addEventListener('mouseup', () => isDragging = false);
        canvas.addEventListener('mouseleave', () => isDragging = false);

        canvas.addEventListener('wheel', (e) => {
            if (!this._camera) return;
            const zoomSpeed = 0.001;
            const distance = this._camera.position.length();
            const newDistance = distance * (1 + e.deltaY * zoomSpeed);
            const maxZoom = this._stageMode ? 200 : 30;
            this._camera.position.normalize().multiplyScalar(Math.max(5, Math.min(maxZoom, newDistance)));
        });
    }

    _resetCamera() {
        if (!this._camera) return;
        if (this._stageMode && this._stageBounds) {
            this._frameStage();
        } else {
            this._camera.position.set(12, 10, 12);
            this._camera.lookAt(0, 2, 0);
        }
    }

    _onResize() {
        if (!this._initialized) return;

        const canvas = document.getElementById('preview-canvas');
        const wrapper = canvas?.parentElement;
        if (!wrapper || !this._camera || !this._renderer) return;

        const width = wrapper.clientWidth;
        const height = wrapper.clientHeight;

        this._camera.aspect = width / height;
        this._camera.updateProjectionMatrix();
        this._renderer.setSize(width, height);
    }

    // === Animation Loop ===

    _canAnimate() {
        return !this._stripCollapsed
            && (this._presentationMode === 'live' || this._presentationMode === 'compact');
    }

    startAnimation() {
        if (!this._initialized || this._failed) return;
        if (!this._canAnimate()) return;
        if (this._animationId) return;
        this._lastFrameTime = performance.now();
        this._animate();
    }

    stopAnimation() {
        if (this._animationId) {
            cancelAnimationFrame(this._animationId);
            this._animationId = null;
        }
    }

    _animate() {
        this._animationId = requestAnimationFrame(() => this._animate());

        if (!this._renderer || !this._scene || !this._camera) return;

        try {
            const now = performance.now();
            const dt = (now - this._lastFrameTime) / 1000;
            this._lastFrameTime = now;

            // FPS calculation
            this._frameCount++;
            if (now - this._lastFpsUpdate >= 1000) {
                this._fps = this._frameCount;
                this._frameCount = 0;
                this._lastFpsUpdate = now;
                const fpsEl = this.elements.previewStatFps;
                if (fpsEl) fpsEl.textContent = this._fps;
            }

            // Smooth block animations (legacy single-zone blocks)
            const lerpSpeed = 0.25;
            this._blocks.forEach((block) => {
                block.position.x += (block.userData.targetX - block.position.x) * lerpSpeed;
                block.position.y += (block.userData.targetY - block.position.y) * lerpSpeed;
                block.position.z += (block.userData.targetZ - block.position.z) * lerpSpeed;

                const targetScale = block.userData.targetScale || 1;
                block.scale.x += (targetScale - block.scale.x) * lerpSpeed;
                block.scale.y += (targetScale - block.scale.y) * lerpSpeed;
                block.scale.z += (targetScale - block.scale.z) * lerpSpeed;
            });

            // Smooth block animations (multi-zone blocks)
            for (const zoneGroup of Object.values(this._zoneGroups)) {
                for (const block of zoneGroup.blocks) {
                    if (!block.visible) continue;
                    block.position.x += (block.userData.targetX - block.position.x) * lerpSpeed;
                    block.position.y += (block.userData.targetY - block.position.y) * lerpSpeed;
                    block.position.z += (block.userData.targetZ - block.position.z) * lerpSpeed;

                    const targetScale = block.userData.targetScale || 1;
                    block.scale.x += (targetScale - block.scale.x) * lerpSpeed;
                    block.scale.y += (targetScale - block.scale.y) * lerpSpeed;
                    block.scale.z += (targetScale - block.scale.z) * lerpSpeed;
                }
            }

            if (this._particleSystem) {
                this._particleSystem.update(dt);
            }

            if (this._bitmapPreview && this.state.bitmap.initialized) {
                this._bitmapPreview.update(dt, {
                    bands: Array.isArray(this.state.bands) ? this.state.bands : [0, 0, 0, 0, 0],
                    amplitude: this.state.amplitude || 0,
                    isBeat: !!this.state.isBeat,
                    beatIntensity: this.state.beatIntensity || 0,
                });
            }

            if (this._autoRotate && this._camera) {
                const spherical = this._spherical;
                spherical.setFromVector3(this._camera.position);
                spherical.theta += 0.0005;
                this._camera.position.setFromSpherical(spherical);
                const lookY = this._stageMode ? 0 : 3;
                this._camera.lookAt(0, lookY, 0);
            }

            this._renderer.render(this._scene, this._camera);
        } catch (error) {
            console.error('[Preview] Render loop failed', error);
            this._failed = true;
            this.stopAnimation();
            this.app.ui.showToast('3D Preview render error; disabled for this session', 'warning');
        }
    }

    // === Audio State Update ===

    updateFromAudioState() {
        if (!this._initialized || this._failed) return;

        try {
            const bands = Array.isArray(this.state.bands) ? this.state.bands : [0, 0, 0, 0, 0];
            const isBeat = !!this.state.isBeat;
            const beatIntensity = Number.isFinite(this.state.beatIntensity) ? this.state.beatIntensity : 0;

            this._updateMeters();

            const zoneEntities = this.state.zoneEntities;
            const hasZoneEntities = this._stageMode && zoneEntities && Object.keys(zoneEntities).length > 0;

            if (hasZoneEntities) {
                this._updateMultiZone(zoneEntities, bands);
                this._blocks.forEach(b => { b.visible = false; });
            } else {
                this._updateSingleZone(bands);
                for (const zg of Object.values(this._zoneGroups)) {
                    zg.blocks.forEach(b => { b.visible = false; });
                    zg.wireframe.visible = false;
                }
            }

            if (this._blockIndicators && this._showGrid) {
                this._blockIndicators.updateFromAudio(bands, isBeat, beatIntensity);
            }

            const beatFlash = document.getElementById('preview-beat-flash');
            if (beatFlash) {
                beatFlash.classList.toggle('active', isBeat);
            }

            if (isBeat && this._particleEffects.enabled) {
                this._spawnBeatParticles();
            }

            if (this._particleEffects.enabled) {
                this._spawnAmbientParticles();
            }

            this._updateStats();
        } catch (error) {
            console.error('[Preview] Update failed', error);
            this._failed = true;
            this.stopAnimation();
            this.app.ui.showToast('3D Preview update failed; disabled for this session', 'warning');
        }
    }

    _updateMultiZone(zoneEntities, bands) {
        const config = this._config;

        for (const [zoneName, entities] of Object.entries(zoneEntities)) {
            if (!Array.isArray(entities)) continue;

            if (this.app.zones.getZoneRenderMode(zoneName) === 'bitmap') {
                const zg = this._zoneGroups[zoneName];
                if (zg) zg.blocks.forEach(b => { b.visible = false; });
                continue;
            }

            const zoneGroup = this._ensureZoneGroup(zoneName);
            if (!zoneGroup) continue;

            zoneGroup.wireframe.visible = true;
            this._ensureZoneBlockCount(zoneGroup, entities.length);

            const sx = zoneGroup.sizeX;
            const sy = zoneGroup.sizeY;
            const sz = zoneGroup.sizeZ;

            for (let i = 0; i < entities.length; i++) {
                const entity = entities[i];
                const block = zoneGroup.blocks[i];
                if (!entity || typeof entity !== 'object' || !block) continue;

                const x = Number.isFinite(entity.x) ? entity.x : 0.5;
                const y = Number.isFinite(entity.y) ? entity.y : 0.0;
                const z = Number.isFinite(entity.z) ? entity.z : 0.5;
                const scale = Number.isFinite(entity.scale) ? entity.scale : 0.5;

                block.userData.targetX = x * sx;
                block.userData.targetY = y * sy;
                block.userData.targetZ = z * sz;
                block.userData.targetScale = scale * 1.5;

                this._updateBlockMaterial(block, entity, bands, config);
            }
        }
    }

    _updateSingleZone(bands) {
        const zones = this.state.allZones || [];
        const zoneName = zones.length === 1 ? zones[0].name : this.state.zone?.name;
        if (zoneName && this.state.bitmap.initializedZones?.has(zoneName)) {
            this._blocks.forEach(block => { block.visible = false; });
            return;
        }

        const entities = Array.isArray(this.state.entities) ? this.state.entities : [];
        if (entities.length === 0) return;

        this._ensureBlockCount(entities.length);
        this._blocks.forEach(b => { b.visible = true; });

        const config = this._config;
        this._blocks.forEach((block, i) => {
            const entity = entities[i];
            if (!entity || typeof entity !== 'object') return;

            const x = Number.isFinite(entity.x) ? entity.x : 0.5;
            const y = Number.isFinite(entity.y) ? entity.y : 0.0;
            const z = Number.isFinite(entity.z) ? entity.z : 0.5;
            const scale = Number.isFinite(entity.scale) ? entity.scale : 0.5;

            block.userData.targetX = (x * config.zoneSize) - config.centerOffset;
            block.userData.targetY = y * config.zoneSize;
            block.userData.targetZ = (z * config.zoneSize) - config.centerOffset;
            block.userData.targetScale = scale * 1.5;

            this._updateBlockMaterial(block, entity, bands, config);
        });
    }

    _updateMeters() {
        for (let i = 0; i < 5; i++) {
            const stripBar = document.getElementById(`strip-band-${i}`);
            if (stripBar) {
                const pct = Math.round(this.state.bands[i] * 100);
                stripBar.style.width = pct + '%';
            }
        }
    }

    _updateStats() {
        const headerBlockCount = document.getElementById('preview-block-count');
        const headerParticleCount = document.getElementById('preview-particle-count');
        if (headerBlockCount && this._blockIndicators) {
            const stats = this._blockIndicators.getStats();
            headerBlockCount.textContent = `${stats.active} blocks`;
        }
        if (headerParticleCount && this._particleSystem) {
            headerParticleCount.textContent = `${this._particleSystem.getActiveCount()} particles`;
        }
    }

    _spawnBeatParticles() {
        if (!this._particleSystem) return;

        const now = performance.now();
        const BEAT_COOLDOWN = 150;
        if (now - this._lastBeatTime < BEAT_COOLDOWN) return;
        this._lastBeatTime = now;

        const bass = this.state.bands[0] || 0;
        const intensity = this.state.beatIntensity || 0.5;

        if (this._particleEffects.bassFlame && bass > 0.3) {
            const count = Math.floor(8 + intensity * 12);
            this._particleSystem.spawn('FLAME', 0, 0.1, 0, count);
            if (bass > 0.6) {
                this._particleSystem.spawn('LAVA', 0, 0.2, 0, Math.floor(count / 3));
            }
        }

        if (this._particleEffects.soulFire && bass > 0.5) {
            const count = Math.floor(5 + intensity * 8);
            this._particleSystem.spawn('SOUL_FIRE_FLAME', 0, 0.1, 0, count);
        }

        if (this._particleEffects.beatRing) {
            const count = Math.floor(16 + intensity * 16);
            this._particleSystem.spawnRing('END_ROD', 0, 0.5, 0, 2, count);
        }
    }

    _spawnAmbientParticles() {
        if (!this._particleSystem) return;

        const high = this.state.bands[3] || 0;

        if (this._particleEffects.notes && high > 0.25) {
            if (Math.random() < high * 0.3) {
                const x = (Math.random() - 0.5) * 6;
                const z = (Math.random() - 0.5) * 6;
                this._particleSystem.spawn('NOTE', x, 1 + Math.random() * 2, z, 1);
            }
        }

        if (this._particleEffects.dust && typeof BAND_COLORS !== 'undefined') {
            for (let i = 0; i < 5; i++) {
                const band = this.state.bands[i] || 0;
                if (band > 0.2 && Math.random() < band * 0.15) {
                    const x = (Math.random() - 0.5) * 8;
                    const z = (Math.random() - 0.5) * 8;
                    const color = BAND_COLORS[i] || [1, 1, 1];
                    this._particleSystem.spawn('DUST', x, 0.5 + Math.random() * 3, z, 1, color);
                }
            }
        }
    }
}
