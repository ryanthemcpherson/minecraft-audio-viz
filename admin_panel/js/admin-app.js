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
        // VJ password: check URL param, then localStorage, then prompt
        const urlParams = new URLSearchParams(window.location.search);
        let vjPassword = urlParams.get('vj_password')
            || localStorage.getItem('mcav_vj_password')
            || '';
        if (vjPassword) {
            localStorage.setItem('mcav_vj_password', vjPassword);
        }
        const wsPort = parseInt(urlParams.get('port'), 10) || 8766;
        this.ws = new WebSocketService({
            host: wsHost,
            port: wsPort,
            vjPassword: vjPassword,
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
            minecraftServerType: null,  // 'paper' or 'fabric'
            // Connect codes state
            connectCodes: [],
            // Stage data
            stages: [],
            selectedStage: null,
            // Per-zone pattern state
            selectedZones: new Set(),    // Currently selected zone names
            zonePatterns: {},            // zone_name -> pattern_name from server
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
            // Band material overrides (per-band block types)
            bandMaterials: [null, null, null, null, null],
            bandMaterialsSource: 'default',
            // Voice chat state
            voiceChat: {
                available: false,
                streaming: false,
                enabled: false,
                channelType: 'static',
                distance: 100,
                connectedPlayers: 0
            },
            // Bitmap LED Wall state
            bitmap: {
                initialized: false,
                initializedZones: new Set(),
                zone: 'main',
                width: 16,
                height: 12,
                patterns: [],
                transitions: [],
                palettes: [],
                activePattern: null,
                activePalette: null,
                brightness: 100,
                strobe: false,
                frozen: false,
                dataFetched: false,
            },
            // 3D Preview state
            entities: [],
            zoneEntities: {},               // zone_name → entity[] from server
            // Scene presets state
            scenes: [],
            currentScene: null
        };

        // Zone pattern change tracking (debounce chip re-rendering)
        this._lastZonePatternsJson = '';

        // Tap tempo tracking
        this.tapTimes = [];
        this.tapTimeout = null;

        // DOM elements cache
        this.elements = {};

        // 3D Preview components (eagerly init on connect, lives in preview strip)
        this._previewInitialized = false;
        this._previewStripCollapsed = localStorage.getItem('mcav-preview-collapsed') === 'true';
        this._previewScene = null;
        this._previewCamera = null;
        this._previewRenderer = null;
        this._previewBlocks = [];
        this._previewParticleSystem = null;
        this._previewBlockIndicators = null;
        this._previewFailed = false;
        this._previewAutoRotate = false;
        this._previewShowGrid = false;
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
        this._threeLoadInFlight = false;

        // Multi-zone preview state
        this._previewZoneGroups = {};       // zone_name → { group, blocks[], wireframe, sizeX/Y/Z }
        this._previewStageCenter = { x: 0, y: 0, z: 0 };
        this._previewStageBounds = null;    // { minX, minY, minZ, maxX, maxY, maxZ }
        this._previewStageMode = false;

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
        this._initBitmapControls();
        this._setupDjLogoListeners();
        this._initPreviewStrip();
        this._updateTabIndicator();
        this._boundUpdateTabIndicator = () => this._updateTabIndicator();
        window.addEventListener('resize', this._boundUpdateTabIndicator);

        // Start connection
        this.ws.connect();

        // Console branding
        console.log(
            '%c MCAV %c Control Center',
            'background:#00D4FF;color:#060611;font-weight:700;padding:4px 8px;border-radius:4px 0 0 4px;font-family:monospace;font-size:14px',
            'background:#151530;color:#00D4FF;font-weight:500;padding:4px 8px;border-radius:0 4px 4px 0;font-family:monospace;font-size:14px;border:1px solid rgba(0,212,255,0.3)'
        );
        console.log('%cKeys: B=Blackout  F=Freeze  T=Tap Tempo  1-8=Patterns', 'color:#7a7aa0;font-family:monospace;font-size:11px');
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

        // Scene presets
        this.elements.sceneNameInput = document.getElementById('scene-name-input');
        this.elements.saveSceneBtn = document.getElementById('save-scene-btn');
        this.elements.scenesGrid = document.getElementById('scenes-grid');

        // Visual sync controls
        this.elements.syncMode = document.getElementById('sync-mode');
        this.elements.ctrlVisualDelay = document.getElementById('ctrl-visual-delay');
        this.elements.syncDelayRow = document.getElementById('sync-delay-row');
        this.elements.syncPresetButtons = document.querySelectorAll('.sync-preset-btn');
        this.elements.syncDashboard = document.getElementById('sync-dashboard');
        this.elements.metricPing = document.getElementById('metric-ping');
        this.elements.metricPipeline = document.getElementById('metric-pipeline');
        this.elements.metricDelay = document.getElementById('metric-delay');
        this.elements.metricSync = document.getElementById('metric-sync');
        this.elements.metricJitter = document.getElementById('metric-jitter');
        this.elements.btnSyncTest = document.getElementById('btn-sync-test');
        this.elements.syncTestResult = document.getElementById('sync-test-result');

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

        // Hot-path elements (cached to avoid per-frame lookups)
        this.elements.header = document.getElementById('header');
        this.elements.previewStatFps = document.getElementById('preview-stat-fps');

        // Audio meter update throttling
        this._meterUpdatePending = false;

        // DJ Queue
        this.elements.djQueue = document.getElementById('dj-queue');

        // Connect Code elements
        this.elements.btnGenerateCode = document.getElementById('btn-generate-code');
        this.elements.activeCodes = document.getElementById('active-codes');
        this.elements.generatedCodeDisplay = document.getElementById('generated-code-display');
        this.elements.generatedCodeText = document.getElementById('generated-code-text');
        this.elements.generatedCodeTtl = document.getElementById('generated-code-ttl');
        this.elements.btnCopyCode = document.getElementById('btn-copy-code');

        // Reconnect button
        this.elements.btnReconnect = document.getElementById('btn-reconnect');

        // Service status indicators
        this.elements.svcPython = document.getElementById('svc-python');
        this.elements.svcMinecraft = document.getElementById('svc-minecraft');

        // DJ Pending section
        this.elements.djPendingSection = document.getElementById('dj-pending-section');
        this.elements.djPendingQueue = document.getElementById('dj-pending-queue');

        // Stage/zone list
        this.elements.stageZoneList = document.getElementById('stage-zone-list');
        this.elements.btnRefreshZones = document.getElementById('btn-refresh-zones');
        this.elements.stageSelect = document.getElementById('stage-select');
        this.elements.zoneChipBar = document.getElementById('zone-chip-bar');

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

        // Voice chat elements
        this.elements.voiceChatSection = document.getElementById('voice-chat-section');
        this.elements.voiceStatusBar = document.getElementById('voice-status-bar');
        this.elements.voiceStatusIndicator = document.getElementById('voice-status-indicator');
        this.elements.voiceDot = document.getElementById('voice-dot');
        this.elements.voiceStatusText = document.getElementById('voice-status-text');
        this.elements.voicePlayersStat = document.getElementById('voice-players-stat');
        this.elements.voiceUnavailableMsg = document.getElementById('voice-unavailable-msg');
        this.elements.voiceControls = document.getElementById('voice-controls');
        this.elements.voiceStreamToggle = document.getElementById('voice-stream-toggle');
        this.elements.voiceChannelType = document.getElementById('voice-channel-type');
        this.elements.voiceDistance = document.getElementById('voice-distance');
        this.elements.voiceDistanceRow = document.getElementById('voice-distance-row');

        // Bitmap LED Wall elements
        this.elements.bitmapZone = document.getElementById('bitmap-zone');
        this.elements.bitmapAutoSize = document.getElementById('bitmap-auto-size');
        this.elements.bitmapManualDims = document.getElementById('bitmap-manual-dims');
        this.elements.bitmapWidth = document.getElementById('bitmap-width');
        this.elements.bitmapHeight = document.getElementById('bitmap-height');
        this.elements.btnBitmapInit = document.getElementById('btn-bitmap-init');
        this.elements.bitmapStatus = document.getElementById('bitmap-status');
        this.elements.bitmapPatternGrid = document.getElementById('bitmap-pattern-grid');
        this.elements.bitmapPaletteGrid = document.getElementById('bitmap-palette-grid');
        this.elements.bitmapTransition = document.getElementById('bitmap-transition');
        this.elements.bitmapTransitionDuration = document.getElementById('bitmap-transition-duration');
        this.elements.bitmapBrightness = document.getElementById('bitmap-brightness');
        this.elements.bitmapStrobe = document.getElementById('bitmap-strobe');
        this.elements.bitmapBeatFlash = document.getElementById('bitmap-beat-flash');
        this.elements.bitmapWashColor = document.getElementById('bitmap-wash-color');
        this.elements.bitmapWashOpacity = document.getElementById('bitmap-wash-opacity');
        this.elements.bitmapBloom = document.getElementById('bitmap-bloom');
        this.elements.bitmapBloomStrength = document.getElementById('bitmap-bloom-strength');
        this.elements.bitmapAmbientLights = document.getElementById('bitmap-ambient-lights');
        this.elements.bitmapLayerPattern = document.getElementById('bitmap-layer-pattern');
        this.elements.bitmapLayerBlend = document.getElementById('bitmap-layer-blend');
        this.elements.bitmapLayerOpacity = document.getElementById('bitmap-layer-opacity');
        this.elements.bitmapSharedPalette = document.getElementById('bitmap-shared-palette');
        this.elements.bitmapSyncMode = document.getElementById('bitmap-sync-mode');

        // DJ Logo elements
        this.elements.djLogoSection = document.getElementById('dj-logo-section');
        this.elements.djLogoModeGrid = document.getElementById('dj-logo-mode-grid');
        this.elements.djLogoThreshold = document.getElementById('dj-logo-threshold');
        this.elements.djLogoFile = document.getElementById('dj-logo-file');
        this.elements.btnDjLogoLoad = document.getElementById('btn-dj-logo-load');

        // Pattern transition elements
        this.elements.transitionDurationSlider = document.getElementById('transition-duration-slider');
        this.elements.transitionDurationValue = document.getElementById('transition-duration-value');
        this.elements.transitionStatus = document.getElementById('transition-status');
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

        // Visual sync controls
        this._setupControl('ctrl-visual-delay', 'val-visual-delay', (val) => {
            this.state.visualDelayMs = val;
            this.ws.send({ type: 'set_visual_delay', delay_ms: val });
        }, (val) => `${val}ms`);

        if (this.elements.syncMode) {
            this.elements.syncMode.addEventListener('change', () => {
                const mode = this.elements.syncMode.value;
                this.state.visualDelayMode = mode;
                this.ws.send({ type: 'set_visual_delay_mode', mode: mode });
                // Show/hide manual delay slider
                if (this.elements.syncDelayRow) {
                    this.elements.syncDelayRow.style.display = mode === 'manual' ? '' : 'none';
                }
                // Clear active sync preset when mode changes
                this.elements.syncPresetButtons.forEach(b => b.classList.remove('active'));
            });
        }

        // Sync preset quick-buttons
        this.elements.syncPresetButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                const delayMs = parseInt(btn.dataset.delay);
                // Set mode to manual and apply the delay
                this.ws.send({ type: 'set_visual_delay_mode', mode: 'manual' });
                this.ws.send({ type: 'set_visual_delay', delay_ms: delayMs });
                // Update UI
                if (this.elements.syncMode) this.elements.syncMode.value = 'manual';
                if (this.elements.ctrlVisualDelay) this.elements.ctrlVisualDelay.value = delayMs;
                const valEl = document.getElementById('val-visual-delay');
                if (valEl) valEl.textContent = `${delayMs}ms`;
                if (this.elements.syncDelayRow) this.elements.syncDelayRow.style.display = '';
                // Highlight active preset
                this.elements.syncPresetButtons.forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
            });
        });

        // Sync test button
        if (this.elements.btnSyncTest) {
            this.elements.btnSyncTest.addEventListener('click', () => {
                this._syncTestSentAt = performance.now();
                this.ws.send({ type: 'sync_test' });
                if (this.elements.syncTestResult) {
                    this.elements.syncTestResult.textContent = 'Testing...';
                }
            });
        }

        // Quick actions
        this.elements.btnBlackout.addEventListener('click', () => this._toggleBlackout());
        this.elements.btnFreeze.addEventListener('click', () => this._toggleFreeze());
        this.elements.btnTapTempo.addEventListener('click', () => this._tapTempo());

        // Effect triggers
        this.elements.effectButtons.forEach(btn => {
            btn.addEventListener('click', () => this._triggerEffect(btn.dataset.effect));
        });

        // Scene presets
        if (this.elements.saveSceneBtn) {
            this.elements.saveSceneBtn.addEventListener('click', () => this._saveScene());
        }
        if (this.elements.sceneNameInput) {
            this.elements.sceneNameInput.addEventListener('keypress', (e) => {
                if (e.key === 'Enter') this._saveScene();
            });
        }

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

        // Refresh stages/zones button
        if (this.elements.btnRefreshZones) {
            this.elements.btnRefreshZones.addEventListener('click', () => {
                this.ws.send({ type: 'get_stages' });
                this.ws.send({ type: 'get_zones' });
            });
        }

        // Pattern transition duration slider
        if (this.elements.transitionDurationSlider) {
            const sendTransitionDuration = debounce((value) => {
                this.ws.send({ type: 'set_transition_duration', duration: value / 1000 });
            }, 50);

            this.elements.transitionDurationSlider.addEventListener('input', () => {
                const ms = parseInt(this.elements.transitionDurationSlider.value);
                const seconds = (ms / 1000).toFixed(1);
                if (this.elements.transitionDurationValue) {
                    this.elements.transitionDurationValue.textContent = `${seconds}s`;
                }
                sendTransitionDuration(ms);
            });
        }

        // Zone settings event listeners
        this._setupZoneEventListeners();

        // Connect code event listeners
        this._setupConnectCodeListeners();

        // Voice chat event listeners
        this._setupVoiceChatListeners();
    }

    _setupConnectCodeListeners() {
        // Generate code button
        if (this.elements.btnGenerateCode) {
            this.elements.btnGenerateCode.addEventListener('click', () => {
                // Show loading state
                this.elements.btnGenerateCode.disabled = true;
                this.elements.btnGenerateCode.classList.add('btn-loading');
                this.elements.btnGenerateCode.textContent = 'Generating...';
                this.ws.send({ type: 'generate_connect_code', ttl_minutes: 30 });
            });
        }

        // Copy code button (inline)
        if (this.elements.btnCopyCode) {
            this.elements.btnCopyCode.addEventListener('click', () => {
                const code = this.elements.generatedCodeText?.textContent || '';
                this._copyToClipboard(code).then((ok) => {
                    if (ok) {
                        this.elements.btnCopyCode.textContent = 'Copied!';
                        this.elements.btnCopyCode.classList.add('btn-copy-success');
                        setTimeout(() => {
                            this.elements.btnCopyCode.textContent = 'Copy';
                            this.elements.btnCopyCode.classList.remove('btn-copy-success');
                        }, 2000);
                    }
                });
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

    _resetGenerateButton() {
        if (this.elements.btnGenerateCode) {
            this.elements.btnGenerateCode.disabled = false;
            this.elements.btnGenerateCode.classList.remove('btn-loading');
            this.elements.btnGenerateCode.textContent = 'Generate Connect Code';
        }
    }

    _showGeneratedCode(code, ttlMinutes = 30) {
        // Reset generate button
        if (this.elements.btnGenerateCode) {
            this.elements.btnGenerateCode.disabled = false;
            this.elements.btnGenerateCode.classList.remove('btn-loading');
            this.elements.btnGenerateCode.textContent = 'Generate Connect Code';
        }
        // Show inline code display
        if (this.elements.generatedCodeText) {
            this.elements.generatedCodeText.textContent = code;
        }
        if (this.elements.generatedCodeTtl) {
            this.elements.generatedCodeTtl.textContent = ttlMinutes;
        }
        if (this.elements.generatedCodeDisplay) {
            this.elements.generatedCodeDisplay.classList.remove('hidden');
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
            this._updateBandMaterialsSourceHint();
        }, 100);

        for (let i = 0; i < 5; i++) {
            const el = document.getElementById(`band-material-${i}`);
            if (el) {
                el.addEventListener('change', sendBandMaterials);
            }
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
                // Auto-select all zones of the new stage
                this.state.selectedZones.clear();
                const zones = this.state.allZones || [];
                const filtered = this.state.selectedStage
                    ? zones.filter(z => z.stage === this.state.selectedStage)
                    : zones;
                filtered.forEach(z => this.state.selectedZones.add(z.name));
                this._updateZoneSelector();
                this._renderStageZoneList();
                this._renderZoneChips();
                this._updatePatternHighlightForZones();
            });
        }

        // Zone selector (hidden, kept for Zone Settings tab compat)
        if (this.elements.zoneSelect) {
            this.elements.zoneSelect.addEventListener('change', () => {
                this.state.zone.name = this.elements.zoneSelect.value;
                this._setZoneControlsLoading(true);
                this._requestZoneStatus();
            });
        }

        // Zone quick-select buttons
        document.querySelectorAll('.zone-quick-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const action = btn.dataset.select;
                this._quickSelectZones(action);
            });
        });

        // Collapsible mixer sections
        document.querySelectorAll('.mixer-section.collapsible > .section-title').forEach(title => {
            title.setAttribute('tabindex', '0');
            title.setAttribute('role', 'button');
            const isCollapsed = title.closest('.mixer-section').classList.contains('collapsed');
            title.setAttribute('aria-expanded', String(!isCollapsed));
            const toggle = () => {
                const section = title.closest('.mixer-section');
                section.classList.toggle('collapsed');
                title.setAttribute('aria-expanded', String(!section.classList.contains('collapsed')));
                this._saveSectionStates();
            };
            title.addEventListener('click', toggle);
            title.addEventListener('keydown', (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    toggle();
                }
            });
        });
        this._restoreSectionStates();
    }

    _saveSectionStates() {
        const states = {};
        document.querySelectorAll('.mixer-section.collapsible').forEach(section => {
            const key = section.querySelector('.section-title')?.textContent.trim();
            if (key) states[key] = section.classList.contains('collapsed');
        });
        try { localStorage.setItem('mcav-section-states', JSON.stringify(states)); } catch(e) {}
    }

    _restoreSectionStates() {
        try {
            const states = JSON.parse(localStorage.getItem('mcav-section-states'));
            if (!states) return;
            document.querySelectorAll('.mixer-section.collapsible').forEach(section => {
                const key = section.querySelector('.section-title')?.textContent.trim();
                if (key && key in states) {
                    section.classList.toggle('collapsed', states[key]);
                    const title = section.querySelector('.section-title');
                    if (title) title.setAttribute('aria-expanded', String(!states[key]));
                }
            });
        } catch(e) {}
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

            // Connection celebration glow
            const app = document.getElementById('app');
            app.classList.add('just-connected');
            setTimeout(() => app.classList.remove('just-connected'), 900);
            // Request initial state
            this.ws.send({ type: 'get_particle_effects' });
            this.ws.send({ type: 'get_stages' });
            this.ws.send({ type: 'get_zones' });
            this.ws.send({ type: 'get_zone', zone: this.state.zone.name });
            this.ws.send({ type: 'get_connect_codes' });
            this.ws.send({ type: 'get_pending_djs' });
            this.ws.send({ type: 'get_voice_status' });
            this.ws.send({ type: 'list_scenes' });

            // Fetch bitmap data on connect (LED Wall is now inline in Mixer)
            this._fetchBitmapData();

            // Eagerly init 3D preview (lives in always-visible strip now)
            if (!this._previewInitialized && !this._previewFailed) {
                this._initPreview().then(() => {
                    if (this._previewInitialized && !this._previewStripCollapsed) {
                        this._startPreviewAnimation();
                    }
                });
            }
        });

        this.ws.addEventListener('disconnected', () => {
            this.state.connected = false;
            this.state.minecraftConnected = false;
            this.state.bitmap.dataFetched = false;
            this._setConnectionStatus('disconnected');
            this._updateServiceIndicators();
            this._resetGenerateButton();
        });

        this.ws.addEventListener('auth_failed', (e) => {
            const detail = e.detail || {};
            const msg = detail.error || 'Authentication failed';
            this._setConnectionStatus('disconnected');
            // Prompt user for VJ password and retry
            const newPassword = prompt(`VJ Auth Failed: ${msg}\nEnter VJ password:`);
            if (newPassword) {
                localStorage.setItem('mcav_vj_password', newPassword);
                this.ws.vjPassword = newPassword;
                this.ws.manualReconnect();
            }
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
                // Handle per-zone pattern state
                if (data.zone_patterns) {
                    this.state.zonePatterns = data.zone_patterns;
                    this._syncBitmapStateFromZonePatterns();
                    this._renderZoneChips();
                    this._updatePatternHighlightForZones();
                    this._updateBitmapZoneSelector();
                }
                // Handle stage data from vj_state
                if (data.stages) {
                    this._handleStagesList({ stages: data.stages });
                }
                if (data.stage !== undefined && data.stage) {
                    this.state.selectedStage = data.stage;
                    if (this.elements.stageSelect) {
                        this.elements.stageSelect.value = data.stage;
                    }
                }
                // Handle initial MC status from vj_state
                if (data.minecraft_connected !== undefined) {
                    this.state.minecraftConnected = data.minecraft_connected;
                    if (data.minecraft_server_type) {
                        this.state.minecraftServerType = data.minecraft_server_type;
                    }
                    this._updateServiceIndicators();
                    this._updateMCDependentControls();
                    // Auto-fetch bitmap data when Minecraft is connected
                    if (data.minecraft_connected && !this.state.bitmap.dataFetched) {
                        this._fetchBitmapData();
                    }
                }
                // Handle initial pending DJs from vj_state
                if (data.pending_djs) {
                    this.state.pendingDJs = data.pending_djs;
                    this._renderPendingDJs();
                }
                if (data.banner_profiles) {
                    this.state.bannerProfiles = data.banner_profiles;
                }
                // Restore bitmap zone init state from server
                if (data.bitmap_zones) {
                    for (const [zoneName, info] of Object.entries(data.bitmap_zones)) {
                        if (info.initialized) {
                            this.state.bitmap.initializedZones.add(zoneName);
                            this.state.bitmap.initialized = true;
                            this.state.bitmap.width = info.width;
                            this.state.bitmap.height = info.height;
                            this._updateBitmapStatus(info);
                        }
                    }
                }
                // Sync bloom/ambient state from server
                if (data.bloom_enabled !== undefined && this.elements.bitmapBloom) {
                    this.elements.bitmapBloom.checked = data.bloom_enabled;
                }
                if (data.bloom_strength !== undefined && this.elements.bitmapBloomStrength) {
                    this.elements.bitmapBloomStrength.value = Math.round(data.bloom_strength * 100);
                    const display = document.getElementById('val-bitmap-bloom-strength');
                    if (display) display.textContent = `${Math.round(data.bloom_strength * 100)}%`;
                }
                if (data.ambient_lights_enabled !== undefined && this.elements.bitmapAmbientLights) {
                    this.elements.bitmapAmbientLights.checked = data.ambient_lights_enabled;
                }
                // Handle band materials state
                if (data.band_materials) {
                    this._syncBandMaterials(data.band_materials);
                }
                if (data.band_materials_source) {
                    this.state.bandMaterialsSource = data.band_materials_source;
                }
                this._updateBandMaterialsSourceHint();
                // Handle visual sync state
                if (data.visual_delay_ms !== undefined) {
                    this.state.visualDelayMs = data.visual_delay_ms;
                    this._updateVisualDelayDisplay();
                }
                if (data.visual_delay_mode !== undefined) {
                    this.state.visualDelayMode = data.visual_delay_mode;
                    this._updateVisualDelayModeDisplay();
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

            case 'transition_duration_sync':
                // Server synced transition duration
                if (data.duration !== undefined && this.elements.transitionDurationSlider) {
                    const ms = Math.round(data.duration * 1000);
                    this.elements.transitionDurationSlider.value = ms;
                    if (this.elements.transitionDurationValue) {
                        this.elements.transitionDurationValue.textContent = `${data.duration.toFixed(1)}s`;
                    }
                }
                break;

            case 'band_materials_sync':
                if (data.materials) {
                    this._syncBandMaterials(data.materials);
                }
                if (data.source) {
                    this.state.bandMaterialsSource = data.source;
                }
                this._updateBandMaterialsSourceHint();
                break;

            case 'visual_delay_sync':
                if (data.delay_ms !== undefined) {
                    this.state.visualDelayMs = data.delay_ms;
                    this._updateVisualDelayDisplay();
                }
                break;

            case 'visual_delay_mode_sync':
                if (data.mode !== undefined) {
                    this.state.visualDelayMode = data.mode;
                    this._updateVisualDelayModeDisplay();
                }
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

            case 'stages':
                this._handleStagesList(data);
                break;

            case 'stage_blocks':
                this._handleStageBlocks(data);
                break;

            case 'connect_code_generated':
                // Show the newly generated code inline
                this._showGeneratedCode(data.code, data.ttl_minutes || 30);
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

            case 'voice_status':
                this._handleVoiceStatus(data);
                break;

            // Bitmap LED Wall messages
            case 'bitmap_patterns':
                this.state.bitmap.patterns = data.patterns || [];
                this._renderBitmapPatterns();
                break;

            case 'bitmap_transitions':
                this.state.bitmap.transitions = data.transitions || [];
                this._renderBitmapTransitions();
                break;

            case 'bitmap_palettes':
                this.state.bitmap.palettes = data.palettes || [];
                this._renderBitmapPalettes();
                break;

            case 'bitmap_initialized': {
                this.state.bitmap.initialized = true;
                this.state.bitmap.width = data.width || 16;
                this.state.bitmap.height = data.height || 12;
                const initZone = data.zone || this.state.bitmap.zone;
                this.state.bitmap.initializedZones.add(initZone);
                this._updateBitmapStatus(data);
                this._showToast(`Bitmap initialized: ${data.width || '?'}x${data.height || '?'}`, 'success');
                // Show bitmap in 3D preview
                if (this._bitmapPreview) {
                    const zg = this._previewZoneGroups[initZone];
                    if (zg) {
                        this._bitmapPreview.activate(initZone, data.width || 16, data.height || 12,
                            data.pattern || this.state.bitmap.activePattern || 'bmp_plasma', zg);
                    }
                    this._bitmapPreview.setZoneVisible(initZone, true);
                }
                break;
            }

            case 'bitmap_pattern_set':
            case 'bitmap_transition_started':
                this.state.bitmap.activePattern = data.pattern;
                this._highlightBitmapPattern(data.pattern);
                // Update bitmap preview pattern
                if (this._bitmapPreview && data.zone) {
                    this._bitmapPreview.setPattern(data.zone, data.pattern);
                }
                break;

            case 'bitmap_palette_set':
                this.state.bitmap.activePalette = data.palette;
                this._highlightBitmapPalette(data.palette);
                break;

            case 'bitmap_status':
                this._updateBitmapStatus(data);
                break;

            case 'bloom_state':
                if (this.elements.bitmapBloom) {
                    this.elements.bitmapBloom.checked = data.enabled;
                }
                if (data.strength !== undefined && this.elements.bitmapBloomStrength) {
                    this.elements.bitmapBloomStrength.value = Math.round(data.strength * 100);
                    const display = document.getElementById('val-bitmap-bloom-strength');
                    if (display) display.textContent = `${Math.round(data.strength * 100)}%`;
                }
                break;

            case 'ambient_lights_state':
                if (this.elements.bitmapAmbientLights) {
                    this.elements.bitmapAmbientLights.checked = data.enabled;
                }
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

            case 'scenes_list':
                this.state.scenes = data.scenes || [];
                this._renderScenes();
                break;

            case 'scene_saved':
                this._showToast(`Scene "${data.name}" saved`, 'success');
                break;

            case 'scene_loaded':
                this._showToast(`Scene "${data.name}" loaded`, 'success');
                this.state.currentScene = data.name;
                this._renderScenes();
                break;

            case 'scene_deleted':
                this._showToast(`Scene "${data.name}" deleted`, 'success');
                break;

            case 'sync_test_flash': {
                // Visual flash for sync test — measure round-trip
                const flashAt = performance.now();
                document.body.style.transition = 'background 0.05s';
                document.body.style.background = '#ffffff';
                setTimeout(() => {
                    document.body.style.background = '';
                    document.body.style.transition = '';
                }, 100);
                if (this._syncTestSentAt && this.elements.syncTestResult) {
                    const rtt = Math.round(flashAt - this._syncTestSentAt);
                    this.elements.syncTestResult.textContent = `Round-trip: ${rtt}ms`;
                    this._syncTestSentAt = null;
                }
                break;
            }

            case 'link_status':
                this._handleLinkStatus(data);
                break;

            case 'parity_check_result':
                this._handleParityCheckResult(data);
                break;
        }
    }

    _handleZonesList(data) {
        const zones = data.zones || [];

        // Derive stage name from zone name prefix if zone has no stage field.
        // Zone names follow "{stage}_{role}", e.g. "stone_right_wing".
        // Find common prefix shared by all zone names to extract the stage.
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

        // Enrich zones with entity counts from their current pattern's recommended_entities
        // (server tracks these but Minecraft may report 0 before pool init)
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

        // Derive stages from zone data
        if (zones.length > 0) {
            const stageNames = [...new Set(zones.map(z => z.stage).filter(Boolean))];
            if (stageNames.length > 0) {
                this._handleStagesList({ stages: stageNames.map(n => ({ name: n })) });
            }
        }

        // Populate zone selector (filtered by selected stage if any)
        this._updateZoneSelector();

        // Render the stage/zone hierarchy in the Zone Settings tab
        this._renderStageZoneList();

        // Auto-select all zones if none currently selected
        if (this.state.selectedZones.size === 0 && zones.length > 0) {
            const selectedStage = this.state.selectedStage;
            const filtered = selectedStage
                ? zones.filter(z => z.stage === selectedStage)
                : zones;
            filtered.forEach(z => this.state.selectedZones.add(z.name));
        }

        // Render zone chips
        this._renderZoneChips();

        // Update bitmap zone selector
        this._updateBitmapZoneSelector();

        // Rebuild 3D preview zone layout when zones change
        if (this._previewInitialized) {
            this._rebuildPreviewZoneLayout();
        }
    }

    _handleStagesList(data) {
        this.state.stages = data.stages || [];

        // Populate stage selector dropdown
        if (this.elements.stageSelect) {
            while (this.elements.stageSelect.firstChild) {
                this.elements.stageSelect.removeChild(this.elements.stageSelect.firstChild);
            }

            this.state.stages.forEach(stage => {
                const option = document.createElement('option');
                option.value = stage.name;
                option.textContent = this._formatStageName(stage.name);
                this.elements.stageSelect.appendChild(option);
            });

            // Always ensure a stage is selected
            const stageNames = this.state.stages.map(s => s.name);
            if (!this.state.selectedStage || !stageNames.includes(this.state.selectedStage)) {
                this.state.selectedStage = stageNames.length > 0 ? stageNames[0] : null;
            }

            if (this.state.selectedStage) {
                this.elements.stageSelect.value = this.state.selectedStage;
            }
        }

        // Re-render zone selector, hierarchy, and chips
        this._updateZoneSelector();
        this._renderStageZoneList();
        this._renderZoneChips();
    }

    // ========== Stage Block Scanning ==========

    _handleStageBlocks(data) {
        if (data.error) {
            this._showToast(data.error, 'error');
            return;
        }
        this._stageBlockData = data;
        this._renderStageBlocks(data);
        this._showToast(`Scanned ${data.blocks.length} blocks`, 'success');
    }

    _renderStageBlocks(data) {
        if (!this._previewInitialized || !this._previewScene) return;

        // Dispose previous stage blocks group
        this._disposeStageBlocks();
        // Re-set data since dispose clears it
        this._stageBlockData = data;
        this._stageBlocksScanned = true;

        const { palette, blocks } = data;
        if (!palette || !blocks || blocks.length === 0) return;

        this._stageBlocksGroup = new THREE.Group();
        this._stageBlocksGroup.name = 'stage-blocks';

        // Use the same center as zone positioning (preview stage center)
        const center = this._previewStageCenter || { x: 0, y: 0, z: 0 };

        // Group blocks by palette index
        const blocksByMaterial = new Map();
        for (const [x, y, z, palIdx] of blocks) {
            if (!blocksByMaterial.has(palIdx)) {
                blocksByMaterial.set(palIdx, []);
            }
            blocksByMaterial.get(palIdx).push({ x, y, z });
        }

        // Shared geometry for all instances
        const boxGeo = new THREE.BoxGeometry(1, 1, 1);

        for (const [palIdx, positions] of blocksByMaterial) {
            const materialName = palette[palIdx];

            // Try to get procedural texture material, fallback to color
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
                // Position block at world coords relative to stage center
                // +0.5 offset centers the block geometry on its grid position
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

        this._previewScene.add(this._stageBlocksGroup);
    }

    _disposeStageBlocks() {
        if (this._stageBlocksGroup) {
            this._stageBlocksGroup.traverse(child => {
                if (child.isMesh) {
                    child.geometry.dispose();
                    if (child.material) child.material.dispose();
                }
            });
            this._previewScene.remove(this._stageBlocksGroup);
            this._stageBlocksGroup = null;
        }
        this._stageBlockData = null;
        this._stageBlocksScanned = false;
    }

    _scanStageBlocks() {
        if (!this.state.selectedStage) {
            this._showToast('No stage selected', 'warning');
            return;
        }
        if (!this.ws || !this.ws.isConnected) {
            this._showToast('Not connected', 'error');
            return;
        }
        this.ws.send({
            type: 'scan_stage_blocks',
            stage: this.state.selectedStage
        });
        this._showToast('Scanning stage blocks...', 'info');
    }

    _requestParityCheck() {
        if (!this.ws || !this.ws.isConnected) {
            this._showToast('Not connected', 'error');
            return;
        }
        if (!this.state.minecraftConnected) {
            this._showToast('Minecraft not connected', 'error');
            return;
        }
        this.ws.send({ type: 'request_parity_check' });
        this._showToast('Running parity check...', 'info');
    }

    _handleParityCheckResult(data) {
        if (data.error) {
            this._showToast(`Parity check failed: ${data.error}`, 'error');
            return;
        }

        const zones = data.zones || {};
        const zoneNames = Object.keys(zones);
        if (zoneNames.length === 0) {
            this._showToast('Parity check: no zones found', 'warning');
            return;
        }

        if (data.ok) {
            this._showToast(`Parity check: all ${zoneNames.length} zones OK`, 'success');
            return;
        }

        // Build summary of mismatches
        const issues = [];
        for (const [name, info] of Object.entries(zones)) {
            if (info.ok) continue;
            const mismatches = (info.mismatches || []).join('; ');
            const repaired = (info.repaired || []).join('; ');
            let line = `${name}: ${mismatches}`;
            if (repaired) line += ` [repaired: ${repaired}]`;
            issues.push(line);
        }

        const okCount = zoneNames.filter(z => zones[z].ok).length;
        const msg = `Parity: ${okCount}/${zoneNames.length} OK\n${issues.join('\n')}`;
        this._showToast(msg, issues.some(l => l.includes('failed')) ? 'error' : 'warning', 8000);
    }

    _updateZoneSelector() {
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
            // Show role label if zone belongs to a stage
            option.textContent = zone.stage_role
                ? `${zone.name} (${zone.stage_role})`
                : zone.name;
            this.elements.zoneSelect.appendChild(option);
        });

        // Keep current zone selected if it's still in the list
        const currentExists = filtered.some(z => z.name === this.state.zone.name);
        if (currentExists) {
            this.elements.zoneSelect.value = this.state.zone.name;
        } else if (filtered.length > 0) {
            this.elements.zoneSelect.value = filtered[0].name;
            this.state.zone.name = filtered[0].name;
        }
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
        // Deduplicate by ID as a safety net
        const seen = new Set();
        const raw = data.patterns || [];
        this.state.patterns = raw.filter(p => {
            if (seen.has(p.id)) return false;
            seen.add(p.id);
            return true;
        });
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
            emptyEl.textContent = 'No DJs in the booth \u2014 generate a connect code below';
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

            // Avatar
            let avatarEl;
            if (dj.avatar_url && /^https?:\/\//.test(dj.avatar_url)) {
                avatarEl = document.createElement('img');
                avatarEl.className = 'dj-avatar';
                avatarEl.src = dj.avatar_url;
                avatarEl.alt = '';
                avatarEl.loading = 'lazy';
            } else {
                avatarEl = document.createElement('div');
                avatarEl.className = 'dj-avatar dj-avatar-initials';
                avatarEl.textContent = (dj.dj_name || '?').charAt(0).toUpperCase();
            }

            // Name and stats
            const infoDiv = document.createElement('div');
            infoDiv.className = 'dj-info';

            // Name + palette wrapper
            const namePaletteDiv = document.createElement('div');
            namePaletteDiv.className = 'dj-name-palette';

            const nameSpan = document.createElement('span');
            nameSpan.className = 'dj-name';
            nameSpan.textContent = dj.dj_name;
            namePaletteDiv.appendChild(nameSpan);

            // Color palette swatches (max 5)
            if (Array.isArray(dj.color_palette)) {
                const colors = dj.color_palette.slice(0, 5);
                colors.forEach(color => {
                    const swatch = document.createElement('span');
                    swatch.className = 'palette-swatch';
                    swatch.style.background = color;
                    namePaletteDiv.appendChild(swatch);
                });
            }

            infoDiv.appendChild(namePaletteDiv);

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

            // Per-DJ sync health badges
            const syncHealthDiv = document.createElement('div');
            syncHealthDiv.className = 'dj-sync-health';
            if (dj.clock_sync_age_s !== undefined && dj.clock_sync_age_s !== null) {
                const clockBadge = document.createElement('span');
                clockBadge.className = 'dj-sync-badge ' + (dj.clock_sync_age_s < 60 ? 'fresh' : 'stale');
                clockBadge.textContent = dj.clock_sync_age_s < 60 ? 'CLK OK' : `CLK ${Math.round(dj.clock_sync_age_s)}s`;
                syncHealthDiv.appendChild(clockBadge);
            }
            if (dj.clock_drift_rate !== undefined && dj.clock_drift_rate > 0.1) {
                const driftBadge = document.createElement('span');
                driftBadge.className = 'dj-sync-badge ' + (dj.clock_drift_rate > 5 ? 'stale' : '');
                driftBadge.textContent = `Drift: ${dj.clock_drift_rate.toFixed(1)}ms/m`;
                syncHealthDiv.appendChild(driftBadge);
            }
            if (dj.jitter_ms !== undefined && dj.jitter_ms > 0) {
                const jitterBadge = document.createElement('span');
                jitterBadge.className = 'dj-sync-badge ' + (dj.jitter_ms > 10 ? 'stale' : 'fresh');
                jitterBadge.textContent = `Jtr: ${dj.jitter_ms.toFixed(1)}ms`;
                syncHealthDiv.appendChild(jitterBadge);
            }
            if (syncHealthDiv.children.length > 0) {
                infoDiv.appendChild(syncHealthDiv);
            }

            // Actions area
            const actionsDiv = document.createElement('div');
            actionsDiv.className = 'dj-actions';

            if (isActive) {
                const badge = document.createElement('span');
                badge.className = 'dj-badge-live';
                badge.textContent = 'LIVE';
                actionsDiv.appendChild(badge);
            }
            if (dj.direct_mode) {
                const directBadge = document.createElement('span');
                directBadge.className = 'dj-badge-direct';
                directBadge.textContent = dj.mc_connected ? 'DIRECT: MC OK' : 'DIRECT: RELAY';
                if (!dj.mc_connected) directBadge.classList.add('dj-badge-direct-relay');
                actionsDiv.appendChild(directBadge);
            }
            if (!isActive) {
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
            djEl.appendChild(avatarEl);
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

        // Store per-zone entities for multi-zone preview
        if (data.zone_entities) {
            this.state.zoneEntities = data.zone_entities;
        }

        // Update zone_patterns from state broadcast (debounced)
        if (data.zone_patterns) {
            const newJson = JSON.stringify(data.zone_patterns);
            if (newJson !== this._lastZonePatternsJson) {
                this._lastZonePatternsJson = newJson;
                this.state.zonePatterns = data.zone_patterns;
                this._syncBitmapStateFromZonePatterns();
                this._renderZoneChips();
                this._updatePatternHighlightForZones();
            }
        }

        // Store latency, BPM, FPS, and sync metrics for throttled update
        if (data.ping_ms !== undefined) {
            this.state.latencyMs = data.ping_ms;
            this.state.pingMs = data.ping_ms;
        } else if (data.latency_ms !== undefined) {
            this.state.latencyMs = data.latency_ms;
        }
        if (data.pipeline_latency_ms !== undefined) {
            this.state.pipelineLatencyMs = data.pipeline_latency_ms;
        }
        if (data.jitter_ms !== undefined) {
            this.state.jitterMs = data.jitter_ms;
        }
        if (data.sync_confidence !== undefined) {
            this.state.syncConfidence = data.sync_confidence;
        }
        if (data.visual_delay_ms !== undefined) {
            this.state.effectiveDelayMs = data.visual_delay_ms;
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
                try {
                    this._updateMeters();
                    this._updateBeatIndicator();
                    this._updateFrameCount();

                    // Update latency display with warning if > 500ms
                    if (this.state.latencyMs !== undefined && this.elements.latencyDisplay) {
                        this.elements.latencyDisplay.textContent = `Latency: ${this.state.latencyMs.toFixed(1)}ms`;
                        this.elements.latencyDisplay.classList.toggle('warning', this.state.latencyMs > 500);
                    }

                    // Update sync dashboard metrics
                    this._updateSyncDashboard();

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
                    if (this._previewInitialized && !this._previewFailed) {
                        this._updatePreviewFromAudioState();
                    }
                } catch (error) {
                    console.error('[UI] Audio state update failed', error);
                    this._showToast('Live UI update error (recovered)', 'warning', 2500);
                } finally {
                    this._meterUpdatePending = false;
                }
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

    _updateSyncDashboard() {
        // Ping
        if (this.elements.metricPing && this.state.pingMs !== undefined) {
            const ping = this.state.pingMs;
            this.elements.metricPing.textContent = `Ping: ${Math.round(ping)}ms`;
            this.elements.metricPing.className = 'sync-metric ' + (ping < 50 ? 'good' : ping < 150 ? 'warn' : 'bad');
        }
        // Pipeline
        if (this.elements.metricPipeline && this.state.pipelineLatencyMs !== undefined) {
            const pl = this.state.pipelineLatencyMs;
            this.elements.metricPipeline.textContent = `Pipeline: ${Math.round(pl)}ms`;
            this.elements.metricPipeline.className = 'sync-metric ' + (pl < 50 ? 'good' : pl < 200 ? 'warn' : 'bad');
        }
        // Delay
        if (this.elements.metricDelay && this.state.effectiveDelayMs !== undefined) {
            this.elements.metricDelay.textContent = `Delay: ${Math.round(this.state.effectiveDelayMs)}ms`;
            this.elements.metricDelay.className = 'sync-metric';
        }
        // Sync confidence
        if (this.elements.metricSync && this.state.syncConfidence !== undefined) {
            const sc = this.state.syncConfidence;
            this.elements.metricSync.textContent = `Sync: ${Math.round(sc)}%`;
            this.elements.metricSync.className = 'sync-metric ' + (sc >= 80 ? 'good' : sc >= 50 ? 'warn' : 'bad');
        }
        // Jitter
        if (this.elements.metricJitter && this.state.jitterMs !== undefined) {
            const jt = this.state.jitterMs;
            this.elements.metricJitter.textContent = `Jitter: ${jt.toFixed(1)}ms`;
            this.elements.metricJitter.className = 'sync-metric ' + (jt < 5 ? 'good' : jt < 15 ? 'warn' : 'bad');
        }
    }

    _handlePatternChanged(data) {
        this.state.currentPattern = data.pattern;
        this._updateCurrentPattern(data.pattern);

        // Sync entity counts from pattern recommended_entities
        // Server already applies these; we just mirror to the UI
        const patternList = data.patterns || this.state.patterns || [];
        if (data.patterns) this.state.patterns = patternList;
        const patternMap = {};
        patternList.forEach(p => { patternMap[p.id] = p; });

        // Update per-zone pattern map and sync entity counts
        if (data.zone_patterns) {
            this.state.zonePatterns = data.zone_patterns;

            // Update allZones entity counts to match each zone's pattern
            (this.state.allZones || []).forEach(zone => {
                const zp = data.zone_patterns[zone.name];
                const patId = zp && typeof zp === 'object' ? zp.pattern : zp;
                const info = patId && patternMap[patId];
                if (info && info.recommended_entities) {
                    zone.entity_count = info.recommended_entities;
                }
            });

            this._syncBitmapStateFromZonePatterns();
            this._renderStageZoneList();
            this._renderZoneChips();
        }

        // Update current zone's entity count slider
        const currentPatInfo = patternMap[data.pattern];
        if (currentPatInfo && currentPatInfo.recommended_entities) {
            this.state.zone.entityCount = currentPatInfo.recommended_entities;
            this._setSliderValue('zone-entity-count', 'val-entity-count',
                currentPatInfo.recommended_entities, v => `${v}`);
        }

        // Highlight based on selected zones
        if (this.state.selectedZones.size > 0) {
            this._updatePatternHighlightForZones();
        } else {
            this._highlightActivePattern(data.pattern);
        }

        // Sync bitmap section highlight for selected zones in bitmap mode
        if (data.zone_patterns) {
            const selected = Array.from(this.state.selectedZones);
            if (selected.length > 0) {
                const zpEntry = data.zone_patterns[selected[0]];
                const patId = zpEntry && typeof zpEntry === 'object' ? zpEntry.pattern : zpEntry;
                if (patId && patId.startsWith('bmp_')) {
                    this.state.bitmap.activePattern = patId;
                    this._highlightBitmapPattern(patId);
                }
            }
        }

        // Show transition status if transitioning
        if (data.transitioning && this.elements.transitionStatus) {
            this.elements.transitionStatus.classList.remove('hidden');
            if (data.transition_duration) {
                setTimeout(() => {
                    if (this.elements.transitionStatus) {
                        this.elements.transitionStatus.classList.add('hidden');
                    }
                }, data.transition_duration * 1000);
            }
        } else if (this.elements.transitionStatus) {
            this.elements.transitionStatus.classList.add('hidden');
        }
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
                this.elements.meters[i].style.transform = `scaleX(${value / 100})`;
            }
            if (this.elements.meterValues[i]) {
                this.elements.meterValues[i].textContent = `${Math.round(value)}%`;
            }
        }

        // Master meter (average)
        const masterValue = this.state.amplitude * 100;
        if (this.elements.meterMaster) {
            this.elements.meterMaster.style.transform = `scaleX(${masterValue / 100})`;
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

            // Beat-reactive header glow (cached element)
            const header = this.elements.header;
            if (header) {
                header.classList.add('beat-pulse');
                setTimeout(() => header.classList.remove('beat-pulse'), 120);
            }
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

    _updateVisualDelayDisplay() {
        const slider = this.elements.ctrlVisualDelay;
        const valueDisplay = document.getElementById('val-visual-delay');
        if (slider) {
            slider.value = this.state.visualDelayMs || 0;
        }
        if (valueDisplay) {
            valueDisplay.textContent = `${Math.round(this.state.visualDelayMs || 0)}ms`;
        }
    }

    _updateVisualDelayModeDisplay() {
        const select = this.elements.syncMode;
        if (select) {
            select.value = this.state.visualDelayMode || 'manual';
        }
        // Show/hide manual delay slider based on mode
        if (this.elements.syncDelayRow) {
            this.elements.syncDelayRow.style.display =
                (this.state.visualDelayMode || 'manual') === 'manual' ? '' : 'none';
        }
    }

    _syncBandMaterials(materials) {
        if (!Array.isArray(materials) || materials.length !== 5) return;
        this.state.bandMaterials = materials;
        for (let i = 0; i < 5; i++) {
            const el = document.getElementById(`band-material-${i}`);
            if (el) {
                el.value = materials[i] || '';
            }
        }
    }

    _updateBandMaterialsSourceHint() {
        const el = document.getElementById('band-materials-source-hint');
        if (!el) return;

        if (this.state.bandMaterialsSource === 'dj_palette') {
            const activeDj = this.state.djRoster?.find(d => d.dj_id === this.state.activeDJ);
            const djName = activeDj ? activeDj.dj_name : 'DJ';
            el.textContent = `Using ${djName}'s palette`;
            el.style.color = '#4ecdc4';
        } else if (this.state.bandMaterialsSource === 'admin') {
            el.textContent = 'Manually overridden by admin';
            el.style.color = '#ff6b6b';
        } else {
            el.textContent = '';
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

        // Group patterns by category
        const groups = {};
        this.state.patterns.forEach(pattern => {
            const cat = pattern.category || 'Other';
            if (!groups[cat]) groups[cat] = [];
            groups[cat].push(pattern);
        });

        // Render each category group
        const sortedCategories = Object.keys(groups).sort((a, b) => {
            if (a === 'Other') return 1;
            if (b === 'Other') return -1;
            return a.localeCompare(b);
        });

        sortedCategories.forEach(category => {
            const label = document.createElement('div');
            label.className = 'pattern-category-label';
            label.textContent = category;
            grid.appendChild(label);

            const groupGrid = document.createElement('div');
            groupGrid.className = 'pattern-category-grid';

            groups[category].forEach(pattern => {
                const btn = document.createElement('button');
                btn.className = 'pattern-btn';
                btn.dataset.pattern = pattern.id;
                btn.textContent = pattern.name;
                btn.title = pattern.description || '';

                if (pattern.id === this.state.currentPattern) {
                    btn.classList.add('active');
                }

                btn.addEventListener('click', () => this._setPattern(pattern.id));
                groupGrid.appendChild(btn);
            });

            grid.appendChild(groupGrid);
        });
    }

    _highlightActivePattern(patternId) {
        document.querySelectorAll('.pattern-btn').forEach(btn => {
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

    _highlightActivePreset(preset) {
        this.elements.presetButtons.forEach(btn => {
            const isActive = btn.dataset.preset === preset;
            btn.classList.toggle('active', isActive);
            if (isActive) {
                btn.classList.remove('just-selected');
                void btn.offsetWidth;
                btn.classList.add('just-selected');
                setTimeout(() => btn.classList.remove('just-selected'), 400);
            }
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
            const isActive = tab.dataset.tab === tabName;
            tab.classList.toggle('active', isActive);
            tab.setAttribute('aria-selected', String(isActive));
        });

        // Slide tab indicator
        this._updateTabIndicator();

        // Update panels
        this.elements.tabPanels.forEach(panel => {
            panel.classList.toggle('active', panel.id === `${tabName}-panel`);
        });

        // Preview animation is handled by the strip, not by tab switching
    }

    _updateTabIndicator() {
        const bar = document.getElementById('tab-bar');
        const active = bar?.querySelector('.tab.active');
        if (!bar || !active) return;
        const barRect = bar.getBoundingClientRect();
        const tabRect = active.getBoundingClientRect();
        bar.style.setProperty('--tab-indicator-left', `${tabRect.left - barRect.left}px`);
        bar.style.setProperty('--tab-indicator-width', `${tabRect.width}px`);
    }

    _setPattern(patternId) {
        const msg = { type: 'set_pattern', pattern: patternId };
        const selected = Array.from(this.state.selectedZones);
        if (selected.length > 0) {
            msg.zones = selected;
        }
        this.ws.send(msg);
    }

    // --- Zone Patterns Helpers ---

    /** Extract pattern ID from zone_patterns entry (handles string or {pattern, render_mode} object). */
    _getZonePatternId(zoneName) {
        const entry = this.state.zonePatterns[zoneName];
        if (!entry) return null;
        return typeof entry === 'object' ? entry.pattern : entry;
    }

    /** Get render mode for a zone from zone_patterns. */
    _getZoneRenderMode(zoneName) {
        const entry = this.state.zonePatterns[zoneName];
        if (entry && typeof entry === 'object') return entry.render_mode || 'block';
        return 'block';
    }

    /** Sync bitmap initializedZones and 3D preview from zone_patterns render_mode. */
    _syncBitmapStateFromZonePatterns() {
        const zp = this.state.zonePatterns;
        if (!zp) return;
        for (const [zoneName, info] of Object.entries(zp)) {
            const rm = typeof info === 'object' ? info.render_mode : null;
            if (rm === 'bitmap') {
                this.state.bitmap.initializedZones.add(zoneName);
                // Activate 3D bitmap preview for this zone
                if (this._bitmapPreview) {
                    const zg = this._previewZoneGroups[zoneName];
                    if (zg) {
                        const bw = this.state.bitmap.width || 16;
                        const bh = this.state.bitmap.height || 12;
                        const patId = typeof info === 'object' ? info.pattern : info;
                        this._bitmapPreview.activate(zoneName, bw, bh, patId || 'bmp_spectrum', zg);
                        this._bitmapPreview.setZoneVisible(zoneName, true);
                    }
                }
            } else {
                this.state.bitmap.initializedZones.delete(zoneName);
                // Deactivate bitmap preview, show block entities
                if (this._bitmapPreview) {
                    this._bitmapPreview.deactivate(zoneName);
                }
            }
        }
    }

    // --- Zone Chip Bar ---

    _renderZoneChips() {
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
            nameSpan.textContent = this._formatZoneDisplayName(zone.name, zone.stage);
            chip.appendChild(nameSpan);

            const zp = this.state.zonePatterns[zone.name];
            const patId = zp && typeof zp === 'object' ? zp.pattern : zp;
            const renderMode = zp && typeof zp === 'object' ? zp.render_mode : 'block';

            // Render mode badge
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
                    // Multi-select toggle
                    if (this.state.selectedZones.has(zone.name)) {
                        this.state.selectedZones.delete(zone.name);
                    } else {
                        this.state.selectedZones.add(zone.name);
                    }
                } else {
                    // Single-select: clear others, select this one
                    this.state.selectedZones.clear();
                    this.state.selectedZones.add(zone.name);
                    // Also set as active zone for Zone Settings tab
                    this.state.zone.name = zone.name;
                    if (this.elements.zoneSelect) {
                        this.elements.zoneSelect.value = zone.name;
                    }
                    // Sync bitmap zone selector with chip selection
                    if (this.elements.bitmapZone) {
                        this.elements.bitmapZone.value = zone.name;
                    }
                    this._setZoneControlsLoading(true);
                    this._requestZoneStatus();
                }
                this._renderZoneChips();
                this._updatePatternHighlightForZones();
            });

            bar.appendChild(chip);
        });
    }

    _quickSelectZones(action) {
        const zones = this.state.allZones || [];
        const selectedStage = this.state.selectedStage;
        const filtered = selectedStage
            ? zones.filter(z => z.stage === selectedStage)
            : zones;

        this.state.selectedZones.clear();

        if (action === 'all') {
            filtered.forEach(z => this.state.selectedZones.add(z.name));
        }
        // 'none' just clears

        this._renderZoneChips();
        this._updatePatternHighlightForZones();
    }

    /**
     * Format a stage name for display: replace underscores, title case.
     * e.g. "stone" → "Stone", "my_stage" → "My Stage"
     */
    _formatStageName(name) {
        return name.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
    }

    /**
     * Format a zone name for display: strip stage prefix, replace underscores, title case.
     * e.g. "stone_right_wing" with stage "stone" → "Right Wing"
     */
    _formatZoneDisplayName(zoneName, stageName) {
        let display = zoneName;
        // Strip stage prefix if present
        if (stageName && display.startsWith(stageName + '_')) {
            display = display.slice(stageName.length + 1);
        }
        // Replace underscores with spaces, title case
        return display.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
    }

    _updatePatternHighlightForZones() {
        const selected = Array.from(this.state.selectedZones);
        if (selected.length === 0) {
            // No zones selected: highlight global current pattern
            this._highlightActivePattern(this.state.currentPattern);
            return;
        }

        // Get unique patterns used by selected zones
        const patternsInUse = new Set();
        selected.forEach(zn => {
            const zp = this.state.zonePatterns[zn];
            const patId = zp && typeof zp === 'object' ? zp.pattern : zp;
            if (patId) patternsInUse.add(patId);
        });

        const allSame = patternsInUse.size === 1;

        document.querySelectorAll('.pattern-btn').forEach(btn => {
            const patternId = btn.dataset.pattern;
            btn.classList.remove('active', 'partial');

            if (patternsInUse.has(patternId)) {
                if (allSame) {
                    btn.classList.add('active');
                } else {
                    btn.classList.add('partial');
                }
            }
        });
    }

    _setPreset(preset) {
        this.ws.send({ type: 'set_preset', preset: preset });
    }

    _saveScene() {
        const name = this.elements.sceneNameInput.value.trim();
        if (!name) {
            alert('Please enter a scene name');
            return;
        }

        this.ws.send({ type: 'save_scene', name });
        this.elements.sceneNameInput.value = '';
    }

    _loadScene(name) {
        this.ws.send({ type: 'load_scene', name });
    }

    _deleteScene(name) {
        if (confirm(`Delete scene "${name}"?`)) {
            this.ws.send({ type: 'delete_scene', name });
        }
    }

    _renderScenes() {
        if (!this.elements.scenesGrid) return;

        const builtInScenes = ['Chill Lounge', 'EDM Stage', 'Rock Arena', 'Ambient'];

        this.elements.scenesGrid.replaceChildren();
        this.state.scenes.forEach(scene => {
            const isBuiltIn = builtInScenes.includes(scene.name);
            const isActive = this.state.currentScene === scene.name;

            const card = document.createElement('div');
            card.className = `scene-card${isActive ? ' active' : ''}${isBuiltIn ? ' built-in' : ''}`;
            card.dataset.scene = scene.name;

            if (!isBuiltIn) {
                const deleteBtn = document.createElement('button');
                deleteBtn.className = 'scene-card-delete';
                deleteBtn.dataset.scene = scene.name;
                deleteBtn.textContent = '\u00d7';
                card.appendChild(deleteBtn);
            }

            const nameDiv = document.createElement('div');
            nameDiv.className = 'scene-card-name';
            nameDiv.textContent = scene.name;
            card.appendChild(nameDiv);

            const detailsDiv = document.createElement('div');
            detailsDiv.className = 'scene-card-details';

            const patternDiv = document.createElement('div');
            patternDiv.className = 'scene-card-pattern';
            patternDiv.textContent = scene.pattern;
            detailsDiv.appendChild(patternDiv);

            const infoDiv = document.createElement('div');
            infoDiv.textContent = `${scene.preset} \u00b7 ${scene.entity_count} blocks`;
            detailsDiv.appendChild(infoDiv);

            card.appendChild(detailsDiv);
            this.elements.scenesGrid.appendChild(card);
        });

        // Add click handlers for scene cards
        this.elements.scenesGrid.querySelectorAll('.scene-card').forEach(card => {
            card.addEventListener('click', (e) => {
                // Don't trigger load if clicking delete button
                if (!e.target.classList.contains('scene-card-delete')) {
                    this._loadScene(card.dataset.scene);
                }
            });
        });

        // Add click handlers for delete buttons
        this.elements.scenesGrid.querySelectorAll('.scene-card-delete').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                this._deleteScene(btn.dataset.scene);
            });
        });
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
        document.getElementById('app').classList.toggle('mode-blackout', this.state.blackout);

        this.ws.send({
            type: 'trigger_effect',
            effect: 'blackout',
            intensity: this.state.blackout ? 1.0 : 0.0
        });
    }

    _toggleFreeze() {
        this.state.freeze = !this.state.freeze;
        this.elements.btnFreeze.classList.toggle('active', this.state.freeze);
        document.getElementById('app').classList.toggle('mode-freeze', this.state.freeze);

        this.ws.send({
            type: 'trigger_effect',
            effect: 'freeze',
            intensity: this.state.freeze ? 1.0 : 0.0
        });
    }

    _tapTempo() {
        const now = Date.now();

        // Visual tap feedback
        const tapBtn = this.elements.btnTapTempo;
        if (tapBtn) {
            tapBtn.classList.add('tapped');
            setTimeout(() => tapBtn.classList.remove('tapped'), 100);
        }

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

            // BPM display pulse
            this.elements.tapBpm.classList.remove('pulse');
            void this.elements.tapBpm.offsetWidth;
            this.elements.tapBpm.classList.add('pulse');
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
            this.elements.particleVizSection.classList.toggle('hidden', !showParticles);
        }

        // Show Bedrock notice when particles are enabled
        if (this.elements.bedrockNotice) {
            this.elements.bedrockNotice.classList.toggle('hidden', !showParticles);
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
                    this.elements.fixedColorRow.classList.toggle('hidden', mode !== 'fixed');
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

    // === Voice Chat ===

    _setupVoiceChatListeners() {
        // Stream toggle
        if (this.elements.voiceStreamToggle) {
            this.elements.voiceStreamToggle.addEventListener('change', () => {
                this._toggleVoiceStream();
            });
        }

        // Channel type selector
        if (this.elements.voiceChannelType) {
            this.elements.voiceChannelType.addEventListener('change', () => {
                this._setVoiceChannelType(this.elements.voiceChannelType.value);
            });
        }

        // Distance slider - debounced
        if (this.elements.voiceDistance) {
            const sendVoiceDistance = debounce((val) => {
                this._setVoiceDistance(val);
            }, 50);

            this.elements.voiceDistance.addEventListener('input', () => {
                const val = parseInt(this.elements.voiceDistance.value);
                const display = document.getElementById('val-voice-distance');
                if (display) display.textContent = `${val}`;
                this.state.voiceChat.distance = val;
                sendVoiceDistance(val);
            });
        }

        // Initialize UI as unavailable until we hear from server
        this._updateVoiceChatUI();
    }

    _toggleVoiceStream() {
        this.state.voiceChat.enabled = !this.state.voiceChat.enabled;
        this._sendVoiceConfig();
    }

    _setVoiceChannelType(type) {
        this.state.voiceChat.channelType = type;

        // Show/hide distance slider based on channel type
        if (this.elements.voiceDistanceRow) {
            this.elements.voiceDistanceRow.classList.toggle('hidden', type !== 'locational');
        }

        this._sendVoiceConfig();
    }

    _setVoiceDistance(distance) {
        this.state.voiceChat.distance = distance;
        this._sendVoiceConfig();
    }

    _sendVoiceConfig() {
        this.ws.send({
            type: 'voice_config',
            enabled: this.state.voiceChat.enabled,
            channel_type: this.state.voiceChat.channelType,
            distance: this.state.voiceChat.distance,
            zone: this.state.zone.name || 'main'
        });
    }

    _handleVoiceStatus(data) {
        const wasStreaming = this.state.voiceChat.streaming;

        this.state.voiceChat.available = data.available || false;
        this.state.voiceChat.streaming = data.streaming || false;
        this.state.voiceChat.connectedPlayers = data.connected_players || 0;

        if (data.channel_type) {
            this.state.voiceChat.channelType = data.channel_type;
        }

        // Sync enabled state from streaming status
        if (data.streaming !== undefined) {
            this.state.voiceChat.enabled = data.streaming;
        }

        this._updateVoiceChatUI();

        // Notify on streaming state changes
        if (data.streaming && !wasStreaming) {
            this._showToast('Voice chat streaming started', 'success');
        } else if (!data.streaming && wasStreaming) {
            this._showToast('Voice chat streaming stopped', 'info');
        }
    }

    _handleLinkStatus(data) {
        this.state.linkEnabled = data.enabled || false;
        this.state.linkPeers = data.peers || 0;
        this.state.linkTempo = data.tempo || 0;

        // Update the sync dashboard with Link info if peers > 0
        if (data.peers > 0) {
            this._showToast(`Link: ${data.peers} peer(s) @ ${data.tempo} BPM`, 'info');
        }
    }

    _updateVoiceChatUI() {
        const vc = this.state.voiceChat;
        const dot = this.elements.voiceDot;
        const statusText = this.elements.voiceStatusText;
        const playersStat = this.elements.voicePlayersStat;
        const unavailableMsg = this.elements.voiceUnavailableMsg;
        const controls = this.elements.voiceControls;
        const streamToggle = this.elements.voiceStreamToggle;
        const channelType = this.elements.voiceChannelType;
        const distanceSlider = this.elements.voiceDistance;
        const distanceRow = this.elements.voiceDistanceRow;

        // Update status dot and text
        if (dot) {
            dot.classList.remove('voice-dot-streaming', 'voice-dot-available', 'voice-dot-unavailable');
            if (!vc.available) {
                dot.classList.add('voice-dot-unavailable');
            } else if (vc.streaming) {
                dot.classList.add('voice-dot-streaming');
            } else {
                dot.classList.add('voice-dot-available');
            }
        }

        if (statusText) {
            if (!vc.available) {
                statusText.textContent = 'Unavailable';
            } else if (vc.streaming) {
                statusText.textContent = 'Streaming';
            } else {
                statusText.textContent = 'Ready';
            }
        }

        // Players stat
        if (playersStat) {
            if (vc.available && vc.connectedPlayers > 0) {
                playersStat.textContent = `${vc.connectedPlayers} player${vc.connectedPlayers !== 1 ? 's' : ''}`;
                playersStat.style.display = '';
            } else {
                playersStat.style.display = 'none';
            }
        }

        // Show/hide unavailable message
        if (unavailableMsg) {
            unavailableMsg.classList.toggle('hidden', vc.available);
        }

        // Enable/disable controls based on availability
        if (controls) {
            controls.classList.toggle('voice-controls-disabled', !vc.available);
        }

        if (streamToggle) {
            streamToggle.checked = vc.enabled;
            streamToggle.disabled = !vc.available;
        }

        if (channelType) {
            channelType.value = vc.channelType;
            channelType.disabled = !vc.available;
        }

        if (distanceSlider) {
            distanceSlider.value = vc.distance;
            distanceSlider.disabled = !vc.available;
            const display = document.getElementById('val-voice-distance');
            if (display) display.textContent = `${vc.distance}`;
        }

        // Show/hide distance row based on channel type
        if (distanceRow) {
            distanceRow.classList.toggle('hidden', vc.channelType !== 'locational');
        }
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
        toast.classList.add('hiding');
        toast.classList.remove('show');
        setTimeout(() => {
            if (toast.parentNode) {
                toast.parentNode.removeChild(toast);
            }
        }, 200);
    }

    // === Service Status Indicators ===

    _updateServiceIndicators() {
        const pythonEl = this.elements.svcPython;
        const mcEl = this.elements.svcMinecraft;

        if (pythonEl) {
            pythonEl.classList.toggle('connected', this.state.connected);
            pythonEl.classList.toggle('disconnected', !this.state.connected);
        }

        if (mcEl) {
            mcEl.classList.toggle('connected', this.state.minecraftConnected);
            mcEl.classList.toggle('disconnected', !this.state.minecraftConnected);
            const label = mcEl.querySelector('.svc-label');
            if (label) {
                const serverType = this.state.minecraftServerType;
                label.textContent = serverType
                    ? `MC · ${serverType.charAt(0).toUpperCase() + serverType.slice(1)}`
                    : 'Minecraft';
            }
        }
    }

    _handleMinecraftStatus(data) {
        const wasConnected = this.state.minecraftConnected;
        this.state.minecraftConnected = data.connected;
        if (data.server_type) {
            this.state.minecraftServerType = data.server_type;
        }
        if (!data.connected) {
            this.state.minecraftServerType = null;
        }
        this._updateServiceIndicators();
        this._updateMCDependentControls();

        // Notify on status change
        if (data.connected && !wasConnected) {
            const typeLabel = data.server_type ? ` (${data.server_type})` : '';
            this._showToast(`Minecraft connected${typeLabel}`, 'success');
            // Auto-fetch bitmap data when Minecraft reconnects
            if (!this.state.bitmap.dataFetched) {
                this._fetchBitmapData();
            }
        } else if (!data.connected && wasConnected) {
            this._showToast('Minecraft disconnected', 'warning');
            // Reset bitmap data state so it re-fetches on reconnect
            this.state.bitmap.dataFetched = false;
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
                badge.textContent = dj.mc_connected ? 'DIRECT: MC OK' : 'DIRECT: RELAY';
                if (!dj.mc_connected) badge.classList.add('dj-badge-direct-relay');
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

    // === Stage/Zone Hierarchy List ===

    _renderStageZoneList() {
        const container = this.elements.stageZoneList;
        if (!container) return;

        while (container.firstChild) {
            container.removeChild(container.firstChild);
        }

        const stages = this.state.stages || [];
        const allZones = this.state.allZones || [];
        const selectedStage = this.state.selectedStage;

        // Group zones by stage
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

        // If filtering by a stage, only show that stage
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

        // Render each stage as an expandable container
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

            // Zone sub-items
            const zoneContainer = document.createElement('div');
            zoneContainer.className = 'stage-zones hidden';

            const stageZones = stageZoneMap.get(stage.name) || [];
            // Also use zones from the stage object if no zone data from zones message
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

            // Toggle expand/collapse on header click
            stageHeader.addEventListener('click', () => {
                const isHidden = zoneContainer.classList.contains('hidden');
                zoneContainer.classList.toggle('hidden');
                expandIcon.textContent = isHidden ? '\u25BC' : '\u25B6';
                stageEl.classList.toggle('expanded', isHidden);
            });

            container.appendChild(stageEl);
        });

        // Render standalone zones (not belonging to any stage)
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
                this._setZoneControlsLoading(true);
                this._requestZoneStatus();
                this.ws.send({ type: 'get_zones' });
                this._showToast(`Switched to zone "${zone.name}"`, 'info');
            });
            actions.appendChild(btn);
        }

        item.appendChild(info);
        item.appendChild(actions);
        return item;
    }

    // === 3D Preview Methods ===

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
            // Prefer local vendor copy.
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

    _initPreviewStrip() {
        const strip = document.getElementById('preview-strip');
        const collapseBtn = document.getElementById('preview-strip-collapse');
        if (!strip) return;

        // Apply saved collapsed state
        if (this._previewStripCollapsed) {
            strip.classList.add('collapsed');
            if (collapseBtn) {
                collapseBtn.querySelector('.collapse-arrow').textContent = '\u25B2'; // ▲
            }
        }

        // Collapse toggle
        if (collapseBtn) {
            collapseBtn.addEventListener('click', () => {
                this._previewStripCollapsed = !this._previewStripCollapsed;
                strip.classList.toggle('collapsed', this._previewStripCollapsed);
                collapseBtn.querySelector('.collapse-arrow').textContent =
                    this._previewStripCollapsed ? '\u25B2' : '\u25BC';
                localStorage.setItem('mcav-preview-collapsed', String(this._previewStripCollapsed));

                if (this._previewStripCollapsed) {
                    this._stopPreviewAnimation();
                } else {
                    this._onPreviewResize();
                    this._startPreviewAnimation();
                }
            });
        }

        // Listen for strip body transition end to resize canvas
        const body = strip.querySelector('.preview-strip-body');
        if (body) {
            body.addEventListener('transitionend', () => {
                if (!this._previewStripCollapsed) {
                    this._onPreviewResize();
                }
            });
        }
    }

    async _initPreview() {
        if (this._previewInitialized || this._previewFailed) return;

        const canvas = document.getElementById('preview-canvas');
        const wrapper = canvas?.parentElement;
        if (!canvas || !wrapper) {
            console.warn('[Preview] Canvas not found');
            return;
        }

        // Check if THREE is loaded
        if (typeof THREE === 'undefined') {
            console.warn('[Preview] Three.js not loaded');
            const loaded = await this._ensureThreeLoaded();
            if (!loaded || typeof THREE === 'undefined') {
                this._previewFailed = true;
                this._showToast('Three.js failed to load; 3D Preview disabled', 'warning');
                return;
            }
        }

        try {
            // Scene
            this._previewScene = new THREE.Scene();
            this._previewScene.background = new THREE.Color(0x0a0a0f);
            this._previewScene.fog = new THREE.Fog(0x0a0a0f, 20, 50);

            // Reusable spherical for camera math (avoids per-frame allocation)
            this._previewSpherical = new THREE.Spherical();

            // Camera
            const width = Math.max(1, wrapper.clientWidth);
            const height = Math.max(1, wrapper.clientHeight);
            const aspect = width / height;
            this._previewCamera = new THREE.PerspectiveCamera(60, aspect, 0.1, 100);
            this._previewCamera.position.set(12, 10, 12);
            this._previewCamera.lookAt(0, 2, 0);

            // Renderer
            this._previewRenderer = new THREE.WebGLRenderer({ canvas, antialias: true });
            this._previewRenderer.setSize(width, height);
            this._previewRenderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
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

            // Procedural environment disabled — clean background only
            // Block texture manager kept for zone block rendering
            if (typeof BlockTextureManager !== 'undefined') {
                this._textureManager = new BlockTextureManager();
                this._textureManager.preload();
            }

            // Pre-create static band-color materials for reuse
            this._bandColorMaterials = this._previewConfig.colors.map(color =>
                new THREE.MeshStandardMaterial({
                    color: color,
                    roughness: 0.3,
                    metalness: 0.2,
                    emissive: new THREE.Color(color),
                    emissiveIntensity: 0.2
                })
            );

            // Create initial blocks
            this._createPreviewBlocks(16);

            // Initialize particle system
            if (typeof ParticleSystem !== 'undefined') {
                this._previewParticleSystem = new ParticleSystem(this._previewScene, 500);
            }

            // Initialize block indicators
            if (typeof BlockIndicatorSystem !== 'undefined') {
                this._previewBlockIndicators = new BlockIndicatorSystem(this._previewScene, 8);
                // Match default _previewShowGrid = false
                this._previewBlockIndicators.setVisible(false);
            }

            // Initialize bitmap LED wall preview
            if (typeof BitmapPreview !== 'undefined') {
                this._bitmapPreview = new BitmapPreview();
            }

            // Setup preview controls
            this._setupPreviewControls();
            this._setupPreviewMouseControls();

            // Handle window resize
            this._boundOnPreviewResize = () => this._onPreviewResize();
            window.addEventListener('resize', this._boundOnPreviewResize);

            this._previewInitialized = true;
            this._previewLastFrameTime = performance.now();

            // If zones are already loaded, build multi-zone layout
            if ((this.state.allZones || []).length > 1) {
                this._rebuildPreviewZoneLayout();
            }
        } catch (error) {
            this._previewFailed = true;
            this._previewInitialized = false;
            this._previewRenderer = null;
            this._previewScene = null;
            this._previewCamera = null;
            console.error('[Preview] Initialization failed', error);
            this._showToast('3D Preview initialization failed; disabled for this session', 'warning');
        }
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

    // === Multi-Zone Preview Methods ===

    /** Compute stage centroid and bounding box from allZones */
    _computeStageLayout() {
        const zones = this.state.allZones || [];
        if (zones.length === 0) {
            this._previewStageCenter = { x: 0, y: 0, z: 0 };
            this._previewStageBounds = null;
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

        // Center on the "main" zone if one exists, otherwise use overall centroid
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
            this._previewStageCenter = {
                x: mox + msx / 2,
                y: moy + msy / 2,
                z: moz + msz / 2
            };
        } else {
            this._previewStageCenter = {
                x: (minX + maxX) / 2,
                y: (minY + maxY) / 2,
                z: (minZ + maxZ) / 2
            };
        }
        this._previewStageBounds = { minX, minY, minZ, maxX, maxY, maxZ };
    }

    /** Get wireframe color for a zone based on its role/name */
    _getZoneWireframeColor(zone) {
        const role = (zone.stage_role || zone.name || '').toLowerCase();
        if (role.includes('main') || role.includes('center')) return 0x00D4FF;  // cyan
        if (role.includes('left') || role.includes('wing_l')) return 0xFF9100;  // orange
        if (role.includes('right') || role.includes('wing_r')) return 0x00E676; // green
        if (role.includes('sky') || role.includes('ceiling')) return 0xD500F9;  // purple
        if (role.includes('audience') || role.includes('floor')) return 0xFFD700; // gold
        return 0x4488AA; // default teal
    }

    /** Create or get a THREE.Group for a specific zone */
    _ensurePreviewZoneGroup(zoneName) {
        if (this._previewZoneGroups[zoneName]) return this._previewZoneGroups[zoneName];

        const zones = this.state.allZones || [];
        const zone = zones.find(z => z.name === zoneName);
        if (!zone) return null;

        const group = new THREE.Group();
        group.name = `zone-${zoneName}`;

        // Position relative to stage center
        const ox = (zone.origin?.x || 0) - this._previewStageCenter.x;
        const oy = (zone.origin?.y || 0) - this._previewStageCenter.y;
        const oz = (zone.origin?.z || 0) - this._previewStageCenter.z;
        const sx = zone.size?.x || 10;
        const sy = zone.size?.y || 10;
        const sz = zone.size?.z || 10;

        // Group origin = zone origin (rotation pivot point)
        // Minecraft rotates around the origin corner, not the center
        group.position.set(ox, oy, oz);
        group.rotation.y = -(zone.rotation || 0) * (Math.PI / 180);

        // Wireframe box showing zone boundaries, offset to (size/2) in local space
        // so it covers (0,0,0) to (sx,sy,sz) before rotation
        const boxGeo = new THREE.BoxGeometry(sx, sy, sz);
        const edgesGeo = new THREE.EdgesGeometry(boxGeo);
        const wireColor = this._getZoneWireframeColor(zone);
        const lineMat = new THREE.LineBasicMaterial({ color: wireColor, opacity: 0.35, transparent: true });
        const wireframe = new THREE.LineSegments(edgesGeo, lineMat);
        wireframe.position.set(sx / 2, sy / 2, sz / 2);
        group.add(wireframe);
        boxGeo.dispose();

        this._previewScene.add(group);

        const zoneGroup = {
            group,
            blocks: [],
            wireframe,
            sizeX: sx,
            sizeY: sy,
            sizeZ: sz,
            zone
        };
        this._previewZoneGroups[zoneName] = zoneGroup;
        return zoneGroup;
    }

    /** Ensure a zone group has enough blocks (pool up/down) */
    _ensureZoneBlockCount(zoneGroup, count) {
        while (zoneGroup.blocks.length < count) {
            const block = this._createPreviewBlock(zoneGroup.blocks.length);
            block.position.set(0, 0, 0);
            block.visible = true;
            zoneGroup.group.add(block);
            zoneGroup.blocks.push(block);
        }
        // Hide excess blocks rather than destroying (pooling)
        for (let i = 0; i < zoneGroup.blocks.length; i++) {
            zoneGroup.blocks[i].visible = i < count;
        }
    }

    /** Update a single block's material from entity data */
    _updateBlockMaterial(block, entity, bands, config) {
        const rawBand = Number.isFinite(entity.band) ? entity.band : 0;
        const bandIndex = Math.max(0, Math.min(4, Math.round(rawBand)));
        block.userData.bandIndex = bandIndex;

        const entityMaterial = entity.material || '';
        if (this._textureManager && entityMaterial && entityMaterial !== block.userData.currentMaterial) {
            const texMat = this._textureManager.getMaterial(entityMaterial);
            if (texMat) {
                // Clone so emissiveIntensity mutations are per-block
                block.material.dispose();
                block.material = texMat.clone();
                block.userData.currentMaterial = entityMaterial;
            }
        } else if (!entityMaterial && block.userData.currentMaterial) {
            // Material removed — dispose cloned material and create fresh band-colored one
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

    /** Rebuild all zone groups from scratch (on zone list change) */
    _rebuildPreviewZoneLayout() {
        if (!this._previewInitialized || !this._previewScene) return;

        // Dispose stage blocks on rebuild (allow re-scan)
        this._disposeStageBlocks();

        // Dispose old zone groups (and their bitmap planes)
        for (const [name, zg] of Object.entries(this._previewZoneGroups)) {
            if (this._bitmapPreview) this._bitmapPreview.deactivate(name);
            zg.blocks.forEach(block => {
                block.geometry.dispose();
                if (block.material && block.material !== this._bandColorMaterials?.[block.userData.bandIndex]) {
                    block.material.dispose();
                }
            });
            zg.wireframe.geometry.dispose();
            zg.wireframe.material.dispose();
            this._previewScene.remove(zg.group);
        }
        this._previewZoneGroups = {};

        // Recompute layout
        this._computeStageLayout();

        const zones = this.state.allZones || [];
        const hasMultipleZones = zones.length > 1 && zones.some(z => z.origin);

        // Show/hide scan button based on stage mode
        const scanBtn = document.getElementById('preview-scan-stage');

        if (hasMultipleZones) {
            this._previewStageMode = true;
            // Hide single-zone floor grid in stage mode
            if (this._previewBlockIndicators) {
                this._previewBlockIndicators.setVisible(false);
            }
            // Pre-create zone groups
            zones.forEach(z => this._ensurePreviewZoneGroup(z.name));

            // Activate bitmap preview for initialized zones only
            if (this._bitmapPreview) {
                const initZones = this.state.bitmap.initializedZones;
                zones.forEach(z => {
                    const zg = this._previewZoneGroups[z.name];
                    if (zg && initZones.has(z.name)) {
                        const bw = this.state.bitmap.width || 16;
                        const bh = this.state.bitmap.height || 12;
                        const bp = this.state.bitmap.activePattern || 'bmp_plasma';
                        this._bitmapPreview.activate(z.name, bw, bh, bp, zg);
                        this._bitmapPreview.setZoneVisible(z.name, true);
                    }
                });
            }

            // Frame camera to stage
            this._frameStage();

            // Show scan button in stage mode
            if (scanBtn) scanBtn.style.display = '';

            // Auto-scan stage blocks on first multi-zone preview
            if (!this._stageBlocksScanned && this.state.selectedStage
                && this.ws && this.ws.isConnected
                && this.state.minecraftConnected) {
                this._stageBlocksScanned = true;
                this._scanStageBlocks();
            }
        } else {
            this._previewStageMode = false;
            // Restore floor grid visibility to match checkbox
            if (this._previewBlockIndicators) {
                this._previewBlockIndicators.setVisible(this._previewShowGrid);
            }
            // Hide scan button in single-zone mode
            if (scanBtn) scanBtn.style.display = 'none';
            // Clean up stage ground if switching back to single-zone
            if (this._stageGround) {
                this._previewScene.remove(this._stageGround);
                this._stageGround.geometry.dispose();
                this._stageGround.material.dispose();
                this._stageGround = null;
            }
        }
    }

    /** Build minimal stage environment (ground plane) for multi-zone mode */
    _buildStageEnvironment() {
        // Remove existing stage ground if any
        if (this._stageGround) {
            this._previewScene.remove(this._stageGround);
            this._stageGround.geometry.dispose();
            this._stageGround.material.dispose();
        }

        const bounds = this._previewStageBounds;
        if (!bounds) return;

        const spanX = bounds.maxX - bounds.minX;
        const spanZ = bounds.maxZ - bounds.minZ;
        const groundSize = Math.max(spanX, spanZ) * 1.5;

        const groundGeo = new THREE.PlaneGeometry(groundSize, groundSize);
        const groundMat = new THREE.MeshStandardMaterial({
            color: 0x0a0a14,
            roughness: 0.95,
            transparent: true,
            opacity: 0.6
        });
        this._stageGround = new THREE.Mesh(groundGeo, groundMat);
        this._stageGround.rotation.x = -Math.PI / 2;
        // Position at bottom of stage bounds, relative to stage center
        this._stageGround.position.y = (bounds.minY - this._previewStageCenter.y) - 0.1;
        this._stageGround.receiveShadow = true;
        this._previewScene.add(this._stageGround);
    }

    /** Position camera to frame the entire stage */
    _frameStage() {
        if (!this._previewCamera || !this._previewStageBounds) return;

        const bounds = this._previewStageBounds;
        const spanX = bounds.maxX - bounds.minX;
        const spanY = bounds.maxY - bounds.minY;
        const spanZ = bounds.maxZ - bounds.minZ;
        const maxSpan = Math.max(spanX, spanY, spanZ);

        const distance = maxSpan * 1.2;
        const angle = Math.PI / 4; // 45 degrees

        this._previewCamera.position.set(
            distance * Math.sin(angle),
            distance * 0.6,
            distance * Math.cos(angle)
        );
        this._previewCamera.lookAt(0, 0, 0);

        // Update far plane and fog for large stages
        this._previewCamera.far = Math.max(100, distance * 4);
        this._previewCamera.updateProjectionMatrix();

        if (this._previewScene.fog) {
            this._previewScene.fog.far = Math.max(50, distance * 3);
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

        // Scan stage blocks button
        const scanBtn = document.getElementById('preview-scan-stage');
        if (scanBtn) {
            scanBtn.addEventListener('click', () => this._scanStageBlocks());
        }

        // Parity check button
        const parityBtn = document.getElementById('parity-check-btn');
        if (parityBtn) {
            parityBtn.addEventListener('click', () => this._requestParityCheck());
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

            const spherical = this._previewSpherical;
            spherical.setFromVector3(this._previewCamera.position);
            spherical.theta -= deltaX * 0.01;
            spherical.phi = Math.max(0.1, Math.min(Math.PI - 0.1, spherical.phi + deltaY * 0.01));
            this._previewCamera.position.setFromSpherical(spherical);
            const lookY = this._previewStageMode ? 0 : 2;
            this._previewCamera.lookAt(0, lookY, 0);

            previousMousePosition = { x: e.clientX, y: e.clientY };
        });

        canvas.addEventListener('mouseup', () => isDragging = false);
        canvas.addEventListener('mouseleave', () => isDragging = false);

        canvas.addEventListener('wheel', (e) => {
            if (!this._previewCamera) return;
            const zoomSpeed = 0.001;
            const distance = this._previewCamera.position.length();
            const newDistance = distance * (1 + e.deltaY * zoomSpeed);
            const maxZoom = this._previewStageMode ? 200 : 30;
            this._previewCamera.position.normalize().multiplyScalar(Math.max(5, Math.min(maxZoom, newDistance)));
        });
    }

    _resetPreviewCamera() {
        if (!this._previewCamera) return;
        if (this._previewStageMode && this._previewStageBounds) {
            this._frameStage();
        } else {
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
        if (!this._previewInitialized || this._previewFailed) return;
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

        try {
            const now = performance.now();
            const dt = (now - this._previewLastFrameTime) / 1000;
            this._previewLastFrameTime = now;

            // FPS calculation
            this._previewFrameCount++;
            if (now - this._previewLastFpsUpdate >= 1000) {
                this._previewFps = this._previewFrameCount;
                this._previewFrameCount = 0;
                this._previewLastFpsUpdate = now;
                const fpsEl = this.elements.previewStatFps;
                if (fpsEl) fpsEl.textContent = this._previewFps;
            }

            // Smooth block animations (legacy single-zone blocks)
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

            // Smooth block animations (multi-zone blocks)
            for (const zoneGroup of Object.values(this._previewZoneGroups)) {
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

            // Update particle system
            if (this._previewParticleSystem) {
                this._previewParticleSystem.update(dt);
            }

            // Update bitmap LED wall preview
            if (this._bitmapPreview && this.state.bitmap.initialized) {
                this._bitmapPreview.update(dt, {
                    bands: Array.isArray(this.state.bands) ? this.state.bands : [0, 0, 0, 0, 0],
                    amplitude: this.state.amplitude || 0,
                    isBeat: !!this.state.isBeat,
                    beatIntensity: this.state.beatIntensity || 0,
                });
            }

            // Auto rotate camera
            if (this._previewAutoRotate && this._previewCamera) {
                const spherical = this._previewSpherical;
                spherical.setFromVector3(this._previewCamera.position);
                spherical.theta += 0.0005;
                this._previewCamera.position.setFromSpherical(spherical);
                const lookY = this._previewStageMode ? 0 : 3;
                this._previewCamera.lookAt(0, lookY, 0);
            }

            this._previewRenderer.render(this._previewScene, this._previewCamera);
        } catch (error) {
            console.error('[Preview] Render loop failed', error);
            this._previewFailed = true;
            this._stopPreviewAnimation();
            this._showToast('3D Preview render error; disabled for this session', 'warning');
        }
    }

    _updatePreviewFromAudioState() {
        if (!this._previewInitialized || this._previewFailed) return;

        try {
            const bands = Array.isArray(this.state.bands) ? this.state.bands : [0, 0, 0, 0, 0];
            const isBeat = !!this.state.isBeat;
            const beatIntensity = Number.isFinite(this.state.beatIntensity) ? this.state.beatIntensity : 0;

            // Update preview meters overlay
            this._updatePreviewMeters();

            // Multi-zone path: use zone_entities when available and stage mode is active
            const zoneEntities = this.state.zoneEntities;
            const hasZoneEntities = this._previewStageMode && zoneEntities && Object.keys(zoneEntities).length > 0;

            if (hasZoneEntities) {
                this._updatePreviewMultiZone(zoneEntities, bands);
                // Hide legacy single-zone blocks
                this._previewBlocks.forEach(b => { b.visible = false; });
            } else {
                // Legacy single-zone path
                this._updatePreviewSingleZone(bands);
                // Hide multi-zone blocks
                for (const zg of Object.values(this._previewZoneGroups)) {
                    zg.blocks.forEach(b => { b.visible = false; });
                    zg.wireframe.visible = false;
                }
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
        } catch (error) {
            console.error('[Preview] Update failed', error);
            this._previewFailed = true;
            this._stopPreviewAnimation();
            this._showToast('3D Preview update failed; disabled for this session', 'warning');
        }
    }

    /** Update preview blocks for all zones simultaneously */
    _updatePreviewMultiZone(zoneEntities, bands) {
        const config = this._previewConfig;

        for (const [zoneName, entities] of Object.entries(zoneEntities)) {
            if (!Array.isArray(entities)) continue;

            // Skip bitmap zones — they render via BitmapPreview, not block entities
            if (this._getZoneRenderMode(zoneName) === 'bitmap') {
                const zg = this._previewZoneGroups[zoneName];
                if (zg) zg.blocks.forEach(b => { b.visible = false; });
                continue;
            }

            const zoneGroup = this._ensurePreviewZoneGroup(zoneName);
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

                // Position in zone-local space (0 to size, matching Minecraft's localToWorld)
                block.userData.targetX = x * sx;
                block.userData.targetY = y * sy;
                block.userData.targetZ = z * sz;
                block.userData.targetScale = scale * 1.5;

                this._updateBlockMaterial(block, entity, bands, config);
            }
        }
    }

    /** Legacy single-zone preview update */
    _updatePreviewSingleZone(bands) {
        const entities = Array.isArray(this.state.entities) ? this.state.entities : [];
        if (entities.length === 0) return;

        this._ensurePreviewBlockCount(entities.length);
        // Make sure legacy blocks are visible
        this._previewBlocks.forEach(b => { b.visible = true; });

        const config = this._previewConfig;
        this._previewBlocks.forEach((block, i) => {
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

    _updatePreviewMeters() {
        for (let i = 0; i < 5; i++) {
            // Strip meters (horizontal, use width)
            const stripBar = document.getElementById(`strip-band-${i}`);
            if (stripBar) {
                const pct = Math.round(this.state.bands[i] * 100);
                stripBar.style.width = pct + '%';
            }
        }
    }

    _updatePreviewStats() {
        // Strip header stats
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
                if (textSettings) textSettings.classList.toggle('hidden', mode !== 'text');
                if (imageSettings) imageSettings.classList.toggle('hidden', mode !== 'image');
            });
        });

        // Color mode dropdown - show/hide fixed color row
        const colorModeSelect = document.getElementById('banner-text-color-mode');
        if (colorModeSelect) {
            colorModeSelect.addEventListener('change', () => {
                const fixedRow = document.getElementById('banner-fixed-color-row');
                if (fixedRow) fixedRow.classList.toggle('hidden', colorModeSelect.value !== 'fixed');
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
        if (textSettings) textSettings.classList.toggle('hidden', p.banner_mode === 'image');
        if (imageSettings) imageSettings.classList.toggle('hidden', p.banner_mode !== 'image');

        // Set text fields
        const textStyle = document.getElementById('banner-text-style');
        if (textStyle) textStyle.value = p.text_style || 'bold';

        const colorMode = document.getElementById('banner-text-color-mode');
        if (colorMode) {
            colorMode.value = p.text_color_mode || 'frequency';
            const fixedRow = document.getElementById('banner-fixed-color-row');
            if (fixedRow) fixedRow.classList.toggle('hidden', colorMode.value !== 'fixed');
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

    // ========== Bitmap LED Wall ==========

    _initBitmapControls() {
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
                if (this._bitmapPreview) this._bitmapPreview.effects.brightness = val / 100;
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
        const effectBtns = {
            'btn-bitmap-blackout': () => {
                this.state.bitmap.blackout = !this.state.bitmap.blackout;
                this.ws.send({ type: 'bitmap_effects', action: 'blackout', zone: el.bitmapZone?.value || 'main', enabled: this.state.bitmap.blackout });
                const btn = document.getElementById('btn-bitmap-blackout');
                if (btn) btn.classList.toggle('firing', this.state.bitmap.blackout);
                if (this._bitmapPreview) this._bitmapPreview.effects.blackout = this.state.bitmap.blackout;
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
                if (this._bitmapPreview) this._bitmapPreview.effects.frozen = this.state.bitmap.frozen;
            },
            'btn-bitmap-reset': () => {
                this.ws.send({ type: 'bitmap_effects', action: 'reset', zone: el.bitmapZone?.value || 'main' });
                // Reset local UI state
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
                // Reset preview effects
                if (this._bitmapPreview) {
                    this._bitmapPreview.effects.brightness = 1.0;
                    this._bitmapPreview.effects.blackout = false;
                    this._bitmapPreview.effects.frozen = false;
                    this._bitmapPreview.effects.washOpacity = 0;
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

        // Text & Overlays
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
                // Clear input after send
                const input = document.getElementById('bitmap-chat-message');
                if (input) input.value = '';
            });
        }

        // Layers
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

        // Layer opacity display
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

        // Update bitmap zone selector when zones change
        if (el.bitmapZone) {
            el.bitmapZone.addEventListener('change', () => {
                this.state.bitmap.zone = el.bitmapZone.value;
                // Update 3D preview — show only the selected zone's bitmap plane
                if (this._bitmapPreview) {
                    const selected = el.bitmapZone.value;
                    for (const zoneName of Object.keys(this._bitmapPreview.zones)) {
                        this._bitmapPreview.setZoneVisible(zoneName, zoneName === selected);
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

    // ========== DJ Logo Controls ==========

    _setupDjLogoListeners() {
        const el = this.elements;
        const zone = () => el.bitmapZone?.value || 'main';

        // Mode buttons
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

        // Threshold slider
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

        // Load image button
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

    _updateDjLogoVisibility(patternId) {
        const section = this.elements.djLogoSection;
        if (!section) return;
        if (patternId === 'bmp_dj_logo') {
            section.classList.remove('hidden');
            section.classList.remove('collapsed');
            // Auto-load image on first selection only
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

    /** Convert "#RRGGBB" hex color to ARGB integer (full alpha) for Minecraft plugin */
    _hexToArgbInt(hex) {
        if (!hex) return 0xFFFFFFFF;
        const r = parseInt(hex.slice(1, 3), 16);
        const g = parseInt(hex.slice(3, 5), 16);
        const b = parseInt(hex.slice(5, 7), 16);
        // ARGB: 0xFF (full alpha) + RGB — use signed 32-bit via bitwise OR
        return ((0xFF << 24) | (r << 16) | (g << 8) | b) | 0;
    }

    _fetchBitmapData() {
        this.ws.send({ type: 'get_bitmap_patterns' });
        this.ws.send({ type: 'get_bitmap_transitions' });
        this.ws.send({ type: 'get_bitmap_palettes' });
        this.state.bitmap.dataFetched = true;
    }

    _renderBitmapPatterns() {
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

            btn.addEventListener('click', () => this._setBitmapPattern(pattern.id || pattern));
            grid.appendChild(btn);
        });

        // Also populate layer pattern dropdown
        this._populateSelectFromList(this.elements.bitmapLayerPattern, this.state.bitmap.patterns, '-- None --');
    }

    _renderBitmapTransitions() {
        const select = this.elements.bitmapTransition;
        if (!select) return;

        const currentVal = select.value;
        while (select.firstChild) select.removeChild(select.firstChild);

        // Add "Instant" option first (no transition, instant cut)
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

    _renderBitmapPalettes() {
        const grid = this.elements.bitmapPaletteGrid;
        if (!grid) return;

        while (grid.firstChild) grid.removeChild(grid.firstChild);

        // "None" button to clear palette
        const noneBtn = document.createElement('button');
        noneBtn.className = 'pattern-btn';
        noneBtn.dataset.palette = '';
        noneBtn.dataset.type = 'palette';
        noneBtn.textContent = 'None';
        if (!this.state.bitmap.activePalette) noneBtn.classList.add('active');
        noneBtn.addEventListener('click', () => this._setBitmapPalette(''));
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

            btn.addEventListener('click', () => this._setBitmapPalette(palette.id || palette));
            grid.appendChild(btn);
        });

        // Also populate shared palette dropdown
        this._populateSelectFromList(this.elements.bitmapSharedPalette, this.state.bitmap.palettes, '-- None --');
    }

    /** Helper: populate a <select> from a list of {id, name} items, preserving current value */
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

    _setBitmapPattern(patternId) {
        const zone = this.elements.bitmapZone?.value || 'main';

        // Auto-init: if zone not yet initialized, send init_bitmap instead
        // The init message accepts a pattern field, so the zone will start with this pattern
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
            // Zone already initialized — switch pattern (with optional transition)
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
        this._highlightBitmapPattern(patternId);
        this._updateDjLogoVisibility(patternId);
    }

    _setBitmapPalette(paletteId) {
        this.ws.send({
            type: 'bitmap_palette',
            palette: paletteId || 'none'
        });
        this.state.bitmap.activePalette = paletteId || null;
        this._highlightBitmapPalette(paletteId);
    }

    _highlightBitmapPattern(patternId) {
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

    _highlightBitmapPalette(paletteId) {
        if (!this.elements.bitmapPaletteGrid) return;
        this.elements.bitmapPaletteGrid.querySelectorAll('.pattern-btn').forEach(btn => {
            const isActive = (btn.dataset.palette || '') === (paletteId || '');
            btn.classList.toggle('active', isActive);
        });
    }

    _updateBitmapStatus(data) {
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
            this._highlightBitmapPattern(data.pattern);
        }
        if (data.palette) {
            this.state.bitmap.activePalette = data.palette;
            this._highlightBitmapPalette(data.palette);
        }
    }

    _updateBitmapZoneSelector() {
        const select = this.elements.bitmapZone;
        if (!select) return;

        const currentVal = select.value;
        while (select.firstChild) select.removeChild(select.firstChild);

        // Build zone list from allZones or fall back to zonePatterns keys
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
            const rm = this._getZoneRenderMode(name);
            opt.textContent = rm === 'bitmap' ? `${name} [B]` : name;
            select.appendChild(opt);
        });

        if (currentVal) select.value = currentVal;
    }
}

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    window.adminApp = new AdminApp();
});
