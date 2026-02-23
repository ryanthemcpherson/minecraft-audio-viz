/**
 * AudioViz Control Center
 * Professional Three.js visualization with particles and block indicators
 */

// Configuration
const CONFIG = {
    wsPort: 8766,
    entityCount: 16,
    gridSize: 4,
    blockSize: 0.8,
    zoneSize: 10,
    centerOffset: 5,
    colors: [
        0xff9100,  // bass - orange (includes kick)
        0xffea00,  // low - yellow
        0x00e676,  // mid - green
        0x00b0ff,  // high - blue
        0xd500f9   // air - magenta
    ]
};

// Three.js components
let scene, camera, renderer;
let blocks = [];
let ground, gridHelper;
let mcEnvironment = null;
let autoRotate = false;

// Scanned stage blocks
let stageBlocksGroup = null;
let stageCenter = { x: 0, y: 0, z: 0 };
let textureManager = null;
let bandColorMaterials = null; // Pre-created materials for each band color
let zoneData = null;
let stageScanRequested = false;

// Multi-zone support
let zoneGroups = {};       // { zoneName: { group, blocks, wireframe, sizeX, sizeY, sizeZ, zone } }
let zoneEntitiesData = null; // Per-zone entities from state broadcasts

// New systems
let particleSystem = null;
let particleEntityRenderer = null;
let blockIndicators = null;
let bitmapPreview = null;
let showBlockGrid = true;
let selectedBitmapZone = '__all__'; // '__all__' or a specific zone name

// View mode: 'blocks', 'particles', 'hybrid'
let viewMode = 'blocks';

// Audio state - 5 bands for ultra-low-latency mode
let audioState = {
    bands: [0, 0, 0, 0, 0],
    amplitude: 0,
    isBeat: false,
    beatIntensity: 0,
    frame: 0,
    entities: [],
    pattern: 'spectrum'
};

// Particle effect settings
let particleEffects = {
    enabled: true,
    bassFlame: true,
    soulFire: true,
    beatRing: true,
    notes: false,
    dust: false
};

// Beat cooldown for particle effects
let lastBeatTime = 0;
const BEAT_COOLDOWN = 150; // ms

// BPM calculation
let beatHistory = [];
const BPM_HISTORY_SIZE = 8;  // Number of beats to average
let currentBpm = 0;
let lastBpmUpdate = 0;

// Available patterns
let availablePatterns = [];

// WebSocket
let ws = null;
let reconnectTimeout = null;
let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 10;
const BASE_RECONNECT_DELAY = 1000;  // 1 second
const MAX_RECONNECT_DELAY = 30000;  // 30 seconds

// FPS tracking
let frameCount = 0;
let lastFpsUpdate = 0;
let currentFps = 60;
let lastFrameTime = 0;

// Debounce helper for slider WebSocket messages
const WS_DEBOUNCE_MS = 50;
const _debounceTimers = {};
function debouncedWsSend(key, payload) {
    if (_debounceTimers[key]) clearTimeout(_debounceTimers[key]);
    _debounceTimers[key] = setTimeout(() => {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify(payload));
        }
    }, WS_DEBOUNCE_MS);
}

// Initialize
function init() {
    // Scene
    scene = new THREE.Scene();
    scene.background = new THREE.Color(0x0a0a0f);
    scene.fog = new THREE.Fog(0x0a0a0f, 40, 100);

    // Camera
    const previewSection = document.querySelector('.preview-section');
    const aspect = previewSection ? previewSection.clientWidth / previewSection.clientHeight : window.innerWidth / window.innerHeight;
    camera = new THREE.PerspectiveCamera(60, aspect, 0.1, 200);
    camera.position.set(0, 12, 30);
    camera.lookAt(0, 5, 0);

    // Renderer
    const canvas = document.getElementById('visualizer');
    renderer = new THREE.WebGLRenderer({ canvas, antialias: true });

    if (previewSection) {
        renderer.setSize(previewSection.clientWidth, previewSection.clientHeight);
    } else {
        renderer.setSize(window.innerWidth, window.innerHeight);
    }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.shadowMap.enabled = true;

    // Handle WebGL context loss and restoration
    canvas.addEventListener('webglcontextlost', (e) => {
        e.preventDefault();
        console.warn('[WebGL] Context lost');
        const statusText = document.querySelector('#connection-status .status-text');
        if (statusText) statusText.textContent = 'WebGL Lost';
    });
    canvas.addEventListener('webglcontextrestored', () => {
        console.info('[WebGL] Context restored');
        const statusText = document.querySelector('#connection-status .status-text');
        if (statusText) statusText.textContent = 'Reconnecting...';
        // Re-initialise renderer state after context restore
        renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
        renderer.shadowMap.enabled = true;
    });

    // Lights
    const ambientLight = new THREE.AmbientLight(0x404040, 0.5);
    scene.add(ambientLight);

    const directionalLight = new THREE.DirectionalLight(0xffffff, 0.8);
    directionalLight.position.set(10, 20, 10);
    directionalLight.castShadow = true;
    directionalLight.shadow.mapSize.width = 2048;
    directionalLight.shadow.mapSize.height = 2048;
    scene.add(directionalLight);

    const pointLight = new THREE.PointLight(0x6366f1, 0.5, 20);
    pointLight.position.set(0, 5, 0);
    scene.add(pointLight);

    // Block texture manager for entity and stage blocks
    if (typeof BlockTextureManager !== 'undefined') {
        textureManager = new BlockTextureManager();
        textureManager.preload();
    }

    // Pre-create band color materials for entity blocks
    bandColorMaterials = CONFIG.colors.map(color =>
        new THREE.MeshStandardMaterial({
            color: color,
            roughness: 0.3,
            metalness: 0.2,
            emissive: new THREE.Color(color),
            emissiveIntensity: 0.2,
        })
    );

    // Procedural environment disabled — clean background only

    // Create visualization blocks
    createBlocks();

    // Initialize particle system (increased capacity for particle mode)
    if (typeof ParticleSystem !== 'undefined') {
        particleSystem = new ParticleSystem(scene, 2000);
    }

    // Initialize particle entity renderer for particle/hybrid view modes
    if (typeof ParticleEntityRenderer !== 'undefined') {
        particleEntityRenderer = new ParticleEntityRenderer(scene, 1500);
        particleEntityRenderer.setVisible(false);  // Hidden by default (block mode)
    }

    // Block indicators removed — floor tiles no longer shown

    // Initialize bitmap LED wall preview
    if (typeof BitmapPreview !== 'undefined') {
        bitmapPreview = new BitmapPreview();
    }

    // Event listeners
    window.addEventListener('resize', onResize);

    // Setup all UI controls
    setupControls();
    setupMouseControls();
    setupParticleToggles();
    setupViewModeControls();

    // Connect WebSocket
    connectWebSocket();

    // Click connection status to manually retry when failed
    const connStatusEl = document.getElementById('connection-status');
    if (connStatusEl) {
        connStatusEl.style.cursor = 'pointer';
        connStatusEl.title = 'Click to reconnect';
        connStatusEl.addEventListener('click', () => {
            if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS || !ws || ws.readyState === WebSocket.CLOSED) {
                console.log('[WS] Manual reconnect triggered');
                if (reconnectTimeout) clearTimeout(reconnectTimeout);
                reconnectAttempts = 0;
                connectWebSocket();
            }
        });
    }

    // Start animation
    lastFrameTime = performance.now();
    animate();
}

function createBlock(index) {
    const geometry = new THREE.BoxGeometry(CONFIG.blockSize, CONFIG.blockSize, CONFIG.blockSize);
    const bandIndex = index % 5;
    const material = new THREE.MeshStandardMaterial({
        color: CONFIG.colors[bandIndex],
        roughness: 0.3,
        metalness: 0.2,
        emissive: CONFIG.colors[bandIndex],
        emissiveIntensity: 0.2
    });

    const block = new THREE.Mesh(geometry, material);
    block.castShadow = true;
    block.receiveShadow = true;

    block.userData.bandIndex = bandIndex;
    block.userData.currentMaterial = '';
    block.userData.targetX = 0;
    block.userData.targetY = 0;
    block.userData.targetZ = 0;
    block.userData.targetScale = 1;

    return block;
}

/**
 * Update a block's material based on entity data.
 * Uses BlockTextureManager for Minecraft block textures when material is specified,
 * falls back to band-colored materials otherwise.
 */
function updateBlockMaterial(block, entity, bands) {
    const rawBand = Number.isFinite(entity.band) ? entity.band : 0;
    const bandIndex = Math.max(0, Math.min(4, Math.round(rawBand)));
    block.userData.bandIndex = bandIndex;

    const entityMaterial = entity.material || '';
    if (textureManager && entityMaterial && entityMaterial !== block.userData.currentMaterial) {
        const texMat = textureManager.getMaterial(entityMaterial);
        if (texMat) {
            // Clone so emissiveIntensity mutations are per-block, not shared
            block.material = texMat.clone();
            block.userData.currentMaterial = entityMaterial;
        }
    } else if (!entityMaterial && block.userData.currentMaterial) {
        // Material removed — dispose cloned material and revert to band color
        block.material.dispose();
        if (bandColorMaterials) {
            block.material = bandColorMaterials[bandIndex].clone();
        }
        block.userData.currentMaterial = '';
    } else if (!entityMaterial && !block.userData.currentMaterial) {
        block.material.color.setHex(CONFIG.colors[bandIndex]);
        block.material.emissive.setHex(CONFIG.colors[bandIndex]);
    }

    const bandValue = Number.isFinite(bands[bandIndex]) ? bands[bandIndex] : 0;
    block.material.emissiveIntensity = 0.3 + bandValue * 1.0;
}

function createBlocks() {
    for (let i = 0; i < CONFIG.entityCount; i++) {
        const block = createBlock(i);
        block.position.set(0, 0.5, 0);
        scene.add(block);
        blocks.push(block);
    }
}

function ensureBlockCount(count) {
    while (blocks.length < count) {
        const block = createBlock(blocks.length);
        block.position.set(0, 0.5, 0);
        scene.add(block);
        blocks.push(block);
    }

    while (blocks.length > count) {
        const block = blocks.pop();
        scene.remove(block);
        block.geometry.dispose();
        block.material.dispose();
    }
}

function setupControls() {
    // Collapsible panels
    document.querySelectorAll('.panel.collapsible .panel-header').forEach(header => {
        header.style.cursor = 'pointer';
        header.addEventListener('click', () => header.parentElement.classList.toggle('collapsed'));
    });

    // Reset camera button
    const resetBtn = document.getElementById('btn-reset');
    if (resetBtn) {
        resetBtn.addEventListener('click', resetCamera);
    }

    // Rescan stage button
    const rescanBtn = document.getElementById('btn-rescan');
    if (rescanBtn) {
        rescanBtn.addEventListener('click', rescanStage);
    }

    // Auto rotate checkbox
    const rotateChk = document.getElementById('chk-rotate');
    if (rotateChk) {
        rotateChk.addEventListener('change', (e) => {
            autoRotate = e.target.checked;
        });
    }

    // Show stage checkbox
    const blocksChk = document.getElementById('chk-blocks');
    if (blocksChk) {
        blocksChk.addEventListener('change', (e) => {
            showBlockGrid = e.target.checked;
            if (blockIndicators) {
                blockIndicators.setVisible(showBlockGrid);
            }
            if (stageBlocksGroup) {
                stageBlocksGroup.visible = showBlockGrid;
            }
            if (mcEnvironment) {
                mcEnvironment.setVisible(showBlockGrid);
            }
            // Show/hide zone wireframes
            for (const zg of Object.values(zoneGroups)) {
                zg.group.visible = showBlockGrid;
            }
        });
    }

    // Pattern selector
    const patternSelect = document.getElementById('pattern-select');
    if (patternSelect) {
        patternSelect.addEventListener('change', (e) => {
            setPattern(e.target.value);
        });
    }

    // Bitmap pattern selector (LED Wall mode) — applies to selected zone or all
    const bitmapPatternSelect = document.getElementById('bitmap-pattern-select');
    if (bitmapPatternSelect) {
        bitmapPatternSelect.addEventListener('change', (e) => {
            if (!bitmapPreview) return;
            if (selectedBitmapZone === '__all__') {
                for (const zoneName of Object.keys(bitmapPreview.zones)) {
                    bitmapPreview.setPattern(zoneName, e.target.value);
                }
            } else {
                bitmapPreview.setPattern(selectedBitmapZone, e.target.value);
            }
        });
    }

    // Bitmap zone selector
    const bitmapZoneSelect = document.getElementById('bitmap-zone-select');
    if (bitmapZoneSelect) {
        bitmapZoneSelect.addEventListener('change', (e) => {
            selectedBitmapZone = e.target.value;
            applyBitmapZoneVisibility();
        });
    }

    // Block count slider
    const blockCountSlider = document.getElementById('block-count');
    const blockCountDisplay = document.getElementById('block-count-display');
    if (blockCountSlider && blockCountDisplay) {
        blockCountSlider.addEventListener('input', (e) => {
            const count = parseInt(e.target.value);
            blockCountDisplay.textContent = count;
            setBlockCount(count);
        });
    }

    // Audio settings sliders
    setupSlider('attack-slider', 'attack-display', '%', (val) => setAudioSetting('attack', val / 100));
    setupSlider('release-slider', 'release-display', '%', (val) => setAudioSetting('release', val / 100));
    setupSlider('agc-slider', 'agc-display', 'x', (val) => setAudioSetting('agc_max_gain', val));
    setupSlider('beat-slider', 'beat-display', '%', (val) => setAudioSetting('beat_sensitivity', val / 100));

    // Beat threshold slider
    const beatThreshSlider = document.getElementById('beat-thresh-slider');
    const beatThreshDisplay = document.getElementById('beat-thresh-display');
    if (beatThreshSlider && beatThreshDisplay) {
        beatThreshSlider.addEventListener('input', (e) => {
            const val = parseFloat(e.target.value);
            beatThreshDisplay.textContent = (val / 100).toFixed(1) + 'x';
            setAudioSetting('beat_threshold', val / 100);
        });
    }

    // Per-band sensitivity sliders (5 bands)
    for (let i = 0; i < 5; i++) {
        const slider = document.getElementById(`sens-${i}`);
        if (slider) {
            slider.addEventListener('input', (e) => {
                const val = parseInt(e.target.value);
                setBandSensitivity(i, val / 100);
            });
        }
    }

    // Preset buttons
    document.querySelectorAll('.preset-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            setPreset(btn.dataset.preset);
        });
    });
}

function setupSlider(sliderId, displayId, suffix, callback) {
    const slider = document.getElementById(sliderId);
    const display = document.getElementById(displayId);
    if (!slider || !display) return;

    slider.addEventListener('input', (e) => {
        const val = parseFloat(e.target.value);
        display.textContent = val + suffix;
        callback(val);
    });
}

function setupMouseControls() {
    const canvas = document.getElementById('visualizer');
    let isDragging = false;
    let previousMousePosition = { x: 0, y: 0 };

    canvas.addEventListener('mousedown', (e) => {
        isDragging = true;
        previousMousePosition = { x: e.clientX, y: e.clientY };
    });

    canvas.addEventListener('mousemove', (e) => {
        if (!isDragging) return;

        const deltaX = e.clientX - previousMousePosition.x;
        const deltaY = e.clientY - previousMousePosition.y;

        const spherical = new THREE.Spherical();
        spherical.setFromVector3(camera.position);
        spherical.theta -= deltaX * 0.01;
        spherical.phi = Math.max(0.1, Math.min(Math.PI - 0.1, spherical.phi + deltaY * 0.01));
        camera.position.setFromSpherical(spherical);
        camera.lookAt(0, 2, 0);

        previousMousePosition = { x: e.clientX, y: e.clientY };
    });

    canvas.addEventListener('mouseup', () => isDragging = false);
    canvas.addEventListener('mouseleave', () => isDragging = false);

    canvas.addEventListener('wheel', (e) => {
        const zoomSpeed = 0.001;
        const distance = camera.position.length();
        const newDistance = distance * (1 + e.deltaY * zoomSpeed);
        camera.position.normalize().multiplyScalar(Math.max(5, Math.min(30, newDistance)));
    });

    // Touch event support for mobile
    let lastTouchDistance = 0;

    canvas.addEventListener('touchstart', (e) => {
        if (e.touches.length === 1) {
            isDragging = true;
            previousMousePosition = { x: e.touches[0].clientX, y: e.touches[0].clientY };
        } else if (e.touches.length === 2) {
            isDragging = false;
            const dx = e.touches[0].clientX - e.touches[1].clientX;
            const dy = e.touches[0].clientY - e.touches[1].clientY;
            lastTouchDistance = Math.hypot(dx, dy);
        }
        e.preventDefault();
    }, { passive: false });

    canvas.addEventListener('touchmove', (e) => {
        if (e.touches.length === 1 && isDragging) {
            const deltaX = e.touches[0].clientX - previousMousePosition.x;
            const deltaY = e.touches[0].clientY - previousMousePosition.y;

            const spherical = new THREE.Spherical();
            spherical.setFromVector3(camera.position);
            spherical.theta -= deltaX * 0.01;
            spherical.phi = Math.max(0.1, Math.min(Math.PI - 0.1, spherical.phi + deltaY * 0.01));
            camera.position.setFromSpherical(spherical);
            camera.lookAt(0, 2, 0);

            previousMousePosition = { x: e.touches[0].clientX, y: e.touches[0].clientY };
        } else if (e.touches.length === 2) {
            const dx = e.touches[0].clientX - e.touches[1].clientX;
            const dy = e.touches[0].clientY - e.touches[1].clientY;
            const touchDistance = Math.hypot(dx, dy);

            if (lastTouchDistance > 0) {
                const scale = lastTouchDistance / touchDistance;
                const distance = camera.position.length();
                camera.position.normalize().multiplyScalar(Math.max(5, Math.min(30, distance * scale)));
            }
            lastTouchDistance = touchDistance;
        }
        e.preventDefault();
    }, { passive: false });

    canvas.addEventListener('touchend', (e) => {
        isDragging = false;
        if (e.touches.length < 2) {
            lastTouchDistance = 0;
        }
    });
}

function setupParticleToggles() {
    // Master toggle
    const masterToggle = document.getElementById('particles-enabled');
    if (masterToggle) {
        masterToggle.addEventListener('change', (e) => {
            particleEffects.enabled = e.target.checked;
        });
    }

    // Individual effect toggles
    const toggleMap = {
        'effect-bass-flame': 'bassFlame',
        'effect-soul-fire': 'soulFire',
        'effect-beat-ring': 'beatRing',
        'effect-notes': 'notes',
        'effect-dust': 'dust'
    };

    Object.entries(toggleMap).forEach(([id, prop]) => {
        const el = document.getElementById(id);
        if (el) {
            el.addEventListener('change', (e) => {
                particleEffects[prop] = e.target.checked;
            });
        }
    });
}

function setupViewModeControls() {
    const radios = document.querySelectorAll('input[name="view-mode"]');
    radios.forEach(radio => {
        radio.addEventListener('change', (e) => {
            if (e.target.checked) {
                viewMode = e.target.value;
                applyViewMode();
            }
        });
    });
}

function applyViewMode() {
    const isBitmap = (viewMode === 'bitmap');

    // Block visibility (hidden in bitmap mode)
    const showBlocks = (viewMode === 'blocks' || viewMode === 'hybrid');
    blocks.forEach(block => {
        block.visible = showBlocks;
    });

    // Bitmap LED wall visibility (respects zone selection)
    applyBitmapZoneVisibility();

    // Toggle pattern selectors based on mode
    const luaSelect = document.getElementById('pattern-select');
    const bmpSelect = document.getElementById('bitmap-pattern-select');
    const bmpZoneSelect = document.getElementById('bitmap-zone-select');
    if (luaSelect) luaSelect.style.display = isBitmap ? 'none' : '';
    if (bmpSelect) bmpSelect.style.display = isBitmap ? '' : 'none';
    if (bmpZoneSelect) bmpZoneSelect.style.display = isBitmap ? '' : 'none';

    // Hide block count slider in bitmap mode
    const blockCountRow = document.getElementById('block-count');
    if (blockCountRow && blockCountRow.parentElement) {
        blockCountRow.parentElement.style.display = isBitmap ? 'none' : '';
    }

    // Particle entity renderer visibility
    if (particleEntityRenderer) {
        const showEntityParticles = (viewMode === 'particles' || viewMode === 'hybrid');
        particleEntityRenderer.setVisible(showEntityParticles);
        particleEntityRenderer.setGlowVisible(showEntityParticles);
        if (!showEntityParticles) {
            particleEntityRenderer.clear();
        }
    }
}

function updateParticleEntities() {
    if (!particleEntityRenderer) return;
    if (viewMode !== 'particles' && viewMode !== 'hybrid') return;

    const entities = audioState.entities;
    if (!entities || entities.length === 0) return;

    particleEntityRenderer.updateFromEntities(
        entities,
        audioState.bands,
        CONFIG.zoneSize,
        CONFIG.centerOffset
    );

    // Notify beat events
    if (audioState.isBeat) {
        particleEntityRenderer.onBeat(audioState.beatIntensity);
    }
}

function connectWebSocket() {
    const statusEl = document.getElementById('connection-status');
    const statusText = statusEl ? statusEl.querySelector('.status-text') : null;

    // VJ password: check URL param, then localStorage
    const urlParams = new URLSearchParams(window.location.search);
    const vjPassword = urlParams.get('vj_password')
        || localStorage.getItem('mcav_vj_password')
        || '';

    try {
        const wsHost = window.location.hostname || 'localhost';
        ws = new WebSocket(`ws://${wsHost}:${CONFIG.wsPort}`);

        ws.onopen = () => {
            // Send VJ auth if a password is available
            if (vjPassword) {
                ws.send(JSON.stringify({ type: 'vj_auth', password: vjPassword }));
                // Auth response handled in onmessage
            }

            if (statusEl) statusEl.classList.add('connected');
            if (statusEl) statusEl.classList.remove('error');
            if (statusText) statusText.textContent = 'Connected';
            console.log('WebSocket connected');
            reconnectAttempts = 0;  // Reset on successful connection
            stageScanRequested = false;

            // Request zone and stage data for scanned block rendering
            ws.send(JSON.stringify({ type: 'get_zones' }));
            ws.send(JSON.stringify({ type: 'get_stages' }));
        };

        ws.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                if (data.type === 'auth_error') {
                    console.error('[WS] VJ auth failed:', data.error);
                    if (statusText) statusText.textContent = 'Auth Failed';
                    if (statusEl) statusEl.classList.add('error');
                    const newPassword = prompt(`VJ Auth Failed: ${data.error || 'Invalid password'}\nEnter VJ password:`);
                    if (newPassword) {
                        localStorage.setItem('mcav_vj_password', newPassword);
                        ws.close();
                        setTimeout(connectWebSocket, 500);
                    }
                    return;
                } else if (data.type === 'auth_success') {
                    console.log('[WS] VJ auth succeeded');
                    return;
                }
                if (data.type === 'audio' || data.type === 'state') {
                    updateAudioState(data);
                } else if (data.type === 'patterns' || data.type === 'pattern_changed') {
                    updatePatternList(data.patterns, data.current || data.pattern);
                } else if (data.type === 'preset_changed') {
                    updateUIFromPreset(data.preset, data.settings);
                } else if (data.type === 'voice_status') {
                    updateVoiceStatus(data);
                } else if (data.type === 'zones') {
                    handleZonesResponse(data.zones || []);
                } else if (data.type === 'stages') {
                    handleStagesResponse(data.stages || []);
                } else if (data.type === 'stage_blocks') {
                    handleStageBlocksResponse(data);
                } else if (data.type === 'bitmap_initialized') {
                    handleBitmapInitialized(data);
                } else if (data.type === 'bitmap_pattern_set' || data.type === 'bitmap_transition_started') {
                    if (bitmapPreview && data.zone) {
                        bitmapPreview.setPattern(data.zone, data.pattern);
                    }
                } else if (data.type === 'dj_joined') {
                    console.log('[Preview] DJ joined:', data.dj && data.dj.dj_name);
                } else if (data.type === 'dj_left') {
                    console.log('[Preview] DJ left:', data.dj_name);
                } else if (data.type === 'vj_state') {
                    // Initial state broadcast — extract zone info if present
                    if (data.zones && !zoneData) {
                        handleZonesResponse(data.zones);
                    }
                    // Capture zone_entities from initial state
                    if (data.zone_entities) {
                        zoneEntitiesData = data.zone_entities;
                    }
                }
            } catch (e) {
                console.error('Parse error:', e);
            }
        };

        ws.onclose = () => {
            if (statusEl) statusEl.classList.remove('connected');
            if (statusText) statusText.textContent = 'Disconnected';
            scheduleReconnect();
        };

        ws.onerror = () => {
            if (statusEl) statusEl.classList.add('error');
            if (statusText) statusText.textContent = 'Error';
        };

    } catch (e) {
        if (statusText) statusText.textContent = 'Failed';
        scheduleReconnect();
    }
}

function scheduleReconnect() {
    if (reconnectTimeout) clearTimeout(reconnectTimeout);

    reconnectAttempts++;

    const statusEl = document.getElementById('connection-status');
    const statusText = statusEl ? statusEl.querySelector('.status-text') : null;

    if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
        console.error('[WS] Max reconnect attempts reached');
        if (statusEl) {
            statusEl.classList.remove('connected');
            statusEl.classList.add('error');
        }
        if (statusText) statusText.textContent = 'Failed — click to retry';
        return;
    }

    // Show reconnecting state
    if (statusEl) {
        statusEl.classList.remove('connected');
        statusEl.classList.remove('error');
    }
    if (statusText) statusText.textContent = `Reconnecting (${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS})...`;

    // Exponential backoff with cap (1s, 2s, 4s, 8s, ... up to 30s)
    const delay = Math.min(
        BASE_RECONNECT_DELAY * Math.pow(2, reconnectAttempts - 1),
        MAX_RECONNECT_DELAY
    );

    console.log(`[WS] Reconnecting in ${Math.round(delay / 1000)}s (attempt ${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS})`);
    reconnectTimeout = setTimeout(connectWebSocket, delay);
}

function updateAudioState(data) {
    audioState.bands = data.bands || audioState.bands;
    audioState.amplitude = data.amplitude || 0;
    audioState.isBeat = data.is_beat || false;
    audioState.beatIntensity = data.beat_intensity || 0;
    audioState.frame = data.frame || 0;
    audioState.entities = data.entities || [];
    audioState.latencyMs = data.latency_ms || 0;

    // Update DJ overlay from state broadcast
    if (data.active_dj !== undefined) {
        updateDJOverlay(data.active_dj);
    }

    // Store per-zone entities for multi-zone positioning
    if (data.zone_entities) {
        zoneEntitiesData = data.zone_entities;
    }

    // Use server-provided BPM if available (more accurate than local calculation)
    if (data.bpm && data.bpm >= 40 && data.bpm <= 240) {
        currentBpm = Math.round(data.bpm);
        const bpmEl = document.getElementById('bpm-value');
        if (bpmEl) bpmEl.textContent = currentBpm;
    }

    // Update UI
    updateStatusBar();
    updateAudioMeters();

    // Update block positions
    updateBlockTargets();

    // Block indicators removed

    // Spawn particles on beat (BPM now updated from server above)
    if (audioState.isBeat) {
        // Only calculate BPM locally if server doesn't provide it
        if (!data.bpm) {
            calculateBpm();
        }
        if (particleEffects.enabled) {
            spawnBeatParticles();
        }
    }

    // Spawn ambient particles
    if (particleEffects.enabled) {
        spawnAmbientParticles();
    }
}

function updateStatusBar() {
    // Frame count
    const frameEl = document.getElementById('frame-count');
    if (frameEl) frameEl.textContent = audioState.frame;

    // Volume
    const volumeEl = document.getElementById('volume');
    const volumeFill = document.getElementById('volume-fill');
    const volumePercent = Math.round(audioState.amplitude * 100);
    if (volumeEl) volumeEl.textContent = volumePercent + '%';
    if (volumeFill) volumeFill.style.width = volumePercent + '%';

    // Beat indicator
    const beatIndicator = document.getElementById('beat-indicator');
    if (beatIndicator) {
        beatIndicator.classList.toggle('active', audioState.isBeat);
    }

    // Beat flash effect
    const flashEl = document.querySelector('.beat-flash');
    if (flashEl) {
        flashEl.classList.toggle('active', audioState.isBeat);
    }

    // Latency display
    const latencyEl = document.getElementById('latency-display');
    if (latencyEl && audioState.latencyMs !== undefined) {
        latencyEl.textContent = `Latency: ${audioState.latencyMs.toFixed(1)}ms`;
    }

    // Beat overlay
    const beatOverlay = document.getElementById('beat-overlay');
    if (beatOverlay) {
        beatOverlay.classList.toggle('active', audioState.isBeat);
    }

    // Block status
    const blockStatus = document.getElementById('block-status');
    if (blockStatus && blockIndicators) {
        const stats = blockIndicators.getStats();
        blockStatus.textContent = `Blocks: ${stats.active}/${stats.total}`;
    }

    // Particle status (combine counts from effects + entity renderer)
    const particleStatus = document.getElementById('particle-status');
    if (particleStatus) {
        let totalParticles = particleSystem ? particleSystem.getActiveCount() : 0;
        if (particleEntityRenderer && (viewMode === 'particles' || viewMode === 'hybrid')) {
            totalParticles += particleEntityRenderer.getActiveCount();
        }
        particleStatus.textContent = `Particles: ${totalParticles}`;
    }
}

function updateAudioMeters() {
    // 5 bands for ultra-low-latency mode
    for (let i = 0; i < 5; i++) {
        const meterBar = document.getElementById(`band-${i}`);
        if (meterBar) {
            const height = Math.round(audioState.bands[i] * 100);
            meterBar.style.height = height + '%';
        }
    }
}

function calculateBpm() {
    const now = performance.now();

    // Add current beat time to history
    beatHistory.push(now);

    // Keep only recent beats
    if (beatHistory.length > BPM_HISTORY_SIZE) {
        beatHistory.shift();
    }

    // Need at least 2 beats to calculate BPM
    if (beatHistory.length < 2) return;

    // Calculate average interval between beats
    let totalInterval = 0;
    for (let i = 1; i < beatHistory.length; i++) {
        totalInterval += beatHistory[i] - beatHistory[i - 1];
    }
    const avgInterval = totalInterval / (beatHistory.length - 1);

    // Convert to BPM (60000ms per minute)
    const bpm = Math.round(60000 / avgInterval);

    // Clamp to reasonable range (40-220 BPM)
    if (bpm >= 40 && bpm <= 220) {
        currentBpm = bpm;

        // Update display
        const bpmEl = document.getElementById('bpm-value');
        if (bpmEl) bpmEl.textContent = currentBpm;
    }

    // Clear history if interval is too long (song stopped/changed)
    if (avgInterval > 2000) {
        beatHistory = [now];
    }
}

function spawnBeatParticles() {
    if (!particleSystem) return;

    const now = performance.now();
    if (now - lastBeatTime < BEAT_COOLDOWN) return;
    lastBeatTime = now;

    // 5-band system: bass is now bands[0]
    const bass = audioState.bands[0] || 0;
    const intensity = audioState.beatIntensity || 0.5;

    // Bass flames (combined kick/bass)
    if (particleEffects.bassFlame && bass > 0.3) {
        const count = Math.floor(8 + intensity * 12);
        particleSystem.spawn('FLAME', 0, 0.1, 0, count);
        if (bass > 0.6) {
            particleSystem.spawn('LAVA', 0, 0.2, 0, Math.floor(count / 3));
        }
    }

    // Soul fire on bass hits
    if (particleEffects.soulFire && bass > 0.5) {
        const count = Math.floor(5 + intensity * 8);
        particleSystem.spawn('SOUL_FIRE_FLAME', 0, 0.1, 0, count);
    }

    // Beat ring
    if (particleEffects.beatRing) {
        const count = Math.floor(16 + intensity * 16);
        particleSystem.spawnRing('END_ROD', 0, 0.5, 0, 2, count);
    }
}

function spawnAmbientParticles() {
    if (!particleSystem) return;

    // 5-band system: high is bands[3], air is bands[4]
    const high = audioState.bands[3] || 0;
    const air = audioState.bands[4] || 0;

    // Musical notes on high frequencies
    if (particleEffects.notes && high > 0.25) {
        if (Math.random() < high * 0.3) {
            const x = (Math.random() - 0.5) * 6;
            const z = (Math.random() - 0.5) * 6;
            particleSystem.spawn('NOTE', x, 1 + Math.random() * 2, z, 1);
        }
    }

    // Spectrum dust (5 bands)
    if (particleEffects.dust) {
        for (let i = 0; i < 5; i++) {
            const band = audioState.bands[i] || 0;
            if (band > 0.2 && Math.random() < band * 0.15) {
                const x = (Math.random() - 0.5) * 8;
                const z = (Math.random() - 0.5) * 8;
                const color = BAND_COLORS ? BAND_COLORS[i] : [1, 1, 1];
                particleSystem.spawn('DUST', x, 0.5 + Math.random() * 3, z, 1, color);
            }
        }
    }
}

function updateBlockTargets() {
    const showBlocks = (viewMode === 'blocks' || viewMode === 'hybrid') && viewMode !== 'bitmap';
    const hasZoneEntities = zoneEntitiesData && zoneData && Object.keys(zoneEntitiesData).length > 0;

    if (hasZoneEntities) {
        // Multi-zone path: position entities in per-zone groups with rotation
        updateMultiZoneEntities(zoneEntitiesData, audioState.bands, showBlocks);
        // Hide legacy single-zone blocks
        blocks.forEach(b => { b.visible = false; });
    } else {
        // Legacy single-zone path
        const entities = audioState.entities;
        if (!entities || entities.length === 0) return;

        ensureBlockCount(entities.length);

        const zoneSize = CONFIG.zoneSize;
        const offset = CONFIG.centerOffset;

        blocks.forEach((block, i) => {
            const entity = entities[i];
            if (!entity) return;

            block.userData.targetX = (entity.x * zoneSize) - offset;
            block.userData.targetY = (entity.y * zoneSize) + 0.5;
            block.userData.targetZ = (entity.z * zoneSize) - offset;
            block.userData.targetScale = entity.scale * 1.5;

            block.visible = showBlocks;

            updateBlockMaterial(block, entity, audioState.bands);
        });

        // Hide multi-zone blocks when in legacy mode
        for (const zg of Object.values(zoneGroups)) {
            zg.blocks.forEach(b => { b.visible = false; });
            if (zg.wireframe) zg.wireframe.visible = false;
        }
    }

    // Update particle entity renderer (particles/hybrid modes)
    updateParticleEntities();
}

// === Multi-Zone Entity Positioning ===

function getZoneWireframeColor(zone) {
    const role = (zone.stage_role || zone.name || '').toLowerCase();
    if (role.includes('main') || role.includes('center')) return 0x00D4FF;  // cyan
    if (role.includes('left') || role.includes('wing_l')) return 0xFF9100;  // orange
    if (role.includes('right') || role.includes('wing_r')) return 0x00E676; // green
    if (role.includes('sky') || role.includes('ceiling')) return 0xD500F9;  // purple
    if (role.includes('audience') || role.includes('floor')) return 0xFFD700; // gold
    return 0x4488AA; // default teal
}

function ensureZoneGroup(zoneName) {
    if (zoneGroups[zoneName]) return zoneGroups[zoneName];
    if (!zoneData) return null;

    const zone = zoneData.find(z => z.name === zoneName);
    if (!zone) return null;

    const group = new THREE.Group();
    group.name = `zone-${zoneName}`;

    // Position relative to stage center
    const ox = (zone.origin?.x || 0) - stageCenter.x;
    const oy = (zone.origin?.y || 0) - stageCenter.y;
    const oz = (zone.origin?.z || 0) - stageCenter.z;
    const sx = zone.size?.x || 10;
    const sy = zone.size?.y || 10;
    const sz = zone.size?.z || 10;

    // Group origin = zone origin (rotation pivot point)
    // Minecraft rotates around the origin corner, not the center
    group.position.set(ox, oy, oz);
    group.rotation.y = -(zone.rotation || 0) * (Math.PI / 180);

    // Wireframe box offset to (size/2) in local space
    // so it covers (0,0,0) to (sx,sy,sz) before rotation
    const boxGeo = new THREE.BoxGeometry(sx, sy, sz);
    const edgesGeo = new THREE.EdgesGeometry(boxGeo);
    const wireColor = getZoneWireframeColor(zone);
    const lineMat = new THREE.LineBasicMaterial({ color: wireColor, opacity: 0.35, transparent: true });
    const wireframe = new THREE.LineSegments(edgesGeo, lineMat);
    wireframe.position.set(sx / 2, sy / 2, sz / 2);
    group.add(wireframe);
    boxGeo.dispose();

    scene.add(group);

    const zoneGroup = {
        group,
        blocks: [],
        wireframe,
        sizeX: sx,
        sizeY: sy,
        sizeZ: sz,
        zone
    };
    zoneGroups[zoneName] = zoneGroup;
    return zoneGroup;
}

function ensureZoneBlockCount(zoneGroup, count) {
    // Add blocks if needed
    while (zoneGroup.blocks.length < count) {
        const geometry = new THREE.BoxGeometry(CONFIG.blockSize, CONFIG.blockSize, CONFIG.blockSize);
        const material = new THREE.MeshStandardMaterial({
            color: 0x6366f1,
            emissive: 0x6366f1,
            emissiveIntensity: 0.3,
            roughness: 0.4,
            metalness: 0.6,
        });
        const block = new THREE.Mesh(geometry, material);
        block.castShadow = true;
        block.receiveShadow = true;
        block.userData = { targetX: 0, targetY: 0, targetZ: 0, targetScale: 1, bandIndex: -1, currentMaterial: '' };
        zoneGroup.group.add(block);
        zoneGroup.blocks.push(block);
    }
    // Hide excess blocks (pooling)
    for (let i = 0; i < zoneGroup.blocks.length; i++) {
        zoneGroup.blocks[i].visible = i < count;
    }
}

function updateMultiZoneEntities(zoneEntities, bands, showBlocks) {
    for (const [zoneName, entities] of Object.entries(zoneEntities)) {
        if (!Array.isArray(entities)) continue;

        const zoneGroup = ensureZoneGroup(zoneName);
        if (!zoneGroup) continue;

        zoneGroup.wireframe.visible = showBlockGrid;
        ensureZoneBlockCount(zoneGroup, entities.length);

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

            block.visible = showBlocks;

            updateBlockMaterial(block, entity, bands);
        }
    }
}

function disposeZoneGroups() {
    for (const [name, zg] of Object.entries(zoneGroups)) {
        // Deactivate bitmap for this zone
        if (bitmapPreview) bitmapPreview.deactivate(name);

        zg.blocks.forEach(block => {
            block.geometry.dispose();
            block.material.dispose();
        });
        if (zg.wireframe) {
            zg.wireframe.geometry.dispose();
            zg.wireframe.material.dispose();
        }
        scene.remove(zg.group);
    }
    zoneGroups = {};
}

function animate() {
    requestAnimationFrame(animate);

    const now = performance.now();
    const dt = (now - lastFrameTime) / 1000;
    lastFrameTime = now;

    // FPS calculation
    frameCount++;
    if (now - lastFpsUpdate >= 1000) {
        currentFps = frameCount;
        frameCount = 0;
        lastFpsUpdate = now;
        const fpsEl = document.getElementById('fps-display');
        if (fpsEl) fpsEl.textContent = currentFps;
    }

    // Smooth block animations (legacy single-zone blocks)
    const lerpSpeed = 0.25;
    blocks.forEach((block) => {
        block.position.x += (block.userData.targetX - block.position.x) * lerpSpeed;
        block.position.y += (block.userData.targetY - block.position.y) * lerpSpeed;
        block.position.z += (block.userData.targetZ - block.position.z) * lerpSpeed;

        const targetScale = block.userData.targetScale || 1;
        block.scale.x += (targetScale - block.scale.x) * lerpSpeed;
        block.scale.y += (targetScale - block.scale.y) * lerpSpeed;
        block.scale.z += (targetScale - block.scale.z) * lerpSpeed;
    });

    // Smooth block animations (multi-zone blocks)
    for (const zg of Object.values(zoneGroups)) {
        for (const block of zg.blocks) {
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

    // Update particle system (beat/ambient effects)
    if (particleSystem) {
        particleSystem.update(dt);
    }

    // Update particle entity renderer (particle/hybrid view mode)
    if (particleEntityRenderer && (viewMode === 'particles' || viewMode === 'hybrid')) {
        particleEntityRenderer.update(dt);
    }

    // Update bitmap LED wall preview
    if (bitmapPreview && viewMode === 'bitmap') {
        bitmapPreview.update(dt, audioState);
    }

    // Auto rotate camera
    if (autoRotate) {
        const spherical = new THREE.Spherical();
        spherical.setFromVector3(camera.position);
        spherical.theta += 0.002;
        camera.position.setFromSpherical(spherical);
        camera.lookAt(0, 5, 0);
    }

    renderer.render(scene, camera);
}

function onResize() {
    const previewSection = document.querySelector('.preview-section');
    if (previewSection) {
        const width = previewSection.clientWidth;
        const height = previewSection.clientHeight;
        camera.aspect = width / height;
        camera.updateProjectionMatrix();
        renderer.setSize(width, height);
    } else {
        camera.aspect = window.innerWidth / window.innerHeight;
        camera.updateProjectionMatrix();
        renderer.setSize(window.innerWidth, window.innerHeight);
    }
}

function resetCamera() {
    camera.position.set(0, 12, 30);
    camera.lookAt(0, 5, 0);
}

function updatePatternList(patterns, currentPattern) {
    if (!patterns) return;

    availablePatterns = patterns;
    const select = document.getElementById('pattern-select');
    if (!select) return;

    while (select.firstChild) {
        select.removeChild(select.firstChild);
    }

    patterns.forEach(p => {
        const option = document.createElement('option');
        option.value = p.id;
        option.textContent = p.name;
        option.title = p.description;
        if (p.id === currentPattern) {
            option.selected = true;
        }
        select.appendChild(option);
    });

    audioState.pattern = currentPattern;
}

function setPattern(patternId) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'set_pattern', pattern: patternId }));
    }
}

function setBlockCount(count) {
    debouncedWsSend('block_count', { type: 'set_block_count', count: count });
}

function setAudioSetting(setting, value) {
    debouncedWsSend('audio_' + setting, { type: 'set_audio_setting', setting: setting, value: value });
}

function setBandSensitivity(bandIndex, sensitivity) {
    debouncedWsSend('band_' + bandIndex, { type: 'set_band_sensitivity', band: bandIndex, sensitivity: sensitivity });
}

function setPreset(presetName) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'set_preset', preset: presetName }));
    }

    document.querySelectorAll('.preset-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.preset === presetName);
    });
}

function updateUIFromPreset(presetName, settings) {
    document.querySelectorAll('.preset-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.preset === presetName);
    });

    if (!settings) return;

    // Update sliders
    const updateSlider = (id, displayId, value, suffix) => {
        const slider = document.getElementById(id);
        const display = document.getElementById(displayId);
        if (slider) slider.value = value;
        if (display) display.textContent = value + suffix;
    };

    if (settings.attack !== undefined) {
        updateSlider('attack-slider', 'attack-display', Math.round(settings.attack * 100), '%');
    }
    if (settings.release !== undefined) {
        updateSlider('release-slider', 'release-display', Math.round(settings.release * 100), '%');
    }
    if (settings.agc_max_gain !== undefined) {
        updateSlider('agc-slider', 'agc-display', settings.agc_max_gain, 'x');
    }
    if (settings.beat_sensitivity !== undefined) {
        updateSlider('beat-slider', 'beat-display', Math.round(settings.beat_sensitivity * 100), '%');
    }
    if (settings.beat_threshold !== undefined) {
        const slider = document.getElementById('beat-thresh-slider');
        const display = document.getElementById('beat-thresh-display');
        if (slider) slider.value = Math.round(settings.beat_threshold * 100);
        if (display) display.textContent = settings.beat_threshold.toFixed(1) + 'x';
    }

    // Update band sensitivity sliders (5 bands)
    if (settings.band_sensitivity) {
        for (let i = 0; i < 5; i++) {
            const val = Math.round(settings.band_sensitivity[i] * 100);
            const slider = document.getElementById(`sens-${i}`);
            if (slider) slider.value = val;
        }
    }
}

function updateVoiceStatus(data) {
    const dot = document.getElementById('voice-header-dot');
    const text = document.getElementById('voice-header-text');

    if (!dot || !text) return;

    // Remove all state classes
    dot.classList.remove('voice-streaming', 'voice-available', 'voice-na');

    if (!data.available) {
        dot.classList.add('voice-na');
        text.textContent = 'Voice: N/A';
    } else if (data.streaming) {
        dot.classList.add('voice-streaming');
        text.textContent = 'Voice: Streaming';
    } else {
        dot.classList.add('voice-available');
        text.textContent = 'Voice: Off';
    }
}

// === Stage Block Scanning ===

function handleZonesResponse(zones) {
    if (!zones || zones.length === 0) return;
    zoneData = zones;
    computeStageCenter(zones);
    console.log(`[Stage] Received ${zones.length} zones, center:`, stageCenter);

    // Dispose and recreate zone groups with new positions
    disposeZoneGroups();
    zones.forEach(zone => {
        if (zone.name) ensureZoneGroup(zone.name);
    });

    // Reposition camera to frame the main zone
    const mainZone = zones.find(z => (z.name || '').toLowerCase().includes('main'));
    if (mainZone) {
        const sy = mainZone.size?.y || 10;
        const sx = mainZone.size?.x || 10;
        const viewDist = Math.max(sx, sy) * 1.8;
        camera.position.set(0, sy * 0.6, viewDist);
        camera.lookAt(0, sy * 0.4, 0);
    }

    // Activate bitmap preview for all zones (visible only in LED Wall mode)
    if (bitmapPreview) {
        zones.forEach(zone => {
            if (zone.name && zoneGroups[zone.name]) {
                bitmapPreview.activate(zone.name, 16, 12, 'bmp_plasma', zoneGroups[zone.name]);
            }
        });
        applyBitmapZoneVisibility();
    }

    // Populate bitmap zone selector
    populateBitmapZoneSelector(zones);
}

function populateBitmapZoneSelector(zones) {
    const select = document.getElementById('bitmap-zone-select');
    if (!select) return;

    const currentVal = select.value;
    while (select.children.length > 1) select.removeChild(select.lastChild);

    zones.forEach(zone => {
        if (!zone.name) return;
        const opt = document.createElement('option');
        opt.value = zone.name;
        opt.textContent = zone.name;
        select.appendChild(opt);
    });

    // Restore previous selection if still valid
    if (currentVal && [...select.options].some(o => o.value === currentVal)) {
        select.value = currentVal;
    } else {
        select.value = '__all__';
        selectedBitmapZone = '__all__';
    }
}

function applyBitmapZoneVisibility() {
    if (!bitmapPreview) return;
    const isBitmap = (viewMode === 'bitmap');
    if (!isBitmap) {
        bitmapPreview.setVisible(false);
        return;
    }
    if (selectedBitmapZone === '__all__') {
        bitmapPreview.setVisible(true);
    } else {
        for (const zoneName of Object.keys(bitmapPreview.zones)) {
            bitmapPreview.setZoneVisible(zoneName, zoneName === selectedBitmapZone);
        }
    }
}

function handleBitmapInitialized(data) {
    if (!bitmapPreview || !data.zone) return;
    const zg = zoneGroups[data.zone];
    if (!zg) return;

    bitmapPreview.activate(
        data.zone,
        data.width || 16,
        data.height || 12,
        data.pattern || 'bmp_plasma',
        zg
    );

    // Auto-switch to bitmap view mode when bitmap zones are initialized
    if (viewMode !== 'bitmap') {
        viewMode = 'bitmap';
        const radio = document.querySelector('input[name="view-mode"][value="bitmap"]');
        if (radio) radio.checked = true;
        applyViewMode();
    } else {
        applyBitmapZoneVisibility();
    }
    console.log(`[Bitmap] Initialized ${data.width}x${data.height} for zone '${data.zone}'`);
}

function handleStagesResponse(stages) {
    if (!stages || stages.length === 0) return;

    // Find first active stage, or just use the first one
    const stage = stages.find(s => s.active) || stages[0];
    if (stage && stage.name && !stageScanRequested) {
        stageScanRequested = true;
        console.log(`[Stage] Requesting block scan for stage: ${stage.name}`);
        requestStageBlocks(stage.name);
    }
}

function handleStageBlocksResponse(data) {
    if (data.error) {
        console.warn('[Stage] Block scan error:', data.error);
        return;
    }
    console.log(`[Stage] Received ${data.blocks ? data.blocks.length : 0} scanned blocks`);
    renderStageBlocks(data);

    // Hide procedural environment once real blocks are loaded
    if (mcEnvironment && data.blocks && data.blocks.length > 0) {
        mcEnvironment.setVisible(false);
    }
}

function rescanStage() {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    stageScanRequested = false;
    disposeStageBlocks();
    disposeZoneGroups();
    zoneData = null;
    zoneEntitiesData = null;
    ws.send(JSON.stringify({ type: 'get_zones' }));
    ws.send(JSON.stringify({ type: 'get_stages' }));
    console.log('[Stage] Rescan requested');
}

function requestStageBlocks(stageName) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'scan_stage_blocks', stage: stageName }));
    }
}

function computeStageCenter(zones) {
    if (!zones || zones.length === 0) {
        stageCenter = { x: 0, y: 0, z: 0 };
        return;
    }

    // Prefer the "main" zone for centering; fall back to bounding box of all zones
    const mainZone = zones.find(z => (z.name || '').toLowerCase().includes('main'));
    const targetZones = mainZone ? [mainZone] : zones;

    let minX = Infinity, minY = Infinity, minZ = Infinity;
    let maxX = -Infinity, maxZ = -Infinity;

    targetZones.forEach(zone => {
        const ox = zone.origin?.x || 0;
        const oy = zone.origin?.y || 0;
        const oz = zone.origin?.z || 0;
        const sx = zone.size?.x || 10;
        const sz = zone.size?.z || 10;

        minX = Math.min(minX, ox);
        minY = Math.min(minY, oy);
        minZ = Math.min(minZ, oz);
        maxX = Math.max(maxX, ox + sx);
        maxZ = Math.max(maxZ, oz + sz);
    });

    // Center on X/Z, but use the bottom Y so zone blocks sit on the floor
    stageCenter = {
        x: (minX + maxX) / 2,
        y: minY,
        z: (minZ + maxZ) / 2
    };
}

function renderStageBlocks(data) {
    const { palette, blocks } = data;
    if (!palette || !blocks || blocks.length === 0) return;

    // Dispose previous stage blocks
    disposeStageBlocks();

    stageBlocksGroup = new THREE.Group();
    stageBlocksGroup.name = 'stage-blocks';

    const center = stageCenter;

    // Group blocks by palette index
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

        // Try procedural texture material, fallback to color
        let material = null;
        if (textureManager) {
            material = textureManager.getEnvironmentMaterial(materialName, 'side');
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

        stageBlocksGroup.add(mesh);
    }

    stageBlocksGroup.visible = showBlockGrid;
    scene.add(stageBlocksGroup);
}

function disposeStageBlocks() {
    if (stageBlocksGroup) {
        stageBlocksGroup.traverse(child => {
            if (child.isMesh) {
                child.geometry.dispose();
                if (child.material) child.material.dispose();
            }
        });
        scene.remove(stageBlocksGroup);
        stageBlocksGroup = null;
    }
}

// === DJ Info Overlay ===

function updateDJOverlay(activeDj) {
    const overlay = document.getElementById('dj-overlay');
    if (!overlay) return;

    if (!activeDj) {
        overlay.style.display = 'none';
        return;
    }

    overlay.style.display = 'flex';
    const avatar = document.getElementById('dj-overlay-avatar');
    const name = document.getElementById('dj-overlay-name');

    if (activeDj.avatar_url) {
        avatar.src = activeDj.avatar_url;
        avatar.style.display = 'block';
    } else {
        avatar.style.display = 'none';
    }

    name.textContent = activeDj.dj_name;
}

// Close WebSocket cleanly when leaving the page
window.addEventListener('beforeunload', () => {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.close();
    }
});

// Start when DOM is ready
document.addEventListener('DOMContentLoaded', init);
