/**
 * AudioViz Admin Control Panel - Main Application
 * Provides live mixing controls for audio visualization
 */

import { WebSocketService } from './services/WebSocketService.js';
import { createInitialState } from './modules/InitialState.js';
import { cacheElements } from './modules/ElementCache.js';
import { setupEventListeners } from './modules/EventWiring.js';
import { setupConnectionLifecycle } from './modules/ConnectionLifecycle.js';
import { setupAdminLogin } from './modules/AdminLoginController.js';
import { MessageRouter } from './modules/MessageRouter.js';
import { WorkspaceManager } from './modules/WorkspaceManager.js';
import { UIHelpers } from './modules/UIHelpers.js';
import { AudioManager } from './modules/AudioManager.js';
import { PatternManager } from './modules/PatternManager.js';
import { ActionsManager } from './modules/ActionsManager.js';
import { ParticleEffectsManager } from './modules/ParticleEffectsManager.js';
import { SceneManager } from './modules/SceneManager.js';
import { ZoneManager } from './modules/ZoneManager.js';
import { ConnectCodeManager } from './modules/ConnectCodeManager.js';
import { VoiceChatManager } from './modules/VoiceChatManager.js';
import { DJManager } from './modules/DJManager.js';
import { BannerManager } from './modules/BannerManager.js';
import { BitmapManager } from './managers/BitmapManager.js';
import { PreviewManager } from './managers/PreviewManager.js';

class AdminApp {
    constructor(options = {}) {
        this.onAuthenticated = options.onAuthenticated || (() => {});
        this.onAuthRequired = options.onAuthRequired || (() => {});
        this.onAuthFailed = options.onAuthFailed || (() => {});

        // WebSocket connection - use same host as the page was served from
        const wsHost = window.location.hostname || 'localhost';
        const urlParams = new URLSearchParams(window.location.search);
        const wsPort = parseInt(urlParams.get('port'), 10) || 8766;
        this.ws = new WebSocketService({
            host: wsHost,
            port: wsPort,
            pageProtocol: window.location.protocol,
            username: options.username || '',
            password: options.password || '',
        });

        this.state = createInitialState();

        // Zone pattern change tracking (debounce chip re-rendering)
        this._lastZonePatternsJson = '';

        this.elements = {};
        cacheElements(this.elements);

        this.ui = new UIHelpers(this);
        this.audio = new AudioManager(this);
        this.patterns = new PatternManager(this);
        this.actions = new ActionsManager(this);
        this.particles = new ParticleEffectsManager(this);
        this.scenes = new SceneManager(this);
        this.connectCodes = new ConnectCodeManager(this);
        this.dj = new DJManager(this);
        this.voice = new VoiceChatManager(this);
        this.banner = new BannerManager(this);
        this.bitmap = new BitmapManager(this);
        this.preview = new PreviewManager(this);
        this.workspaces = new WorkspaceManager({
            onChange: (workspace) => this.preview?.setPresentationMode?.(workspace),
        });
        this.zones = new ZoneManager(this);
        this.router = new MessageRouter(this);

        this.workspaces.setup();
        setupEventListeners(this);
        setupConnectionLifecycle(this);
        this.dj.setupQueueDelegation();
        this.dj.setupPendingDelegation();
        this.bitmap.initControls();
        this.bitmap.setupDjLogoListeners();
        this.preview.initPreviewStrip();

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

}

// Initialize the protected control surface when DOM is ready.
document.addEventListener('DOMContentLoaded', () => {
    setupAdminLogin({
        createApp: (options) => new AdminApp(options),
        getApp: () => window.adminApp,
        setApp: (app) => { window.adminApp = app; },
    });
});
