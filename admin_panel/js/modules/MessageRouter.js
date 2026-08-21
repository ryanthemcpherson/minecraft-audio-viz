/**
 * MessageRouter - Routes incoming WebSocket messages to appropriate managers.
 */

export class MessageRouter {
    constructor(app) {
        this.app = app;
    }

    handleMessage(data) {
        const { app } = this;

        if (!data || typeof data.type !== 'string') {
            console.warn('[Protocol] Ignored malformed message envelope', data);
            app.ui.showToast('Ignored malformed server message', 'warning', 2500);
            return;
        }

        switch (data.type) {
            case 'vj_state':
                app.patterns.handlePatterns(data);
                app.dj.handleDJRoster(data);
                if (data.blackout !== undefined) {
                    app.actions.setBlackoutState(data.blackout);
                }
                if (data.freeze !== undefined) {
                    app.actions.setFreezeState(data.freeze);
                }
                if (data.current_pattern) {
                    app.state.currentPattern = data.current_pattern;
                    app.patterns.updatePatternDisplay();
                }
                if (data.entity_count !== undefined) {
                    app.state.blockCount = data.entity_count;
                    app.ui.updateBlockCountDisplay();
                }
                if (data.zone !== undefined) {
                    app.state.currentZone = data.zone;
                }
                if (data.zone_patterns) {
                    app.state.zonePatterns = data.zone_patterns;
                    app.zones.syncBitmapStateFromZonePatterns();
                    app.zones.renderZoneChips();
                    app.patterns.updatePatternHighlightForZones();
                    app.bitmap.updateZoneSelector();
                }
                if (data.stage !== undefined && data.stage) {
                    app.state.selectedStage = data.stage;
                    if (app.elements.stageSelect) {
                        app.elements.stageSelect.value = data.stage;
                    }
                }
                if (data.minecraft_connected !== undefined) {
                    app.state.minecraftConnected = data.minecraft_connected;
                    if (data.minecraft_server_type) {
                        app.state.minecraftServerType = data.minecraft_server_type;
                    }
                    app.ui.updateServiceIndicators();
                    app.ui.updateMCDependentControls();
                    if (data.minecraft_connected && !app.state.bitmap.dataFetched) {
                        app.bitmap.fetchBitmapData();
                    }
                }
                if (data.pending_djs) {
                    app.state.pendingDJs = data.pending_djs;
                    app.dj.renderPendingDJs();
                }
                if (data.banner_profiles) {
                    app.state.bannerProfiles = data.banner_profiles;
                }
                if (Object.prototype.hasOwnProperty.call(data, 'bitmap_zones')) {
                    const snapshot = data.bitmap_zones && typeof data.bitmap_zones === 'object'
                        ? data.bitmap_zones
                        : {};
                    const initializedZones = new Set();
                    const bitmapZones = {};

                    for (const [zoneName, info] of Object.entries(snapshot)) {
                        if (!info || typeof info !== 'object' || !info.initialized) continue;
                        const patternEntry = app.state.zonePatterns?.[zoneName];
                        const pattern = patternEntry && typeof patternEntry === 'object'
                            ? patternEntry.pattern
                            : patternEntry;
                        initializedZones.add(zoneName);
                        bitmapZones[zoneName] = {
                            initialized: true,
                            width: info.width || 16,
                            height: info.height || 12,
                            pattern: pattern || app.state.bitmap.zones?.[zoneName]?.pattern
                                || app.state.bitmap.activePattern || 'bmp_plasma',
                        };
                        app.state.bitmap.width = info.width || 16;
                        app.state.bitmap.height = info.height || 12;
                        app.bitmap.updateStatus({ ...info, zone: zoneName, pattern });
                    }

                    app.state.bitmap.initializedZones = initializedZones;
                    app.state.bitmap.zones = bitmapZones;
                    app.state.bitmap.initialized = initializedZones.size > 0;
                    app.preview?.syncBitmapZones();
                }
                if (data.stages) {
                    app.zones.handleStagesList({ stages: data.stages });
                }
                if (data.bloom_enabled !== undefined && app.elements.bitmapBloom) {
                    app.elements.bitmapBloom.checked = data.bloom_enabled;
                }
                if (data.bloom_strength !== undefined && app.elements.bitmapBloomStrength) {
                    app.elements.bitmapBloomStrength.value = Math.round(data.bloom_strength * 100);
                    const display = document.getElementById('val-bitmap-bloom-strength');
                    if (display) display.textContent = `${Math.round(data.bloom_strength * 100)}%`;
                }
                if (data.ambient_lights_enabled !== undefined && app.elements.bitmapAmbientLights) {
                    app.elements.bitmapAmbientLights.checked = data.ambient_lights_enabled;
                }
                if (data.band_materials) {
                    app.audio.syncBandMaterials(data.band_materials);
                }
                if (data.band_materials_source) {
                    app.state.bandMaterialsSource = data.band_materials_source;
                }
                app.audio.updateBandMaterialsSourceHint();
                if (data.visual_delay_ms !== undefined) {
                    app.state.visualDelayMs = data.visual_delay_ms;
                    app.audio.updateVisualDelayDisplay();
                }
                if (data.visual_delay_mode !== undefined) {
                    app.state.visualDelayMode = data.visual_delay_mode;
                    app.audio.updateVisualDelayModeDisplay();
                }
                break;

            case 'config_update':
                if (data.entity_count !== undefined) {
                    app.state.blockCount = data.entity_count;
                    app.ui.updateBlockCountDisplay();
                }
                if (data.zone !== undefined) {
                    app.state.currentZone = data.zone;
                }
                if (data.current_pattern) {
                    app.state.currentPattern = data.current_pattern;
                    app.patterns.updatePatternDisplay();
                }
                break;

            case 'patterns':
                app.patterns.handlePatterns(data);
                break;

            case 'dj_roster':
                app.dj.handleDJRoster(data);
                break;

            case 'state':
            case 'audio':
                app.audio.handleAudioState(data);
                break;

            case 'pattern_changed':
                app.patterns.handlePatternChanged(data);
                break;

            case 'preset_changed':
                app.patterns.handlePresetChanged(data);
                break;

            case 'transition_duration_sync':
                if (data.duration !== undefined && app.elements.transitionDurationSlider) {
                    const ms = Math.round(data.duration * 1000);
                    app.elements.transitionDurationSlider.value = ms;
                    if (app.elements.transitionDurationValue) {
                        app.elements.transitionDurationValue.textContent = `${data.duration.toFixed(1)}s`;
                    }
                }
                break;

            case 'band_materials_sync':
                if (data.materials) {
                    app.audio.syncBandMaterials(data.materials);
                }
                if (data.source) {
                    app.state.bandMaterialsSource = data.source;
                }
                app.audio.updateBandMaterialsSourceHint();
                break;

            case 'visual_delay_sync':
                if (data.delay_ms !== undefined) {
                    app.state.visualDelayMs = data.delay_ms;
                    app.audio.updateVisualDelayDisplay();
                }
                break;

            case 'visual_delay_mode_sync':
                if (data.mode !== undefined) {
                    app.state.visualDelayMode = data.mode;
                    app.audio.updateVisualDelayModeDisplay();
                }
                break;

            case 'state_snapshot':
                app.patterns.handleStateSnapshot(data);
                break;

            case 'particle_effects':
                app.particles.handleParticleEffects(data);
                break;

            case 'particle_effect_changed':
                app.particles.handleParticleEffectChanged(data);
                break;

            case 'particle_config_changed':
                app.particles.handleParticleConfigChanged(data);
                break;

            case 'zone':
            case 'zone_status':
                app.zones.handleZoneStatus(data);
                break;

            case 'zones':
                app.zones.handleZonesList(data);
                break;

            case 'stages':
                app.zones.handleStagesList(data);
                break;

            case 'stage_blocks':
                app.preview?.handleStageBlocks(data);
                break;

            case 'connect_code_generated':
                app.connectCodes.showGeneratedCode(data.code, data.ttl_minutes || 30);
                break;

            case 'connect_codes':
                app.state.connectCodes = data.codes || [];
                app.connectCodes.renderConnectCodes();
                break;

            case 'minecraft_status':
                this._handleMinecraftStatus(data);
                break;

            case 'dj_pending':
                app.dj.handleDJPending(data);
                break;

            case 'pending_djs':
                app.state.pendingDJs = data.pending || [];
                app.dj.renderPendingDJs();
                break;

            case 'dj_approved':
            case 'dj_denied':
                app.ws.send({ type: 'get_pending_djs' });
                break;

            case 'banner_profile':
                app.banner.handleBannerProfile(data);
                break;

            case 'banner_profile_saved':
                app.ui.showToast('Banner profile saved', 'success');
                break;

            case 'banner_logo_processed':
                app.ui.showToast(`Logo processed: ${data.grid_width}x${data.grid_height} pixels`, 'success');
                break;

            case 'all_banner_profiles':
                app.state.bannerProfiles = data.profiles || {};
                break;

            case 'banner_config_received':
                app.ui.showToast('Banner applied to Minecraft', 'success');
                break;

            case 'voice_status':
                app.voice.handleVoiceStatus(data);
                break;

            case 'bitmap_patterns':
                app.state.bitmap.patterns = data.patterns || [];
                app.bitmap.renderPatterns();
                break;

            case 'bitmap_transitions':
                app.state.bitmap.transitions = data.transitions || [];
                app.bitmap.renderTransitions();
                break;

            case 'bitmap_palettes':
                app.state.bitmap.palettes = data.palettes || [];
                app.bitmap.renderPalettes();
                break;

            case 'bitmap_initialized': {
                app.state.bitmap.initialized = true;
                app.state.bitmap.width = data.width || 16;
                app.state.bitmap.height = data.height || 12;
                const initZone = data.zone || app.state.bitmap.zone;
                app.state.bitmap.initializedZones.add(initZone);
                const pattern = data.pattern || app.state.bitmap.zones?.[initZone]?.pattern
                    || app.state.bitmap.activePattern || 'bmp_plasma';
                app.state.bitmap.zones[initZone] = {
                    initialized: true,
                    width: data.width || 16,
                    height: data.height || 12,
                    pattern,
                };
                app.bitmap.updateStatus(data);
                app.ui.showToast(`Bitmap initialized: ${data.width || '?'}x${data.height || '?'}`, 'success');
                app.preview?.syncBitmapZones();
                break;
            }

            case 'bitmap_pattern_set':
            case 'bitmap_transition_started':
                app.state.bitmap.activePattern = data.pattern;
                app.bitmap.highlightPattern(data.pattern);
                if (data.zone) {
                    const previous = app.state.bitmap.zones?.[data.zone] || {};
                    app.state.bitmap.zones[data.zone] = { ...previous, pattern: data.pattern };
                    app.preview?.bitmapPreview?.setPattern(data.zone, data.pattern);
                }
                break;

            case 'bitmap_frame':
                app.preview?.handleBitmapFrame(data);
                break;

            case 'bitmap_palette_set':
                app.state.bitmap.activePalette = data.palette;
                app.bitmap.highlightPalette(data.palette);
                break;

            case 'bitmap_status':
                app.bitmap.updateStatus(data);
                break;

            case 'bloom_state':
                if (app.elements.bitmapBloom) {
                    app.elements.bitmapBloom.checked = data.enabled;
                }
                if (data.strength !== undefined && app.elements.bitmapBloomStrength) {
                    app.elements.bitmapBloomStrength.value = Math.round(data.strength * 100);
                    const display = document.getElementById('val-bitmap-bloom-strength');
                    if (display) display.textContent = `${Math.round(data.strength * 100)}%`;
                }
                break;

            case 'ambient_lights_state':
                if (app.elements.bitmapAmbientLights) {
                    app.elements.bitmapAmbientLights.checked = data.enabled;
                }
                break;

            case 'error':
                app.ui.showToast(data.message || 'An error occurred', 'error');
                break;

            case 'zone_cleaned':
                app.ui.showToast(`Zone "${data.zone || 'unknown'}" cleaned up`, 'success');
                break;

            case 'pool_initialized':
                app.ui.showToast(`Pool initialized: ${data.count || '?'} entities`, 'success');
                break;

            case 'scenes_list':
                app.state.scenes = data.scenes || [];
                app.scenes.renderScenes();
                break;

            case 'scene_saved':
                app.ui.showToast(`Scene "${data.name}" saved`, 'success');
                break;

            case 'scene_loaded':
                app.ui.showToast(`Scene "${data.name}" loaded`, 'success');
                app.state.currentScene = data.name;
                app.scenes.renderScenes();
                break;

            case 'scene_deleted':
                app.ui.showToast(`Scene "${data.name}" deleted`, 'success');
                break;

            case 'sync_test_flash': {
                const flashAt = performance.now();
                document.body.style.transition = 'background 0.05s';
                document.body.style.background = '#ffffff';
                setTimeout(() => {
                    document.body.style.background = '';
                    document.body.style.transition = '';
                }, 100);
                if (app._syncTestSentAt && app.elements.syncTestResult) {
                    const rtt = Math.round(flashAt - app._syncTestSentAt);
                    app.elements.syncTestResult.textContent = `Round-trip: ${rtt}ms`;
                    app._syncTestSentAt = null;
                }
                break;
            }

            case 'link_status':
                this._handleLinkStatus(data);
                break;

            case 'parity_check_result':
                app.preview.handleParityCheckResult(data);
                break;
        }
    }

    _handleMinecraftStatus(data) {
        const app = this.app;
        const wasConnected = app.state.minecraftConnected;
        app.state.minecraftConnected = data.connected;
        if (data.server_type) {
            app.state.minecraftServerType = data.server_type;
        }
        if (!data.connected) {
            app.state.minecraftServerType = null;
        }
        app.ui.updateServiceIndicators();
        app.ui.updateMCDependentControls();

        if (data.connected && !wasConnected) {
            const typeLabel = data.server_type ? ` (${data.server_type})` : '';
            app.ui.showToast(`Minecraft connected${typeLabel}`, 'success');
            if (!app.state.bitmap.dataFetched) {
                app.bitmap.fetchBitmapData();
            }
        } else if (!data.connected && wasConnected) {
            app.ui.showToast('Minecraft disconnected', 'warning');
            app.state.bitmap.dataFetched = false;
        }
    }

    _handleLinkStatus(data) {
        const app = this.app;
        app.state.linkEnabled = data.enabled || false;
        app.state.linkPeers = data.peers || 0;
        app.state.linkTempo = data.tempo || 0;

        if (data.peers > 0) {
            app.ui.showToast(`Link: ${data.peers} peer(s) @ ${data.tempo} BPM`, 'info');
        }
    }
}
