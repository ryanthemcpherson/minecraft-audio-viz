/**
 * AudioViz Admin Control Panel - Main Application
 * Entry point that wires together all modules.
 */

import { WebSocketService } from './services/WebSocketService.js';
import { debounce, throttle, rafThrottle } from './utils/debounce.js';
import { ModalDialog } from './ui/ModalDialog.js';

// Modules
import { UIHelpers } from './modules/UIHelpers.js';
import { MessageRouter } from './modules/MessageRouter.js';
import { AudioManager } from './modules/AudioManager.js';
import { PatternManager } from './modules/PatternManager.js';
import { ZoneManager } from './modules/ZoneManager.js';
import { DJManager } from './modules/DJManager.js';
import { ConnectCodeManager } from './modules/ConnectCodeManager.js';
import { VoiceChatManager } from './modules/VoiceChatManager.js';
import { ParticleEffectsManager } from './modules/ParticleEffectsManager.js';
import { SceneManager } from './modules/SceneManager.js';
import { BannerManager } from './modules/BannerManager.js';
import { ActionsManager } from './modules/ActionsManager.js';
import { cacheElements } from './modules/ElementCache.js';
import { setupEventListeners } from './modules/EventWiring.js';
import { createInitialState } from './modules/InitialState.js';

// Pre-extracted managers
import { PreviewManager } from './managers/PreviewManager.js';
import { BitmapManager } from './managers/BitmapManager.js';

class AdminApp {
    constructor() {
        // WebSocket connection
        const wsHost = window.location.hostname || 'localhost';
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
        this.state = createInitialState();

        // Internal tracking
        this._lastZonePatternsJson = '';
        this._meterUpdatePending = false;
        this._syncTestSentAt = null;
        this._frameCount = 0;
        this._lastFpsUpdate = Date.now();

        // Expose debounce utils for modules
        this._debounceUtils = { debounce, throttle, rafThrottle };

        // DOM elements cache
        this.elements = {};
        cacheElements(this.elements);

        // Initialize modules
        this.ui = new UIHelpers(this);
        this.router = new MessageRouter(this);
        this.audio = new AudioManager(this);
        this.patterns = new PatternManager(this);
        this.zones = new ZoneManager(this);
        this.dj = new DJManager(this);
        this.connectCodes = new ConnectCodeManager(this);
        this.voice = new VoiceChatManager(this);
        this.particles = new ParticleEffectsManager(this);
        this.scenes = new SceneManager(this);
        this.banner = new BannerManager(this);
        this.actions = new ActionsManager(this);

        // Pre-extracted managers (use app reference pattern)
        this.preview = new PreviewManager(this);
        this._bitmapMgr = new BitmapManager(this);

        // Bitmap adapter: bridge between MessageRouter's expected API and BitmapManager's API
        this.bitmap = {
            fetchBitmapData: () => this._bitmapMgr.fetchBitmapData(),
            renderBitmapPatterns: () => this._bitmapMgr.renderPatterns(),
            renderBitmapTransitions: () => this._bitmapMgr.renderTransitions(),
            renderBitmapPalettes: () => this._bitmapMgr.renderPalettes(),
            highlightBitmapPattern: (id) => this._bitmapMgr.highlightPattern(id),
            highlightBitmapPalette: (id) => this._bitmapMgr.highlightPalette(id),
            updateBitmapStatus: (data) => this._bitmapMgr.updateStatus(data),
            updateBitmapZoneSelector: () => this._bitmapMgr.updateZoneSelector(),
        };

        // Setup event listeners and WebSocket
        setupEventListeners(this);
        this._setupWebSocket();
        this.dj.setupQueueDelegation();
        this.dj.setupPendingDelegation();
        this._bitmapMgr.initControls();
        this._bitmapMgr.setupDjLogoListeners();
        this.preview.initPreviewStrip();
        this.ui.updateTabIndicator();
        this._boundUpdateTabIndicator = () => this.ui.updateTabIndicator();
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

    // === Compatibility Shims for PreviewManager/BitmapManager ===

    _showToast(message, type, duration) {
        this.ui.showToast(message, type, duration);
    }

    _requestParityCheck() {
        if (!this.ws || !this.ws.isConnected) {
            this.ui.showToast('Not connected', 'error');
            return;
        }
        if (!this.state.minecraftConnected) {
            this.ui.showToast('Minecraft not connected', 'error');
            return;
        }
        this.ws.send({ type: 'request_parity_check' });
        this.ui.showToast('Running parity check...', 'info');
    }

    _getZoneRenderMode(zoneName) {
        return this.zones.getZoneRenderMode(zoneName);
    }

    _hideCodeModal() {
        // No-op: legacy method for Escape key handler
    }

    // === WebSocket Setup ===

    _setupWebSocket() {
        this.ws.addEventListener('connecting', (e) => {
            const detail = e.detail || {};
            this.ui.setConnectionStatus('connecting', detail.attempt, detail.maxAttempts);
        });

        this.ws.addEventListener('connected', () => {
            this.state.connected = true;
            this.ui.setConnectionStatus('connected');
            this.ui.showToast('Connected to server', 'success');

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

            this.bitmap.fetchBitmapData();

            // Init 3D preview
            if (!this.preview._initialized && !this.preview._failed) {
                this.preview.initPreview().then(() => {
                    if (this.preview._initialized && !this.preview._stripCollapsed) {
                        this.preview.startAnimation();
                    }
                });
            }
        });

        this.ws.addEventListener('disconnected', () => {
            this.state.connected = false;
            this.state.minecraftConnected = false;
            this.state.bitmap.dataFetched = false;
            this.ui.setConnectionStatus('disconnected');
            this.ui.updateServiceIndicators();
            this.connectCodes.resetGenerateButton();
        });

        this.ws.addEventListener('auth_failed', async (e) => {
            const detail = e.detail || {};
            const msg = detail.error || 'Authentication failed';
            this.ui.setConnectionStatus('disconnected');
            const newPassword = await ModalDialog.prompt('VJ Auth Failed', `${msg}\nEnter VJ password:`);
            if (newPassword) {
                localStorage.setItem('mcav_vj_password', newPassword);
                this.ws.vjPassword = newPassword;
                this.ws.manualReconnect();
            }
        });

        this.ws.addEventListener('error', () => {
            this.ui.setConnectionStatus('error');
        });

        this.ws.addEventListener('reconnect_failed', () => {
            this.ui.setConnectionStatus('failed');
            this.ui.showToast('Connection failed. Click Reconnect to retry.', 'error', 0);
        });

        this.ws.addEventListener('message', (e) => {
            this.router.handleMessage(e.detail);
        });
    }
}

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    window.adminApp = new AdminApp();
});
