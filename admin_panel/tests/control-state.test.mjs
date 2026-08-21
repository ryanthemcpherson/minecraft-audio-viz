import assert from 'node:assert/strict';
import test from 'node:test';

import { setupConnectionLifecycle } from '../js/modules/ConnectionLifecycle.js';
import { ActionsManager } from '../js/modules/ActionsManager.js';
import { MessageRouter } from '../js/modules/MessageRouter.js';
import { UIHelpers } from '../js/modules/UIHelpers.js';
import { VoiceChatManager } from '../js/modules/VoiceChatManager.js';
import { deriveControlState } from '../js/utils/control-state.js';
import { readPanelFile } from './helpers/panel-source.mjs';

class FakeClassList {
    constructor() {
        this.values = new Set();
    }

    add(...names) {
        names.forEach((name) => this.values.add(name));
    }

    remove(...names) {
        names.forEach((name) => this.values.delete(name));
    }

    toggle(name, force) {
        const enabled = force === undefined ? !this.values.has(name) : Boolean(force);
        if (enabled) this.values.add(name);
        else this.values.delete(name);
        return enabled;
    }

    contains(name) {
        return this.values.has(name);
    }
}

class FakeElement {
    constructor(id = '') {
        this.id = id;
        this.dataset = {};
        this.disabled = false;
        this.textContent = '';
        this.className = '';
        this.classList = new FakeClassList();
        this.attributes = new Map();
        this.childrenBySelector = new Map();
        this.style = {};
    }

    setAttribute(name, value) {
        this.attributes.set(name, String(value));
    }

    getAttribute(name) {
        return this.attributes.get(name) ?? null;
    }

    removeAttribute(name) {
        this.attributes.delete(name);
    }

    querySelector(selector) {
        return this.childrenBySelector.get(selector) ?? null;
    }
}

class FakeSocket {
    constructor() {
        this.listeners = new Map();
        this.sent = [];
    }

    addEventListener(type, listener) {
        const listeners = this.listeners.get(type) ?? [];
        listeners.push(listener);
        this.listeners.set(type, listeners);
    }

    emit(type, detail = {}) {
        for (const listener of this.listeners.get(type) ?? []) {
            listener({ detail });
        }
    }

    send(message) {
        this.sent.push(message);
    }
}

function withControlHarness(run) {
    const originalDocument = globalThis.document;
    const originalRequestAnimationFrame = globalThis.requestAnimationFrame;
    const originalSetTimeout = globalThis.setTimeout;

    const appRoot = new FakeElement('app');
    const connectionStatus = new FakeElement('connection-status');
    const reconnect = new FakeElement('btn-reconnect');
    const networkControl = new FakeElement('btn-blackout');
    networkControl.setAttribute('aria-describedby', 'emergency-control-reason');
    const minecraftControl = new FakeElement('btn-cleanup-zone');
    const lastKnownContainer = new FakeElement('workspace-live');
    const lastKnownStatus = new FakeElement('last-known-state-status');
    const emergencyReason = new FakeElement('emergency-control-reason');
    const capabilityStatus = new FakeElement('capability-status');
    const voiceSection = new FakeElement('voice-chat-section');
    const voiceCapabilityReason = new FakeElement('voice-capability-reason');
    const bitmapSection = new FakeElement('ledwall-section');
    const bitmapCapabilityReason = new FakeElement('bitmap-capability-reason');
    const previewStrip = new FakeElement('preview-strip');
    const previewCapabilityReason = new FakeElement('preview-capability-reason');
    const currentPattern = new FakeElement('current-pattern');
    currentPattern.textContent = 'Spectrum Bars';

    const pythonService = new FakeElement('svc-python');
    pythonService.childrenBySelector.set('.svc-label', new FakeElement());
    const minecraftService = new FakeElement('svc-minecraft');
    minecraftService.childrenBySelector.set('.svc-label', new FakeElement());

    const byId = new Map([
        ['app', appRoot],
        ['last-known-state-status', lastKnownStatus],
        ['emergency-control-reason', emergencyReason],
        ['capability-status', capabilityStatus],
        ['voice-chat-section', voiceSection],
        ['voice-capability-reason', voiceCapabilityReason],
        ['ledwall-section', bitmapSection],
        ['bitmap-capability-reason', bitmapCapabilityReason],
        ['preview-strip', previewStrip],
        ['preview-capability-reason', previewCapabilityReason],
        ['current-pattern', currentPattern],
    ]);

    globalThis.document = {
        getElementById(id) {
            return byId.get(id) ?? null;
        },
        querySelectorAll(selector) {
            if (selector === '[data-requires-connection]') return [networkControl];
            if (selector === '[data-requires-minecraft]') return [minecraftControl];
            if (selector === '[data-last-known-state]') return [lastKnownContainer];
            return [];
        },
    };
    globalThis.requestAnimationFrame = (callback) => callback();
    globalThis.setTimeout = () => 0;

    const socket = new FakeSocket();
    const state = {
        connected: false,
        minecraftConnected: false,
        minecraftServerType: null,
        bitmap: { dataFetched: false },
        zone: { name: 'main' },
    };
    const app = {
        state,
        ws: socket,
        elements: {
            connectionStatus,
            btnReconnect: reconnect,
            svcPython: pythonService,
            svcMinecraft: minecraftService,
            btnBlackout: networkControl,
            btnCleanupZone: minecraftControl,
        },
        onAuthenticated() {},
        onAuthRequired() {},
        onAuthFailed() {},
        bitmap: {
            fetchBitmapData() {
                state.bitmap.dataFetched = true;
            },
        },
        preview: { previewInitialized: true, previewFailed: false },
        connectCodes: {
            resetGenerateButton() {
                networkControl.disabled = false;
            },
        },
        router: { handleMessage() {} },
    };
    app.ui = new UIHelpers(app);

    try {
        return run({
            app,
            appRoot,
            bitmapCapabilityReason,
            bitmapSection,
            capabilityStatus,
            currentPattern,
            emergencyReason,
            lastKnownContainer,
            lastKnownStatus,
            minecraftControl,
            networkControl,
            previewCapabilityReason,
            previewStrip,
            reconnect,
            socket,
            voiceCapabilityReason,
            voiceSection,
        });
    } finally {
        globalThis.document = originalDocument;
        globalThis.requestAnimationFrame = originalRequestAnimationFrame;
        globalThis.setTimeout = originalSetTimeout;
    }
}

test('derives stale state without erasing the last show state', () => {
    assert.deepEqual(
        deriveControlState({ connected: false, minecraftConnected: false }),
        {
            connectionState: 'stale',
            disableNetworkControls: true,
            disableMinecraftControls: true,
        },
    );
});

test('keeps server controls active while disabling Minecraft-only controls', () => {
    assert.deepEqual(
        deriveControlState({ connected: true, minecraftConnected: false }),
        {
            connectionState: 'connected',
            disableNetworkControls: false,
            disableMinecraftControls: true,
        },
    );
    assert.deepEqual(
        deriveControlState({ connected: true, minecraftConnected: true }),
        {
            connectionState: 'connected',
            disableNetworkControls: false,
            disableMinecraftControls: false,
        },
    );
});

test('applies initial, reconnect, Minecraft, and disconnect state through real lifecycle boundaries', () => {
    withControlHarness(({
        app,
        appRoot,
        currentPattern,
        emergencyReason,
        lastKnownContainer,
        lastKnownStatus,
        minecraftControl,
        networkControl,
        reconnect,
        socket,
    }) => {
        assert.equal(appRoot.dataset.connectionState, 'stale');
        assert.equal(lastKnownContainer.dataset.stale, 'true');
        assert.equal(lastKnownStatus.textContent, 'Last known show state');
        assert.equal(networkControl.disabled, true);
        assert.equal(minecraftControl.disabled, true);
        assert.equal(reconnect.disabled, false, 'reconnect must remain available');

        setupConnectionLifecycle(app);
        socket.emit('connecting', { attempt: 2, maxAttempts: 10 });
        assert.equal(appRoot.dataset.connectionState, 'stale');
        assert.equal(networkControl.disabled, true);

        socket.emit('connected');
        assert.equal(appRoot.dataset.connectionState, 'connected');
        assert.equal(lastKnownContainer.dataset.stale, 'false');
        assert.equal(networkControl.disabled, false);
        assert.equal(minecraftControl.disabled, true);
        assert.match(emergencyReason.textContent, /Minecraft/i);

        const router = new MessageRouter(app);
        router._handleMinecraftStatus({ connected: true, server_type: 'paper' });
        assert.equal(networkControl.disabled, false);
        assert.equal(minecraftControl.disabled, false);

        router._handleMinecraftStatus({ connected: false });
        assert.equal(networkControl.disabled, false);
        assert.equal(minecraftControl.disabled, true);
        assert.match(emergencyReason.textContent, /Minecraft/i);

        socket.emit('disconnected');
        assert.equal(appRoot.dataset.connectionState, 'stale');
        assert.equal(networkControl.disabled, true);
        assert.equal(minecraftControl.disabled, true);
        assert.equal(reconnect.classList.contains('hidden'), false);
        assert.match(emergencyReason.textContent, /VJ server/i);
        assert.equal(currentPattern.textContent, 'Spectrum Bars', 'last known show values remain intact');
    });
});

test('control-state application does not disable unmarked local workspace controls', () => {
    withControlHarness(({ app, reconnect }) => {
        const localSearch = new FakeElement('pattern-search');
        const localFavorite = new FakeElement('pattern-favorite');
        const localWorkspaceTab = new FakeElement('workspace-tab-live');

        app.ui.applyControlState();

        assert.equal(localSearch.disabled, false);
        assert.equal(localFavorite.disabled, false);
        assert.equal(localWorkspaceTab.disabled, false);
        assert.equal(reconnect.disabled, false);
    });
});

test('capability loading, unavailable, and error states stay visible with explanations', () => {
    withControlHarness(({
        app,
        bitmapCapabilityReason,
        bitmapSection,
        previewCapabilityReason,
        previewStrip,
        voiceCapabilityReason,
        voiceSection,
    }) => {
        assert.equal(bitmapSection.dataset.uiState, 'unavailable');
        assert.match(bitmapCapabilityReason.textContent, /VJ server/i);

        app.state.connected = true;
        app.state.minecraftConnected = true;
        app.state.voiceChat = { available: false, statusReceived: false };
        app.preview = { previewInitialized: false, previewFailed: false };
        app.ui.applyControlState();
        assert.equal(voiceSection.dataset.uiState, 'loading');
        assert.match(voiceCapabilityReason.textContent, /Checking/i);
        assert.equal(previewStrip.dataset.uiState, 'loading');

        app.state.voiceChat.statusReceived = true;
        app.ui.applyControlState();
        assert.equal(voiceSection.dataset.uiState, 'unavailable');
        assert.match(voiceCapabilityReason.textContent, /not available/i);

        app.preview.previewFailed = true;
        app.ui.applyControlState();
        assert.equal(previewStrip.dataset.uiState, 'error');
        assert.match(previewCapabilityReason.textContent, /failed/i);
    });
});

test('emergency state remains authoritative while a non-queued command is pending', () => {
    const originalDocument = globalThis.document;
    const originalSetTimeout = globalThis.setTimeout;
    const originalClearTimeout = globalThis.clearTimeout;
    const appRoot = new FakeElement('app');
    const status = new FakeElement('emergency-control-status');
    const blackout = new FakeElement('btn-blackout');
    const freeze = new FakeElement('btn-freeze');
    const immediate = [];
    let timeoutCallback;
    globalThis.document = {
        getElementById: (id) => id === 'app' ? appRoot : id === 'emergency-control-status' ? status : null,
    };
    globalThis.setTimeout = (callback) => {
        timeoutCallback = callback;
        return 42;
    };
    globalThis.clearTimeout = () => {};

    const app = {
        state: { blackout: false, freeze: false },
        elements: { btnBlackout: blackout, btnFreeze: freeze },
        ws: {
            sendImmediate(message) {
                immediate.push(message);
                return true;
            },
        },
    };
    const actions = new ActionsManager(app);

    try {
        actions.applyEmergencyState({
            type: 'vj_state',
            blackout: false,
            freeze: false,
            emergency_epoch: 'authority-server',
            emergency_revision: 0,
        });
        assert.equal(actions.toggleBlackout(), true);
        assert.equal(app.state.blackout, false);
        assert.equal(blackout.getAttribute('aria-pressed'), 'false');
        assert.equal(blackout.getAttribute('aria-busy'), 'true');
        assert.match(status.textContent, /Blackout.*pending/i);
        assert.equal(immediate.length, 1);
        assert.equal(immediate[0].type, 'set_blackout');
        assert.equal(immediate[0].enabled, true);
        assert.match(immediate[0].request_id, /^emergency-/);

        actions.applyEmergencyState({
            type: 'emergency_state',
            blackout: true,
            freeze: false,
            request_id: immediate[0].request_id,
            emergency_epoch: 'authority-server',
            emergency_revision: 1,
        });
        assert.equal(app.state.blackout, true);
        assert.equal(blackout.getAttribute('aria-pressed'), 'true');
        assert.equal(blackout.getAttribute('aria-busy'), 'false');
        assert.match(status.textContent, /Blackout on/i);

        assert.equal(actions.toggleFreeze(), true);
        assert.equal(app.state.freeze, false);
        timeoutCallback();
        assert.equal(freeze.getAttribute('aria-busy'), 'false');
        assert.match(status.textContent, /not confirmed/i);
    } finally {
        globalThis.document = originalDocument;
        globalThis.setTimeout = originalSetTimeout;
        globalThis.clearTimeout = originalClearTimeout;
    }
});

test('correlated protocol errors clear emergency pending state without changing pressed state', () => {
    const originalDocument = globalThis.document;
    const originalSetTimeout = globalThis.setTimeout;
    const appRoot = new FakeElement('app');
    const status = new FakeElement('emergency-control-status');
    const blackout = new FakeElement('btn-blackout');
    const sent = [];
    globalThis.document = {
        getElementById: (id) => id === 'app' ? appRoot : id === 'emergency-control-status' ? status : null,
    };
    globalThis.setTimeout = () => 12;
    const app = {
        state: { blackout: false, freeze: false },
        elements: { btnBlackout: blackout, btnFreeze: new FakeElement('btn-freeze') },
        ws: { sendImmediate: (message) => (sent.push(message), true) },
    };
    const actions = new ActionsManager(app);

    try {
        actions.toggleBlackout();
        actions.handleEmergencyError(sent[0].request_id, 'Rate limited — too many commands');

        assert.equal(app.state.blackout, false);
        assert.equal(blackout.getAttribute('aria-pressed'), 'false');
        assert.equal(blackout.getAttribute('aria-busy'), 'false');
        assert.match(status.textContent, /Rate limited/i);
    } finally {
        globalThis.document = originalDocument;
        globalThis.setTimeout = originalSetTimeout;
    }
});

test('reconnect trusts only a vj_state epoch switch and rejects late old authority', () => {
    const originalDocument = globalThis.document;
    const originalSetTimeout = globalThis.setTimeout;
    const originalClearTimeout = globalThis.clearTimeout;
    const appRoot = new FakeElement('app');
    const status = new FakeElement('emergency-control-status');
    const blackout = new FakeElement('btn-blackout');
    const freeze = new FakeElement('btn-freeze');
    const sent = [];
    const timeoutCallbacks = [];
    globalThis.document = {
        getElementById: (id) => id === 'app' ? appRoot : id === 'emergency-control-status' ? status : null,
    };
    globalThis.setTimeout = (callback) => {
        timeoutCallbacks.push(callback);
        return timeoutCallbacks.length;
    };
    globalThis.clearTimeout = () => {};
    const app = {
        state: { blackout: false, freeze: false },
        elements: { btnBlackout: blackout, btnFreeze: freeze },
        ws: { sendImmediate: (message) => (sent.push(message), true) },
    };
    const actions = new ActionsManager(app);

    try {
        actions.applyEmergencyState({
            type: 'vj_state',
            blackout: false,
            freeze: false,
            emergency_epoch: 'server-before-restart',
            emergency_revision: 8,
        });
        actions.toggleBlackout();
        const endedRequestId = sent[0].request_id;
        actions.handleConnectionLost();
        assert.equal(blackout.getAttribute('aria-busy'), 'false');
        assert.match(status.textContent, /connection lost/i);

        assert.equal(actions.applyEmergencyState({
            type: 'emergency_state',
            blackout: true,
            freeze: true,
        }), false);
        assert.equal(actions.applyEmergencyState({
            type: 'emergency_state',
            blackout: true,
            freeze: true,
            emergency_epoch: 'server-before-restart',
            emergency_revision: 7,
            request_id: endedRequestId,
        }), false);
        assert.equal(actions.applyEmergencyState({
            type: 'emergency_state',
            blackout: true,
            freeze: true,
            emergency_epoch: 'server-after-restart',
            emergency_revision: 1,
        }), false);
        assert.equal(app.state.blackout, false);
        assert.equal(app.state.freeze, false);

        assert.equal(actions.applyEmergencyState({
            type: 'vj_state',
            blackout: false,
            freeze: true,
            emergency_epoch: 'server-after-restart',
            emergency_revision: 1,
        }), true);
        actions.toggleBlackout();
        const freshRequestId = sent[1].request_id;
        assert.notEqual(freshRequestId, endedRequestId);
        assert.equal(blackout.getAttribute('aria-busy'), 'true');

        actions.applyEmergencyState({
            type: 'emergency_state',
            blackout: true,
            freeze: false,
            request_id: endedRequestId,
            emergency_epoch: 'server-before-restart',
            emergency_revision: 9,
        });
        assert.equal(app.state.blackout, false);
        assert.equal(app.state.freeze, true);
        assert.equal(blackout.getAttribute('aria-busy'), 'true');
        assert.match(status.textContent, /pending/i);

        actions.applyEmergencyState({
            type: 'vj_state',
            blackout: true,
            freeze: true,
            emergency_epoch: 'server-after-restart',
            emergency_revision: 2,
        });
        assert.equal(app.state.blackout, true);
        assert.equal(app.state.freeze, true);
        assert.equal(blackout.getAttribute('aria-busy'), 'false');
        assert.match(status.textContent, /synchronized/i);

        for (const timeoutCallback of timeoutCallbacks) timeoutCallback();
        assert.doesNotMatch(status.textContent, /not confirmed/i);
        assert.equal(blackout.getAttribute('aria-busy'), 'false');
    } finally {
        globalThis.document = originalDocument;
        globalThis.setTimeout = originalSetTimeout;
        globalThis.clearTimeout = originalClearTimeout;
    }
});

test('legacy emergency state is accepted only until revisioned authority is observed', () => {
    const originalDocument = globalThis.document;
    const appRoot = new FakeElement('app');
    const blackout = new FakeElement('btn-blackout');
    globalThis.document = {
        getElementById: (id) => id === 'app' ? appRoot : null,
    };
    const app = {
        state: { blackout: false, freeze: false },
        elements: { btnBlackout: blackout, btnFreeze: new FakeElement('btn-freeze') },
        ws: { sendImmediate: () => true },
    };
    const actions = new ActionsManager(app);

    try {
        assert.equal(actions.applyEmergencyState({
            type: 'emergency_state',
            blackout: true,
            freeze: false,
        }), true);
        assert.equal(app.state.blackout, true);

        assert.equal(actions.applyEmergencyState({
            type: 'vj_state',
            blackout: false,
            freeze: false,
            emergency_epoch: 'revisioned-server',
            emergency_revision: 4,
        }), true);
        actions.handleConnectionLost();
        assert.equal(actions.applyEmergencyState({
            type: 'emergency_state',
            blackout: true,
            freeze: true,
        }), false);
        assert.equal(app.state.blackout, false);
        assert.equal(app.state.freeze, false);
    } finally {
        globalThis.document = originalDocument;
    }
});

test('equal revision affects only the matching in-flight request', () => {
    const originalDocument = globalThis.document;
    const originalSetTimeout = globalThis.setTimeout;
    const appRoot = new FakeElement('app');
    const blackout = new FakeElement('btn-blackout');
    const sent = [];
    globalThis.document = {
        getElementById: (id) => id === 'app' ? appRoot : null,
    };
    globalThis.setTimeout = () => 23;
    const app = {
        state: { blackout: false, freeze: false },
        elements: { btnBlackout: blackout, btnFreeze: new FakeElement('btn-freeze') },
        ws: { sendImmediate: (message) => (sent.push(message), true) },
    };
    const actions = new ActionsManager(app);

    try {
        actions.applyEmergencyState({
            type: 'vj_state',
            blackout: false,
            freeze: false,
            emergency_epoch: 'equal-revision-server',
            emergency_revision: 5,
        });
        actions.toggleBlackout();

        assert.equal(actions.applyEmergencyState({
            type: 'emergency_state',
            blackout: true,
            freeze: false,
            emergency_epoch: 'equal-revision-server',
            emergency_revision: 5,
        }), false);
        assert.equal(app.state.blackout, false);
        assert.equal(blackout.getAttribute('aria-busy'), 'true');

        assert.equal(actions.applyEmergencyState({
            type: 'emergency_state',
            blackout: false,
            freeze: false,
            emergency_epoch: 'equal-revision-server',
            emergency_revision: 5,
            request_id: sent[0].request_id,
        }), true);
        assert.equal(blackout.getAttribute('aria-busy'), 'false');
    } finally {
        globalThis.document = originalDocument;
        globalThis.setTimeout = originalSetTimeout;
    }
});

test('uncorrelated authoritative broadcasts never clear an in-flight emergency request', () => {
    const originalDocument = globalThis.document;
    const originalSetTimeout = globalThis.setTimeout;
    const appRoot = new FakeElement('app');
    const status = new FakeElement('emergency-control-status');
    const blackout = new FakeElement('btn-blackout');
    globalThis.document = {
        getElementById: (id) => id === 'app' ? appRoot : id === 'emergency-control-status' ? status : null,
    };
    globalThis.setTimeout = () => 7;
    const app = {
        state: { blackout: false, freeze: false },
        elements: { btnBlackout: blackout, btnFreeze: new FakeElement('btn-freeze') },
        ws: { sendImmediate: () => true },
    };
    const actions = new ActionsManager(app);

    try {
        actions.toggleBlackout();
        actions.applyEmergencyState({
            type: 'emergency_state',
            blackout: true,
            freeze: false,
        });

        assert.equal(app.state.blackout, true);
        assert.equal(blackout.getAttribute('aria-pressed'), 'true');
        assert.equal(blackout.getAttribute('aria-busy'), 'true');
        assert.match(status.textContent, /pending/i);
    } finally {
        globalThis.document = originalDocument;
        globalThis.setTimeout = originalSetTimeout;
    }
});

test('disabled emergency controls cannot be fired through global shortcuts', () => {
    withControlHarness(({ app }) => {
        let blackoutCalls = 0;
        let freezeCalls = 0;
        app.actions = {
            toggleBlackout: () => { blackoutCalls += 1; },
            toggleFreeze: () => { freezeCalls += 1; },
            tapTempo() {},
        };
        app.elements.btnBlackout.disabled = true;
        app.elements.btnFreeze = new FakeElement('btn-freeze');
        app.elements.btnFreeze.disabled = true;

        app.ui.handleKeyboard({ key: 'B', target: new FakeElement() });
        app.ui.handleKeyboard({ key: 'F', target: new FakeElement() });

        assert.equal(blackoutCalls, 0);
        assert.equal(freezeCalls, 0);
    });
});

test('static command boundaries disable mutations without capturing local presentation controls', async () => {
    const html = await readPanelFile('index.html');
    const openingTag = (id) => {
        const match = html.match(new RegExp(`<[^>]+\\bid="${id}"[^>]*>`));
        assert.ok(match, `missing #${id}`);
        return match[0];
    };

    for (const id of ['btn-blackout', 'btn-freeze', 'ctrl-attack', 'btn-sync-test']) {
        assert.match(openingTag(id), /\bdata-requires-connection(?:[\s=>])/);
    }
    for (const id of ['btn-bitmap-init', 'btn-reinit-pool', 'btn-cleanup-zone', 'parity-check-btn']) {
        assert.match(openingTag(id), /\bdata-requires-minecraft(?:[\s=>])/);
    }
    for (const id of ['pattern-search', 'workspace-tab-live', 'btn-bitmap-advanced', 'preview-strip-collapse']) {
        assert.doesNotMatch(openingTag(id), /\bdata-requires-(?:connection|minecraft)/);
    }
    assert.match(openingTag('btn-blackout'), /\baria-pressed="false"/);
    assert.match(openingTag('btn-freeze'), /\baria-pressed="false"/);
});

test('authoritative voice status resolves loading state without hiding the capability', () => {
    withControlHarness(({ app, voiceSection }) => {
        app.state.connected = true;
        app.state.minecraftConnected = true;
        app.state.voiceChat = {
            available: false,
            streaming: false,
            enabled: false,
            channelType: 'static',
            distance: 100,
            connectedPlayers: 0,
            statusReceived: false,
        };
        app.elements = {
            ...app.elements,
            voiceDot: new FakeElement(),
            voiceStatusText: new FakeElement(),
            voicePlayersStat: new FakeElement(),
            voiceUnavailableMsg: new FakeElement(),
            voiceControls: new FakeElement(),
            voiceStreamToggle: new FakeElement(),
            voiceChannelType: new FakeElement(),
            voiceDistance: new FakeElement(),
            voiceDistanceRow: new FakeElement(),
        };
        const voice = new VoiceChatManager(app);

        voice.handleVoiceStatus({ available: false, streaming: false, connected_players: 0 });

        assert.equal(app.state.voiceChat.statusReceived, true);
        assert.equal(voiceSection.dataset.uiState, 'unavailable');
    });
});

test('voice capability reset returns the visible section to loading without stale availability', () => {
    withControlHarness(({ app, voiceSection }) => {
        app.state.connected = true;
        app.state.voiceChat = {
            available: true,
            streaming: true,
            enabled: true,
            channelType: 'static',
            distance: 100,
            connectedPlayers: 2,
            statusReceived: true,
        };
        app.elements = {
            ...app.elements,
            voiceDot: new FakeElement(),
            voiceStatusText: new FakeElement(),
            voicePlayersStat: new FakeElement(),
            voiceUnavailableMsg: new FakeElement(),
            voiceControls: new FakeElement(),
            voiceStreamToggle: new FakeElement(),
            voiceChannelType: new FakeElement(),
            voiceDistance: new FakeElement(),
            voiceDistanceRow: new FakeElement(),
        };
        const voice = new VoiceChatManager(app);

        voice.resetCapabilityStatus();

        assert.equal(app.state.voiceChat.statusReceived, false);
        assert.equal(app.state.voiceChat.available, false);
        assert.equal(voiceSection.dataset.uiState, 'loading');
    });
});
