/**
 * AudioViz Browser Preview
 * Three.js visualization with Minecraft-style blocks
 */

// Configuration
const CONFIG = {
    wsPort: 8766,
    entityCount: 16,
    gridSize: 4,
    blockSize: 0.8,
    zoneSize: 10,
    centerOffset: 5,  // Center the zone (zoneSize / 2)
    colors: [
        0xff4444,  // subBass - red
        0xff8844,  // bass - orange
        0xffff44,  // lowMid - yellow
        0x44ff44,  // mid - green
        0x4444ff,  // highMid - blue
        0xff44ff   // high - magenta
    ]
};

// Three.js components
let scene, camera, renderer, controls;
let blocks = [];
let ground, gridHelper;
let autoRotate = true;

// Audio state
let audioState = {
    bands: [0, 0, 0, 0, 0, 0],
    amplitude: 0,
    isBeat: false,
    beatIntensity: 0,
    frame: 0,
    entities: [],  // Pre-calculated entity positions from server
    pattern: 'spectrum'
};

// Available patterns (populated from server)
let availablePatterns = [];

// WebSocket
let ws = null;
let reconnectTimeout = null;

// Initialize
function init() {
    // Scene
    scene = new THREE.Scene();
    scene.background = new THREE.Color(0x1a1a2e);
    scene.fog = new THREE.Fog(0x1a1a2e, 15, 40);

    // Camera
    camera = new THREE.PerspectiveCamera(60, window.innerWidth / window.innerHeight, 0.1, 100);
    camera.position.set(12, 10, 12);
    camera.lookAt(0, 2, 0);

    // Renderer
    const canvas = document.getElementById('visualizer');
    renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
    renderer.setSize(window.innerWidth, window.innerHeight);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.shadowMap.enabled = true;

    // Lights
    const ambientLight = new THREE.AmbientLight(0x404040, 0.5);
    scene.add(ambientLight);

    const directionalLight = new THREE.DirectionalLight(0xffffff, 0.8);
    directionalLight.position.set(10, 20, 10);
    directionalLight.castShadow = true;
    directionalLight.shadow.mapSize.width = 2048;
    directionalLight.shadow.mapSize.height = 2048;
    scene.add(directionalLight);

    const pointLight = new THREE.PointLight(0x4ecdc4, 0.5, 20);
    pointLight.position.set(0, 5, 0);
    scene.add(pointLight);

    // Ground plane
    const groundGeometry = new THREE.PlaneGeometry(20, 20);
    const groundMaterial = new THREE.MeshStandardMaterial({
        color: 0x2a2a4a,
        roughness: 0.8
    });
    ground = new THREE.Mesh(groundGeometry, groundMaterial);
    ground.rotation.x = -Math.PI / 2;
    ground.receiveShadow = true;
    scene.add(ground);

    // Grid
    gridHelper = new THREE.GridHelper(20, 20, 0x444444, 0x333333);
    gridHelper.position.y = 0.01;
    scene.add(gridHelper);

    // Create visualization blocks
    createBlocks();

    // Add beat flash overlay
    const flashDiv = document.createElement('div');
    flashDiv.className = 'beat-flash';
    document.body.appendChild(flashDiv);

    // Event listeners
    window.addEventListener('resize', onResize);
    document.getElementById('btn-reset').addEventListener('click', resetCamera);
    document.getElementById('chk-rotate').addEventListener('change', (e) => {
        autoRotate = e.target.checked;
    });

    // Pattern selector
    document.getElementById('pattern-select').addEventListener('change', (e) => {
        setPattern(e.target.value);
    });

    // Block count slider
    const blockCountSlider = document.getElementById('block-count');
    const blockCountDisplay = document.getElementById('block-count-display');
    blockCountSlider.addEventListener('input', (e) => {
        const count = parseInt(e.target.value);
        blockCountDisplay.textContent = count;
        setBlockCount(count);
    });

    // Audio reactivity sliders
    setupAudioSlider('attack-slider', 'attack-display', '%', (val) => {
        setAudioSetting('attack', val / 100);  // Convert to 0-1 range
    });

    setupAudioSlider('release-slider', 'release-display', '%', (val) => {
        setAudioSetting('release', val / 100);  // Convert to 0-1 range
    });

    setupAudioSlider('agc-slider', 'agc-display', 'x', (val) => {
        setAudioSetting('agc_max_gain', val);
    });

    setupAudioSlider('beat-slider', 'beat-display', '%', (val) => {
        setAudioSetting('beat_sensitivity', val / 100);  // Convert to multiplier
    });

    // Beat threshold needs special formatting (80 -> 0.8x, 130 -> 1.3x)
    const beatThreshSlider = document.getElementById('beat-thresh-slider');
    const beatThreshDisplay = document.getElementById('beat-thresh-display');
    if (beatThreshSlider && beatThreshDisplay) {
        beatThreshSlider.addEventListener('input', (e) => {
            const val = parseFloat(e.target.value);
            beatThreshDisplay.textContent = (val / 100).toFixed(1) + 'x';
            setAudioSetting('beat_threshold', val / 100);
        });
    }

    // Per-band sensitivity sliders
    for (let i = 0; i < 6; i++) {
        const slider = document.getElementById(`sens-${i}`);
        if (slider) {
            slider.addEventListener('input', (e) => {
                const val = parseInt(e.target.value);
                setBandSensitivity(i, val / 100);  // Convert to 0-2 multiplier
            });
        }
    }

    // Mouse controls for camera
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

        // Rotate camera around center
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

    // Setup preset buttons
    setupPresetButtons();

    // Sync band sensitivity sliders (inline <-> advanced panel)
    syncBandSensitivitySliders();

    // Connect WebSocket
    connectWebSocket();

    // Start animation
    animate();
}

function createBlock(index) {
    const geometry = new THREE.BoxGeometry(CONFIG.blockSize, CONFIG.blockSize, CONFIG.blockSize);
    const bandIndex = index % 6;
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

    // Store targets for smooth animation
    block.userData.bandIndex = bandIndex;
    block.userData.targetX = 0;
    block.userData.targetY = 0;
    block.userData.targetZ = 0;
    block.userData.targetScale = 1;

    return block;
}

function createBlocks() {
    // Create initial blocks
    for (let i = 0; i < CONFIG.entityCount; i++) {
        const block = createBlock(i);
        block.position.set(0, 0.5, 0);
        scene.add(block);
        blocks.push(block);
    }
}

function ensureBlockCount(count) {
    // Add blocks if needed
    while (blocks.length < count) {
        const block = createBlock(blocks.length);
        block.position.set(0, 0.5, 0);
        scene.add(block);
        blocks.push(block);
    }

    // Remove blocks if needed
    while (blocks.length > count) {
        const block = blocks.pop();
        scene.remove(block);
        block.geometry.dispose();
        block.material.dispose();
    }
}

function connectWebSocket() {
    const statusEl = document.getElementById('status');

    try {
        ws = new WebSocket(`ws://localhost:${CONFIG.wsPort}`);

        ws.onopen = () => {
            statusEl.textContent = 'Connected';
            statusEl.className = 'connected';
            console.log('WebSocket connected');
        };

        ws.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                if (data.type === 'audio' || data.type === 'state') {
                    updateAudioState(data);
                } else if (data.type === 'patterns' || data.type === 'pattern_changed') {
                    updatePatternList(data.patterns, data.current || data.pattern);
                } else if (data.type === 'preset_changed') {
                    updateUIFromPreset(data.preset, data.settings);
                }
            } catch (e) {
                console.error('Parse error:', e);
            }
        };

        ws.onclose = () => {
            statusEl.textContent = 'Disconnected - Reconnecting...';
            statusEl.className = 'error';
            scheduleReconnect();
        };

        ws.onerror = () => {
            statusEl.textContent = 'Connection Error';
            statusEl.className = 'error';
        };

    } catch (e) {
        statusEl.textContent = 'Connection Failed';
        statusEl.className = 'error';
        scheduleReconnect();
    }
}

function scheduleReconnect() {
    if (reconnectTimeout) clearTimeout(reconnectTimeout);
    reconnectTimeout = setTimeout(connectWebSocket, 2000);
}

function updateAudioState(data) {
    audioState.bands = data.bands || audioState.bands;
    audioState.amplitude = data.amplitude || 0;
    audioState.isBeat = data.is_beat || false;
    audioState.beatIntensity = data.beat_intensity || 0;
    audioState.frame = data.frame || 0;
    audioState.entities = data.entities || [];  // Pre-calculated positions

    // Update UI
    document.getElementById('frame-count').textContent = audioState.frame;
    document.getElementById('volume').textContent = Math.round(audioState.amplitude * 100) + '%';

    const beatEl = document.getElementById('beat-indicator');
    if (audioState.isBeat) {
        beatEl.textContent = '!';
        beatEl.className = 'active';
    } else {
        beatEl.textContent = '-';
        beatEl.className = '';
    }

    // Update band bars
    for (let i = 0; i < 6; i++) {
        const bandEl = document.getElementById(`band-${i}`);
        if (bandEl) {
            bandEl.style.width = (audioState.bands[i] * 100) + '%';
        }
    }

    // Beat flash effect
    const flashEl = document.querySelector('.beat-flash');
    if (flashEl) {
        flashEl.classList.toggle('active', audioState.isBeat);
    }

    // Update block targets from server-calculated positions
    updateBlockTargets();
}

function updateBlockTargets() {
    // Use pre-calculated entity positions from server (single source of truth)
    const entities = audioState.entities;
    if (!entities || entities.length === 0) return;

    // Dynamically adjust block count to match server
    ensureBlockCount(entities.length);

    const zoneSize = CONFIG.zoneSize;
    const offset = CONFIG.centerOffset;

    blocks.forEach((block, i) => {
        const entity = entities[i];
        if (!entity) return;

        // Server sends normalized 0-1 coordinates, convert to world space
        // Center the visualization around origin
        block.userData.targetX = (entity.x * zoneSize) - offset;
        block.userData.targetY = entity.y * zoneSize;
        block.userData.targetZ = (entity.z * zoneSize) - offset;
        block.userData.targetScale = entity.scale * 1.5;

        // Update color based on band
        const bandIndex = entity.band || 0;
        if (block.userData.bandIndex !== bandIndex) {
            block.userData.bandIndex = bandIndex;
            block.material.color.setHex(CONFIG.colors[bandIndex]);
            block.material.emissive.setHex(CONFIG.colors[bandIndex]);
        }

        // Emissive intensity based on band value
        const bandValue = audioState.bands[bandIndex] || 0;
        block.material.emissiveIntensity = 0.3 + bandValue * 1.0;
    });
}

function animate() {
    requestAnimationFrame(animate);

    // Smooth block animations - lerp all 3 axes
    const lerpSpeed = 0.25;  // Smooth but responsive

    blocks.forEach((block) => {
        // Lerp position on all axes
        block.position.x += (block.userData.targetX - block.position.x) * lerpSpeed;
        block.position.y += (block.userData.targetY - block.position.y) * lerpSpeed;
        block.position.z += (block.userData.targetZ - block.position.z) * lerpSpeed;

        // Lerp scale uniformly
        const targetScale = block.userData.targetScale || 1;
        block.scale.x += (targetScale - block.scale.x) * lerpSpeed;
        block.scale.y += (targetScale - block.scale.y) * lerpSpeed;
        block.scale.z += (targetScale - block.scale.z) * lerpSpeed;
    });

    // Auto rotate camera
    if (autoRotate) {
        const spherical = new THREE.Spherical();
        spherical.setFromVector3(camera.position);
        spherical.theta += 0.002;
        camera.position.setFromSpherical(spherical);
        camera.lookAt(0, 3, 0);  // Look slightly higher
    }

    renderer.render(scene, camera);
}

function onResize() {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
}

function resetCamera() {
    camera.position.set(12, 10, 12);
    camera.lookAt(0, 2, 0);
}

function updatePatternList(patterns, currentPattern) {
    if (!patterns) return;

    availablePatterns = patterns;
    const select = document.getElementById('pattern-select');

    // Clear existing options using DOM methods
    while (select.firstChild) {
        select.removeChild(select.firstChild);
    }

    // Add new options
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
    console.log('Patterns loaded:', patterns.length, 'Current:', currentPattern);
}

function setPattern(patternId) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
            type: 'set_pattern',
            pattern: patternId
        }));
        console.log('Switching to pattern:', patternId);
    }
}

function setBlockCount(count) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
            type: 'set_block_count',
            count: count
        }));
        console.log('Setting block count:', count);
    }
}

function setupAudioSlider(sliderId, displayId, suffix, callback) {
    const slider = document.getElementById(sliderId);
    const display = document.getElementById(displayId);

    if (!slider || !display) return;

    slider.addEventListener('input', (e) => {
        const val = parseFloat(e.target.value);
        display.textContent = val + suffix;
        callback(val);
    });
}

function setAudioSetting(setting, value) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
            type: 'set_audio_setting',
            setting: setting,
            value: value
        }));
        console.log('Audio setting:', setting, '=', value);
    }
}

function setBandSensitivity(bandIndex, sensitivity) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
            type: 'set_band_sensitivity',
            band: bandIndex,
            sensitivity: sensitivity
        }));
        console.log('Band', bandIndex, 'sensitivity:', sensitivity);
    }
}

function setPreset(presetName) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
            type: 'set_preset',
            preset: presetName
        }));
        console.log('Switching to preset:', presetName);
    }

    // Update button states
    document.querySelectorAll('.preset').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.preset === presetName);
    });
}

function setupPresetButtons() {
    document.querySelectorAll('.preset').forEach(btn => {
        btn.addEventListener('click', () => {
            setPreset(btn.dataset.preset);
        });
    });
}

function syncBandSensitivitySliders() {
    // Sync inline band sliders with advanced panel sliders
    for (let i = 0; i < 6; i++) {
        const inlineSlider = document.getElementById(`sens-${i}`);
        const advSlider = document.getElementById(`sens-adv-${i}`);

        if (inlineSlider && advSlider) {
            // Sync inline -> advanced
            inlineSlider.addEventListener('input', (e) => {
                advSlider.value = e.target.value;
            });

            // Sync advanced -> inline
            advSlider.addEventListener('input', (e) => {
                inlineSlider.value = e.target.value;
                const val = parseInt(e.target.value);
                setBandSensitivity(i, val / 100);
            });
        }
    }
}

function updateUIFromPreset(presetName, settings) {
    // Update preset button states
    document.querySelectorAll('.preset').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.preset === presetName);
    });

    if (!settings) return;

    // Update attack slider
    const attackSlider = document.getElementById('attack-slider');
    const attackDisplay = document.getElementById('attack-display');
    if (attackSlider && attackDisplay) {
        attackSlider.value = Math.round(settings.attack * 100);
        attackDisplay.textContent = attackSlider.value + '%';
    }

    // Update release slider
    const releaseSlider = document.getElementById('release-slider');
    const releaseDisplay = document.getElementById('release-display');
    if (releaseSlider && releaseDisplay) {
        releaseSlider.value = Math.round(settings.release * 100);
        releaseDisplay.textContent = releaseSlider.value + '%';
    }

    // Update AGC slider
    const agcSlider = document.getElementById('agc-slider');
    const agcDisplay = document.getElementById('agc-display');
    if (agcSlider && agcDisplay) {
        agcSlider.value = settings.agc_max_gain;
        agcDisplay.textContent = settings.agc_max_gain + 'x';
    }

    // Update beat sensitivity slider
    const beatSlider = document.getElementById('beat-slider');
    const beatDisplay = document.getElementById('beat-display');
    if (beatSlider && beatDisplay) {
        beatSlider.value = Math.round(settings.beat_sensitivity * 100);
        beatDisplay.textContent = beatSlider.value + '%';
    }

    // Update beat threshold slider
    const beatThreshSlider = document.getElementById('beat-thresh-slider');
    const beatThreshDisplay = document.getElementById('beat-thresh-display');
    if (beatThreshSlider && beatThreshDisplay) {
        beatThreshSlider.value = Math.round(settings.beat_threshold * 100);
        beatThreshDisplay.textContent = settings.beat_threshold.toFixed(1) + 'x';
    }

    // Update band sensitivity sliders
    if (settings.band_sensitivity) {
        for (let i = 0; i < 6; i++) {
            const val = Math.round(settings.band_sensitivity[i] * 100);
            const inlineSlider = document.getElementById(`sens-${i}`);
            const advSlider = document.getElementById(`sens-adv-${i}`);
            if (inlineSlider) inlineSlider.value = val;
            if (advSlider) advSlider.value = val;
        }
    }

    console.log('UI updated for preset:', presetName);
}

// Start when DOM is ready
document.addEventListener('DOMContentLoaded', init);
