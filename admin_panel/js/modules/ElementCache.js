/**
 * ElementCache - Caches DOM element references for quick access.
 */

export function cacheElements(elements) {
    // Status elements
    elements.connectionStatus = document.getElementById('connection-status');
    elements.currentPattern = document.getElementById('current-pattern');
    elements.currentPreset = document.getElementById('current-preset');
    elements.bpmEstimate = document.getElementById('bpm-estimate');
    elements.frameCount = document.getElementById('frame-count');
    elements.beatIndicator = document.getElementById('beat-indicator');
    elements.latencyDisplay = document.getElementById('latency-display');
    elements.queueDepthDisplay = document.getElementById('queue-depth-display');

    // Meters
    elements.meters = [];
    elements.meterValues = [];
    for (let i = 0; i < 5; i++) {
        elements.meters.push(document.getElementById(`meter-${i}`));
        elements.meterValues.push(document.getElementById(`meter-val-${i}`));
    }
    elements.meterMaster = document.getElementById('meter-master');
    elements.meterMasterValue = document.getElementById('meter-val-master');

    // Pattern grid
    elements.patternGrid = document.getElementById('pattern-grid');

    // Preset buttons
    elements.presetButtons = document.querySelectorAll('.preset-btn');

    // Faders
    elements.faders = document.querySelectorAll('.fader');

    // Controls
    elements.ctrlAttack = document.getElementById('ctrl-attack');
    elements.ctrlRelease = document.getElementById('ctrl-release');
    elements.ctrlAgc = document.getElementById('ctrl-agc');
    elements.ctrlBeatSens = document.getElementById('ctrl-beat-sens');
    elements.ctrlBeatThresh = document.getElementById('ctrl-beat-thresh');
    elements.ctrlBlocks = document.getElementById('ctrl-blocks');

    // Quick actions
    elements.btnBlackout = document.getElementById('btn-blackout');
    elements.btnFreeze = document.getElementById('btn-freeze');
    elements.btnTapTempo = document.getElementById('btn-tap-tempo');
    elements.tapBpm = document.getElementById('tap-bpm');

    // Effect triggers
    elements.effectButtons = document.querySelectorAll('.effect-btn');

    // Scene presets
    elements.sceneNameInput = document.getElementById('scene-name-input');
    elements.saveSceneBtn = document.getElementById('save-scene-btn');
    elements.scenesGrid = document.getElementById('scenes-grid');

    // Visual sync controls
    elements.syncMode = document.getElementById('sync-mode');
    elements.ctrlVisualDelay = document.getElementById('ctrl-visual-delay');
    elements.syncDelayRow = document.getElementById('sync-delay-row');
    elements.syncPresetButtons = document.querySelectorAll('.sync-preset-btn');
    elements.syncDashboard = document.getElementById('sync-dashboard');
    elements.metricPing = document.getElementById('metric-ping');
    elements.metricPipeline = document.getElementById('metric-pipeline');
    elements.metricDelay = document.getElementById('metric-delay');
    elements.metricSync = document.getElementById('metric-sync');
    elements.metricJitter = document.getElementById('metric-jitter');
    elements.btnSyncTest = document.getElementById('btn-sync-test');
    elements.syncTestResult = document.getElementById('sync-test-result');

    // Particle effects
    elements.particleGlobalIntensity = document.getElementById('particle-global-intensity');
    elements.particleBeatEffects = document.getElementById('particle-beat-effects');
    elements.particleAmbientEffects = document.getElementById('particle-ambient-effects');

    // Tabs
    elements.tabs = document.querySelectorAll('.tab');
    elements.tabPanels = document.querySelectorAll('.tab-panel');

    // FPS counter
    elements.fpsDisplay = document.getElementById('fps-display');

    // Hot-path elements
    elements.header = document.getElementById('header');
    elements.previewStatFps = document.getElementById('preview-stat-fps');

    // DJ Queue
    elements.djQueue = document.getElementById('dj-queue');

    // Connect Code elements
    elements.btnGenerateCode = document.getElementById('btn-generate-code');
    elements.activeCodes = document.getElementById('active-codes');
    elements.generatedCodeDisplay = document.getElementById('generated-code-display');
    elements.generatedCodeText = document.getElementById('generated-code-text');
    elements.generatedCodeTtl = document.getElementById('generated-code-ttl');
    elements.btnCopyCode = document.getElementById('btn-copy-code');

    // Reconnect button
    elements.btnReconnect = document.getElementById('btn-reconnect');

    // Service status indicators
    elements.svcPython = document.getElementById('svc-python');
    elements.svcMinecraft = document.getElementById('svc-minecraft');

    // DJ Pending section
    elements.djPendingSection = document.getElementById('dj-pending-section');
    elements.djPendingQueue = document.getElementById('dj-pending-queue');

    // Stage/zone list
    elements.stageZoneList = document.getElementById('stage-zone-list');
    elements.btnRefreshZones = document.getElementById('btn-refresh-zones');
    elements.stageSelect = document.getElementById('stage-select');
    elements.zoneChipBar = document.getElementById('zone-chip-bar');

    // Toast container
    elements.toastContainer = document.getElementById('toast-container');

    // Zone settings elements
    elements.zoneSelect = document.getElementById('zone-select');
    elements.zoneEntityCount = document.getElementById('zone-entity-count');
    elements.zoneBlockType = document.getElementById('zone-block-type');
    elements.zoneBaseScale = document.getElementById('zone-base-scale');
    elements.zoneMaxScale = document.getElementById('zone-max-scale');
    elements.zoneBrightness = document.getElementById('zone-brightness');
    elements.zoneInterpolation = document.getElementById('zone-interpolation');
    elements.zoneGlowBeat = document.getElementById('zone-glow-beat');
    elements.zoneDynamicBrightness = document.getElementById('zone-dynamic-brightness');
    elements.zoneSizeX = document.getElementById('zone-size-x');
    elements.zoneSizeY = document.getElementById('zone-size-y');
    elements.zoneSizeZ = document.getElementById('zone-size-z');
    elements.zoneRotation = document.getElementById('zone-rotation');
    elements.zoneShowBpm = document.getElementById('zone-show-bpm');
    elements.zoneShowPattern = document.getElementById('zone-show-pattern');
    elements.zoneShowBands = document.getElementById('zone-show-bands');
    elements.btnReinitPool = document.getElementById('btn-reinit-pool');
    elements.btnCleanupZone = document.getElementById('btn-cleanup-zone');
    elements.btnResetDefaults = document.getElementById('btn-reset-defaults');

    // Render mode elements
    elements.renderModeButtons = document.querySelectorAll('.render-mode-btn');
    elements.bedrockNotice = document.getElementById('bedrock-notice');
    elements.entityConfigSection = document.getElementById('entity-config-section');
    elements.particleVizSection = document.getElementById('particle-viz-section');

    // Particle visualization elements
    elements.particleVizType = document.getElementById('particle-viz-type');
    elements.particleVizDensity = document.getElementById('particle-viz-density');
    elements.particleVizColor = document.getElementById('particle-viz-color');
    elements.particleVizFixedColor = document.getElementById('particle-viz-fixed-color');
    elements.fixedColorRow = document.getElementById('fixed-color-row');
    elements.particleVizSize = document.getElementById('particle-viz-size');
    elements.particleVizTrail = document.getElementById('particle-viz-trail');

    // Voice chat elements
    elements.voiceChatSection = document.getElementById('voice-chat-section');
    elements.voiceStatusBar = document.getElementById('voice-status-bar');
    elements.voiceStatusIndicator = document.getElementById('voice-status-indicator');
    elements.voiceDot = document.getElementById('voice-dot');
    elements.voiceStatusText = document.getElementById('voice-status-text');
    elements.voicePlayersStat = document.getElementById('voice-players-stat');
    elements.voiceUnavailableMsg = document.getElementById('voice-unavailable-msg');
    elements.voiceControls = document.getElementById('voice-controls');
    elements.voiceStreamToggle = document.getElementById('voice-stream-toggle');
    elements.voiceChannelType = document.getElementById('voice-channel-type');
    elements.voiceDistance = document.getElementById('voice-distance');
    elements.voiceDistanceRow = document.getElementById('voice-distance-row');

    // Bitmap LED Wall elements
    elements.bitmapZone = document.getElementById('bitmap-zone');
    elements.bitmapAutoSize = document.getElementById('bitmap-auto-size');
    elements.bitmapManualDims = document.getElementById('bitmap-manual-dims');
    elements.bitmapWidth = document.getElementById('bitmap-width');
    elements.bitmapHeight = document.getElementById('bitmap-height');
    elements.btnBitmapInit = document.getElementById('btn-bitmap-init');
    elements.bitmapStatus = document.getElementById('bitmap-status');
    elements.bitmapPatternGrid = document.getElementById('bitmap-pattern-grid');
    elements.bitmapPaletteGrid = document.getElementById('bitmap-palette-grid');
    elements.bitmapTransition = document.getElementById('bitmap-transition');
    elements.bitmapTransitionDuration = document.getElementById('bitmap-transition-duration');
    elements.bitmapBrightness = document.getElementById('bitmap-brightness');
    elements.bitmapStrobe = document.getElementById('bitmap-strobe');
    elements.bitmapBeatFlash = document.getElementById('bitmap-beat-flash');
    elements.bitmapWashColor = document.getElementById('bitmap-wash-color');
    elements.bitmapWashOpacity = document.getElementById('bitmap-wash-opacity');
    elements.bitmapBloom = document.getElementById('bitmap-bloom');
    elements.bitmapBloomStrength = document.getElementById('bitmap-bloom-strength');
    elements.bitmapAmbientLights = document.getElementById('bitmap-ambient-lights');
    elements.bitmapLayerPattern = document.getElementById('bitmap-layer-pattern');
    elements.bitmapLayerBlend = document.getElementById('bitmap-layer-blend');
    elements.bitmapLayerOpacity = document.getElementById('bitmap-layer-opacity');
    elements.bitmapSharedPalette = document.getElementById('bitmap-shared-palette');
    elements.bitmapSyncMode = document.getElementById('bitmap-sync-mode');

    // DJ Logo elements
    elements.djLogoSection = document.getElementById('dj-logo-section');
    elements.djLogoModeGrid = document.getElementById('dj-logo-mode-grid');
    elements.djLogoThreshold = document.getElementById('dj-logo-threshold');
    elements.djLogoFile = document.getElementById('dj-logo-file');
    elements.btnDjLogoLoad = document.getElementById('btn-dj-logo-load');

    // Pattern transition elements
    elements.transitionDurationSlider = document.getElementById('transition-duration-slider');
    elements.transitionDurationValue = document.getElementById('transition-duration-value');
    elements.transitionStatus = document.getElementById('transition-status');
}
