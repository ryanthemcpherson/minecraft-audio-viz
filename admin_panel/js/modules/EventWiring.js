/**
 * EventWiring - Sets up all DOM event listeners for the admin panel.
 */

import { debounce } from '../utils/debounce.js';

export function setupEventListeners(app) {
    const { elements, state, ws, ui, audio, patterns, actions, particles, scenes, zones, connectCodes, voice } = app;

    // Tab switching
    elements.tabs.forEach(tab => {
        tab.addEventListener('click', () => ui.switchTab(tab.dataset.tab));
    });

    // Preset buttons
    elements.presetButtons.forEach(btn => {
        btn.addEventListener('click', () => patterns.setPreset(btn.dataset.preset));
    });

    // Band faders
    elements.faders.forEach(fader => {
        const input = fader.querySelector('.fader-input');
        const band = fader.dataset.band;

        const sendUpdate = debounce((value) => {
            if (band === 'master') {
                audio.sendAllBandSensitivities();
            } else {
                const bandIndex = parseInt(band);
                audio.sendBandSensitivity(bandIndex, value / 100);
            }
        }, 50);

        input.addEventListener('input', () => {
            const value = parseInt(input.value);
            ui.updateFaderDisplay(fader, value);

            if (band === 'master') {
                state.masterSensitivity = value / 100;
            } else {
                const bandIndex = parseInt(band);
                state.bandSensitivity[bandIndex] = value / 100;
            }

            sendUpdate(value);
        });
    });

    // Audio controls
    ui.setupControl('ctrl-attack', 'val-attack', (val) => {
        state.attack = val / 100;
        audio.sendSetting('attack', val / 100);
    }, (val) => `${val}%`);

    ui.setupControl('ctrl-release', 'val-release', (val) => {
        state.release = val / 100;
        audio.sendSetting('release', val / 100);
    }, (val) => `${val}%`);

    ui.setupControl('ctrl-agc', 'val-agc', (val) => {
        state.agcMaxGain = val;
        audio.sendSetting('agc_max_gain', val);
    }, (val) => `${val}x`);

    ui.setupControl('ctrl-beat-sens', 'val-beat-sens', (val) => {
        state.beatSensitivity = val / 100;
        audio.sendSetting('beat_sensitivity', val / 100);
    }, (val) => `${val}%`);

    ui.setupControl('ctrl-beat-thresh', 'val-beat-thresh', (val) => {
        state.beatThreshold = val / 100;
        audio.sendSetting('beat_threshold', val / 100);
    }, (val) => `${(val / 100).toFixed(2)}x`);

    ui.setupControl('ctrl-blocks', 'val-blocks', (val) => {
        state.blockCount = val;
        ws.send({ type: 'set_block_count', count: val });
    }, (val) => `${val}`);

    // Visual sync controls
    ui.setupControl('ctrl-visual-delay', 'val-visual-delay', (val) => {
        state.visualDelayMs = val;
        ws.send({ type: 'set_visual_delay', delay_ms: val });
    }, (val) => `${val}ms`);

    if (elements.syncMode) {
        elements.syncMode.addEventListener('change', () => {
            const mode = elements.syncMode.value;
            state.visualDelayMode = mode;
            ws.send({ type: 'set_visual_delay_mode', mode: mode });
            if (elements.syncDelayRow) {
                elements.syncDelayRow.style.display = mode === 'manual' ? '' : 'none';
            }
            elements.syncPresetButtons.forEach(b => b.classList.remove('active'));
        });
    }

    // Sync preset quick-buttons
    elements.syncPresetButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            const delayMs = parseInt(btn.dataset.delay);
            ws.send({ type: 'set_visual_delay_mode', mode: 'manual' });
            ws.send({ type: 'set_visual_delay', delay_ms: delayMs });
            if (elements.syncMode) elements.syncMode.value = 'manual';
            if (elements.ctrlVisualDelay) elements.ctrlVisualDelay.value = delayMs;
            const valEl = document.getElementById('val-visual-delay');
            if (valEl) valEl.textContent = `${delayMs}ms`;
            if (elements.syncDelayRow) elements.syncDelayRow.style.display = '';
            elements.syncPresetButtons.forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
        });
    });

    // Sync test button
    if (elements.btnSyncTest) {
        elements.btnSyncTest.addEventListener('click', () => {
            app._syncTestSentAt = performance.now();
            ws.send({ type: 'sync_test' });
            if (elements.syncTestResult) {
                elements.syncTestResult.textContent = 'Testing...';
            }
        });
    }

    // Quick actions
    elements.btnBlackout.addEventListener('click', () => actions.toggleBlackout());
    elements.btnFreeze.addEventListener('click', () => actions.toggleFreeze());
    elements.btnTapTempo.addEventListener('click', () => actions.tapTempo());

    // Effect triggers
    elements.effectButtons.forEach(btn => {
        btn.addEventListener('click', () => actions.triggerEffect(btn.dataset.effect));
    });

    // Scene presets
    if (elements.saveSceneBtn) {
        elements.saveSceneBtn.addEventListener('click', () => scenes.saveScene());
    }
    if (elements.sceneNameInput) {
        elements.sceneNameInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') scenes.saveScene();
        });
    }

    // Keyboard shortcuts
    document.addEventListener('keydown', (e) => ui.handleKeyboard(e));

    // Particle effects global intensity
    if (elements.particleGlobalIntensity) {
        const sendParticleIntensity = debounce((val) => {
            particles.sendParticleConfig({ global_intensity: val / 100 });
        }, 50);

        elements.particleGlobalIntensity.addEventListener('input', () => {
            const val = parseInt(elements.particleGlobalIntensity.value);
            document.getElementById('val-particle-intensity').textContent = `${val}%`;
            sendParticleIntensity(val);
        });
    }

    // Reconnect button
    if (elements.btnReconnect) {
        elements.btnReconnect.addEventListener('click', () => {
            ws.manualReconnect();
        });
    }

    // Refresh stages/zones button
    if (elements.btnRefreshZones) {
        elements.btnRefreshZones.addEventListener('click', () => {
            ws.send({ type: 'get_stages' });
            ws.send({ type: 'get_zones' });
        });
    }

    // Pattern transition duration slider
    if (elements.transitionDurationSlider) {
        const sendTransitionDuration = debounce((value) => {
            ws.send({ type: 'set_transition_duration', duration: value / 1000 });
        }, 50);

        elements.transitionDurationSlider.addEventListener('input', () => {
            const ms = parseInt(elements.transitionDurationSlider.value);
            const seconds = (ms / 1000).toFixed(1);
            if (elements.transitionDurationValue) {
                elements.transitionDurationValue.textContent = `${seconds}s`;
            }
            sendTransitionDuration(ms);
        });
    }

    // Zone settings
    zones.setupZoneEventListeners();

    // Connect codes
    connectCodes.setupListeners();

    // Voice chat
    voice.setupListeners();

    // Collapsible sections
    ui.setupCollapsibleSections();
}
