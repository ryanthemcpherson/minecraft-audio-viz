/**
 * ZoneManager - Zone settings, stage/zone hierarchy, zone chips,
 * render mode, particle viz, and zone config management.
 */

import { debounce } from '../utils/debounce.js';
import { ModalDialog } from '../ui/ModalDialog.js';

export class ZoneManager {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;
    }

    // === Zone Patterns Helpers ===

    getZonePatternId(zoneName) {
        const entry = this.state.zonePatterns[zoneName];
        if (!entry) return null;
        return typeof entry === 'object' ? entry.pattern : entry;
    }

    getZoneRenderMode(zoneName) {
        const entry = this.state.zonePatterns[zoneName];
        if (entry && typeof entry === 'object') return entry.render_mode || 'block';
        return 'block';
    }

    syncBitmapStateFromZonePatterns() {
        const zp = this.state.zonePatterns;
        if (!zp) return;
        const bitmapZones = this.state.bitmap.zones || (this.state.bitmap.zones = {});
        for (const [zoneName, info] of Object.entries(zp)) {
            const rm = typeof info === 'object' ? info.render_mode : null;
            if (rm === 'bitmap') {
                this.state.bitmap.initializedZones.add(zoneName);
                const patId = typeof info === 'object' ? info.pattern : info;
                bitmapZones[zoneName] = {
                    ...bitmapZones[zoneName],
                    initialized: true,
                    pattern: patId || bitmapZones[zoneName]?.pattern || 'bmp_spectrum',
                };
            } else {
                this.state.bitmap.initializedZones.delete(zoneName);
                delete bitmapZones[zoneName];
            }
        }
        this.state.bitmap.initialized = this.state.bitmap.initializedZones.size > 0;
        this.app.preview?.syncBitmapZones();
    }

    // === Zone Event Listeners ===

    setupZoneEventListeners() {
        const sendZoneConfig = debounce(() => this.sendZoneConfig(), 100);

        // Render mode buttons
        this.elements.renderModeButtons.forEach(btn => {
            btn.addEventListener('click', () => this._setRenderMode(btn.dataset.mode));
        });

        // Particle visualization config
        this._setupParticleVizListeners();

        // Banner settings
        this.app.banner.setupBannerListeners();

        // Entity count
        this.app.ui.setupZoneControl('zone-entity-count', 'val-entity-count', (val) => {
            this.state.zone.entityCount = val;
            sendZoneConfig();
        }, (val) => `${val}`);

        // Block type
        if (this.elements.zoneBlockType) {
            this.elements.zoneBlockType.addEventListener('change', () => {
                this.state.zone.blockType = this.elements.zoneBlockType.value;
                sendZoneConfig();
            });
        }

        // Base scale
        this.app.ui.setupZoneControl('zone-base-scale', 'val-base-scale', (val) => {
            this.state.zone.baseScale = val / 100;
            sendZoneConfig();
        }, (val) => (val / 100).toFixed(2));

        // Max scale
        this.app.ui.setupZoneControl('zone-max-scale', 'val-max-scale', (val) => {
            this.state.zone.maxScale = val / 100;
            sendZoneConfig();
        }, (val) => (val / 100).toFixed(2));

        // Brightness
        this.app.ui.setupZoneControl('zone-brightness', 'val-brightness', (val) => {
            this.state.zone.brightness = val;
            sendZoneConfig();
        }, (val) => `${val}`);

        // Interpolation
        this.app.ui.setupZoneControl('zone-interpolation', 'val-interpolation', (val) => {
            this.state.zone.interpolation = val;
            sendZoneConfig();
        }, (val) => `${val} ticks`);

        // Zone size controls
        this.app.ui.setupZoneControl('zone-size-x', 'val-size-x', (val) => {
            this.state.zone.sizeX = val;
            sendZoneConfig();
        }, (val) => `${val}`);

        this.app.ui.setupZoneControl('zone-size-y', 'val-size-y', (val) => {
            this.state.zone.sizeY = val;
            sendZoneConfig();
        }, (val) => `${val}`);

        this.app.ui.setupZoneControl('zone-size-z', 'val-size-z', (val) => {
            this.state.zone.sizeZ = val;
            sendZoneConfig();
        }, (val) => `${val}`);

        // Rotation
        this.app.ui.setupZoneControl('zone-rotation', 'val-rotation', (val) => {
            this.state.zone.rotation = val;
            sendZoneConfig();
        }, (val) => `${val}\u00b0`);

        // Toggle switches
        this.app.ui.setupToggle('zone-glow-beat', 'glowOnBeat', sendZoneConfig);
        this.app.ui.setupToggle('zone-dynamic-brightness', 'dynamicBrightness', sendZoneConfig);
        this.app.ui.setupToggle('zone-show-bpm', 'showBpm', sendZoneConfig);
        this.app.ui.setupToggle('zone-show-pattern', 'showPattern', sendZoneConfig);
        this.app.ui.setupToggle('zone-show-bands', 'showBands', sendZoneConfig);

        // Band material overrides
        const sendBandMaterials = debounce(() => {
            const materials = [];
            for (let i = 0; i < 5; i++) {
                const el = document.getElementById(`band-material-${i}`);
                materials.push(el && el.value ? el.value : null);
            }
            this.state.bandMaterials = materials;
            this.ws.send({ type: 'set_band_materials', materials });
            this.state.bandMaterialsSource = 'admin';
            this.app.audio.updateBandMaterialsSourceHint();
        }, 100);

        for (let i = 0; i < 5; i++) {
            const el = document.getElementById(`band-material-${i}`);
            if (el) el.addEventListener('change', sendBandMaterials);
        }

        // Zone action buttons
        if (this.elements.btnReinitPool) {
            this.elements.btnReinitPool.addEventListener('click', () => this._reinitPool());
        }
        if (this.elements.btnCleanupZone) {
            this.elements.btnCleanupZone.addEventListener('click', () => this._cleanupZone());
        }
        if (this.elements.btnResetDefaults) {
            this.elements.btnResetDefaults.addEventListener('click', () => this._resetZoneDefaults());
        }

        // Stage selector
        if (this.elements.stageSelect) {
            this.elements.stageSelect.addEventListener('change', () => {
                this.state.selectedStage = this.elements.stageSelect.value || this.state.selectedStage;
                this.state.selectedZones.clear();
                const zones = this.state.allZones || [];
                const filtered = this.state.selectedStage
                    ? zones.filter(z => z.stage === this.state.selectedStage)
                    : zones;
                filtered.forEach(z => this.state.selectedZones.add(z.name));
                this.updateZoneSelector();
                this.renderStageZoneList();
                this.renderZoneChips();
                this.app.patterns.updatePatternHighlightForZones();
            });
        }

        // Zone selector
        if (this.elements.zoneSelect) {
            this.elements.zoneSelect.addEventListener('change', () => {
                this.state.zone.name = this.elements.zoneSelect.value;
                this.setZoneControlsLoading(true);
                this.requestZoneStatus();
            });
        }

        // Zone quick-select buttons
        document.querySelectorAll('.zone-quick-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                this.quickSelectZones(btn.dataset.select);
            });
        });
    }

    // === Zone Config ===

    sendZoneConfig() {
        const zone = this.state.zone;
        this.ws.send({
            type: 'set_zone_config',
            zone: zone.name,
            config: {
                entity_count: zone.entityCount,
                block_type: zone.blockType,
                base_scale: zone.baseScale,
                max_scale: zone.maxScale,
                brightness: zone.brightness,
                interpolation: zone.interpolation,
                glow_on_beat: zone.glowOnBeat,
                dynamic_brightness: zone.dynamicBrightness,
                size: { x: zone.sizeX, y: zone.sizeY, z: zone.sizeZ },
                rotation: zone.rotation,
                text_overlay: {
                    show_bpm: zone.showBpm,
                    show_pattern: zone.showPattern,
                    show_bands: zone.showBands
                }
            }
        });
    }

    requestZoneStatus() {
        this.ws.send({ type: 'get_zone', zone: this.state.zone.name });
    }

    setZoneControlsLoading(loading) {
        const controls = [
            'zone-entity-count', 'zone-block-type', 'zone-base-scale', 'zone-max-scale',
            'zone-brightness', 'zone-interpolation', 'zone-size-x', 'zone-size-y',
            'zone-size-z', 'zone-rotation', 'zone-glow-beat', 'zone-dynamic-brightness',
            'zone-show-bpm', 'zone-show-pattern', 'zone-show-bands'
        ];

        controls.forEach(id => {
            const el = document.getElementById(id);
            if (el) el.disabled = loading;
        });

        const zonePanel = document.getElementById('workspace-zones');
        if (zonePanel) zonePanel.classList.toggle('loading', loading);
    }

    _reinitPool() {
        if (!this.state.minecraftConnected) {
            this.app.ui.showToast('Minecraft not connected', 'warning');
            return;
        }
        const zone = this.state.zone;
        this.ws.send({
            type: 'init_pool',
            zone: zone.name,
            count: zone.entityCount,
            material: zone.blockType,
            brightness: zone.brightness,
            interpolation: zone.interpolation
        });
        this.app.ui.showToast('Reinitializing entity pool...', 'info');
    }

    async _cleanupZone() {
        if (!this.state.minecraftConnected) {
            this.app.ui.showToast('Minecraft not connected', 'warning');
            return;
        }
        if (await ModalDialog.confirm('Cleanup Zone', 'This will remove all entities in the zone. Continue?', { destructive: true })) {
            this.ws.send({ type: 'cleanup_zone', zone: this.state.zone.name });
            this.app.ui.showToast('Cleaning up zone...', 'info');
        }
    }

    _resetZoneDefaults() {
        this.state.zone = {
            ...this.state.zone,
            entityCount: 16,
            blockType: 'SEA_LANTERN',
            baseScale: 0.5,
            maxScale: 1.0,
            brightness: 15,
            interpolation: 2,
            glowOnBeat: false,
            dynamicBrightness: false,
            sizeX: 10,
            sizeY: 10,
            sizeZ: 10,
            rotation: 0,
            showBpm: false,
            showPattern: false,
            showBands: false
        };
        this.syncZoneUI();
        this.sendZoneConfig();
    }

    syncZoneUI() {
        const zone = this.state.zone;
        const ui = this.app.ui;

        ui.setSliderValue('zone-entity-count', 'val-entity-count', zone.entityCount, v => `${v}`);
        ui.setSliderValue('zone-base-scale', 'val-base-scale', zone.baseScale * 100, v => (v / 100).toFixed(2));
        ui.setSliderValue('zone-max-scale', 'val-max-scale', zone.maxScale * 100, v => (v / 100).toFixed(2));
        ui.setSliderValue('zone-brightness', 'val-brightness', zone.brightness, v => `${v}`);
        ui.setSliderValue('zone-interpolation', 'val-interpolation', zone.interpolation, v => `${v} ticks`);
        ui.setSliderValue('zone-size-x', 'val-size-x', zone.sizeX, v => `${v}`);
        ui.setSliderValue('zone-size-y', 'val-size-y', zone.sizeY, v => `${v}`);
        ui.setSliderValue('zone-size-z', 'val-size-z', zone.sizeZ, v => `${v}`);
        ui.setSliderValue('zone-rotation', 'val-rotation', zone.rotation, v => `${v}\u00b0`);

        if (this.elements.zoneBlockType) {
            this.elements.zoneBlockType.value = zone.blockType;
        }

        ui.setToggleValue('zone-glow-beat', zone.glowOnBeat);
        ui.setToggleValue('zone-dynamic-brightness', zone.dynamicBrightness);
        ui.setToggleValue('zone-show-bpm', zone.showBpm);
        ui.setToggleValue('zone-show-pattern', zone.showPattern);
        ui.setToggleValue('zone-show-bands', zone.showBands);
    }

    handleZoneStatus(data) {
        this.setZoneControlsLoading(false);

        if (data.zone) {
            const z = data.zone;
            this.state.zone = {
                name: z.name || 'main',
                entityCount: data.entity_count || 16,
                blockType: z.block_type || 'SEA_LANTERN',
                baseScale: z.base_scale || 0.5,
                maxScale: z.max_scale || 1.0,
                brightness: z.brightness || 15,
                interpolation: z.interpolation || 2,
                glowOnBeat: z.glow_on_beat || false,
                dynamicBrightness: z.dynamic_brightness || false,
                sizeX: z.size?.x || 10,
                sizeY: z.size?.y || 10,
                sizeZ: z.size?.z || 10,
                rotation: z.rotation || 0,
                showBpm: z.text_overlay?.show_bpm || false,
                showPattern: z.text_overlay?.show_pattern || false,
                showBands: z.text_overlay?.show_bands || false,
                renderMode: z.render_mode || 'entities',
                particleViz: z.particle_viz || this.state.zone.particleViz
            };
            this.syncZoneUI();
            this._updateRenderModeUI(this.state.zone.renderMode);
        }
    }

    // === Render Mode ===

    _setRenderMode(mode) {
        this.state.zone.renderMode = mode;
        this._updateRenderModeUI(mode);
        this.ws.send({
            type: 'set_render_mode',
            zone: this.state.zone.name,
            mode: mode
        });
    }

    _updateRenderModeUI(mode) {
        this.elements.renderModeButtons.forEach(btn => {
            btn.classList.toggle('active', btn.dataset.mode === mode);
        });

        const showEntities = mode === 'entities' || mode === 'hybrid';
        const showParticles = mode === 'particles' || mode === 'hybrid';

        if (this.elements.entityConfigSection) {
            this.elements.entityConfigSection.style.display = showEntities ? 'block' : 'none';
        }
        if (this.elements.particleVizSection) {
            this.elements.particleVizSection.classList.toggle('hidden', !showParticles);
        }
        if (this.elements.bedrockNotice) {
            this.elements.bedrockNotice.classList.toggle('hidden', !showParticles);
        }
    }

    // === Particle Viz ===

    _setupParticleVizListeners() {
        const sendParticleVizConfig = debounce(() => this._sendParticleVizConfig(), 100);

        if (this.elements.particleVizType) {
            this.elements.particleVizType.addEventListener('change', () => {
                this.state.zone.particleViz.particleType = this.elements.particleVizType.value;
                sendParticleVizConfig();
            });
        }

        if (this.elements.particleVizDensity) {
            this.elements.particleVizDensity.addEventListener('input', () => {
                const val = parseInt(this.elements.particleVizDensity.value);
                document.getElementById('val-particle-density').textContent = `${val}x`;
                this.state.zone.particleViz.density = val;
                sendParticleVizConfig();
            });
        }

        if (this.elements.particleVizColor) {
            this.elements.particleVizColor.addEventListener('change', () => {
                const mode = this.elements.particleVizColor.value;
                this.state.zone.particleViz.colorMode = mode;
                if (this.elements.fixedColorRow) {
                    this.elements.fixedColorRow.classList.toggle('hidden', mode !== 'fixed');
                }
                sendParticleVizConfig();
            });
        }

        if (this.elements.particleVizFixedColor) {
            this.elements.particleVizFixedColor.addEventListener('input', () => {
                this.state.zone.particleViz.fixedColor = this.elements.particleVizFixedColor.value;
                sendParticleVizConfig();
            });
        }

        if (this.elements.particleVizSize) {
            this.elements.particleVizSize.addEventListener('input', () => {
                const val = parseInt(this.elements.particleVizSize.value);
                document.getElementById('val-particle-size').textContent = (val / 10).toFixed(1);
                this.state.zone.particleViz.particleSize = val / 10;
                sendParticleVizConfig();
            });
        }

        if (this.elements.particleVizTrail) {
            this.elements.particleVizTrail.addEventListener('change', () => {
                this.state.zone.particleViz.trail = this.elements.particleVizTrail.checked;
                sendParticleVizConfig();
            });
        }
    }

    _sendParticleVizConfig() {
        const pv = this.state.zone.particleViz;
        this.ws.send({
            type: 'set_particle_viz_config',
            zone: this.state.zone.name,
            config: {
                particle_type: pv.particleType,
                density: pv.density,
                color_mode: pv.colorMode,
                fixed_color: pv.fixedColor,
                particle_size: pv.particleSize,
                trail: pv.trail
            }
        });
    }

    // === Zone List & Stages ===

    handleZonesList(data) {
        const zones = data.zones || [];
        const nextZoneNames = new Set(zones.map(zone => zone.name));

        if (zones.length > 0 && !zones[0].stage) {
            const names = zones.map(z => z.name);
            let prefix = names[0];
            for (let i = 1; i < names.length; i++) {
                while (prefix && !names[i].startsWith(prefix + '_')) {
                    const lastUnderscore = prefix.lastIndexOf('_');
                    prefix = lastUnderscore > 0 ? prefix.substring(0, lastUnderscore) : '';
                }
            }
            if (prefix) {
                zones.forEach(z => { z.stage = prefix; });
            }
        }

        this.state.allZones = zones;
        for (const zoneName of [...this.state.bitmap.initializedZones]) {
            if (!nextZoneNames.has(zoneName)) {
                this.state.bitmap.initializedZones.delete(zoneName);
                delete this.state.bitmap.zones?.[zoneName];
            }
        }
        this.state.bitmap.initialized = this.state.bitmap.initializedZones.size > 0;
        this.app.preview?.reconcileBitmapZoneInventory(nextZoneNames);

        const zonePatterns = this.state.zonePatterns || {};
        const patterns = this.state.patterns || [];
        if (patterns.length > 0) {
            const patternMap = {};
            patterns.forEach(p => { patternMap[p.id] = p; });
            zones.forEach(z => {
                if (!z.entity_count || z.entity_count === 0) {
                    const zp = zonePatterns[z.name];
                    const pat = (zp && typeof zp === 'object' ? zp.pattern : zp) || this.state.currentPattern;
                    const info = pat && patternMap[pat];
                    if (info && info.recommended_entities) {
                        z.entity_count = info.recommended_entities;
                    }
                }
            });
        }

        if (zones.length > 0) {
            const stageNames = [...new Set(zones.map(z => z.stage).filter(Boolean))];
            if (stageNames.length > 0) {
                this.handleStagesList({ stages: stageNames.map(n => ({ name: n })) });
            }
        }

        this.updateZoneSelector();
        this.renderStageZoneList();

        if (this.state.selectedZones.size === 0 && zones.length > 0) {
            const selectedStage = this.state.selectedStage;
            const filtered = selectedStage
                ? zones.filter(z => z.stage === selectedStage)
                : zones;
            filtered.forEach(z => this.state.selectedZones.add(z.name));
        }

        this.renderZoneChips();
        this.app.bitmap.updateZoneSelector();

        if (this.app.preview?.previewInitialized) {
            this.app.preview.rebuildZoneLayout();
        }
    }

    handleStagesList(data) {
        this.state.stages = data.stages || [];

        if (this.elements.stageSelect) {
            while (this.elements.stageSelect.firstChild) {
                this.elements.stageSelect.removeChild(this.elements.stageSelect.firstChild);
            }

            this.state.stages.forEach(stage => {
                const option = document.createElement('option');
                option.value = stage.name;
                option.textContent = this.app.ui.formatStageName(stage.name);
                this.elements.stageSelect.appendChild(option);
            });

            const stageNames = this.state.stages.map(s => s.name);
            if (!this.state.selectedStage || !stageNames.includes(this.state.selectedStage)) {
                this.state.selectedStage = stageNames.length > 0 ? stageNames[0] : null;
            }

            if (this.state.selectedStage) {
                this.elements.stageSelect.value = this.state.selectedStage;
            }
        }

        this.updateZoneSelector();
        this.renderStageZoneList();
        this.renderZoneChips();
    }

    updateZoneSelector() {
        if (!this.elements.zoneSelect) return;
        const zones = this.state.allZones || [];
        const selectedStage = this.state.selectedStage;

        while (this.elements.zoneSelect.firstChild) {
            this.elements.zoneSelect.removeChild(this.elements.zoneSelect.firstChild);
        }

        const filtered = selectedStage
            ? zones.filter(z => z.stage === selectedStage)
            : zones;

        filtered.forEach(zone => {
            const option = document.createElement('option');
            option.value = zone.name;
            option.textContent = zone.stage_role
                ? `${zone.name} (${zone.stage_role})`
                : zone.name;
            this.elements.zoneSelect.appendChild(option);
        });

        const currentExists = filtered.some(z => z.name === this.state.zone.name);
        if (currentExists) {
            this.elements.zoneSelect.value = this.state.zone.name;
        } else if (filtered.length > 0) {
            this.elements.zoneSelect.value = filtered[0].name;
            this.state.zone.name = filtered[0].name;
        }
    }

    // === Zone Chip Bar ===

    renderZoneChips() {
        const bar = this.elements.zoneChipBar;
        if (!bar) return;

        while (bar.firstChild) {
            bar.removeChild(bar.firstChild);
        }

        const zones = this.state.allZones || [];
        const selectedStage = this.state.selectedStage;
        const filtered = selectedStage
            ? zones.filter(z => z.stage === selectedStage)
            : zones;

        if (filtered.length === 0) {
            const empty = document.createElement('span');
            empty.className = 'zone-chip-empty';
            empty.textContent = 'No zones';
            empty.style.color = 'var(--text-muted)';
            empty.style.fontSize = 'var(--font-size-xs)';
            bar.appendChild(empty);
            return;
        }

        filtered.forEach(zone => {
            const chip = document.createElement('button');
            chip.className = 'zone-chip';
            chip.dataset.zone = zone.name;

            if (this.state.selectedZones.has(zone.name)) {
                chip.classList.add('selected');
            }

            const nameSpan = document.createElement('span');
            nameSpan.className = 'zone-chip-name';
            nameSpan.textContent = this.app.ui.formatZoneDisplayName(zone.name, zone.stage);
            chip.appendChild(nameSpan);

            const zp = this.state.zonePatterns[zone.name];
            const patId = zp && typeof zp === 'object' ? zp.pattern : zp;
            const renderMode = zp && typeof zp === 'object' ? zp.render_mode : 'block';

            const modeBadge = document.createElement('span');
            modeBadge.className = 'zone-chip-mode';
            modeBadge.textContent = renderMode === 'bitmap' ? 'B' : '3D';
            modeBadge.title = renderMode === 'bitmap' ? 'Bitmap mode' : 'Block entity mode';
            chip.appendChild(modeBadge);

            const patternSpan = document.createElement('span');
            patternSpan.className = 'zone-chip-pattern';
            patternSpan.textContent = patId || '--';
            chip.appendChild(patternSpan);

            chip.addEventListener('click', (e) => {
                if (e.ctrlKey || e.metaKey) {
                    if (this.state.selectedZones.has(zone.name)) {
                        this.state.selectedZones.delete(zone.name);
                    } else {
                        this.state.selectedZones.add(zone.name);
                    }
                } else {
                    this.state.selectedZones.clear();
                    this.state.selectedZones.add(zone.name);
                    this.state.zone.name = zone.name;
                    if (this.elements.zoneSelect) {
                        this.elements.zoneSelect.value = zone.name;
                    }
                    if (this.elements.bitmapZone) {
                        this.elements.bitmapZone.value = zone.name;
                    }
                    this.setZoneControlsLoading(true);
                    this.requestZoneStatus();
                }
                this.renderZoneChips();
                this.app.patterns.updatePatternHighlightForZones();
            });

            bar.appendChild(chip);
        });
    }

    quickSelectZones(action) {
        const zones = this.state.allZones || [];
        const selectedStage = this.state.selectedStage;
        const filtered = selectedStage
            ? zones.filter(z => z.stage === selectedStage)
            : zones;

        this.state.selectedZones.clear();

        if (action === 'all') {
            filtered.forEach(z => this.state.selectedZones.add(z.name));
        }

        this.renderZoneChips();
        this.app.patterns.updatePatternHighlightForZones();
    }

    // === Stage/Zone Hierarchy List ===

    renderStageZoneList() {
        const container = this.elements.stageZoneList;
        if (!container) return;

        while (container.firstChild) {
            container.removeChild(container.firstChild);
        }

        const stages = this.state.stages || [];
        const allZones = this.state.allZones || [];
        const selectedStage = this.state.selectedStage;

        const stageZoneMap = new Map();
        const standaloneZones = [];

        allZones.forEach(zone => {
            if (zone.stage) {
                if (!stageZoneMap.has(zone.stage)) {
                    stageZoneMap.set(zone.stage, []);
                }
                stageZoneMap.get(zone.stage).push(zone);
            } else {
                standaloneZones.push(zone);
            }
        });

        const stagesToRender = selectedStage
            ? stages.filter(s => s.name === selectedStage)
            : stages;

        if (stagesToRender.length === 0 && standaloneZones.length === 0 && allZones.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'zone-empty';
            empty.textContent = 'No stages or zones found';
            container.appendChild(empty);
            return;
        }

        stagesToRender.forEach(stage => {
            const stageEl = document.createElement('div');
            stageEl.className = 'stage-item';
            if (stage.active) stageEl.classList.add('active');

            const stageHeader = document.createElement('div');
            stageHeader.className = 'stage-header';

            const stageInfo = document.createElement('div');
            stageInfo.className = 'stage-info';

            const stageName = document.createElement('span');
            stageName.className = 'stage-name';
            stageName.textContent = stage.name;

            const stageMeta = document.createElement('span');
            stageMeta.className = 'stage-meta';
            const stageZones2 = stageZoneMap.get(stage.name) || [];
            const zoneCount = stageZones2.length || stage.zone_count || Object.keys(stage.zones || {}).length;
            const totalEntities = stageZones2.reduce((sum, z) => sum + (z.entity_count || 0), 0) || stage.total_entities || 0;
            stageMeta.textContent = `${stage.template || 'custom'} | ${zoneCount} zone${zoneCount !== 1 ? 's' : ''} | ${totalEntities} entities`;

            stageInfo.appendChild(stageName);
            stageInfo.appendChild(stageMeta);

            const expandIcon = document.createElement('span');
            expandIcon.className = 'stage-expand-icon';
            expandIcon.textContent = '\u25B6';

            stageHeader.appendChild(expandIcon);
            stageHeader.appendChild(stageInfo);
            stageEl.appendChild(stageHeader);

            const zoneContainer = document.createElement('div');
            zoneContainer.className = 'stage-zones hidden';

            const stageZones = stageZoneMap.get(stage.name) || [];
            if (stageZones.length === 0 && stage.zones) {
                Object.entries(stage.zones).forEach(([role, zoneInfo]) => {
                    stageZones.push({
                        name: zoneInfo.zone_name,
                        stage: stage.name,
                        stage_role: role,
                        entity_count: zoneInfo.entity_count || 0,
                        display_name: zoneInfo.display_name || role
                    });
                });
            }

            stageZones.forEach(zone => {
                const zoneEl = this._createZoneItem(zone);
                zoneContainer.appendChild(zoneEl);
            });

            if (stageZones.length === 0) {
                const noZones = document.createElement('div');
                noZones.className = 'zone-empty zone-empty-nested';
                noZones.textContent = 'No zones in this stage';
                zoneContainer.appendChild(noZones);
            }

            stageEl.appendChild(zoneContainer);

            stageHeader.addEventListener('click', () => {
                const isHidden = zoneContainer.classList.contains('hidden');
                zoneContainer.classList.toggle('hidden');
                expandIcon.textContent = isHidden ? '\u25BC' : '\u25B6';
                stageEl.classList.toggle('expanded', isHidden);
            });

            container.appendChild(stageEl);
        });

        if (!selectedStage && standaloneZones.length > 0) {
            const standaloneHeader = document.createElement('div');
            standaloneHeader.className = 'standalone-zones-header';
            standaloneHeader.textContent = 'Standalone Zones';
            container.appendChild(standaloneHeader);

            standaloneZones.forEach(zone => {
                const zoneEl = this._createZoneItem(zone);
                container.appendChild(zoneEl);
            });
        }
    }

    _createZoneItem(zone) {
        const item = document.createElement('div');
        item.className = 'zone-item';
        if (zone.name === this.state.zone.name) {
            item.classList.add('active');
        }

        const info = document.createElement('div');
        info.className = 'zone-info';

        const name = document.createElement('span');
        name.className = 'zone-name';
        name.textContent = zone.display_name || zone.name;

        const meta = document.createElement('span');
        meta.className = 'zone-meta';
        const rolePart = zone.stage_role ? `${zone.stage_role} | ` : '';
        meta.textContent = `${rolePart}${zone.entity_count || 0} entities`;

        info.appendChild(name);
        info.appendChild(meta);

        const actions = document.createElement('div');
        actions.className = 'zone-item-actions';

        if (zone.name === this.state.zone.name) {
            const badge = document.createElement('span');
            badge.className = 'zone-badge-active';
            badge.textContent = 'ACTIVE';
            actions.appendChild(badge);
        } else {
            const btn = document.createElement('button');
            btn.className = 'btn btn-small';
            btn.textContent = 'Select';
            btn.addEventListener('click', () => {
                this.state.zone.name = zone.name;
                if (this.elements.zoneSelect) {
                    this.elements.zoneSelect.value = zone.name;
                }
                this.setZoneControlsLoading(true);
                this.requestZoneStatus();
                this.ws.send({ type: 'get_zones' });
                this.app.ui.showToast(`Switched to zone "${zone.name}"`, 'info');
            });
            actions.appendChild(btn);
        }

        item.appendChild(info);
        item.appendChild(actions);
        return item;
    }
}
