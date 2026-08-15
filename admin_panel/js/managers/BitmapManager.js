/**
 * BitmapManager - Bitmap LED Wall controls and rendering
 * Manages bitmap patterns, palettes, transitions, effects,
 * DJ logo, marquee, layers, and composition.
 */

import { debounce } from '../utils/debounce.js';

export class BitmapManager {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;
    }

    // === Initialization ===

    initControls() {
        const el = this.elements;

        // Advanced panel toggle
        const advToggle = document.getElementById('btn-bitmap-advanced');
        const advPanel = document.getElementById('bitmap-advanced-panel');
        if (advToggle && advPanel) {
            advToggle.addEventListener('click', () => {
                const open = advPanel.style.display !== 'none';
                advPanel.style.display = open ? 'none' : '';
                advToggle.classList.toggle('open', !open);
            });
        }

        // Auto-size checkbox toggles manual dimension inputs
        if (el.bitmapAutoSize) {
            el.bitmapAutoSize.addEventListener('change', () => {
                if (el.bitmapManualDims) {
                    el.bitmapManualDims.style.display = el.bitmapAutoSize.checked ? 'none' : '';
                }
            });
        }

        // Re-init button (in advanced panel)
        if (el.btnBitmapInit) {
            el.btnBitmapInit.addEventListener('click', () => {
                const zone = el.bitmapZone?.value || 'main';
                const autoSize = el.bitmapAutoSize?.checked ?? true;
                const msg = {
                    type: 'init_bitmap',
                    zone,
                    pattern: this.state.bitmap.activePattern || 'bmp_spectrum'
                };
                if (!autoSize) {
                    msg.width = parseInt(el.bitmapWidth?.value) || 16;
                    msg.height = parseInt(el.bitmapHeight?.value) || 12;
                }
                this.ws.send(msg);
            });
        }

        // Transition duration slider
        if (el.bitmapTransitionDuration) {
            el.bitmapTransitionDuration.addEventListener('input', () => {
                const val = parseInt(el.bitmapTransitionDuration.value);
                const display = document.getElementById('val-bitmap-transition-duration');
                if (display) display.textContent = `${val} ticks`;
            });
        }

        // Brightness slider (debounced)
        if (el.bitmapBrightness) {
            const sendBrightness = debounce((val) => {
                this.ws.send({
                    type: 'bitmap_effects',
                    action: 'brightness',
                    zone: el.bitmapZone?.value || 'main',
                    level: val / 100
                });
            }, 50);

            el.bitmapBrightness.addEventListener('input', () => {
                const val = parseInt(el.bitmapBrightness.value);
                const display = document.getElementById('val-bitmap-brightness');
                if (display) display.textContent = `${val}%`;
                this.state.bitmap.brightness = val;
                sendBrightness(val);
                const bitmapPreview = this.app.preview?.bitmapPreview;
                if (bitmapPreview) bitmapPreview.effects.brightness = val / 100;
            });
        }

        // Strobe toggle
        if (el.bitmapStrobe) {
            el.bitmapStrobe.addEventListener('change', () => {
                this.ws.send({
                    type: 'bitmap_effects',
                    action: 'strobe',
                    zone: el.bitmapZone?.value || 'main',
                    enabled: el.bitmapStrobe.checked
                });
            });
        }

        // Beat Flash toggle
        if (el.bitmapBeatFlash) {
            el.bitmapBeatFlash.addEventListener('change', () => {
                this.ws.send({
                    type: 'bitmap_effects',
                    action: 'beat_flash',
                    zone: el.bitmapZone?.value || 'main',
                    enabled: el.bitmapBeatFlash.checked
                });
            });
        }

        // Bloom toggle
        if (el.bitmapBloom) {
            el.bitmapBloom.addEventListener('change', () => {
                this.ws.send({
                    type: 'set_bloom',
                    enabled: el.bitmapBloom.checked
                });
            });
        }

        // Bloom strength slider (debounced)
        if (el.bitmapBloomStrength) {
            const sendBloomStrength = debounce((val) => {
                this.ws.send({
                    type: 'set_bloom',
                    strength: val / 100
                });
            }, 50);

            el.bitmapBloomStrength.addEventListener('input', () => {
                const val = parseInt(el.bitmapBloomStrength.value);
                const display = document.getElementById('val-bitmap-bloom-strength');
                if (display) display.textContent = `${val}%`;
                sendBloomStrength(val);
            });
        }

        // Ambient lights toggle
        if (el.bitmapAmbientLights) {
            el.bitmapAmbientLights.addEventListener('change', () => {
                this.ws.send({
                    type: 'set_ambient_lights',
                    enabled: el.bitmapAmbientLights.checked
                });
            });
        }

        // Wash color + opacity (debounced)
        const sendWash = debounce(() => {
            if (!el.bitmapWashColor || !el.bitmapWashOpacity) return;
            const color = this._hexToArgbInt(el.bitmapWashColor.value);
            const opacity = parseInt(el.bitmapWashOpacity.value) / 100;
            this.ws.send({
                type: 'bitmap_effects',
                action: 'wash',
                zone: el.bitmapZone?.value || 'main',
                color,
                opacity
            });
        }, 50);

        if (el.bitmapWashColor) el.bitmapWashColor.addEventListener('input', sendWash);
        if (el.bitmapWashOpacity) {
            el.bitmapWashOpacity.addEventListener('input', () => {
                const val = parseInt(el.bitmapWashOpacity.value);
                const display = document.getElementById('val-bitmap-wash-opacity');
                if (display) display.textContent = `${val}%`;
                sendWash();
            });
        }

        // Effect buttons
        this._setupEffectButtons(el);

        // Text & Overlays
        this._setupTextOverlays(el);

        // Layers
        this._setupLayers(el);

        // Bitmap zone selector
        if (el.bitmapZone) {
            el.bitmapZone.addEventListener('change', () => {
                this.state.bitmap.zone = el.bitmapZone.value;
                const bitmapPreview = this.app.preview?.bitmapPreview;
                if (bitmapPreview) {
                    const selected = el.bitmapZone.value;
                    for (const zoneName of Object.keys(bitmapPreview.zones)) {
                        bitmapPreview.setZoneVisible(zoneName, zoneName === selected);
                    }
                }
            });
        }

        // Composition: Sync Mode
        if (el.bitmapSyncMode) {
            el.bitmapSyncMode.addEventListener('change', () => {
                this.ws.send({
                    type: 'bitmap_composition',
                    action: 'set_sync_mode',
                    mode: el.bitmapSyncMode.value
                });
            });
        }

        // Composition: Shared Palette
        if (el.bitmapSharedPalette) {
            el.bitmapSharedPalette.addEventListener('change', () => {
                const val = el.bitmapSharedPalette.value;
                this.ws.send({
                    type: 'bitmap_composition',
                    action: 'set_shared_palette',
                    palette: val || 'none'
                });
            });
        }
    }

    _setupEffectButtons(el) {
        const bitmapPreview = () => this.app.preview?.bitmapPreview;

        const effectBtns = {
            'btn-bitmap-blackout': () => {
                this.state.bitmap.blackout = !this.state.bitmap.blackout;
                this.ws.send({ type: 'bitmap_effects', action: 'blackout', zone: el.bitmapZone?.value || 'main', enabled: this.state.bitmap.blackout });
                const btn = document.getElementById('btn-bitmap-blackout');
                if (btn) btn.classList.toggle('firing', this.state.bitmap.blackout);
                if (bitmapPreview()) bitmapPreview().effects.blackout = this.state.bitmap.blackout;
            },
            'btn-bitmap-freeze': () => {
                this.state.bitmap.frozen = !this.state.bitmap.frozen;
                this.ws.send({
                    type: 'bitmap_effects',
                    action: 'freeze',
                    zone: el.bitmapZone?.value || 'main',
                    enabled: this.state.bitmap.frozen
                });
                const btn = document.getElementById('btn-bitmap-freeze');
                if (btn) btn.classList.toggle('firing', this.state.bitmap.frozen);
                if (bitmapPreview()) bitmapPreview().effects.frozen = this.state.bitmap.frozen;
            },
            'btn-bitmap-reset': () => {
                this.ws.send({ type: 'bitmap_effects', action: 'reset', zone: el.bitmapZone?.value || 'main' });
                if (el.bitmapBrightness) el.bitmapBrightness.value = 100;
                const brightDisplay = document.getElementById('val-bitmap-brightness');
                if (brightDisplay) brightDisplay.textContent = '100%';
                if (el.bitmapStrobe) el.bitmapStrobe.checked = false;
                if (el.bitmapBeatFlash) el.bitmapBeatFlash.checked = false;
                if (el.bitmapWashOpacity) el.bitmapWashOpacity.value = 0;
                const washDisplay = document.getElementById('val-bitmap-wash-opacity');
                if (washDisplay) washDisplay.textContent = '0%';
                this.state.bitmap.frozen = false;
                this.state.bitmap.blackout = false;
                const freezeBtn = document.getElementById('btn-bitmap-freeze');
                if (freezeBtn) freezeBtn.classList.remove('firing');
                const blackoutBtn = document.getElementById('btn-bitmap-blackout');
                if (blackoutBtn) blackoutBtn.classList.remove('firing');
                if (bitmapPreview()) {
                    bitmapPreview().effects.brightness = 1.0;
                    bitmapPreview().effects.blackout = false;
                    bitmapPreview().effects.frozen = false;
                    bitmapPreview().effects.washOpacity = 0;
                }
            },
            'btn-bitmap-firework': () => {
                this.ws.send({ type: 'bitmap_firework' });
            },
            'btn-bitmap-flash-all': () => {
                this.ws.send({ type: 'bitmap_composition', action: 'flash_all' });
            }
        };

        Object.entries(effectBtns).forEach(([id, handler]) => {
            const btn = document.getElementById(id);
            if (btn) btn.addEventListener('click', handler);
        });
    }

    _setupTextOverlays(el) {
        const btnMarquee = document.getElementById('btn-bitmap-marquee');
        if (btnMarquee) {
            btnMarquee.addEventListener('click', () => {
                const text = document.getElementById('bitmap-marquee-text')?.value;
                const colorHex = document.getElementById('bitmap-marquee-color')?.value;
                if (!text) return;
                this.ws.send({
                    type: 'bitmap_marquee',
                    zone: el.bitmapZone?.value || 'main',
                    text,
                    color: this._hexToArgbInt(colorHex)
                });
            });
        }

        const btnTrack = document.getElementById('btn-bitmap-track');
        if (btnTrack) {
            btnTrack.addEventListener('click', () => {
                const artist = document.getElementById('bitmap-track-artist')?.value || '';
                const title = document.getElementById('bitmap-track-title')?.value || '';
                this.ws.send({
                    type: 'bitmap_track_display',
                    zone: el.bitmapZone?.value || 'main',
                    artist,
                    title
                });
            });
        }

        const btnCountdownStart = document.getElementById('btn-bitmap-countdown-start');
        if (btnCountdownStart) {
            btnCountdownStart.addEventListener('click', () => {
                const seconds = parseInt(document.getElementById('bitmap-countdown-seconds')?.value) || 10;
                this.ws.send({
                    type: 'bitmap_countdown',
                    zone: el.bitmapZone?.value || 'main',
                    action: 'start',
                    seconds
                });
            });
        }

        const btnCountdownStop = document.getElementById('btn-bitmap-countdown-stop');
        if (btnCountdownStop) {
            btnCountdownStop.addEventListener('click', () => {
                this.ws.send({
                    type: 'bitmap_countdown',
                    zone: el.bitmapZone?.value || 'main',
                    action: 'stop'
                });
            });
        }

        const btnChat = document.getElementById('btn-bitmap-chat');
        if (btnChat) {
            btnChat.addEventListener('click', () => {
                const message = document.getElementById('bitmap-chat-message')?.value;
                if (!message) return;
                this.ws.send({
                    type: 'bitmap_chat',
                    zone: el.bitmapZone?.value || 'main',
                    player: 'VJ',
                    message
                });
                const input = document.getElementById('bitmap-chat-message');
                if (input) input.value = '';
            });
        }
    }

    _setupLayers(el) {
        const btnLayerSet = document.getElementById('btn-bitmap-layer-set');
        if (btnLayerSet) {
            btnLayerSet.addEventListener('click', () => {
                const pattern = el.bitmapLayerPattern?.value;
                if (!pattern) return;
                this.ws.send({
                    type: 'bitmap_layer',
                    zone: el.bitmapZone?.value || 'main',
                    action: 'set',
                    pattern,
                    blend_mode: el.bitmapLayerBlend?.value || 'ADDITIVE',
                    opacity: (parseInt(el.bitmapLayerOpacity?.value) || 50) / 100
                });
            });
        }

        if (el.bitmapLayerOpacity) {
            el.bitmapLayerOpacity.addEventListener('input', () => {
                const val = parseInt(el.bitmapLayerOpacity.value);
                const display = document.getElementById('val-bitmap-layer-opacity');
                if (display) display.textContent = `${val}%`;
            });
        }

        const btnLayerClear = document.getElementById('btn-bitmap-layer-clear');
        if (btnLayerClear) {
            btnLayerClear.addEventListener('click', () => {
                this.ws.send({
                    type: 'bitmap_layer',
                    zone: el.bitmapZone?.value || 'main',
                    action: 'clear'
                });
            });
        }
    }

    // === Data Fetching ===

    fetchBitmapData() {
        this.ws.send({ type: 'get_bitmap_patterns' });
        this.ws.send({ type: 'get_bitmap_transitions' });
        this.ws.send({ type: 'get_bitmap_palettes' });
        this.state.bitmap.dataFetched = true;
    }

    // === Rendering ===

    renderPatterns() {
        const grid = this.elements.bitmapPatternGrid;
        if (!grid) return;

        while (grid.firstChild) grid.removeChild(grid.firstChild);

        this.state.bitmap.patterns.forEach(pattern => {
            const btn = document.createElement('button');
            btn.className = 'pattern-btn';
            btn.dataset.pattern = pattern.id || pattern;
            btn.textContent = pattern.name || pattern;
            btn.title = pattern.description || '';

            if ((pattern.id || pattern) === this.state.bitmap.activePattern) {
                btn.classList.add('active');
            }

            btn.addEventListener('click', () => this.setPattern(pattern.id || pattern));
            grid.appendChild(btn);
        });

        this._populateSelectFromList(this.elements.bitmapLayerPattern, this.state.bitmap.patterns, '-- None --');
    }

    renderTransitions() {
        const select = this.elements.bitmapTransition;
        if (!select) return;

        const currentVal = select.value;
        while (select.firstChild) select.removeChild(select.firstChild);

        const instantOpt = document.createElement('option');
        instantOpt.value = 'INSTANT';
        instantOpt.textContent = 'Instant';
        select.appendChild(instantOpt);

        this.state.bitmap.transitions.forEach(t => {
            const opt = document.createElement('option');
            opt.value = t.id || t;
            opt.textContent = t.name || t;
            select.appendChild(opt);
        });

        if (currentVal) select.value = currentVal;
    }

    renderPalettes() {
        const grid = this.elements.bitmapPaletteGrid;
        if (!grid) return;

        while (grid.firstChild) grid.removeChild(grid.firstChild);

        const noneBtn = document.createElement('button');
        noneBtn.className = 'pattern-btn';
        noneBtn.dataset.palette = '';
        noneBtn.dataset.type = 'palette';
        noneBtn.textContent = 'None';
        if (!this.state.bitmap.activePalette) noneBtn.classList.add('active');
        noneBtn.addEventListener('click', () => this.setPalette(''));
        grid.appendChild(noneBtn);

        this.state.bitmap.palettes.forEach(palette => {
            const btn = document.createElement('button');
            btn.className = 'pattern-btn';
            btn.dataset.palette = palette.id || palette;
            btn.dataset.type = 'palette';
            btn.textContent = palette.name || palette;

            if ((palette.id || palette) === this.state.bitmap.activePalette) {
                btn.classList.add('active');
            }

            btn.addEventListener('click', () => this.setPalette(palette.id || palette));
            grid.appendChild(btn);
        });

        this._populateSelectFromList(this.elements.bitmapSharedPalette, this.state.bitmap.palettes, '-- None --');
    }

    // === Actions ===

    setPattern(patternId) {
        const zone = this.elements.bitmapZone?.value || 'main';

        if (!this.state.bitmap.initializedZones.has(zone)) {
            const autoSize = this.elements.bitmapAutoSize?.checked ?? true;
            const msg = {
                type: 'init_bitmap',
                zone,
                pattern: patternId
            };
            if (!autoSize) {
                msg.width = parseInt(this.elements.bitmapWidth?.value) || 16;
                msg.height = parseInt(this.elements.bitmapHeight?.value) || 12;
            }
            this.ws.send(msg);
        } else {
            const transition = this.elements.bitmapTransition?.value;
            const duration = parseInt(this.elements.bitmapTransitionDuration?.value) || 20;

            if (transition && transition !== 'INSTANT') {
                this.ws.send({
                    type: 'bitmap_transition',
                    zone,
                    pattern: patternId,
                    transition,
                    duration_ticks: duration
                });
            } else {
                this.ws.send({
                    type: 'set_bitmap_pattern',
                    zone,
                    pattern: patternId
                });
            }
        }

        this.state.bitmap.activePattern = patternId;
        this.highlightPattern(patternId);
        this.updateDjLogoVisibility(patternId);
    }

    setPalette(paletteId) {
        this.ws.send({
            type: 'bitmap_palette',
            palette: paletteId || 'none'
        });
        this.state.bitmap.activePalette = paletteId || null;
        this.highlightPalette(paletteId);
    }

    // === Highlighting ===

    highlightPattern(patternId) {
        if (!this.elements.bitmapPatternGrid) return;
        this.elements.bitmapPatternGrid.querySelectorAll('.pattern-btn').forEach(btn => {
            const isActive = btn.dataset.pattern === patternId;
            btn.classList.toggle('active', isActive);
            if (isActive) {
                btn.classList.remove('just-selected');
                void btn.offsetWidth;
                btn.classList.add('just-selected');
                setTimeout(() => btn.classList.remove('just-selected'), 400);
            }
        });
    }

    highlightPalette(paletteId) {
        if (!this.elements.bitmapPaletteGrid) return;
        this.elements.bitmapPaletteGrid.querySelectorAll('.pattern-btn').forEach(btn => {
            const isActive = (btn.dataset.palette || '') === (paletteId || '');
            btn.classList.toggle('active', isActive);
        });
    }

    // === Status ===

    updateStatus(data) {
        const statusEl = this.elements.bitmapStatus;
        if (!statusEl) return;

        if (data.active || data.initialized) {
            this.state.bitmap.initialized = true;
            const w = data.width || this.state.bitmap.width;
            const h = data.height || this.state.bitmap.height;
            statusEl.classList.add('active');
            statusEl.title = `Active: ${w}x${h}`;
        } else {
            statusEl.classList.remove('active');
            statusEl.title = 'Not initialized';
        }

        if (data.pattern) {
            this.state.bitmap.activePattern = data.pattern;
            this.highlightPattern(data.pattern);
        }
        if (data.palette) {
            this.state.bitmap.activePalette = data.palette;
            this.highlightPalette(data.palette);
        }
    }

    updateZoneSelector() {
        const select = this.elements.bitmapZone;
        if (!select) return;

        const currentVal = select.value;
        while (select.firstChild) select.removeChild(select.firstChild);

        let zoneNames = [];
        if (this.state.allZones && this.state.allZones.length > 0) {
            zoneNames = this.state.allZones.map(z => z.name);
        } else if (this.state.zonePatterns && Object.keys(this.state.zonePatterns).length > 0) {
            zoneNames = Object.keys(this.state.zonePatterns);
        } else {
            zoneNames = ['main'];
        }

        zoneNames.forEach(name => {
            const opt = document.createElement('option');
            opt.value = name;
            const rm = this.app._getZoneRenderMode(name);
            opt.textContent = rm === 'bitmap' ? `${name} [B]` : name;
            select.appendChild(opt);
        });

        if (currentVal) select.value = currentVal;
    }

    // === DJ Logo ===

    setupDjLogoListeners() {
        const el = this.elements;
        const zone = () => el.bitmapZone?.value || 'main';

        if (el.djLogoModeGrid) {
            el.djLogoModeGrid.querySelectorAll('[data-logo-mode]').forEach(btn => {
                btn.addEventListener('click', () => {
                    this.ws.send({
                        type: 'bitmap_dj_logo',
                        zone: zone(),
                        action: 'set_mode',
                        mode: btn.dataset.logoMode
                    });
                    el.djLogoModeGrid.querySelectorAll('.pattern-btn').forEach(b => b.classList.remove('active'));
                    btn.classList.add('active');
                });
            });
        }

        if (el.djLogoThreshold) {
            let thresholdTimer = null;
            el.djLogoThreshold.addEventListener('input', () => {
                const val = el.djLogoThreshold.value;
                document.getElementById('val-dj-logo-threshold').textContent = val;
                clearTimeout(thresholdTimer);
                thresholdTimer = setTimeout(() => {
                    this.ws.send({
                        type: 'bitmap_dj_logo',
                        zone: zone(),
                        action: 'set_threshold',
                        threshold: parseInt(val)
                    });
                }, 50);
            });
        }

        if (el.btnDjLogoLoad) {
            el.btnDjLogoLoad.addEventListener('click', () => {
                const path = el.djLogoFile?.value?.trim();
                if (!path) return;
                this._djLogoLoaded = true;
                this.ws.send({
                    type: 'bitmap_dj_logo',
                    zone: zone(),
                    action: 'load_file',
                    path
                });
            });
        }
    }

    updateDjLogoVisibility(patternId) {
        const section = this.elements.djLogoSection;
        if (!section) return;
        if (patternId === 'bmp_dj_logo') {
            section.classList.remove('hidden');
            section.classList.remove('collapsed');
            const path = this.elements.djLogoFile?.value?.trim();
            if (path && !this._djLogoLoaded) {
                this._djLogoLoaded = true;
                const zone = this.elements.bitmapZone?.value || 'main';
                this.ws.send({
                    type: 'bitmap_dj_logo',
                    zone,
                    action: 'load_file',
                    path
                });
            }
        } else {
            section.classList.add('hidden');
        }
    }

    // === Helpers ===

    _hexToArgbInt(hex) {
        if (!hex) return 0xFFFFFFFF;
        const r = parseInt(hex.slice(1, 3), 16);
        const g = parseInt(hex.slice(3, 5), 16);
        const b = parseInt(hex.slice(5, 7), 16);
        return ((0xFF << 24) | (r << 16) | (g << 8) | b) | 0;
    }

    _populateSelectFromList(select, items, placeholder) {
        if (!select) return;
        const currentVal = select.value;
        while (select.firstChild) select.removeChild(select.firstChild);

        if (placeholder) {
            const opt = document.createElement('option');
            opt.value = '';
            opt.textContent = placeholder;
            select.appendChild(opt);
        }

        items.forEach(item => {
            const opt = document.createElement('option');
            opt.value = item.id || item;
            opt.textContent = item.name || item;
            select.appendChild(opt);
        });

        if (currentVal) select.value = currentVal;
    }
}
