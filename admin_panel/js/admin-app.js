/**
 * AudioViz Admin Control Panel - Main Application
 * Provides live mixing controls for audio visualization
 */

import { WebSocketService } from './services/WebSocketService.js';
import { debounce, throttle, rafThrottle } from './utils/debounce.js';

class AdminApp {
    constructor() {
        // WebSocket connection - use same host as the page was served from
        const wsHost = window.location.hostname || 'localhost';
        console.log('[AdminApp] Connecting to WebSocket at:', wsHost, ':8766');
        this.ws = new WebSocketService({
            host: wsHost,
            port: 8766
        });

        // Application state
        this.state = {
            connected: false,
            patterns: [],
            currentPattern: null,
            currentPreset: 'auto',
            bands: [0, 0, 0, 0, 0, 0],
            amplitude: 0,
            isBeat: false,
            beatIntensity: 0,
            frame: 0,
            bandSensitivity: [1, 1, 1, 1, 1, 1],
            masterSensitivity: 1,
            attack: 0.35,
            release: 0.08,
            agcMaxGain: 8,
            beatSensitivity: 1,
            beatThreshold: 1.3,
            blockCount: 16,
            blackout: false,
            freeze: false,
            // Particle effects state
            particleEffects: [],
            enabledParticleEffects: new Set(),
            particleGlobalIntensity: 1.0,
            // DJ roster state
            djRoster: [],
            activeDJ: null,
            pendingDJs: [],
            // Service status
            minecraftConnected: false,
            // Connect codes state
            connectCodes: [],
            // Zone settings
            zone: {
                name: 'main',
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
                showBands: false,
                // Render mode: 'entities', 'particles', or 'hybrid'
                renderMode: 'entities',
                // Particle visualization settings (for Bedrock mode)
                particleViz: {
                    particleType: 'DUST',
                    density: 3,
                    colorMode: 'frequency',
                    fixedColor: '#00ffff',
                    particleSize: 1.5,
                    trail: false
                }
            },
            // 3D Preview state
            entities: []
        };

        // Tap tempo tracking
        this.tapTimes = [];
        this.tapTimeout = null;

        // DOM elements cache
        this.elements = {};

        // 3D Preview components (lazy-loaded)
        this._previewInitialized = false;
        this._previewScene = null;
        this._previewCamera = null;
        this._previewRenderer = null;
        this._previewBlocks = [];
        this._previewParticleSystem = null;
        this._previewBlockIndicators = null;
        this._previewAutoRotate = true;
        this._previewShowGrid = true;
        this._previewAnimationId = null;
        this._previewLastFrameTime = 0;
        this._previewFps = 60;
        this._previewFrameCount = 0;
        this._previewLastFpsUpdate = 0;
        this._previewLastBeatTime = 0;
        this._previewParticleEffects = {
            enabled: true,
            bassFlame: true,
            soulFire: true,
            beatRing: true,
            notes: false,
            dust: false
        };

        // Preview config
        this._previewConfig = {
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

        // Initialize
        this._cacheElements();
        this._setupEventListeners();
        this._setupWebSocket();
        this._setupDJQueueDelegation();
        this._setupDJPendingDelegation();

        // Start connection
        this.ws.connect();

        console.log('Admin Panel initialized');
    }

    // === Initialization ===

    _cacheElements() {
        // Status elements
        this.elements.connectionStatus = document.getElementById('connection-status');
        this.elements.currentPattern = document.getElementById('current-pattern');
        this.elements.currentPreset = document.getElementById('current-preset');
        this.elements.bpmEstimate = document.getElementById('bpm-estimate');
        this.elements.frameCount = document.getElementById('frame-count');
        this.elements.beatIndicator = document.getElementById('beat-indicator');
        this.elements.latencyDisplay = document.getElementById('latency-display');
        this.elements.queueDepthDisplay = document.getElementById('queue-depth-display');

        // Meters
        this.elements.meters = [];
        this.elements.meterValues = [];
        for (let i = 0; i < 5; i++) {
            this.elements.meters.push(document.getElementById(`meter-${i}`));
            this.elements.meterValues.push(document.getElementById(`meter-val-${i}`));
        }
        this.elements.meterMaster = document.getElementById('meter-master');
        this.elements.meterMasterValue = document.getElementById('meter-val-master');

        // Pattern grid
        this.elements.patternGrid = document.getElementById('pattern-grid');

        // Preset buttons
        this.elements.presetButtons = document.querySelectorAll('.preset-btn');

        // Faders
        this.elements.faders = document.querySelectorAll('.fader');

        // Controls
        this.elements.ctrlAttack = document.getElementById('ctrl-attack');
        this.elements.ctrlRelease = document.getElementById('ctrl-release');
        this.elements.ctrlAgc = document.getElementById('ctrl-agc');
        this.elements.ctrlBeatSens = document.getElementById('ctrl-beat-sens');
        this.elements.ctrlBeatThresh = document.getElementById('ctrl-beat-thresh');
        this.elements.ctrlBlocks = document.getElementById('ctrl-blocks');

        // Quick actions
        this.elements.btnBlackout = document.getElementById('btn-blackout');
        this.elements.btnFreeze = document.getElementById('btn-freeze');
        this.elements.btnTapTempo = document.getElementById('btn-tap-tempo');
        this.elements.tapBpm = document.getElementById('tap-bpm');

        // Effect triggers
        this.elements.effectButtons = document.querySelectorAll('.effect-btn');

        // Particle effects
        this.elements.particleGlobalIntensity = document.getElementById('particle-global-intensity');
        this.elements.particleBeatEffects = document.getElementById('particle-beat-effects');
        this.elements.particleAmbientEffects = document.getElementById('particle-ambient-effects');

        // Tabs
        this.elements.tabs = document.querySelectorAll('.tab');
        this.elements.tabPanels = document.querySelectorAll('.tab-panel');

        // FPS counter
        this.elements.fpsDisplay = document.getElementById('fps-display');
        this._frameCount = 0;
        this._lastFpsUpdate = Date.now();

        // Audio meter update throttling
        this._meterUpdatePending = false;

        // DJ Queue
        this.elements.djQueue = document.getElementById('dj-queue');

        // Connect Code elements
        this.elements.btnGenerateCode = document.getElementById('btn-generate-code');
        this.elements.activeCodes = document.getElementById('active-codes');
        this.elements.codeModal = document.getElementById('code-modal');
        this.elements.modalCode = document.getElementById('modal-code');
        this.elements.modalTtl = document.getElementById('modal-ttl');
        this.elements.btnCopyCode = document.getElementById('btn-copy-code');
        this.elements.btnCloseModal = document.getElementById('btn-close-modal');

        // Reconnect button
        this.elements.btnReconnect = document.getElementById('btn-reconnect');

        // Service status indicators
        this.elements.svcPython = document.getElementById('svc-python');
        this.elements.svcMinecraft = document.getElementById('svc-minecraft');

        // DJ Pending section
        this.elements.djPendingSection = document.getElementById('dj-pending-section');
        this.elements.djPendingQueue = document.getElementById('dj-pending-queue');

        // Zone/stage list
        this.elements.zoneList = document.getElementById('zone-list');
        this.elements.btnRefreshZones = document.getElementById('btn-refresh-zones');

        // Toast container
        this.elements.toastContainer = document.getElementById('toast-container');

        // Zone settings elements
        this.elements.zoneSelect = document.getElementById('zone-select');
        this.elements.zoneEntityCount = document.getElementById('zone-entity-count');
        this.elements.zoneBlockType = document.getElementById('zone-block-type');
        this.elements.zoneBaseScale = document.getElementById('zone-base-scale');
        this.elements.zoneMaxScale = document.getElementById('zone-max-scale');
        this.elements.zoneBrightness = document.getElementById('zone-brightness');
        this.elements.zoneInterpolation = document.getElementById('zone-interpolation');
        this.elements.zoneGlowBeat = document.getElementById('zone-glow-beat');
        this.elements.zoneDynamicBrightness = document.getElementById('zone-dynamic-brightness');
        this.elements.zoneSizeX = document.getElementById('zone-size-x');
        this.elements.zoneSizeY = document.getElementById('zone-size-y');
        this.elements.zoneSizeZ = document.getElementById('zone-size-z');
        this.elements.zoneRotation = document.getElementById('zone-rotation');
        this.elements.zoneShowBpm = document.getElementById('zone-show-bpm');
        this.elements.zoneShowPattern = document.getElementById('zone-show-pattern');
        this.elements.zoneShowBands = document.getElementById('zone-show-bands');
        this.elements.btnReinitPool = document.getElementById('btn-reinit-pool');
        this.elements.btnCleanupZone = document.getElementById('btn-cleanup-zone');
        this.elements.btnResetDefaults = document.getElementById('btn-reset-defaults');

        // Render mode elements
        this.elements.renderModeButtons = document.querySelectorAll('.render-mode-btn');
        this.elements.bedrockNotice = document.getElementById('bedrock-notice');
        this.elements.entityConfigSection = document.getElementById('entity-config-section');
        this.elements.particleVizSection = document.getElementById('particle-viz-section');

        // Particle visualization elements
        this.elements.particleVizType = document.getElementById('particle-viz-type');
        this.elements.particleVizDensity = document.getElementById('particle-viz-density');
        this.elements.particleVizColor = document.getElementById('particle-viz-color');
        this.elements.particleVizFixedColor = document.getElementById('particle-viz-fixed-color');
        this.elements.fixedColorRow = document.getElementById('fixed-color-row');
        this.elements.particleVizSize = document.getElementById('particle-viz-size');
        this.elements.particleVizTrail = document.getElementById('particle-viz-trail');
    }

    _setupEventListeners() {
        // Tab switching
        this.elements.tabs.forEach(tab => {
            tab.addEventListener('click', () => this._switchTab(tab.dataset.tab));
        });

        // Preset buttons
        this.elements.presetButtons.forEach(btn => {
            btn.addEventListener('click', () => this._setPreset(btn.dataset.preset));
        });

        // Band faders - debounced to prevent message spam
        this.elements.faders.forEach(fader => {
            const input = fader.querySelector('.fader-input');
            const band = fader.dataset.band;

            // Debounce the network send, but update display immediately
            const sendUpdate = debounce((value) => {
                if (band === 'master') {
                    this._sendAllBandSensitivities();
                } else {
                    const bandIndex = parseInt(band);
                    this._sendBandSensitivity(bandIndex, value / 100);
                }
            }, 50);

            input.addEventListener('input', () => {
                const value = parseInt(input.value);
                this._updateFaderDisplay(fader, value);

                if (band === 'master') {
                    this.state.masterSensitivity = value / 100;
                } else {
                    const bandIndex = parseInt(band);
                    this.state.bandSensitivity[bandIndex] = value / 100;
                }

                sendUpdate(value);
            });
        });

        // Audio controls
        this._setupControl('ctrl-attack', 'val-attack', (val) => {
            this.state.attack = val / 100;
            this._sendSetting('attack', val / 100);
        }, (val) => `${val}%`);

        this._setupControl('ctrl-release', 'val-release', (val) => {
            this.state.release = val / 100;
            this._sendSetting('release', val / 100);
        }, (val) => `${val}%`);

        this._setupControl('ctrl-agc', 'val-agc', (val) => {
            this.state.agcMaxGain = val;
            this._sendSetting('agc_max_gain', val);
        }, (val) => `${val}x`);

        this._setupControl('ctrl-beat-sens', 'val-beat-sens', (val) => {
            this.state.beatSensitivity = val / 100;
            this._sendSetting('beat_sensitivity', val / 100);
        }, (val) => `${val}%`);

        this._setupControl('ctrl-beat-thresh', 'val-beat-thresh', (val) => {
            this.state.beatThreshold = val / 100;
            this._sendSetting('beat_threshold', val / 100);
        }, (val) => `${(val / 100).toFixed(2)}x`);

        this._setupControl('ctrl-blocks', 'val-blocks', (val) => {
            this.state.blockCount = val;
            this.ws.send({ type: 'set_block_count', count: val });
        }, (val) => `${val}`);

        // Quick actions
        this.elements.btnBlackout.addEventListener('click', () => this._toggleBlackout());
        this.elements.btnFreeze.addEventListener('click', () => this._toggleFreeze());
        this.elements.btnTapTempo.addEventListener('click', () => this._tapTempo());

        // Effect triggers
        this.elements.effectButtons.forEach(btn => {
            btn.addEventListener('click', () => this._triggerEffect(btn.dataset.effect));
        });

        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => this._handleKeyboard(e));

        // Particle effects global intensity - debounced
        if (this.elements.particleGlobalIntensity) {
            const sendParticleIntensity = debounce((val) => {
                this._sendParticleConfig({ global_intensity: val / 100 });
            }, 50);

            this.elements.particleGlobalIntensity.addEventListener('input', () => {
                const val = parseInt(this.elements.particleGlobalIntensity.value);
                document.getElementById('val-particle-intensity').textContent = `${val}%`;
                sendParticleIntensity(val);
            });
        }

        // Reconnect button
        if (this.elements.btnReconnect) {
            this.elements.btnReconnect.addEventListener('click', () => {
                this.ws.manualReconnect();
            });
        }

        // Refresh zones button
        if (this.elements.btnRefreshZones) {
            this.elements.btnRefreshZones.addEventListener('click', () => {
                this.ws.send({ type: 'get_zones' });
            });
        }

        // Zone settings event listeners
        this._setupZoneEventListeners();

        // Connect code event listeners
        this._setupConnectCodeListeners();
    }

    _setupConnectCodeListeners() {
        // Generate code button
        if (this.elements.btnGenerateCode) {
            this.elements.btnGenerateCode.addEventListener('click', () => {
                this.ws.send({ type: 'generate_connect_code', ttl_minutes: 30 });
            });
        }

        // Copy code button
        if (this.elements.btnCopyCode) {
            this.elements.btnCopyCode.addEventListener('click', () => {
                const code = this.elements.modalCode?.textContent || '';
                this._copyToClipboard(code).then((ok) => {
                    if (ok) {
                        this.elements.btnCopyCode.textContent = 'Copied!';
                        this.elements.btnCopyCode.classList.add('btn-copy-success');
                        setTimeout(() => {
                            this.elements.btnCopyCode.textContent = 'Copy Code';
                            this.elements.btnCopyCode.classList.remove('btn-copy-success');
                        }, 2000);
                    }
                });
            });
        }

        // Close modal button
        if (this.elements.btnCloseModal) {
            this.elements.btnCloseModal.addEventListener('click', () => {
                this._hideCodeModal();
            });
        }

        // Click backdrop to close
        const backdrop = this.elements.codeModal?.querySelector('.modal-backdrop');
        if (backdrop) {
            backdrop.addEventListener('click', () => {
                this._hideCodeModal();
            });
        }

        // Event delegation for revoke buttons
        if (this.elements.activeCodes) {
            this.elements.activeCodes.addEventListener('click', (e) => {
                const btn = e.target.closest('.btn-revoke');
                if (btn && btn.dataset.code) {
                    this.ws.send({ type: 'revoke_connect_code', code: btn.dataset.code });
                }
            });
        }
    }

    _showCodeModal(code, ttlMinutes = 30) {
        if (this.elements.modalCode) {
            this.elements.modalCode.textContent = code;
        }
        if (this.elements.modalTtl) {
            this.elements.modalTtl.textContent = ttlMinutes;
        }
        if (this.elements.codeModal) {
            this.elements.codeModal.classList.remove('hidden');
        }
    }

    _hideCodeModal() {
        if (this.elements.codeModal) {
            this.elements.codeModal.classList.add('hidden');
        }
    }

    // Clipboard helper — navigator.clipboard requires HTTPS/localhost.
    // Falls back to execCommand for plain HTTP (e.g. LAN access).
    async _copyToClipboard(text) {
        if (navigator.clipboard && window.isSecureContext) {
            try {
                await navigator.clipboard.writeText(text);
                return true;
            } catch (_) { /* fall through */ }
        }
        // Fallback: hidden textarea + execCommand
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.select();
        try {
            document.execCommand('copy');
            return true;
        } catch (_) {
            return false;
        } finally {
            document.body.removeChild(ta);
        }
    }

    _renderConnectCodes() {
        const container = this.elements.activeCodes;
        if (!container) return;

        // Clear existing
        while (container.firstChild) {
            container.removeChild(container.firstChild);
        }

        if (!this.state.connectCodes || this.state.connectCodes.length === 0) {
            return;
        }

        const now = Date.now() / 1000;

        this.state.connectCodes.forEach(codeObj => {
            const item = document.createElement('div');
            item.className = 'active-code-item';

            const codeSpan = document.createElement('span');
            codeSpan.className = 'code-text';
            codeSpan.textContent = codeObj.code;

            const expiresSpan = document.createElement('span');
            expiresSpan.className = 'code-expires';
            const remaining = Math.max(0, Math.floor((codeObj.expires_at - now) / 60));
            expiresSpan.textContent = `${remaining}m`;
            if (remaining < 5) {
                expiresSpan.classList.add('expiring-soon');
            }

            const revokeBtn = document.createElement('button');
            revokeBtn.className = 'btn-revoke';
            revokeBtn.dataset.code = codeObj.code;
            revokeBtn.textContent = 'X';
            revokeBtn.title = 'Revoke code';

            item.appendChild(codeSpan);
            item.appendChild(expiresSpan);
            item.appendChild(revokeBtn);
            container.appendChild(item);
        });
    }

    _setupZoneEventListeners() {
        // Debounced zone config sender
        const sendZoneConfig = debounce(() => this._sendZoneConfig(), 100);

        // Render mode buttons
        this.elements.renderModeButtons.forEach(btn => {
            btn.addEventListener('click', () => this._setRenderMode(btn.dataset.mode));
        });

        // Particle visualization config
        this._setupParticleVizListeners();

        // Banner settings
        this._setupBannerListeners();

        // Entity count
        this._setupZoneControl('zone-entity-count', 'val-entity-count', (val) => {
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
        this._setupZoneControl('zone-base-scale', 'val-base-scale', (val) => {
            this.state.zone.baseScale = val / 100;
            sendZoneConfig();
        }, (val) => (val / 100).toFixed(2));

        // Max scale
        this._setupZoneControl('zone-max-scale', 'val-max-scale', (val) => {
            this.state.zone.maxScale = val / 100;
            sendZoneConfig();
        }, (val) => (val / 100).toFixed(2));

        // Brightness
        this._setupZoneControl('zone-brightness', 'val-brightness', (val) => {
            this.state.zone.brightness = val;
            sendZoneConfig();
        }, (val) => `${val}`);

        // Interpolation
        this._setupZoneControl('zone-interpolation', 'val-interpolation', (val) => {
            this.state.zone.interpolation = val;
            sendZoneConfig();
        }, (val) => `${val} ticks`);

        // Zone size controls
        this._setupZoneControl('zone-size-x', 'val-size-x', (val) => {
            this.state.zone.sizeX = val;
            sendZoneConfig();
        }, (val) => `${val}`);

        this._setupZoneControl('zone-size-y', 'val-size-y', (val) => {
            this.state.zone.sizeY = val;
            sendZoneConfig();
        }, (val) => `${val}`);

        this._setupZoneControl('zone-size-z', 'val-size-z', (val) => {
            this.state.zone.sizeZ = val;
            sendZoneConfig();
        }, (val) => `${val}`);

        // Rotation
        this._setupZoneControl('zone-rotation', 'val-rotation', (val) => {
            this.state.zone.rotation = val;
            sendZoneConfig();
        }, (val) => `${val}°`);

        // Toggle switches
        this._setupToggle('zone-glow-beat', 'glowOnBeat', sendZoneConfig);
        this._setupToggle('zone-dynamic-brightness', 'dynamicBrightness', sendZoneConfig);
        this._setupToggle('zone-show-bpm', 'showBpm', sendZoneConfig);
        this._setupToggle('zone-show-pattern', 'showPattern', sendZoneConfig);
        this._setupToggle('zone-show-bands', 'showBands', sendZoneConfig);

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

        // Zone selector
        if (this.elements.zoneSelect) {
            this.elements.zoneSelect.addEventListener('change', () => {
                this.state.zone.name = this.elements.zoneSelect.value;
                this._setZoneControlsLoading(true);
                this._requestZoneStatus();
            });
        }
    }

    _setZoneControlsLoading(loading) {
        // Disable/enable zone controls during zone switch to prevent wrong-zone adjustments
        const controls = [
            'zone-entity-count', 'zone-block-type', 'zone-base-scale', 'zone-max-scale',
            'zone-brightness', 'zone-interpolation', 'zone-size-x', 'zone-size-y',
            'zone-size-z', 'zone-rotation', 'zone-glow-beat', 'zone-dynamic-brightness',
            'zone-show-bpm', 'zone-show-pattern', 'zone-show-bands'
        ];

        controls.forEach(id => {
            const el = document.getElementById(id);
            if (el) {
                el.disabled = loading;
            }
        });

        // Show loading indicator on zone panel
        const zonePanel = document.getElementById('zone-panel');
        if (zonePanel) {
            zonePanel.classList.toggle('loading', loading);
        }
    }

    _setupZoneControl(inputId, displayId, callback, formatFn) {
        const input = document.getElementById(inputId);
        const display = document.getElementById(displayId);

        if (input && display) {
            input.addEventListener('input', () => {
                const val = parseFloat(input.value);
                display.textContent = formatFn(val);
                callback(val);
            });
        }
    }

    _setupToggle(elementId, stateKey, onChange) {
        const toggle = document.getElementById(elementId);
        if (toggle) {
            toggle.addEventListener('change', () => {
                this.state.zone[stateKey] = toggle.checked;
                onChange();
            });
        }
    }

    _setupControl(inputId, displayId, callback, formatFn) {
        const input = document.getElementById(inputId);
        const display = document.getElementById(displayId);

        if (input && display) {
            // Debounce the callback to prevent message spam
            const debouncedCallback = debounce(callback, 50);

            input.addEventListener('input', () => {
                const val = parseFloat(input.value);
                display.textContent = formatFn(val);
                debouncedCallback(val);
            });
        }
    }

    _setupWebSocket() {
        this.ws.addEventListener('connecting', (e) => {
            const detail = e.detail || {};
            this._setConnectionStatus('connecting', detail.attempt, detail.maxAttempts);
        });

        this.ws.addEventListener('connected', () => {
            this.state.connected = true;
            this._setConnectionStatus('connected');
            this._showToast('Connected to server', 'success');
            // Request initial state
            this.ws.send({ type: 'get_particle_effects' });
            this.ws.send({ type: 'get_zones' });
            this.ws.send({ type: 'get_zone', zone: this.state.zone.name });
            this.ws.send({ type: 'get_connect_codes' });
            this.ws.send({ type: 'get_pending_djs' });
        });

        this.ws.addEventListener('disconnected', () => {
            this.state.connected = false;
            this.state.minecraftConnected = false;
            this._setConnectionStatus('disconnected');
            this._updateServiceIndicators();
        });

        this.ws.addEventListener('error', () => {
            this._setConnectionStatus('error');
        });

        this.ws.addEventListener('reconnect_failed', () => {
            this._setConnectionStatus('failed');
            this._showToast('Connection failed. Click Reconnect to retry.', 'error', 0);
        });

        // Handle incoming messages
        this.ws.addEventListener('message', (e) => {
            this._handleMessage(e.detail);
        });
    }

    // === Message Handling ===

    _handleMessage(data) {
        switch (data.type) {
            case 'vj_state':
                // VJ server sends combined state with patterns and DJ roster
                this._handlePatterns(data);
                this._handleDJRoster(data);
                if (data.current_pattern) {
                    this.state.currentPattern = data.current_pattern;
                    this._updatePatternDisplay();
                }
                if (data.entity_count !== undefined) {
                    this.state.blockCount = data.entity_count;
                    this._updateBlockCountDisplay();
                }
                if (data.zone !== undefined) {
                    this.state.currentZone = data.zone;
                }
                // Handle initial MC status from vj_state
                if (data.minecraft_connected !== undefined) {
                    this.state.minecraftConnected = data.minecraft_connected;
                    this._updateServiceIndicators();
                    this._updateMCDependentControls();
                }
                // Handle initial pending DJs from vj_state
                if (data.pending_djs) {
                    this.state.pendingDJs = data.pending_djs;
                    this._renderPendingDJs();
                }
                if (data.banner_profiles) {
                    this.state.bannerProfiles = data.banner_profiles;
                }
                break;

            case 'config_update':
                // Server broadcasts config changes (entity_count, zone, pattern)
                if (data.entity_count !== undefined) {
                    this.state.blockCount = data.entity_count;
                    this._updateBlockCountDisplay();
                }
                if (data.zone !== undefined) {
                    this.state.currentZone = data.zone;
                }
                if (data.current_pattern) {
                    this.state.currentPattern = data.current_pattern;
                    this._updatePatternDisplay();
                }
                break;

            case 'patterns':
                this._handlePatterns(data);
                break;

            case 'dj_roster':
                this._handleDJRoster(data);
                break;

            case 'state':
            case 'audio':
                this._handleAudioState(data);
                break;

            case 'pattern_changed':
                this._handlePatternChanged(data);
                break;

            case 'preset_changed':
                this._handlePresetChanged(data);
                break;

            case 'state_snapshot':
                this._handleStateSnapshot(data);
                break;

            case 'particle_effects':
                this._handleParticleEffects(data);
                break;

            case 'particle_effect_changed':
                this._handleParticleEffectChanged(data);
                break;

            case 'particle_config_changed':
                this._handleParticleConfigChanged(data);
                break;

            case 'zone':
            case 'zone_status':
                this._handleZoneStatus(data);
                break;

            case 'zones':
                this._handleZonesList(data);
                break;

            case 'connect_code_generated':
                // Show the newly generated code in modal
                this._showCodeModal(data.code, data.ttl_minutes || 30);
                break;

            case 'connect_codes':
                // Update list of active codes
                this.state.connectCodes = data.codes || [];
                this._renderConnectCodes();
                break;

            case 'minecraft_status':
                this._handleMinecraftStatus(data);
                break;

            case 'dj_pending':
                // New DJ requesting approval
                this._handleDJPending(data);
                break;

            case 'pending_djs':
                // Full list of pending DJs
                this.state.pendingDJs = data.pending || [];
                this._renderPendingDJs();
                break;

            case 'dj_approved':
            case 'dj_denied':
                // DJ was approved/denied, refresh pending list
                this.ws.send({ type: 'get_pending_djs' });
                break;

            case 'banner_profile':
                this._handleBannerProfile(data);
                break;

            case 'banner_profile_saved':
                this._showToast('Banner profile saved', 'success');
                break;

            case 'banner_logo_processed':
                this._showToast(`Logo processed: ${data.grid_width}x${data.grid_height} pixels`, 'success');
                break;

            case 'all_banner_profiles':
                this.state.bannerProfiles = data.profiles || {};
                break;

            case 'banner_config_received':
                this._showToast('Banner applied to Minecraft', 'success');
                break;

            case 'error':
                this._showToast(data.message || 'An error occurred', 'error');
                break;

            case 'zone_cleaned':
                this._showToast(`Zone "${data.zone || 'unknown'}" cleaned up`, 'success');
                break;

            case 'pool_initialized':
                this._showToast(`Pool initialized: ${data.count || '?'} entities`, 'success');
                break;
        }
    }

    _handleZonesList(data) {
        // Populate zone selector
        if (data.zones && this.elements.zoneSelect) {
            // Clear existing options
            this.elements.zoneSelect.innerHTML = '';

            // Add zone options
            data.zones.forEach(zone => {
                const option = document.createElement('option');
                option.value = zone.name;
                option.textContent = zone.name;
                this.elements.zoneSelect.appendChild(option);
            });

            // Select current zone
            this.elements.zoneSelect.value = this.state.zone.name;
        }

        // Render the zone list in the Zone Settings tab
        this._renderZoneList(data.zones || []);
    }

    _handleParticleEffects(data) {
        this.state.particleEffects = data.effects || [];
        this.state.enabledParticleEffects = new Set(
            data.effects?.filter(e => e.enabled).map(e => e.id) || []
        );
        this.state.particleGlobalIntensity = data.global_intensity || 1.0;

        // Update global intensity slider
        if (this.elements.particleGlobalIntensity) {
            this.elements.particleGlobalIntensity.value = Math.round(this.state.particleGlobalIntensity * 100);
            document.getElementById('val-particle-intensity').textContent =
                `${Math.round(this.state.particleGlobalIntensity * 100)}%`;
        }

        this._renderParticleEffects();
    }

    _handleParticleEffectChanged(data) {
        if (data.enabled) {
            this.state.enabledParticleEffects.add(data.effect);
        } else {
            this.state.enabledParticleEffects.delete(data.effect);
        }

        // Update toggle button state
        const toggle = document.querySelector(`.particle-toggle[data-effect="${data.effect}"]`);
        if (toggle) {
            toggle.classList.toggle('active', data.enabled);
        }
    }

    _handleParticleConfigChanged(data) {
        if (data.global_intensity !== undefined) {
            this.state.particleGlobalIntensity = data.global_intensity;
        }
    }

    _renderParticleEffects() {
        const beatContainer = this.elements.particleBeatEffects;
        const ambientContainer = this.elements.particleAmbientEffects;

        if (!beatContainer || !ambientContainer) return;

        // Clear containers using safe DOM methods
        while (beatContainer.firstChild) {
            beatContainer.removeChild(beatContainer.firstChild);
        }
        while (ambientContainer.firstChild) {
            ambientContainer.removeChild(ambientContainer.firstChild);
        }

        // Render effect toggles
        this.state.particleEffects.forEach(effect => {
            const toggle = this._createParticleToggle(effect);
            if (effect.category === 'beat') {
                beatContainer.appendChild(toggle);
            } else {
                ambientContainer.appendChild(toggle);
            }
        });
    }

    _createParticleToggle(effect) {
        const isEnabled = this.state.enabledParticleEffects.has(effect.id);

        const toggle = document.createElement('label');
        toggle.className = `particle-toggle ${effect.category}${isEnabled ? ' active' : ''}`;
        toggle.dataset.effect = effect.id;

        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.checked = isEnabled;

        const indicator = document.createElement('span');
        indicator.className = 'toggle-indicator';

        const name = document.createElement('span');
        name.className = 'toggle-name';
        name.textContent = effect.name;

        toggle.appendChild(checkbox);
        toggle.appendChild(indicator);
        toggle.appendChild(name);

        // Click handler
        toggle.addEventListener('click', (e) => {
            e.preventDefault();
            this._toggleParticleEffect(effect.id);
        });

        return toggle;
    }

    _toggleParticleEffect(effectId) {
        const isEnabled = this.state.enabledParticleEffects.has(effectId);
        this.ws.send({
            type: 'set_particle_effect',
            zone: this.state.zone.name || 'main',
            effect: effectId,
            enabled: !isEnabled
        });
    }

    _sendParticleConfig(config) {
        this.ws.send({
            type: 'set_particle_config',
            zone: this.state.zone.name || 'main',
            ...config
        });
    }

    _handlePatterns(data) {
        this.state.patterns = data.patterns || [];
        this.state.currentPattern = data.current || data.current_pattern;
        this._renderPatternGrid();
        this._updateCurrentPattern(this.state.currentPattern);
    }

    _handleDJRoster(data) {
        // Server sends 'dj_roster' in vj_state, but 'roster' in dj_roster broadcasts
        this.state.djRoster = data.dj_roster || data.roster || [];
        this.state.activeDJ = data.active_dj || null;
        this._renderDJQueue();
        this._updateBannerDJSelector();
    }

    _renderDJQueue() {
        const container = this.elements.djQueue;
        if (!container) return;

        // Clear existing children safely
        while (container.firstChild) {
            container.removeChild(container.firstChild);
        }

        if (!this.state.djRoster || this.state.djRoster.length === 0) {
            const emptyEl = document.createElement('div');
            emptyEl.className = 'dj-empty';
            emptyEl.textContent = 'No DJs connected';
            container.appendChild(emptyEl);
            return;
        }

        this.state.djRoster.forEach((dj, index) => {
            const isActive = dj.dj_id === this.state.activeDJ;
            const djEl = document.createElement('div');
            djEl.className = 'dj-item' + (isActive ? ' active' : '');

            // Position number
            const posSpan = document.createElement('span');
            posSpan.className = 'dj-position';
            posSpan.textContent = `#${index + 1}`;

            // Name and stats
            const infoDiv = document.createElement('div');
            infoDiv.className = 'dj-info';

            const nameSpan = document.createElement('span');
            nameSpan.className = 'dj-name';
            nameSpan.textContent = dj.dj_name;

            infoDiv.appendChild(nameSpan);

            // Stats row (BPM, latency, FPS)
            if (dj.bpm || dj.latency_ms !== undefined || dj.fps !== undefined) {
                const statsDiv = document.createElement('div');
                statsDiv.className = 'dj-stats';
                if (dj.bpm) {
                    const bpmStat = document.createElement('span');
                    bpmStat.className = 'dj-stat';
                    bpmStat.textContent = `${Math.round(dj.bpm)} BPM`;
                    statsDiv.appendChild(bpmStat);
                }
                if (dj.latency_ms !== undefined) {
                    const latStat = document.createElement('span');
                    latStat.className = 'dj-stat';
                    latStat.textContent = `${Math.round(dj.latency_ms)}ms`;
                    if (dj.latency_ms > 200) latStat.classList.add('warning');
                    statsDiv.appendChild(latStat);
                }
                if (dj.fps !== undefined) {
                    const fpsStat = document.createElement('span');
                    fpsStat.className = 'dj-stat';
                    fpsStat.textContent = `${Math.round(dj.fps)} FPS`;
                    statsDiv.appendChild(fpsStat);
                }
                infoDiv.appendChild(statsDiv);
            }

            // Actions area
            const actionsDiv = document.createElement('div');
            actionsDiv.className = 'dj-actions';

            if (isActive) {
                const badge = document.createElement('span');
                badge.className = 'dj-badge-live';
                badge.textContent = 'LIVE';
                actionsDiv.appendChild(badge);
            } else {
                const goLiveBtn = document.createElement('button');
                goLiveBtn.className = 'btn btn-go-live';
                goLiveBtn.dataset.action = 'activate';
                goLiveBtn.dataset.dj = dj.dj_id;
                goLiveBtn.textContent = 'Go Live';
                actionsDiv.appendChild(goLiveBtn);
            }

            // Queue reorder controls
            const queueControls = document.createElement('div');
            queueControls.className = 'dj-queue-controls';

            if (index > 0) {
                const upBtn = document.createElement('button');
                upBtn.className = 'btn btn-queue-move';
                upBtn.dataset.action = 'move_up';
                upBtn.dataset.dj = dj.dj_id;
                upBtn.dataset.position = index;
                upBtn.textContent = '\u25B2';
                upBtn.title = 'Move up';
                queueControls.appendChild(upBtn);
            }
            if (index < this.state.djRoster.length - 1) {
                const downBtn = document.createElement('button');
                downBtn.className = 'btn btn-queue-move';
                downBtn.dataset.action = 'move_down';
                downBtn.dataset.dj = dj.dj_id;
                downBtn.dataset.position = index;
                downBtn.textContent = '\u25BC';
                downBtn.title = 'Move down';
                queueControls.appendChild(downBtn);
            }

            const kickBtn = document.createElement('button');
            kickBtn.className = 'btn btn-kick';
            kickBtn.dataset.action = 'kick';
            kickBtn.dataset.dj = dj.dj_id;
            kickBtn.dataset.name = dj.dj_name;
            kickBtn.textContent = 'Kick';
            kickBtn.title = 'Kick DJ';
            queueControls.appendChild(kickBtn);

            djEl.appendChild(posSpan);
            djEl.appendChild(infoDiv);
            djEl.appendChild(actionsDiv);
            djEl.appendChild(queueControls);
            container.appendChild(djEl);
        });
    }

    _setupDJQueueDelegation() {
        // Event delegation for DJ queue - single listener on container
        // This prevents listener duplication when the roster is re-rendered
        const container = this.elements.djQueue;
        if (!container || container._delegationSetup) return;

        container.addEventListener('click', (e) => {
            const btn = e.target.closest('[data-action]');
            if (!btn || !btn.dataset.dj) return;

            const action = btn.dataset.action;
            const djId = btn.dataset.dj;

            switch (action) {
                case 'activate':
                    this.ws.send({ type: 'set_active_dj', dj_id: djId });
                    this._showToast('Switching active DJ...', 'info');
                    break;

                case 'move_up': {
                    const pos = parseInt(btn.dataset.position);
                    if (pos > 0) {
                        this.ws.send({ type: 'reorder_dj_queue', dj_id: djId, new_position: pos - 1 });
                    }
                    break;
                }

                case 'move_down': {
                    const pos = parseInt(btn.dataset.position);
                    if (pos < this.state.djRoster.length - 1) {
                        this.ws.send({ type: 'reorder_dj_queue', dj_id: djId, new_position: pos + 1 });
                    }
                    break;
                }

                case 'kick': {
                    const name = btn.dataset.name || djId;
                    if (confirm(`Kick DJ "${name}"?`)) {
                        this.ws.send({ type: 'kick_dj', dj_id: djId });
                        this._showToast(`Kicked DJ "${name}"`, 'info');
                    }
                    break;
                }
            }
        });
        container._delegationSetup = true;
    }

    _handleAudioState(data) {
        // Update state
        this.state.bands = data.bands || [0, 0, 0, 0, 0, 0];
        this.state.amplitude = data.amplitude || 0;
        this.state.isBeat = data.is_beat || false;
        this.state.beatIntensity = data.beat_intensity || 0;
        this.state.frame = data.frame || 0;
        this.state.entities = data.entities || [];

        // Store latency, BPM, and FPS for throttled update
        if (data.latency_ms !== undefined) {
            this.state.latencyMs = data.latency_ms;
        }
        if (data.fps !== undefined) {
            this.state.fps = data.fps;
        }
        if (data.zone_status?.bpm_estimate) {
            this.state.bpmEstimate = data.zone_status.bpm_estimate;
        }
        if (data.bpm !== undefined) {
            this.state.bpm = data.bpm;
        }

        // RAF-throttle meter updates to avoid excessive DOM updates (~60Hz messages)
        if (!this._meterUpdatePending) {
            this._meterUpdatePending = true;
            requestAnimationFrame(() => {
                this._updateMeters();
                this._updateBeatIndicator();
                this._updateFrameCount();

                // Update latency display with warning if > 500ms
                if (this.state.latencyMs !== undefined && this.elements.latencyDisplay) {
                    this.elements.latencyDisplay.textContent = `Latency: ${this.state.latencyMs.toFixed(1)}ms`;
                    this.elements.latencyDisplay.classList.toggle('warning', this.state.latencyMs > 500);
                }

                // Update queue depth display (only show when > 0)
                this._updateQueueDepthDisplay();

                // Update BPM if available
                if (this.state.bpmEstimate && this.elements.bpmEstimate) {
                    this.elements.bpmEstimate.textContent = Math.round(this.state.bpmEstimate);
                }

                // Update FPS if available
                if (this.state.fps !== undefined && this.elements.fpsDisplay) {
                    this.elements.fpsDisplay.textContent = `${Math.round(this.state.fps)} FPS`;
                }

                // Update 3D preview if initialized
                if (this._previewInitialized) {
                    this._updatePreviewFromAudioState();
                }

                this._meterUpdatePending = false;
            });
        }
    }

    _updateQueueDepthDisplay() {
        const queueDepth = this.ws.messageQueue.length;
        const maxQueueSize = this.ws.maxQueueSize;

        if (this.elements.queueDepthDisplay) {
            if (queueDepth > 0) {
                this.elements.queueDepthDisplay.textContent = `Queue: ${queueDepth}/${maxQueueSize}`;
                this.elements.queueDepthDisplay.classList.remove('hidden');
                // Warning if queue depth exceeds 50% capacity
                this.elements.queueDepthDisplay.classList.toggle('warning', queueDepth > maxQueueSize * 0.5);
            } else {
                this.elements.queueDepthDisplay.classList.add('hidden');
            }
        }
    }

    _handlePatternChanged(data) {
        this.state.currentPattern = data.pattern;
        this._updateCurrentPattern(data.pattern);
        this._highlightActivePattern(data.pattern);
    }

    _handlePresetChanged(data) {
        this.state.currentPreset = data.preset;
        this._updateCurrentPreset(data.preset);
        this._highlightActivePreset(data.preset);

        // Update controls if settings provided
        if (data.settings) {
            this._syncControlsFromSettings(data.settings);
        }
    }

    _handleStateSnapshot(data) {
        // Sync all state from server
        if (data.pattern) {
            this.state.currentPattern = data.pattern;
            this._updateCurrentPattern(data.pattern);
            this._highlightActivePattern(data.pattern);
        }

        if (data.preset) {
            this.state.currentPreset = data.preset;
            this._updateCurrentPreset(data.preset);
            this._highlightActivePreset(data.preset);
        }

        if (data.parameters) {
            this._syncControlsFromSettings(data.parameters);
        }
    }

    // === UI Updates ===

    _setConnectionStatus(status, attempt = 0, maxAttempts = 10) {
        const el = this.elements.connectionStatus;
        el.className = `status ${status}`;

        let statusText;
        if (status === 'connecting' && attempt > 0) {
            statusText = `Reconnecting (${attempt}/${maxAttempts})...`;
        } else {
            const statusTexts = {
                'connecting': 'Connecting...',
                'connected': 'Connected',
                'disconnected': 'Disconnected',
                'error': 'Error',
                'failed': 'Connection Failed'
            };
            statusText = statusTexts[status] || status;
        }

        el.textContent = statusText;

        // Show/hide reconnect button
        if (this.elements.btnReconnect) {
            this.elements.btnReconnect.classList.toggle('hidden', status !== 'failed');
        }

        // Update service indicators
        this._updateServiceIndicators();
    }

    _updateMeters() {
        const bands = this.state.bands;

        for (let i = 0; i < 5; i++) {
            const value = Math.min(100, Math.max(0, bands[i] * 100));
            if (this.elements.meters[i]) {
                this.elements.meters[i].style.width = `${value}%`;
            }
            if (this.elements.meterValues[i]) {
                this.elements.meterValues[i].textContent = `${Math.round(value)}%`;
            }
        }

        // Master meter (average)
        const masterValue = this.state.amplitude * 100;
        if (this.elements.meterMaster) {
            this.elements.meterMaster.style.width = `${masterValue}%`;
        }
        if (this.elements.meterMasterValue) {
            this.elements.meterMasterValue.textContent = `${Math.round(masterValue)}%`;
        }
    }

    _updateBeatIndicator() {
        const el = this.elements.beatIndicator;
        if (this.state.isBeat) {
            el.classList.add('active');
            setTimeout(() => el.classList.remove('active'), 100);
        }
    }

    _updateFrameCount() {
        this.elements.frameCount.textContent = this.state.frame;
    }

    _updateCurrentPattern(pattern) {
        this.elements.currentPattern.textContent = pattern || '--';
        this._highlightActivePattern(pattern);
    }

    _updatePatternDisplay() {
        this._renderPatternGrid();
        this._updateCurrentPattern(this.state.currentPattern);
    }

    _updateBlockCountDisplay() {
        // Update the slider and value display for block count
        const slider = document.getElementById('ctrl-blocks');
        const valueDisplay = document.getElementById('val-blocks');
        if (slider) {
            slider.value = this.state.blockCount;
        }
        if (valueDisplay) {
            valueDisplay.textContent = `${this.state.blockCount}`;
        }
    }

    _updateCurrentPreset(preset) {
        this.elements.currentPreset.textContent = preset || '--';
    }

    _updateFaderDisplay(fader, value) {
        const display = fader.querySelector('.fader-value');
        if (display) {
            display.textContent = `${value}%`;
        }
    }

    _renderPatternGrid() {
        const grid = this.elements.patternGrid;

        // Clear existing children safely
        while (grid.firstChild) {
            grid.removeChild(grid.firstChild);
        }

        // Create pattern buttons using safe DOM methods
        this.state.patterns.forEach(pattern => {
            const btn = document.createElement('button');
            btn.className = 'pattern-btn';
            btn.dataset.pattern = pattern.id;
            btn.textContent = pattern.name; // Safe: textContent escapes HTML
            btn.title = pattern.description || '';

            if (pattern.id === this.state.currentPattern) {
                btn.classList.add('active');
            }

            btn.addEventListener('click', () => this._setPattern(pattern.id));
            grid.appendChild(btn);
        });
    }

    _highlightActivePattern(patternId) {
        document.querySelectorAll('.pattern-btn').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.pattern === patternId);
        });
    }

    _highlightActivePreset(preset) {
        this.elements.presetButtons.forEach(btn => {
            btn.classList.toggle('active', btn.dataset.preset === preset);
        });
    }

    _syncControlsFromSettings(settings) {
        if (settings.attack !== undefined) {
            const val = Math.round(settings.attack * 100);
            this.elements.ctrlAttack.value = val;
            document.getElementById('val-attack').textContent = `${val}%`;
            this.state.attack = settings.attack;
        }

        if (settings.release !== undefined) {
            const val = Math.round(settings.release * 100);
            this.elements.ctrlRelease.value = val;
            document.getElementById('val-release').textContent = `${val}%`;
            this.state.release = settings.release;
        }

        if (settings.agc_max_gain !== undefined) {
            this.elements.ctrlAgc.value = settings.agc_max_gain;
            document.getElementById('val-agc').textContent = `${settings.agc_max_gain}x`;
            this.state.agcMaxGain = settings.agc_max_gain;
        }

        if (settings.beat_sensitivity !== undefined) {
            const val = Math.round(settings.beat_sensitivity * 100);
            this.elements.ctrlBeatSens.value = val;
            document.getElementById('val-beat-sens').textContent = `${val}%`;
            this.state.beatSensitivity = settings.beat_sensitivity;
        }

        if (settings.beat_threshold !== undefined) {
            const val = Math.round(settings.beat_threshold * 100);
            this.elements.ctrlBeatThresh.value = val;
            document.getElementById('val-beat-thresh').textContent = `${settings.beat_threshold.toFixed(2)}x`;
            this.state.beatThreshold = settings.beat_threshold;
        }

        if (settings.band_sensitivity) {
            settings.band_sensitivity.forEach((sens, i) => {
                this.state.bandSensitivity[i] = sens;
                const fader = document.querySelector(`.fader[data-band="${i}"] .fader-input`);
                if (fader) {
                    fader.value = Math.round(sens * 100);
                    this._updateFaderDisplay(fader.closest('.fader'), Math.round(sens * 100));
                }
            });
        }
    }

    // === Actions ===

    _switchTab(tabName) {
        // Update tab buttons
        this.elements.tabs.forEach(tab => {
            tab.classList.toggle('active', tab.dataset.tab === tabName);
        });

        // Update panels
        this.elements.tabPanels.forEach(panel => {
            panel.classList.toggle('active', panel.id === `${tabName}-panel`);
        });

        // Initialize 3D preview on first switch to preview tab
        if (tabName === 'preview' && !this._previewInitialized) {
            this._initPreview();
        }

        // Handle preview animation based on tab visibility
        if (tabName === 'preview') {
            this._startPreviewAnimation();
            // Show preview stats in header
            const statsEl = document.getElementById('preview-stats');
            if (statsEl) statsEl.classList.remove('hidden');
        } else {
            this._stopPreviewAnimation();
            // Hide preview stats in header
            const statsEl = document.getElementById('preview-stats');
            if (statsEl) statsEl.classList.add('hidden');
        }
    }

    _setPattern(patternId) {
        this.ws.send({ type: 'set_pattern', pattern: patternId });
    }

    _setPreset(preset) {
        this.ws.send({ type: 'set_preset', preset: preset });
    }

    _sendBandSensitivity(band, value) {
        this.ws.send({
            type: 'set_band_sensitivity',
            band: band,
            sensitivity: value * this.state.masterSensitivity
        });
    }

    _sendAllBandSensitivities() {
        for (let i = 0; i < 5; i++) {
            this._sendBandSensitivity(i, this.state.bandSensitivity[i]);
        }
    }

    _sendSetting(setting, value) {
        this.ws.send({
            type: 'set_audio_setting',
            setting: setting,
            value: value
        });
    }

    _toggleBlackout() {
        this.state.blackout = !this.state.blackout;
        this.elements.btnBlackout.classList.toggle('active', this.state.blackout);

        this.ws.send({
            type: 'trigger_effect',
            effect: 'blackout',
            intensity: this.state.blackout ? 1.0 : 0.0
        });
    }

    _toggleFreeze() {
        this.state.freeze = !this.state.freeze;
        this.elements.btnFreeze.classList.toggle('active', this.state.freeze);

        this.ws.send({
            type: 'trigger_effect',
            effect: 'freeze',
            intensity: this.state.freeze ? 1.0 : 0.0
        });
    }

    _tapTempo() {
        const now = Date.now();

        // Clear old taps
        if (this.tapTimeout) {
            clearTimeout(this.tapTimeout);
        }

        // Reset if tap is too far from last
        if (this.tapTimes.length > 0 && now - this.tapTimes[this.tapTimes.length - 1] > 2000) {
            this.tapTimes = [];
        }

        this.tapTimes.push(now);

        // Keep last 8 taps
        if (this.tapTimes.length > 8) {
            this.tapTimes.shift();
        }

        // Calculate BPM from intervals
        if (this.tapTimes.length >= 2) {
            const intervals = [];
            for (let i = 1; i < this.tapTimes.length; i++) {
                intervals.push(this.tapTimes[i] - this.tapTimes[i - 1]);
            }
            const avgInterval = intervals.reduce((a, b) => a + b, 0) / intervals.length;
            const bpm = Math.round(60000 / avgInterval);

            this.elements.tapBpm.textContent = `${bpm} BPM`;
        }

        // Reset after 2 seconds of no taps
        this.tapTimeout = setTimeout(() => {
            this.tapTimes = [];
        }, 2000);
    }

    _triggerEffect(effect) {
        const btn = document.querySelector(`.effect-btn[data-effect="${effect}"]`);
        if (btn) {
            btn.classList.add('firing');
            setTimeout(() => btn.classList.remove('firing'), 200);
        }

        this.ws.send({
            type: 'trigger_effect',
            effect: effect,
            intensity: 1.0,
            duration: 2000
        });
    }

    _handleKeyboard(e) {
        // Escape key closes any open modal regardless of focus
        if (e.key === 'Escape') {
            this._hideCodeModal();
            return;
        }

        // Ignore if typing in an input
        if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') {
            return;
        }

        switch (e.key) {
            case 'b':
            case 'B':
                this._toggleBlackout();
                break;

            case 'f':
            case 'F':
                this._toggleFreeze();
                break;

            case 't':
            case 'T':
                this._tapTempo();
                break;

            // Number keys 1-8 for pattern switching
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
                const patternIndex = parseInt(e.key) - 1;
                if (this.state.patterns[patternIndex]) {
                    this._setPattern(this.state.patterns[patternIndex].id);
                }
                break;
        }
    }

    // === Zone Management ===

    _sendZoneConfig() {
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

    _reinitPool() {
        if (!this.state.minecraftConnected) {
            this._showToast('Minecraft not connected', 'warning');
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
        this._showToast('Reinitializing entity pool...', 'info');
    }

    _cleanupZone() {
        if (!this.state.minecraftConnected) {
            this._showToast('Minecraft not connected', 'warning');
            return;
        }
        if (confirm('This will remove all entities in the zone. Continue?')) {
            this.ws.send({
                type: 'cleanup_zone',
                zone: this.state.zone.name
            });
            this._showToast('Cleaning up zone...', 'info');
        }
    }

    _resetZoneDefaults() {
        // Reset to default values
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

        // Update UI
        this._syncZoneUI();

        // Send config
        this._sendZoneConfig();
    }

    _requestZoneStatus() {
        this.ws.send({
            type: 'get_zone',
            zone: this.state.zone.name
        });
    }

    _syncZoneUI() {
        const zone = this.state.zone;

        // Sliders
        this._setSliderValue('zone-entity-count', 'val-entity-count', zone.entityCount, v => `${v}`);
        this._setSliderValue('zone-base-scale', 'val-base-scale', zone.baseScale * 100, v => (v / 100).toFixed(2));
        this._setSliderValue('zone-max-scale', 'val-max-scale', zone.maxScale * 100, v => (v / 100).toFixed(2));
        this._setSliderValue('zone-brightness', 'val-brightness', zone.brightness, v => `${v}`);
        this._setSliderValue('zone-interpolation', 'val-interpolation', zone.interpolation, v => `${v} ticks`);
        this._setSliderValue('zone-size-x', 'val-size-x', zone.sizeX, v => `${v}`);
        this._setSliderValue('zone-size-y', 'val-size-y', zone.sizeY, v => `${v}`);
        this._setSliderValue('zone-size-z', 'val-size-z', zone.sizeZ, v => `${v}`);
        this._setSliderValue('zone-rotation', 'val-rotation', zone.rotation, v => `${v}°`);

        // Select
        if (this.elements.zoneBlockType) {
            this.elements.zoneBlockType.value = zone.blockType;
        }

        // Toggles
        this._setToggleValue('zone-glow-beat', zone.glowOnBeat);
        this._setToggleValue('zone-dynamic-brightness', zone.dynamicBrightness);
        this._setToggleValue('zone-show-bpm', zone.showBpm);
        this._setToggleValue('zone-show-pattern', zone.showPattern);
        this._setToggleValue('zone-show-bands', zone.showBands);
    }

    _setSliderValue(inputId, displayId, value, formatFn) {
        const input = document.getElementById(inputId);
        const display = document.getElementById(displayId);
        if (input) input.value = value;
        if (display) display.textContent = formatFn(value);
    }

    _setToggleValue(elementId, value) {
        const toggle = document.getElementById(elementId);
        if (toggle) toggle.checked = value;
    }

    _handleZoneStatus(data) {
        // Re-enable zone controls now that data has arrived
        this._setZoneControlsLoading(false);

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
            this._syncZoneUI();
            this._updateRenderModeUI(this.state.zone.renderMode);
        }
    }

    // === Render Mode (Bedrock Compatibility) ===

    _setRenderMode(mode) {
        this.state.zone.renderMode = mode;

        // Update UI immediately
        this._updateRenderModeUI(mode);

        // Send to server
        this.ws.send({
            type: 'set_render_mode',
            zone: this.state.zone.name,
            mode: mode
        });
    }

    _updateRenderModeUI(mode) {
        // Update button states
        this.elements.renderModeButtons.forEach(btn => {
            btn.classList.toggle('active', btn.dataset.mode === mode);
        });

        // Show/hide sections based on mode
        const showEntities = mode === 'entities' || mode === 'hybrid';
        const showParticles = mode === 'particles' || mode === 'hybrid';

        if (this.elements.entityConfigSection) {
            this.elements.entityConfigSection.style.display = showEntities ? 'block' : 'none';
        }

        if (this.elements.particleVizSection) {
            this.elements.particleVizSection.style.display = showParticles ? 'block' : 'none';
        }

        // Show Bedrock notice when particles are enabled
        if (this.elements.bedrockNotice) {
            this.elements.bedrockNotice.style.display = showParticles ? 'flex' : 'none';
        }
    }

    _setupParticleVizListeners() {
        const sendParticleVizConfig = debounce(() => this._sendParticleVizConfig(), 100);

        // Particle type
        if (this.elements.particleVizType) {
            this.elements.particleVizType.addEventListener('change', () => {
                this.state.zone.particleViz.particleType = this.elements.particleVizType.value;
                sendParticleVizConfig();
            });
        }

        // Density
        if (this.elements.particleVizDensity) {
            this.elements.particleVizDensity.addEventListener('input', () => {
                const val = parseInt(this.elements.particleVizDensity.value);
                document.getElementById('val-particle-density').textContent = `${val}x`;
                this.state.zone.particleViz.density = val;
                sendParticleVizConfig();
            });
        }

        // Color mode
        if (this.elements.particleVizColor) {
            this.elements.particleVizColor.addEventListener('change', () => {
                const mode = this.elements.particleVizColor.value;
                this.state.zone.particleViz.colorMode = mode;

                // Show/hide fixed color picker
                if (this.elements.fixedColorRow) {
                    this.elements.fixedColorRow.style.display = mode === 'fixed' ? 'flex' : 'none';
                }

                sendParticleVizConfig();
            });
        }

        // Fixed color picker
        if (this.elements.particleVizFixedColor) {
            this.elements.particleVizFixedColor.addEventListener('input', () => {
                this.state.zone.particleViz.fixedColor = this.elements.particleVizFixedColor.value;
                sendParticleVizConfig();
            });
        }

        // Particle size
        if (this.elements.particleVizSize) {
            this.elements.particleVizSize.addEventListener('input', () => {
                const val = parseInt(this.elements.particleVizSize.value);
                document.getElementById('val-particle-size').textContent = (val / 10).toFixed(1);
                this.state.zone.particleViz.particleSize = val / 10;
                sendParticleVizConfig();
            });
        }

        // Trail toggle
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

    // === Toast Notification System ===

    _showToast(message, type = 'info', duration = 4000) {
        const container = this.elements.toastContainer;
        if (!container) return;

        const toast = document.createElement('div');
        toast.className = `toast toast-${type}`;
        toast.textContent = message;

        container.appendChild(toast);

        // Trigger show animation
        requestAnimationFrame(() => toast.classList.add('show'));

        // Auto-dismiss (0 = persistent until manually dismissed or replaced)
        if (duration > 0) {
            setTimeout(() => this._dismissToast(toast), duration);
        }
    }

    _dismissToast(toast) {
        if (!toast || !toast.parentNode) return;
        toast.classList.remove('show');
        setTimeout(() => {
            if (toast.parentNode) {
                toast.parentNode.removeChild(toast);
            }
        }, 300);
    }

    // === Service Status Indicators ===

    _updateServiceIndicators() {
        const pythonDot = this.elements.svcPython?.querySelector('.svc-dot');
        const mcDot = this.elements.svcMinecraft?.querySelector('.svc-dot');

        if (pythonDot) {
            pythonDot.classList.toggle('connected', this.state.connected);
            pythonDot.classList.toggle('disconnected', !this.state.connected);
        }

        if (mcDot) {
            mcDot.classList.toggle('connected', this.state.minecraftConnected);
            mcDot.classList.toggle('disconnected', !this.state.minecraftConnected);
        }
    }

    _handleMinecraftStatus(data) {
        const wasConnected = this.state.minecraftConnected;
        this.state.minecraftConnected = data.connected;
        this._updateServiceIndicators();
        this._updateMCDependentControls();

        // Notify on status change
        if (data.connected && !wasConnected) {
            this._showToast('Minecraft connected', 'success');
        } else if (!data.connected && wasConnected) {
            this._showToast('Minecraft disconnected', 'warning');
        }
    }

    _updateMCDependentControls() {
        const mcDependent = [this.elements.btnReinitPool, this.elements.btnCleanupZone];
        mcDependent.forEach(btn => {
            if (btn) {
                btn.disabled = !this.state.minecraftConnected;
                btn.title = this.state.minecraftConnected ? '' : 'Minecraft not connected';
            }
        });
    }

    // === DJ Pending Approval ===

    _handleDJPending(data) {
        // A new DJ is requesting approval - add to pending list
        const dj = data.dj || data;
        const exists = this.state.pendingDJs.some(d => d.dj_id === dj.dj_id);
        if (!exists) {
            this.state.pendingDJs.push(dj);
        }
        this._renderPendingDJs();
        this._showToast(`DJ "${dj.dj_name || 'Unknown'}" requesting approval`, 'info', 8000);
    }

    _renderPendingDJs() {
        const section = this.elements.djPendingSection;
        const container = this.elements.djPendingQueue;
        if (!section || !container) return;

        // Clear existing
        while (container.firstChild) {
            container.removeChild(container.firstChild);
        }

        // Show/hide section
        section.classList.toggle('hidden', this.state.pendingDJs.length === 0);

        this.state.pendingDJs.forEach(dj => {
            const item = document.createElement('div');
            item.className = 'dj-pending-item';
            item.dataset.djId = dj.dj_id;

            const info = document.createElement('div');
            info.className = 'dj-pending-info';

            const name = document.createElement('span');
            name.className = 'dj-pending-name';
            name.textContent = dj.dj_name || 'Unknown DJ';

            const meta = document.createElement('span');
            meta.className = 'dj-pending-meta';
            const waitTime = dj.waiting_since
                ? Math.floor((Date.now() / 1000 - dj.waiting_since) / 60)
                : 0;
            meta.textContent = waitTime > 0 ? `Waiting ${waitTime}m` : 'Just now';
            if (dj.direct_mode) {
                const badge = document.createElement('span');
                badge.className = 'dj-badge-direct';
                badge.textContent = 'DIRECT';
                meta.appendChild(badge);
            }

            info.appendChild(name);
            info.appendChild(meta);

            const actions = document.createElement('div');
            actions.className = 'dj-pending-actions';

            const approveBtn = document.createElement('button');
            approveBtn.className = 'btn btn-approve';
            approveBtn.dataset.action = 'approve';
            approveBtn.dataset.dj = dj.dj_id;
            approveBtn.textContent = 'Approve';

            const denyBtn = document.createElement('button');
            denyBtn.className = 'btn btn-deny';
            denyBtn.dataset.action = 'deny';
            denyBtn.dataset.dj = dj.dj_id;
            denyBtn.textContent = 'Deny';

            actions.appendChild(approveBtn);
            actions.appendChild(denyBtn);

            item.appendChild(info);
            item.appendChild(actions);
            container.appendChild(item);
        });
    }

    _setupDJPendingDelegation() {
        const container = this.elements.djPendingQueue;
        if (!container || container._delegationSetup) return;

        container.addEventListener('click', (e) => {
            const btn = e.target.closest('[data-action]');
            if (!btn || !btn.dataset.dj) return;

            if (btn.dataset.action === 'approve') {
                this.ws.send({ type: 'approve_dj', dj_id: btn.dataset.dj });
                this._showToast('DJ approved', 'success');
                // Optimistically remove from pending
                this.state.pendingDJs = this.state.pendingDJs.filter(d => d.dj_id !== btn.dataset.dj);
                this._renderPendingDJs();
            } else if (btn.dataset.action === 'deny') {
                this.ws.send({ type: 'deny_dj', dj_id: btn.dataset.dj });
                this._showToast('DJ denied', 'info');
                this.state.pendingDJs = this.state.pendingDJs.filter(d => d.dj_id !== btn.dataset.dj);
                this._renderPendingDJs();
            }
        });
        container._delegationSetup = true;
    }

    // === Zone/Stage List ===

    _renderZoneList(zones) {
        const container = this.elements.zoneList;
        if (!container) return;

        while (container.firstChild) {
            container.removeChild(container.firstChild);
        }

        if (!zones || zones.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'zone-empty';
            empty.textContent = 'No stages found';
            container.appendChild(empty);
            return;
        }

        zones.forEach(zone => {
            const item = document.createElement('div');
            item.className = 'zone-item';

            const info = document.createElement('div');
            info.className = 'zone-info';

            const name = document.createElement('span');
            name.className = 'zone-name';
            name.textContent = zone.name;

            const meta = document.createElement('span');
            meta.className = 'zone-meta';
            meta.textContent = `${zone.entity_count || 0} entities`;

            info.appendChild(name);
            info.appendChild(meta);

            const actions = document.createElement('div');
            actions.className = 'zone-actions';

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
                    this._setZoneControlsLoading(true);
                    this._requestZoneStatus();
                    this.ws.send({ type: 'get_zones' });
                    this._showToast(`Switched to stage "${zone.name}"`, 'info');
                });
                actions.appendChild(btn);
            }

            item.appendChild(info);
            item.appendChild(actions);
            container.appendChild(item);
        });
    }

    // === 3D Preview Methods ===

    _initPreview() {
        if (this._previewInitialized) return;

        const canvas = document.getElementById('preview-canvas');
        const wrapper = canvas?.parentElement;
        if (!canvas || !wrapper) {
            console.warn('[Preview] Canvas not found');
            return;
        }

        // Check if THREE is loaded
        if (typeof THREE === 'undefined') {
            console.warn('[Preview] Three.js not loaded');
            return;
        }

        console.log('[Preview] Initializing 3D preview');

        // Scene
        this._previewScene = new THREE.Scene();
        this._previewScene.background = new THREE.Color(0x0a0a0f);
        this._previewScene.fog = new THREE.Fog(0x0a0a0f, 20, 50);

        // Camera
        const aspect = wrapper.clientWidth / wrapper.clientHeight;
        this._previewCamera = new THREE.PerspectiveCamera(60, aspect, 0.1, 100);
        this._previewCamera.position.set(12, 10, 12);
        this._previewCamera.lookAt(0, 2, 0);

        // Renderer
        this._previewRenderer = new THREE.WebGLRenderer({ canvas, antialias: true });
        this._previewRenderer.setSize(wrapper.clientWidth, wrapper.clientHeight);
        this._previewRenderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
        this._previewRenderer.shadowMap.enabled = true;

        // Lights
        const ambientLight = new THREE.AmbientLight(0x404040, 0.5);
        this._previewScene.add(ambientLight);

        const directionalLight = new THREE.DirectionalLight(0xffffff, 0.8);
        directionalLight.position.set(10, 20, 10);
        directionalLight.castShadow = true;
        directionalLight.shadow.mapSize.width = 2048;
        directionalLight.shadow.mapSize.height = 2048;
        this._previewScene.add(directionalLight);

        const pointLight = new THREE.PointLight(0x6366f1, 0.5, 20);
        pointLight.position.set(0, 5, 0);
        this._previewScene.add(pointLight);

        // Ground plane
        const groundGeometry = new THREE.PlaneGeometry(30, 30);
        const groundMaterial = new THREE.MeshStandardMaterial({
            color: 0x12121a,
            roughness: 0.9
        });
        const ground = new THREE.Mesh(groundGeometry, groundMaterial);
        ground.rotation.x = -Math.PI / 2;
        ground.receiveShadow = true;
        this._previewScene.add(ground);

        // Grid helper
        const gridHelper = new THREE.GridHelper(30, 30, 0x1a1a25, 0x1a1a25);
        gridHelper.position.y = 0.01;
        this._previewScene.add(gridHelper);

        // Create initial blocks
        this._createPreviewBlocks(16);

        // Initialize particle system
        if (typeof ParticleSystem !== 'undefined') {
            this._previewParticleSystem = new ParticleSystem(this._previewScene, 500);
        }

        // Initialize block indicators
        if (typeof BlockIndicatorSystem !== 'undefined') {
            this._previewBlockIndicators = new BlockIndicatorSystem(this._previewScene, 8);
        }

        // Setup preview controls
        this._setupPreviewControls();
        this._setupPreviewMouseControls();

        // Handle window resize
        window.addEventListener('resize', () => this._onPreviewResize());

        this._previewInitialized = true;
        this._previewLastFrameTime = performance.now();
        console.log('[Preview] 3D preview initialized');
    }

    _createPreviewBlock(index) {
        const config = this._previewConfig;
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

    _createPreviewBlocks(count) {
        for (let i = 0; i < count; i++) {
            const block = this._createPreviewBlock(i);
            block.position.set(0, 0.5, 0);
            this._previewScene.add(block);
            this._previewBlocks.push(block);
        }
    }

    _ensurePreviewBlockCount(count) {
        while (this._previewBlocks.length < count) {
            const block = this._createPreviewBlock(this._previewBlocks.length);
            block.position.set(0, 0.5, 0);
            this._previewScene.add(block);
            this._previewBlocks.push(block);
        }

        while (this._previewBlocks.length > count) {
            const block = this._previewBlocks.pop();
            this._previewScene.remove(block);
            block.geometry.dispose();
            block.material.dispose();
        }
    }

    _setupPreviewControls() {
        // Reset camera button
        const resetBtn = document.getElementById('preview-reset-camera');
        if (resetBtn) {
            resetBtn.addEventListener('click', () => this._resetPreviewCamera());
        }

        // Auto rotate checkbox
        const rotateChk = document.getElementById('preview-auto-rotate');
        if (rotateChk) {
            rotateChk.addEventListener('change', (e) => {
                this._previewAutoRotate = e.target.checked;
            });
        }

        // Show grid checkbox
        const gridChk = document.getElementById('preview-show-grid');
        if (gridChk) {
            gridChk.addEventListener('change', (e) => {
                this._previewShowGrid = e.target.checked;
                if (this._previewBlockIndicators) {
                    this._previewBlockIndicators.setVisible(this._previewShowGrid);
                }
            });
        }

        // Particles enabled checkbox
        const particlesChk = document.getElementById('preview-particles-enabled');
        if (particlesChk) {
            particlesChk.addEventListener('change', (e) => {
                this._previewParticleEffects.enabled = e.target.checked;
            });
        }

        // Individual particle effect toggles
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
                    this._previewParticleEffects[prop] = e.target.checked;
                });
            }
        });
    }

    _setupPreviewMouseControls() {
        const canvas = document.getElementById('preview-canvas');
        if (!canvas) return;

        let isDragging = false;
        let previousMousePosition = { x: 0, y: 0 };

        canvas.addEventListener('mousedown', (e) => {
            isDragging = true;
            previousMousePosition = { x: e.clientX, y: e.clientY };
        });

        canvas.addEventListener('mousemove', (e) => {
            if (!isDragging || !this._previewCamera) return;

            const deltaX = e.clientX - previousMousePosition.x;
            const deltaY = e.clientY - previousMousePosition.y;

            const spherical = new THREE.Spherical();
            spherical.setFromVector3(this._previewCamera.position);
            spherical.theta -= deltaX * 0.01;
            spherical.phi = Math.max(0.1, Math.min(Math.PI - 0.1, spherical.phi + deltaY * 0.01));
            this._previewCamera.position.setFromSpherical(spherical);
            this._previewCamera.lookAt(0, 2, 0);

            previousMousePosition = { x: e.clientX, y: e.clientY };
        });

        canvas.addEventListener('mouseup', () => isDragging = false);
        canvas.addEventListener('mouseleave', () => isDragging = false);

        canvas.addEventListener('wheel', (e) => {
            if (!this._previewCamera) return;
            const zoomSpeed = 0.001;
            const distance = this._previewCamera.position.length();
            const newDistance = distance * (1 + e.deltaY * zoomSpeed);
            this._previewCamera.position.normalize().multiplyScalar(Math.max(5, Math.min(30, newDistance)));
        });
    }

    _resetPreviewCamera() {
        if (this._previewCamera) {
            this._previewCamera.position.set(12, 10, 12);
            this._previewCamera.lookAt(0, 2, 0);
        }
    }

    _onPreviewResize() {
        if (!this._previewInitialized) return;

        const canvas = document.getElementById('preview-canvas');
        const wrapper = canvas?.parentElement;
        if (!wrapper || !this._previewCamera || !this._previewRenderer) return;

        const width = wrapper.clientWidth;
        const height = wrapper.clientHeight;

        this._previewCamera.aspect = width / height;
        this._previewCamera.updateProjectionMatrix();
        this._previewRenderer.setSize(width, height);
    }

    _startPreviewAnimation() {
        if (this._previewAnimationId) return;
        this._previewLastFrameTime = performance.now();
        this._animatePreview();
    }

    _stopPreviewAnimation() {
        if (this._previewAnimationId) {
            cancelAnimationFrame(this._previewAnimationId);
            this._previewAnimationId = null;
        }
    }

    _animatePreview() {
        this._previewAnimationId = requestAnimationFrame(() => this._animatePreview());

        if (!this._previewRenderer || !this._previewScene || !this._previewCamera) return;

        const now = performance.now();
        const dt = (now - this._previewLastFrameTime) / 1000;
        this._previewLastFrameTime = now;

        // FPS calculation
        this._previewFrameCount++;
        if (now - this._previewLastFpsUpdate >= 1000) {
            this._previewFps = this._previewFrameCount;
            this._previewFrameCount = 0;
            this._previewLastFpsUpdate = now;
            const fpsEl = document.getElementById('preview-stat-fps');
            if (fpsEl) fpsEl.textContent = this._previewFps;
        }

        // Smooth block animations
        const lerpSpeed = 0.25;
        this._previewBlocks.forEach((block) => {
            block.position.x += (block.userData.targetX - block.position.x) * lerpSpeed;
            block.position.y += (block.userData.targetY - block.position.y) * lerpSpeed;
            block.position.z += (block.userData.targetZ - block.position.z) * lerpSpeed;

            const targetScale = block.userData.targetScale || 1;
            block.scale.x += (targetScale - block.scale.x) * lerpSpeed;
            block.scale.y += (targetScale - block.scale.y) * lerpSpeed;
            block.scale.z += (targetScale - block.scale.z) * lerpSpeed;
        });

        // Update particle system
        if (this._previewParticleSystem) {
            this._previewParticleSystem.update(dt);
        }

        // Auto rotate camera
        if (this._previewAutoRotate && this._previewCamera) {
            const spherical = new THREE.Spherical();
            spherical.setFromVector3(this._previewCamera.position);
            spherical.theta += 0.002;
            this._previewCamera.position.setFromSpherical(spherical);
            this._previewCamera.lookAt(0, 3, 0);
        }

        this._previewRenderer.render(this._previewScene, this._previewCamera);
    }

    _updatePreviewFromAudioState() {
        if (!this._previewInitialized) return;

        const entities = this.state.entities;
        const bands = this.state.bands;
        const isBeat = this.state.isBeat;
        const beatIntensity = this.state.beatIntensity;

        // Update preview meters overlay
        this._updatePreviewMeters();

        // Update block positions from entities
        if (entities && entities.length > 0) {
            this._ensurePreviewBlockCount(entities.length);

            const config = this._previewConfig;
            this._previewBlocks.forEach((block, i) => {
                const entity = entities[i];
                if (!entity) return;

                block.userData.targetX = (entity.x * config.zoneSize) - config.centerOffset;
                block.userData.targetY = entity.y * config.zoneSize;
                block.userData.targetZ = (entity.z * config.zoneSize) - config.centerOffset;
                block.userData.targetScale = entity.scale * 1.5;

                const bandIndex = entity.band || 0;
                if (block.userData.bandIndex !== bandIndex) {
                    block.userData.bandIndex = bandIndex;
                    block.material.color.setHex(config.colors[bandIndex]);
                    block.material.emissive.setHex(config.colors[bandIndex]);
                }

                const bandValue = bands[bandIndex] || 0;
                block.material.emissiveIntensity = 0.3 + bandValue * 1.0;
            });
        }

        // Update block indicators
        if (this._previewBlockIndicators && this._previewShowGrid) {
            this._previewBlockIndicators.updateFromAudio(bands, isBeat, beatIntensity);
        }

        // Beat flash
        const beatFlash = document.getElementById('preview-beat-flash');
        if (beatFlash) {
            beatFlash.classList.toggle('active', isBeat);
        }

        // Spawn particles on beat
        if (isBeat && this._previewParticleEffects.enabled) {
            this._spawnPreviewBeatParticles();
        }

        // Spawn ambient particles
        if (this._previewParticleEffects.enabled) {
            this._spawnPreviewAmbientParticles();
        }

        // Update stats
        this._updatePreviewStats();
    }

    _updatePreviewMeters() {
        for (let i = 0; i < 5; i++) {
            const meterBar = document.getElementById(`preview-band-${i}`);
            if (meterBar) {
                const height = Math.round(this.state.bands[i] * 100);
                meterBar.style.height = height + '%';
            }
        }
    }

    _updatePreviewStats() {
        // Block stats
        const blockStatsEl = document.getElementById('preview-stat-blocks');
        if (blockStatsEl && this._previewBlockIndicators) {
            const stats = this._previewBlockIndicators.getStats();
            blockStatsEl.textContent = `${stats.active}/${stats.total}`;
        }

        // Particle stats
        const particleStatsEl = document.getElementById('preview-stat-particles');
        if (particleStatsEl && this._previewParticleSystem) {
            particleStatsEl.textContent = this._previewParticleSystem.getActiveCount();
        }

        // Header stats (when preview tab is active)
        const headerBlockCount = document.getElementById('preview-block-count');
        const headerParticleCount = document.getElementById('preview-particle-count');
        if (headerBlockCount && this._previewBlockIndicators) {
            const stats = this._previewBlockIndicators.getStats();
            headerBlockCount.textContent = `${stats.active} blocks`;
        }
        if (headerParticleCount && this._previewParticleSystem) {
            headerParticleCount.textContent = `${this._previewParticleSystem.getActiveCount()} particles`;
        }
    }

    _spawnPreviewBeatParticles() {
        if (!this._previewParticleSystem) return;

        const now = performance.now();
        const BEAT_COOLDOWN = 150;
        if (now - this._previewLastBeatTime < BEAT_COOLDOWN) return;
        this._previewLastBeatTime = now;

        const bass = this.state.bands[0] || 0;
        const intensity = this.state.beatIntensity || 0.5;

        // Bass flames
        if (this._previewParticleEffects.bassFlame && bass > 0.3) {
            const count = Math.floor(8 + intensity * 12);
            this._previewParticleSystem.spawn('FLAME', 0, 0.1, 0, count);
            if (bass > 0.6) {
                this._previewParticleSystem.spawn('LAVA', 0, 0.2, 0, Math.floor(count / 3));
            }
        }

        // Soul fire on bass hits
        if (this._previewParticleEffects.soulFire && bass > 0.5) {
            const count = Math.floor(5 + intensity * 8);
            this._previewParticleSystem.spawn('SOUL_FIRE_FLAME', 0, 0.1, 0, count);
        }

        // Beat ring
        if (this._previewParticleEffects.beatRing) {
            const count = Math.floor(16 + intensity * 16);
            this._previewParticleSystem.spawnRing('END_ROD', 0, 0.5, 0, 2, count);
        }
    }

    _spawnPreviewAmbientParticles() {
        if (!this._previewParticleSystem) return;

        const high = this.state.bands[3] || 0;

        // Musical notes on high frequencies
        if (this._previewParticleEffects.notes && high > 0.25) {
            if (Math.random() < high * 0.3) {
                const x = (Math.random() - 0.5) * 6;
                const z = (Math.random() - 0.5) * 6;
                this._previewParticleSystem.spawn('NOTE', x, 1 + Math.random() * 2, z, 1);
            }
        }

        // Spectrum dust (5 bands)
        if (this._previewParticleEffects.dust && typeof BAND_COLORS !== 'undefined') {
            for (let i = 0; i < 5; i++) {
                const band = this.state.bands[i] || 0;
                if (band > 0.2 && Math.random() < band * 0.15) {
                    const x = (Math.random() - 0.5) * 8;
                    const z = (Math.random() - 0.5) * 8;
                    const color = BAND_COLORS[i] || [1, 1, 1];
                    this._previewParticleSystem.spawn('DUST', x, 0.5 + Math.random() * 3, z, 1, color);
                }
            }
        }
    }
    // ========== Banner System ==========

    _setupBannerListeners() {
        // Banner mode toggle
        const modeButtons = document.querySelectorAll('[data-banner-mode]');
        modeButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                modeButtons.forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                const mode = btn.dataset.bannerMode;
                const textSettings = document.getElementById('banner-text-settings');
                const imageSettings = document.getElementById('banner-image-settings');
                if (textSettings) textSettings.style.display = mode === 'text' ? '' : 'none';
                if (imageSettings) imageSettings.style.display = mode === 'image' ? '' : 'none';
            });
        });

        // Color mode dropdown - show/hide fixed color row
        const colorModeSelect = document.getElementById('banner-text-color-mode');
        if (colorModeSelect) {
            colorModeSelect.addEventListener('change', () => {
                const fixedRow = document.getElementById('banner-fixed-color-row');
                if (fixedRow) fixedRow.style.display = colorModeSelect.value === 'fixed' ? '' : 'none';
            });
        }

        // Grid width/height sliders
        const gridW = document.getElementById('banner-grid-width');
        const gridH = document.getElementById('banner-grid-height');
        if (gridW) {
            gridW.addEventListener('input', () => {
                const label = document.getElementById('val-banner-grid-width');
                if (label) label.textContent = gridW.value;
            });
        }
        if (gridH) {
            gridH.addEventListener('input', () => {
                const label = document.getElementById('val-banner-grid-height');
                if (label) label.textContent = gridH.value;
            });
        }

        // Pulse intensity slider
        const pulse = document.getElementById('banner-pulse-intensity');
        if (pulse) {
            pulse.addEventListener('input', () => {
                const label = document.getElementById('val-banner-pulse');
                if (label) label.textContent = pulse.value + '%';
            });
        }

        // Upload logo button
        const uploadBtn = document.getElementById('btn-upload-logo');
        const fileInput = document.getElementById('banner-logo-file');
        if (uploadBtn && fileInput) {
            uploadBtn.addEventListener('click', () => fileInput.click());
            fileInput.addEventListener('change', () => this._handleLogoUpload());
        }

        // Save profile button
        const saveBtn = document.getElementById('btn-save-banner-profile');
        if (saveBtn) {
            saveBtn.addEventListener('click', () => this._saveBannerProfile());
        }

        // Apply now button
        const applyBtn = document.getElementById('btn-apply-banner-now');
        if (applyBtn) {
            applyBtn.addEventListener('click', () => this._applyBannerNow());
        }

        // DJ selector - load profile when DJ changes
        const djSelect = document.getElementById('banner-dj-select');
        if (djSelect) {
            djSelect.addEventListener('change', () => {
                const djId = djSelect.value;
                if (djId) {
                    this.ws.send({ type: 'get_banner_profile', dj_id: djId });
                }
            });
        }
    }

    _handleLogoUpload() {
        const fileInput = document.getElementById('banner-logo-file');
        const filenameLabel = document.getElementById('banner-logo-filename');
        const djSelect = document.getElementById('banner-dj-select');
        if (!fileInput || !fileInput.files[0] || !djSelect) return;

        const file = fileInput.files[0];
        if (filenameLabel) filenameLabel.textContent = file.name;

        const reader = new FileReader();
        reader.onload = (e) => {
            // Draw preview
            this._drawLogoPreview(e.target.result);

            // Convert to base64 (strip data:image/...;base64, prefix)
            const base64 = e.target.result.split(',')[1];
            const gridW = parseInt(document.getElementById('banner-grid-width')?.value || '24');
            const gridH = parseInt(document.getElementById('banner-grid-height')?.value || '12');

            this.ws.send({
                type: 'upload_banner_logo',
                dj_id: djSelect.value,
                image_base64: base64,
                grid_width: gridW,
                grid_height: gridH,
                filename: file.name,
            });
        };
        reader.readAsDataURL(file);
    }

    _drawLogoPreview(dataUrl) {
        const canvas = document.getElementById('banner-preview-canvas');
        if (!canvas) return;

        const ctx = canvas.getContext('2d');
        const img = new Image();
        img.onload = () => {
            const gridW = parseInt(document.getElementById('banner-grid-width')?.value || '24');
            const gridH = parseInt(document.getElementById('banner-grid-height')?.value || '12');

            canvas.width = gridW * 10;
            canvas.height = gridH * 10;
            ctx.imageSmoothingEnabled = false;

            // Draw downsampled then scale up for pixel-art preview
            const tempCanvas = document.createElement('canvas');
            tempCanvas.width = gridW;
            tempCanvas.height = gridH;
            const tempCtx = tempCanvas.getContext('2d');
            tempCtx.drawImage(img, 0, 0, gridW, gridH);

            ctx.drawImage(tempCanvas, 0, 0, canvas.width, canvas.height);
        };
        img.src = dataUrl;
    }

    _saveBannerProfile() {
        const djSelect = document.getElementById('banner-dj-select');
        if (!djSelect || !djSelect.value) {
            this._showToast('Select a DJ first', 'error');
            return;
        }

        const activeMode = document.querySelector('[data-banner-mode].active');
        const profile = {
            banner_mode: activeMode ? activeMode.dataset.bannerMode : 'text',
            text_style: document.getElementById('banner-text-style')?.value || 'bold',
            text_color_mode: document.getElementById('banner-text-color-mode')?.value || 'frequency',
            text_fixed_color: document.getElementById('banner-text-fixed-color')?.value || 'f',
            text_format: document.getElementById('banner-text-format')?.value || '%s',
            grid_width: parseInt(document.getElementById('banner-grid-width')?.value || '24'),
            grid_height: parseInt(document.getElementById('banner-grid-height')?.value || '12'),
        };

        this.ws.send({
            type: 'set_banner_profile',
            dj_id: djSelect.value,
            profile: profile,
        });
    }

    _applyBannerNow() {
        const djSelect = document.getElementById('banner-dj-select');
        if (!djSelect || !djSelect.value) {
            this._showToast('Select a DJ first', 'error');
            return;
        }

        const activeMode = document.querySelector('[data-banner-mode].active');
        const msg = {
            type: 'banner_config',
            banner_mode: activeMode ? activeMode.dataset.bannerMode : 'text',
            text_style: document.getElementById('banner-text-style')?.value || 'bold',
            text_color_mode: document.getElementById('banner-text-color-mode')?.value || 'frequency',
            text_fixed_color: document.getElementById('banner-text-fixed-color')?.value || 'f',
            text_format: document.getElementById('banner-text-format')?.value || '%s',
            grid_width: parseInt(document.getElementById('banner-grid-width')?.value || '24'),
            grid_height: parseInt(document.getElementById('banner-grid-height')?.value || '12'),
        };

        this.ws.send(msg);
    }

    _handleBannerProfile(data) {
        if (!data.profile) return;
        const p = data.profile;

        // Set mode
        const modeButtons = document.querySelectorAll('[data-banner-mode]');
        modeButtons.forEach(btn => {
            btn.classList.toggle('active', btn.dataset.bannerMode === (p.banner_mode || 'text'));
        });
        const textSettings = document.getElementById('banner-text-settings');
        const imageSettings = document.getElementById('banner-image-settings');
        if (textSettings) textSettings.style.display = p.banner_mode === 'image' ? 'none' : '';
        if (imageSettings) imageSettings.style.display = p.banner_mode === 'image' ? '' : 'none';

        // Set text fields
        const textStyle = document.getElementById('banner-text-style');
        if (textStyle) textStyle.value = p.text_style || 'bold';

        const colorMode = document.getElementById('banner-text-color-mode');
        if (colorMode) {
            colorMode.value = p.text_color_mode || 'frequency';
            const fixedRow = document.getElementById('banner-fixed-color-row');
            if (fixedRow) fixedRow.style.display = colorMode.value === 'fixed' ? '' : 'none';
        }

        const fixedColor = document.getElementById('banner-text-fixed-color');
        if (fixedColor) fixedColor.value = p.text_fixed_color || 'f';

        const textFormat = document.getElementById('banner-text-format');
        if (textFormat) textFormat.value = p.text_format || '%s';

        // Set grid dimensions
        const gridW = document.getElementById('banner-grid-width');
        if (gridW) {
            gridW.value = p.grid_width || 24;
            const label = document.getElementById('val-banner-grid-width');
            if (label) label.textContent = gridW.value;
        }

        const gridH = document.getElementById('banner-grid-height');
        if (gridH) {
            gridH.value = p.grid_height || 12;
            const label = document.getElementById('val-banner-grid-height');
            if (label) label.textContent = gridH.value;
        }

        // Show logo filename if available
        const filenameLabel = document.getElementById('banner-logo-filename');
        if (filenameLabel) {
            filenameLabel.textContent = p.logo_filename || (p.has_image ? 'Logo uploaded' : 'No file selected');
        }
    }

    /**
     * Update the DJ selector dropdown when the roster changes.
     * Called from _handleDJRoster.
     */
    _updateBannerDJSelector() {
        const select = document.getElementById('banner-dj-select');
        if (!select) return;

        const currentVal = select.value;
        select.innerHTML = '<option value="">-- Select DJ --</option>';

        if (this.state.djRoster) {
            this.state.djRoster.forEach(dj => {
                const opt = document.createElement('option');
                opt.value = dj.dj_id;
                opt.textContent = dj.dj_name + (dj.is_active ? ' (Active)' : '');
                select.appendChild(opt);
            });
        }

        // Restore selection
        if (currentVal) select.value = currentVal;
    }
}

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    window.adminApp = new AdminApp();
});
