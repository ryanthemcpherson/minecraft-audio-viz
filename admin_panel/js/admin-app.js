/**
 * AudioViz Admin Control Panel - Main Application
 * Provides live mixing controls and (future) timeline editing
 */

import { WebSocketService } from './services/WebSocketService.js';

class AdminApp {
    constructor() {
        // WebSocket connection
        this.ws = new WebSocketService({
            host: 'localhost',
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
            // Timeline state
            timeline: {
                state: 'stopped',
                position: 0,
                duration: 0,
                showName: null,
                bpm: 128,
                loop: false
            },
            show: null,
            selectedCue: null,
            // DJ management state
            dj: {
                isVJMode: false,
                roster: [],
                activeDjId: null,
                crossfadeTarget: null
            }
        };

        // Tap tempo tracking
        this.tapTimes = [];
        this.tapTimeout = null;

        // DOM elements cache
        this.elements = {};

        // Initialize
        this._cacheElements();
        this._setupEventListeners();
        this._setupWebSocket();

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

        // Meters
        this.elements.meters = [];
        this.elements.meterValues = [];
        for (let i = 0; i < 6; i++) {
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

        // Tabs
        this.elements.tabs = document.querySelectorAll('.tab');
        this.elements.tabPanels = document.querySelectorAll('.tab-panel');

        // DJ Management
        this.elements.djModeBadge = document.getElementById('dj-mode-badge');
        this.elements.djCount = document.getElementById('dj-count');
        this.elements.djRoster = document.getElementById('dj-roster');
        this.elements.djQueue = document.getElementById('dj-queue');
        this.elements.crossfadeFrom = document.getElementById('crossfade-from');
        this.elements.crossfadeTo = document.getElementById('crossfade-to');
        this.elements.crossfadeSlider = document.getElementById('crossfade-slider');
        this.elements.crossfadeDuration = document.getElementById('crossfade-duration');
        this.elements.crossfadeBeatSync = document.getElementById('crossfade-beat-sync');
        this.elements.btnStartCrossfade = document.getElementById('btn-start-crossfade');

        // Transport
        this.elements.btnPlay = document.getElementById('btn-play');
        this.elements.btnStop = document.getElementById('btn-stop');
        this.elements.timelinePosition = document.getElementById('timeline-position');
        this.elements.timelineDuration = document.getElementById('timeline-duration');
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

        // Band faders
        this.elements.faders.forEach(fader => {
            const input = fader.querySelector('.fader-input');
            const band = fader.dataset.band;

            input.addEventListener('input', () => {
                const value = parseInt(input.value);
                this._updateFaderDisplay(fader, value);

                if (band === 'master') {
                    this.state.masterSensitivity = value / 100;
                    // Master affects all bands proportionally
                    this._sendAllBandSensitivities();
                } else {
                    const bandIndex = parseInt(band);
                    this.state.bandSensitivity[bandIndex] = value / 100;
                    this._sendBandSensitivity(bandIndex, value / 100);
                }
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

        // Transport (placeholder for timeline)
        this.elements.btnPlay.addEventListener('click', () => this._togglePlayback());
        this.elements.btnStop.addEventListener('click', () => this._stopPlayback());

        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => this._handleKeyboard(e));

        // Timeline buttons
        const btnNewShow = document.getElementById('btn-new-show');
        const btnLoadDemo = document.getElementById('btn-load-demo');
        const btnSaveShow = document.getElementById('btn-save-show');
        const btnLoadDemoInline = document.getElementById('btn-load-demo-inline');

        if (btnNewShow) btnNewShow.addEventListener('click', () => this._newShow());
        if (btnLoadDemo) btnLoadDemo.addEventListener('click', () => this._loadDemoShow());
        if (btnSaveShow) btnSaveShow.addEventListener('click', () => this._saveShow());
        if (btnLoadDemoInline) btnLoadDemoInline.addEventListener('click', () => this._loadDemoShow());
    }

    _setupControl(inputId, displayId, callback, formatFn) {
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

    _setupWebSocket() {
        this.ws.addEventListener('connecting', () => {
            this._setConnectionStatus('connecting');
        });

        this.ws.addEventListener('connected', () => {
            this.state.connected = true;
            this._setConnectionStatus('connected');
        });

        this.ws.addEventListener('disconnected', () => {
            this.state.connected = false;
            this._setConnectionStatus('disconnected');
        });

        this.ws.addEventListener('error', () => {
            this._setConnectionStatus('error');
        });

        // Handle incoming messages
        this.ws.addEventListener('message', (e) => {
            this._handleMessage(e.detail);
        });
    }

    // === Message Handling ===

    _handleMessage(data) {
        switch (data.type) {
            case 'patterns':
                this._handlePatterns(data);
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

            case 'timeline_status':
                this._handleTimelineStatus(data);
                break;

            case 'show_loaded':
                this._handleShowLoaded(data);
                break;

            case 'show_list':
                this._handleShowList(data);
                break;

            case 'cue_added':
            case 'cue_updated':
            case 'cue_deleted':
                this._handleCueChange(data);
                break;

            // DJ Management messages
            case 'vj_state':
                this._handleVJState(data);
                break;

            case 'dj_roster':
                this._handleDJRoster(data);
                break;
        }
    }

    _handleTimelineStatus(data) {
        this.state.timeline = {
            state: data.state || 'stopped',
            position: data.position || 0,
            duration: data.duration || 0,
            showName: data.show_name || null,
            bpm: data.bpm || 128,
            loop: data.loop || false
        };
        this._updateTimelineUI();
    }

    _handleShowLoaded(data) {
        this.state.show = data.show;
        this.state.timeline.showName = data.show?.name;
        this.state.timeline.duration = data.show?.duration || 0;
        this.state.timeline.bpm = data.show?.bpm || 128;
        this._renderTimeline();
        this._updateTimelineUI();
    }

    _handleShowList(data) {
        // Could display in a modal or dropdown
        console.log('Available shows:', data.shows);
    }

    _handleCueChange(data) {
        // Refresh timeline display when cues change
        if (this.state.show) {
            this._renderTimeline();
        }
    }

    _handleVJState(data) {
        // VJ server sent full state including DJ roster
        this.state.dj.isVJMode = true;
        this.state.dj.roster = data.dj_roster || [];
        this.state.dj.activeDjId = data.active_dj || null;

        // Update patterns if provided
        if (data.patterns) {
            this.state.patterns = data.patterns;
            this._renderPatternGrid();
        }
        if (data.current_pattern) {
            this.state.currentPattern = data.current_pattern;
            this._updateCurrentPattern(data.current_pattern);
            this._highlightActivePattern(data.current_pattern);
        }

        this._updateDJModeIndicator();
        this._renderDJRoster();
        this._renderDJQueue();
        this._updateCrossfadeControls();
    }

    _handleDJRoster(data) {
        // DJ roster update from VJ server
        this.state.dj.isVJMode = true;
        this.state.dj.roster = data.roster || [];
        this.state.dj.activeDjId = data.active_dj || null;

        this._updateDJModeIndicator();
        this._renderDJRoster();
        this._renderDJQueue();
        this._updateCrossfadeControls();
    }

    _updateDJModeIndicator() {
        const badge = this.elements.djModeBadge;
        if (!badge) return;

        if (this.state.dj.isVJMode) {
            badge.textContent = 'VJ Server Connected';
            badge.className = 'dj-mode-badge vj-connected';
        } else {
            badge.textContent = 'Standalone Mode';
            badge.className = 'dj-mode-badge standalone';
        }
    }

    _renderDJRoster() {
        const roster = this.elements.djRoster;
        if (!roster) return;

        const djs = this.state.dj.roster;

        // Clear existing content
        while (roster.firstChild) {
            roster.removeChild(roster.firstChild);
        }

        if (!djs || djs.length === 0) {
            const emptyDiv = document.createElement('div');
            emptyDiv.className = 'dj-roster-empty';

            const p1 = document.createElement('p');
            p1.textContent = 'No DJs connected';
            emptyDiv.appendChild(p1);

            const p2 = document.createElement('p');
            p2.className = 'hint';
            p2.textContent = 'DJs connect using: ';
            const code = document.createElement('code');
            code.textContent = '--dj-relay';
            p2.appendChild(code);
            p2.appendChild(document.createTextNode(' mode'));
            emptyDiv.appendChild(p2);

            roster.appendChild(emptyDiv);

            if (this.elements.djCount) {
                this.elements.djCount.textContent = '0 DJs';
            }
            return;
        }

        if (this.elements.djCount) {
            this.elements.djCount.textContent = `${djs.length} DJ${djs.length !== 1 ? 's' : ''}`;
        }

        djs.forEach(dj => {
            const card = this._createDJCard(dj);
            roster.appendChild(card);
        });
    }

    _createDJCard(dj) {
        const card = document.createElement('div');
        card.className = `dj-card${dj.is_active ? ' active' : ''}`;
        card.dataset.djId = dj.dj_id;

        // Status dot
        const statusDot = document.createElement('div');
        statusDot.className = 'dj-status-dot';
        card.appendChild(statusDot);

        // DJ info container
        const infoDiv = document.createElement('div');
        infoDiv.className = 'dj-info';

        // DJ name row
        const nameDiv = document.createElement('div');
        nameDiv.className = 'dj-name';
        nameDiv.textContent = dj.dj_name;

        if (dj.is_active) {
            const liveBadge = document.createElement('span');
            liveBadge.className = 'dj-live-badge';
            liveBadge.textContent = 'LIVE';
            nameDiv.appendChild(liveBadge);
        }
        infoDiv.appendChild(nameDiv);

        // Stats row
        const statsDiv = document.createElement('div');
        statsDiv.className = 'dj-stats';

        // BPM stat
        const bpmStat = document.createElement('span');
        bpmStat.className = 'dj-stat';
        const bpmLabel = document.createElement('span');
        bpmLabel.className = 'dj-stat-label';
        bpmLabel.textContent = 'BPM:';
        const bpmValue = document.createElement('span');
        bpmValue.className = 'dj-stat-value';
        bpmValue.textContent = Math.round(dj.bpm);
        bpmStat.appendChild(bpmLabel);
        bpmStat.appendChild(bpmValue);
        statsDiv.appendChild(bpmStat);

        // FPS stat
        let fpsClass = 'good';
        if (dj.fps < 50) fpsClass = 'warning';
        if (dj.fps < 30) fpsClass = 'bad';

        const fpsStat = document.createElement('span');
        fpsStat.className = 'dj-stat';
        const fpsLabel = document.createElement('span');
        fpsLabel.className = 'dj-stat-label';
        fpsLabel.textContent = 'FPS:';
        const fpsValue = document.createElement('span');
        fpsValue.className = `dj-stat-value ${fpsClass}`;
        fpsValue.textContent = dj.fps.toFixed(0);
        fpsStat.appendChild(fpsLabel);
        fpsStat.appendChild(fpsValue);
        statsDiv.appendChild(fpsStat);

        // Latency stat
        let latencyClass = 'good';
        if (dj.latency_ms > 150) latencyClass = 'warning';
        if (dj.latency_ms > 300) latencyClass = 'bad';

        const latStat = document.createElement('span');
        latStat.className = 'dj-stat';
        const latLabel = document.createElement('span');
        latLabel.className = 'dj-stat-label';
        latLabel.textContent = 'Latency:';
        const latValue = document.createElement('span');
        latValue.className = `dj-stat-value ${latencyClass}`;
        latValue.textContent = `${dj.latency_ms.toFixed(0)}ms`;
        latStat.appendChild(latLabel);
        latStat.appendChild(latValue);
        statsDiv.appendChild(latStat);

        infoDiv.appendChild(statsDiv);
        card.appendChild(infoDiv);

        // Actions
        const actionsDiv = document.createElement('div');
        actionsDiv.className = 'dj-actions';

        if (!dj.is_active) {
            const activateBtn = document.createElement('button');
            activateBtn.className = 'dj-action-btn btn-activate';
            activateBtn.title = 'Set as Active DJ';
            activateBtn.textContent = '\u25B6'; // Play symbol
            activateBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this._setActiveDJ(dj.dj_id);
            });
            actionsDiv.appendChild(activateBtn);
        }

        const kickBtn = document.createElement('button');
        kickBtn.className = 'dj-action-btn btn-kick';
        kickBtn.title = 'Kick DJ';
        kickBtn.textContent = '\u2715'; // X symbol
        kickBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            this._kickDJ(dj.dj_id);
        });
        actionsDiv.appendChild(kickBtn);

        card.appendChild(actionsDiv);

        return card;
    }

    _renderDJQueue() {
        const queue = this.elements.djQueue;
        if (!queue) return;

        const djs = this.state.dj.roster;

        // Clear existing content
        while (queue.firstChild) {
            queue.removeChild(queue.firstChild);
        }

        if (!djs || djs.length === 0) {
            const emptyDiv = document.createElement('div');
            emptyDiv.className = 'dj-queue-empty';
            const p = document.createElement('p');
            p.textContent = 'Queue empty';
            emptyDiv.appendChild(p);
            queue.appendChild(emptyDiv);
            return;
        }

        // Sort by priority
        const sortedDjs = [...djs].sort((a, b) => a.priority - b.priority);

        sortedDjs.forEach((dj, index) => {
            const item = document.createElement('div');
            item.className = 'dj-queue-item';
            item.dataset.djId = dj.dj_id;

            const position = document.createElement('span');
            position.className = 'dj-queue-position';
            position.textContent = index + 1;
            item.appendChild(position);

            const name = document.createElement('span');
            name.className = 'dj-queue-name';
            name.textContent = dj.dj_name;
            item.appendChild(name);

            queue.appendChild(item);
        });
    }

    _updateCrossfadeControls() {
        const djs = this.state.dj.roster;
        const hasMultipleDJs = djs && djs.length >= 2;

        // Enable/disable crossfade controls
        if (this.elements.crossfadeSlider) {
            this.elements.crossfadeSlider.disabled = !hasMultipleDJs;
        }
        if (this.elements.crossfadeDuration) {
            this.elements.crossfadeDuration.disabled = !hasMultipleDJs;
        }
        if (this.elements.crossfadeBeatSync) {
            this.elements.crossfadeBeatSync.disabled = !hasMultipleDJs;
        }
        if (this.elements.btnStartCrossfade) {
            this.elements.btnStartCrossfade.disabled = !hasMultipleDJs;
        }

        // Update crossfade DJ labels
        if (hasMultipleDJs) {
            const activeDj = djs.find(d => d.is_active);
            const otherDjs = djs.filter(d => !d.is_active);

            if (this.elements.crossfadeFrom && activeDj) {
                this.elements.crossfadeFrom.textContent = activeDj.dj_name;
                this.elements.crossfadeFrom.classList.add('active');
            }

            if (this.elements.crossfadeTo && otherDjs.length > 0) {
                // Default to first non-active DJ
                this.state.dj.crossfadeTarget = otherDjs[0].dj_id;
                this.elements.crossfadeTo.textContent = otherDjs[0].dj_name;
            }
        } else {
            if (this.elements.crossfadeFrom) {
                this.elements.crossfadeFrom.textContent = '--';
                this.elements.crossfadeFrom.classList.remove('active');
            }
            if (this.elements.crossfadeTo) {
                this.elements.crossfadeTo.textContent = '--';
            }
        }
    }

    _setActiveDJ(djId) {
        this.ws.send({
            type: 'set_active_dj',
            dj_id: djId
        });
    }

    _kickDJ(djId) {
        const dj = this.state.dj.roster.find(d => d.dj_id === djId);
        const djName = dj ? dj.dj_name : djId;

        if (confirm(`Kick ${djName} from the session?`)) {
            this.ws.send({
                type: 'kick_dj',
                dj_id: djId
            });
        }
    }

    _startCrossfade() {
        if (!this.state.dj.crossfadeTarget) return;

        const duration = parseInt(this.elements.crossfadeDuration?.value || 4000);
        const beatSync = this.elements.crossfadeBeatSync?.checked || false;

        this.ws.send({
            type: 'start_crossfade',
            from_dj: this.state.dj.activeDjId,
            to_dj: this.state.dj.crossfadeTarget,
            duration_ms: duration,
            beat_sync: beatSync
        });
    }

    _handlePatterns(data) {
        this.state.patterns = data.patterns || [];
        this.state.currentPattern = data.current;
        this._renderPatternGrid();
        this._updateCurrentPattern(data.current);
    }

    _handleAudioState(data) {
        // Update state
        this.state.bands = data.bands || [0, 0, 0, 0, 0, 0];
        this.state.amplitude = data.amplitude || 0;
        this.state.isBeat = data.is_beat || false;
        this.state.beatIntensity = data.beat_intensity || 0;
        this.state.frame = data.frame || 0;

        // Update UI
        this._updateMeters();
        this._updateBeatIndicator();
        this._updateFrameCount();

        // Update BPM if available
        if (data.zone_status?.bpm_estimate) {
            this.elements.bpmEstimate.textContent = Math.round(data.zone_status.bpm_estimate);
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

    _setConnectionStatus(status) {
        const el = this.elements.connectionStatus;
        el.className = `status ${status}`;

        const statusText = {
            'connecting': 'Connecting...',
            'connected': 'Connected',
            'disconnected': 'Disconnected',
            'error': 'Error'
        };

        el.textContent = statusText[status] || status;
    }

    _updateMeters() {
        const bands = this.state.bands;

        for (let i = 0; i < 6; i++) {
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
        for (let i = 0; i < 6; i++) {
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

    _togglePlayback() {
        const btn = this.elements.btnPlay;

        if (this.state.timeline.state === 'playing') {
            this.ws.send({ type: 'timeline_pause' });
            btn.classList.remove('playing');
        } else {
            this.ws.send({ type: 'timeline_play' });
            btn.classList.add('playing');
        }
    }

    _stopPlayback() {
        this.ws.send({ type: 'timeline_stop' });
        this.elements.btnPlay.classList.remove('playing');
    }

    _seekTimeline(position) {
        this.ws.send({ type: 'timeline_seek', position: position });
    }

    _updateTimelineUI() {
        // Update transport display
        this.elements.timelinePosition.textContent = this._formatTime(this.state.timeline.position);
        this.elements.timelineDuration.textContent = this._formatTime(this.state.timeline.duration);

        // Update play button state
        const isPlaying = this.state.timeline.state === 'playing';
        this.elements.btnPlay.classList.toggle('playing', isPlaying);

        // Update playhead position if timeline canvas exists
        this._updatePlayhead();
    }

    _formatTime(ms) {
        const totalSeconds = Math.floor(ms / 1000);
        const hours = Math.floor(totalSeconds / 3600);
        const minutes = Math.floor((totalSeconds % 3600) / 60);
        const seconds = totalSeconds % 60;

        return `${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
    }

    _updatePlayhead() {
        const playhead = document.getElementById('timeline-playhead');
        if (playhead && this.state.timeline.duration > 0) {
            const percent = (this.state.timeline.position / this.state.timeline.duration) * 100;
            playhead.style.left = `${percent}%`;
        }
    }

    _renderTimeline() {
        const canvas = document.getElementById('timeline-canvas');
        if (!canvas || !this.state.show) return;

        // Clear existing content
        while (canvas.firstChild) {
            canvas.removeChild(canvas.firstChild);
        }

        const show = this.state.show;
        const tracks = show.tracks || [];

        // Create track elements
        tracks.forEach((track, trackIndex) => {
            const trackEl = document.createElement('div');
            trackEl.className = 'timeline-track';
            trackEl.dataset.trackType = track.type;
            trackEl.style.setProperty('--track-color', track.color);

            // Track header
            const headerEl = document.createElement('div');
            headerEl.className = 'track-header';
            headerEl.textContent = track.name;
            trackEl.appendChild(headerEl);

            // Track content area
            const contentEl = document.createElement('div');
            contentEl.className = 'track-content';

            // Render cues
            (track.cues || []).forEach(cue => {
                const cueEl = this._createCueElement(cue, show.duration);
                contentEl.appendChild(cueEl);
            });

            trackEl.appendChild(contentEl);
            canvas.appendChild(trackEl);
        });

        // Add playhead
        const playhead = document.createElement('div');
        playhead.id = 'timeline-playhead';
        playhead.className = 'timeline-playhead';
        canvas.appendChild(playhead);

        this._updatePlayhead();
    }

    _createCueElement(cue, showDuration) {
        const cueEl = document.createElement('div');
        cueEl.className = 'cue-block';
        cueEl.dataset.cueId = cue.id;

        // Position and size
        const startPercent = (cue.start_time / showDuration) * 100;
        const widthPercent = (cue.duration / showDuration) * 100;
        cueEl.style.left = `${startPercent}%`;
        cueEl.style.width = `${Math.max(widthPercent, 0.5)}%`;

        // Label
        const label = document.createElement('span');
        label.className = 'cue-label';
        label.textContent = cue.name || cue.type;
        cueEl.appendChild(label);

        // Click to select
        cueEl.addEventListener('click', (e) => {
            e.stopPropagation();
            this._selectCue(cue);
        });

        // Highlight if selected
        if (this.state.selectedCue?.id === cue.id) {
            cueEl.classList.add('selected');
        }

        // Mark as fired during playback
        if (cue.fired) {
            cueEl.classList.add('fired');
        }

        return cueEl;
    }

    _selectCue(cue) {
        this.state.selectedCue = cue;

        // Update selection highlight
        document.querySelectorAll('.cue-block').forEach(el => {
            el.classList.toggle('selected', el.dataset.cueId === cue.id);
        });

        // Update inspector
        this._updateCueInspector(cue);
    }

    _updateCueInspector(cue) {
        // For now, just log - full inspector can be added later
        console.log('Selected cue:', cue);
    }

    // === Show Management ===

    _newShow() {
        this.ws.send({
            type: 'new_show',
            name: 'New Show',
            duration: 180000,
            bpm: 128
        });
    }

    _loadDemoShow() {
        this.ws.send({ type: 'create_demo_show' });
    }

    _saveShow() {
        if (this.state.show) {
            this.ws.send({ type: 'save_show' });
        }
    }

    _handleKeyboard(e) {
        // Ignore if typing in an input
        if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') {
            return;
        }

        switch (e.key) {
            case ' ':
                e.preventDefault();
                this._togglePlayback();
                break;

            case 'Escape':
                this._stopPlayback();
                break;

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
}

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    window.adminApp = new AdminApp();
});
