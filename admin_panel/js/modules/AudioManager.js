/**
 * AudioManager - Audio state handling, meters, beat indicator, sync dashboard,
 * fader controls, and audio settings.
 */

export class AudioManager {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;
    }

    handleAudioState(data) {
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
            if (newJson !== this.app._lastZonePatternsJson) {
                this.app._lastZonePatternsJson = newJson;
                this.state.zonePatterns = data.zone_patterns;
                this.app.zones.syncBitmapStateFromZonePatterns();
                this.app.zones.renderZoneChips();
                this.app.patterns.updatePatternHighlightForZones();
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
        if (!this.app._meterUpdatePending) {
            this.app._meterUpdatePending = true;
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

                    // Update queue depth display
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
                    if (this.app.preview?.previewInitialized && !this.app.preview.previewFailed) {
                        this.app.preview.updateFromAudioState();
                    }
                } catch (error) {
                    console.error('[UI] Audio state update failed', error);
                    this.app.ui.showToast('Live UI update error (recovered)', 'warning', 2500);
                } finally {
                    this.app._meterUpdatePending = false;
                }
            });
        }
    }

    // === Meters ===

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

    _updateQueueDepthDisplay() {
        const queueDepth = this.ws.messageQueue.length;
        const maxQueueSize = this.ws.maxQueueSize;

        if (this.elements.queueDepthDisplay) {
            if (queueDepth > 0) {
                this.elements.queueDepthDisplay.textContent = `Queue: ${queueDepth}/${maxQueueSize}`;
                this.elements.queueDepthDisplay.classList.remove('hidden');
                this.elements.queueDepthDisplay.classList.toggle('warning', queueDepth > maxQueueSize * 0.5);
            } else {
                this.elements.queueDepthDisplay.classList.add('hidden');
            }
        }
    }

    _updateSyncDashboard() {
        if (this.elements.metricPing && this.state.pingMs !== undefined) {
            const ping = this.state.pingMs;
            this.elements.metricPing.textContent = `Ping: ${Math.round(ping)}ms`;
            this.elements.metricPing.className = 'sync-metric ' + (ping < 50 ? 'good' : ping < 150 ? 'warn' : 'bad');
        }
        if (this.elements.metricPipeline && this.state.pipelineLatencyMs !== undefined) {
            const pl = this.state.pipelineLatencyMs;
            this.elements.metricPipeline.textContent = `Pipeline: ${Math.round(pl)}ms`;
            this.elements.metricPipeline.className = 'sync-metric ' + (pl < 50 ? 'good' : pl < 200 ? 'warn' : 'bad');
        }
        if (this.elements.metricDelay && this.state.effectiveDelayMs !== undefined) {
            this.elements.metricDelay.textContent = `Delay: ${Math.round(this.state.effectiveDelayMs)}ms`;
            this.elements.metricDelay.className = 'sync-metric';
        }
        if (this.elements.metricSync && this.state.syncConfidence !== undefined) {
            const sc = this.state.syncConfidence;
            this.elements.metricSync.textContent = `Sync: ${Math.round(sc)}%`;
            this.elements.metricSync.className = 'sync-metric ' + (sc >= 80 ? 'good' : sc >= 50 ? 'warn' : 'bad');
        }
        if (this.elements.metricJitter && this.state.jitterMs !== undefined) {
            const jt = this.state.jitterMs;
            this.elements.metricJitter.textContent = `Jitter: ${jt.toFixed(1)}ms`;
            this.elements.metricJitter.className = 'sync-metric ' + (jt < 5 ? 'good' : jt < 15 ? 'warn' : 'bad');
        }
    }

    // === Settings Sync ===

    syncControlsFromSettings(settings) {
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
                    this.app.ui.updateFaderDisplay(fader.closest('.fader'), Math.round(sens * 100));
                }
            });
        }
    }

    // === Visual Delay ===

    updateVisualDelayDisplay() {
        const slider = this.elements.ctrlVisualDelay;
        const valueDisplay = document.getElementById('val-visual-delay');
        if (slider) slider.value = this.state.visualDelayMs || 0;
        if (valueDisplay) valueDisplay.textContent = `${Math.round(this.state.visualDelayMs || 0)}ms`;
    }

    updateVisualDelayModeDisplay() {
        const select = this.elements.syncMode;
        if (select) {
            select.value = this.state.visualDelayMode || 'manual';
        }
        if (this.elements.syncDelayRow) {
            this.elements.syncDelayRow.style.display =
                (this.state.visualDelayMode || 'manual') === 'manual' ? '' : 'none';
        }
    }

    // === Band Materials ===

    syncBandMaterials(materials) {
        if (!Array.isArray(materials) || materials.length !== 5) return;
        this.state.bandMaterials = materials;
        for (let i = 0; i < 5; i++) {
            const el = document.getElementById(`band-material-${i}`);
            if (el) el.value = materials[i] || '';
        }
    }

    updateBandMaterialsSourceHint() {
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

    // === Actions ===

    sendBandSensitivity(band, value) {
        this.ws.send({
            type: 'set_band_sensitivity',
            band: band,
            sensitivity: value * this.state.masterSensitivity
        });
    }

    sendAllBandSensitivities() {
        for (let i = 0; i < 5; i++) {
            this.sendBandSensitivity(i, this.state.bandSensitivity[i]);
        }
    }

    sendSetting(setting, value) {
        this.ws.send({
            type: 'set_audio_setting',
            setting: setting,
            value: value
        });
    }
}
