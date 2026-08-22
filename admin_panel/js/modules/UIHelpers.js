/**
 * UIHelpers - Toast notifications, service indicators, tab switching,
 * keyboard shortcuts, collapsible sections, and misc UI utilities.
 */

import { ModalDialog } from '../ui/ModalDialog.js';
import { deriveControlState } from '../utils/control-state.js';
import { debounce } from '../utils/debounce.js';

const editableTags = new Set(['INPUT', 'TEXTAREA', 'SELECT']);

export class UIHelpers {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;
        if (typeof document !== 'undefined') {
            this.applyControlState();
        }
    }

    // === Toast Notification System ===

    showToast(message, type = 'info', duration = 4000) {
        const container = this.elements.toastContainer;
        if (!container) return;

        const toast = document.createElement('div');
        toast.className = `toast toast-${type}`;
        toast.textContent = message;

        container.appendChild(toast);

        // Trigger show animation
        requestAnimationFrame(() => toast.classList.add('show'));

        // Auto-dismiss (0 = persistent until manually dismissed or replaced)
        if (duration > 0) {
            setTimeout(() => this._dismissToast(toast), duration);
        }
    }

    _dismissToast(toast) {
        if (!toast || !toast.parentNode) return;
        toast.classList.add('hiding');
        toast.classList.remove('show');
        setTimeout(() => {
            if (toast.parentNode) {
                toast.parentNode.removeChild(toast);
            }
        }, 200);
    }

    // === Connection Status ===

    setConnectionStatus(status, attempt = 0, maxAttempts = 10) {
        const el = this.elements.connectionStatus;
        if (el) el.className = `status ${status}`;

        let statusText;
        if (status === 'connecting' && attempt > 0) {
            statusText = `Reconnecting (${attempt}/${maxAttempts})...`;
        } else {
            const statusTexts = {
                'connecting': 'Connecting...',
                'connected': 'Connected',
                'disconnected': 'Disconnected',
                'error': 'Error',
                'failed': 'Connection Failed'
            };
            statusText = statusTexts[status] || status;
        }

        if (el) el.textContent = statusText;

        // Show/hide reconnect button
        if (this.elements.btnReconnect) {
            const reconnectAvailable = ['disconnected', 'error', 'failed'].includes(status);
            this.elements.btnReconnect.classList.toggle('hidden', !reconnectAvailable);
            this.elements.btnReconnect.disabled = false;
            this.elements.btnReconnect.setAttribute('aria-disabled', 'false');
        }

        // Update service indicators
        this.updateServiceIndicators();
    }

    // === Service Status Indicators ===

    updateServiceIndicators() {
        const pythonEl = this.elements.svcPython;
        const mcEl = this.elements.svcMinecraft;

        if (pythonEl) {
            pythonEl.classList.toggle('connected', this.state.connected);
            pythonEl.classList.toggle('disconnected', !this.state.connected);
        }

        if (mcEl) {
            mcEl.classList.toggle('connected', this.state.minecraftConnected);
            mcEl.classList.toggle('disconnected', !this.state.minecraftConnected);
            const label = mcEl.querySelector('.svc-label');
            if (label) {
                const serverType = this.state.minecraftServerType;
                label.textContent = serverType
                    ? `MC \u00b7 ${serverType.charAt(0).toUpperCase() + serverType.slice(1)}`
                    : 'Minecraft';
            }
        }

        this.applyControlState();
    }

    applyControlState() {
        const presentation = deriveControlState(this.state);
        const root = document.getElementById('app');
        if (root) root.dataset.connectionState = presentation.connectionState;

        this._setControlGroupDisabled(
            '[data-requires-connection]',
            presentation.disableNetworkControls,
        );
        this._setControlGroupDisabled(
            '[data-requires-minecraft]',
            presentation.disableMinecraftControls,
        );
        this._setControlGroupDisabled(
            '[data-requires-voice]',
            !this.state.connected || !this.state.voiceChat?.available,
        );

        for (const container of document.querySelectorAll('[data-last-known-state]')) {
            container.dataset.stale = String(presentation.connectionState === 'stale');
        }

        const staleStatus = document.getElementById('last-known-state-status');
        if (staleStatus) {
            staleStatus.textContent = presentation.connectionState === 'stale'
                ? 'Last known show state'
                : '';
        }

        const emergencyReason = document.getElementById('emergency-control-reason');
        if (emergencyReason) {
            if (!this.state.connected) {
                emergencyReason.textContent = 'Blackout and freeze are unavailable while the VJ server is disconnected.';
            } else if (!this.state.minecraftConnected) {
                emergencyReason.textContent = 'Minecraft output is unavailable; server commands remain available.';
            } else {
                emergencyReason.textContent = '';
            }
        }

        const capabilityStatus = document.getElementById('capability-status');
        if (capabilityStatus) {
            capabilityStatus.textContent = !this.state.connected
                ? 'Server capabilities unavailable until reconnection.'
                : (!this.state.minecraftConnected
                    ? 'Minecraft capabilities unavailable; VJ server controls remain available.'
                    : 'Server and Minecraft capabilities available.');
        }

        this._applyCapabilityStates();
    }

    _setControlGroupDisabled(selector, disabled) {
        for (const element of document.querySelectorAll(selector)) {
            if (disabled) {
                if (!element.disabled || element.dataset.controlStateDisabled === 'true') {
                    element.dataset.controlStateDisabled = 'true';
                }
                element.disabled = true;
                element.setAttribute('aria-disabled', 'true');
                if (!element.getAttribute('aria-describedby')) {
                    element.setAttribute('aria-describedby', 'capability-status');
                    element.dataset.controlStateDescription = 'true';
                }
            } else if (element.dataset.controlStateDisabled === 'true') {
                element.disabled = false;
                element.setAttribute('aria-disabled', 'false');
                delete element.dataset.controlStateDisabled;
                if (element.dataset.controlStateDescription === 'true') {
                    element.removeAttribute('aria-describedby');
                    delete element.dataset.controlStateDescription;
                }
            }
        }
    }

    _setCapabilityState(elementId, reasonId, state, message) {
        const element = document.getElementById(elementId);
        const reason = document.getElementById(reasonId);
        if (element) {
            element.dataset.uiState = state;
            element.setAttribute('aria-busy', String(state === 'loading'));
        }
        if (reason) reason.textContent = message;
    }

    _applyCapabilityStates() {
        const serverAvailable = Boolean(this.state.connected);
        const minecraftAvailable = serverAvailable && Boolean(this.state.minecraftConnected);

        const bitmapState = !minecraftAvailable
            ? 'unavailable'
            : (this.state.bitmap?.dataFetched ? 'available' : 'loading');
        const bitmapReason = !serverAvailable
            ? 'Connect to the VJ server to load bitmap controls.'
            : (!this.state.minecraftConnected
                ? 'Minecraft is not connected, so bitmap output is unavailable.'
                : (bitmapState === 'loading' ? 'Loading bitmap capabilities…' : ''));
        this._setCapabilityState('ledwall-section', 'bitmap-capability-reason', bitmapState, bitmapReason);

        const voiceStatusReceived = Boolean(this.state.voiceChat?.statusReceived);
        const voiceError = this.state.voiceChat?.error;
        const voiceState = !serverAvailable
            ? 'unavailable'
            : (voiceError
                ? 'error'
                : (!voiceStatusReceived
                    ? 'loading'
                    : (this.state.voiceChat?.available ? 'available' : 'unavailable')));
        const voiceReason = !serverAvailable
            ? 'Connect to the VJ server to check voice chat.'
            : (voiceError
                ? `Voice chat status error: ${voiceError}`
                : (!voiceStatusReceived
                    ? 'Checking voice chat capability…'
                    : (this.state.voiceChat?.available ? '' : 'Voice chat is not available on this Minecraft server.')));
        this._setCapabilityState('voice-chat-section', 'voice-capability-reason', voiceState, voiceReason);

        const previewFailed = Boolean(this.app.preview?.previewFailed);
        const previewInitialized = Boolean(this.app.preview?.previewInitialized);
        const previewState = previewFailed ? 'error' : (previewInitialized ? 'available' : 'loading');
        const previewReason = previewFailed
            ? 'Live preview failed to initialize. Show controls remain available.'
            : (previewInitialized ? '' : 'Loading live preview…');
        this._setCapabilityState('preview-strip', 'preview-capability-reason', previewState, previewReason);
    }

    updateMCDependentControls() {
        this.applyControlState();
    }

    // === Keyboard Shortcuts ===

    handleKeyboard(e) {
        // Escape key closes any open modal regardless of focus
        if (e.key === 'Escape') {
            this.elements.generatedCodeDisplay?.classList.add('hidden');
            return;
        }

        // Shortcuts never override browser/system commands or editable controls.
        const target = e.target;
        if (
            e.altKey
            || e.ctrlKey
            || e.metaKey
            || target?.isContentEditable
            || editableTags.has(target?.tagName)
        ) {
            return;
        }

        switch (e.key) {
            case 'b':
            case 'B':
                if (!this.elements.btnBlackout?.disabled) {
                    this.app.actions.toggleBlackout();
                }
                break;

            case 'f':
            case 'F':
                if (!this.elements.btnFreeze?.disabled) {
                    this.app.actions.toggleFreeze();
                }
                break;

            case 't':
            case 'T':
                this.app.actions.tapTempo();
                break;

            // Number keys 1-8 for pattern switching
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8': {
                const patternIndex = parseInt(e.key) - 1;
                if (this.state.patterns[patternIndex]) {
                    this.app.patterns.setPattern(this.state.patterns[patternIndex].id);
                }
                break;
            }
        }
    }

    // === Collapsible Sections ===

    setupCollapsibleSections() {
        document.querySelectorAll('.mixer-section.collapsible > .section-title').forEach(title => {
            title.setAttribute('tabindex', '0');
            title.setAttribute('role', 'button');
            const isCollapsed = title.closest('.mixer-section').classList.contains('collapsed');
            title.setAttribute('aria-expanded', String(!isCollapsed));
            const toggle = () => {
                const section = title.closest('.mixer-section');
                section.classList.toggle('collapsed');
                title.setAttribute('aria-expanded', String(!section.classList.contains('collapsed')));
                this.saveSectionStates();
            };
            title.addEventListener('click', toggle);
            title.addEventListener('keydown', (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    toggle();
                }
            });
        });
        this.restoreSectionStates();
    }

    saveSectionStates() {
        const states = {};
        document.querySelectorAll('.mixer-section.collapsible').forEach(section => {
            const key = section.querySelector('.section-title')?.textContent.trim();
            if (key) states[key] = section.classList.contains('collapsed');
        });
        try { localStorage.setItem('mcav-section-states', JSON.stringify(states)); } catch(e) {}
    }

    restoreSectionStates() {
        try {
            const states = JSON.parse(localStorage.getItem('mcav-section-states'));
            if (!states) return;
            document.querySelectorAll('.mixer-section.collapsible').forEach(section => {
                const key = section.querySelector('.section-title')?.textContent.trim();
                if (key && key in states) {
                    section.classList.toggle('collapsed', states[key]);
                    const title = section.querySelector('.section-title');
                    if (title) title.setAttribute('aria-expanded', String(!states[key]));
                }
            });
        } catch(e) {}
    }

    // === Clipboard Helper ===

    async copyToClipboard(text) {
        if (navigator.clipboard && window.isSecureContext) {
            try {
                await navigator.clipboard.writeText(text);
                return true;
            } catch (_) { /* fall through */ }
        }
        // Fallback: hidden textarea + execCommand
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.select();
        try {
            document.execCommand('copy');
            return true;
        } catch (_) {
            return false;
        } finally {
            document.body.removeChild(ta);
        }
    }

    // === Misc UI Helpers ===

    /** Convert "#RRGGBB" hex color to ARGB integer (full alpha) for Minecraft plugin */
    hexToArgbInt(hex) {
        if (!hex) return 0xFFFFFFFF;
        const r = parseInt(hex.slice(1, 3), 16);
        const g = parseInt(hex.slice(3, 5), 16);
        const b = parseInt(hex.slice(5, 7), 16);
        // ARGB: 0xFF (full alpha) + RGB — use signed 32-bit via bitwise OR
        return ((0xFF << 24) | (r << 16) | (g << 8) | b) | 0;
    }

    /** Helper: populate a <select> from a list of {id, name} items, preserving current value */
    populateSelectFromList(select, items, placeholder) {
        if (!select) return;
        const currentVal = select.value;
        while (select.firstChild) select.removeChild(select.firstChild);

        if (placeholder) {
            const opt = document.createElement('option');
            opt.value = '';
            opt.textContent = placeholder;
            select.appendChild(opt);
        }

        items.forEach(item => {
            const opt = document.createElement('option');
            opt.value = item.id || item;
            opt.textContent = item.name || item;
            select.appendChild(opt);
        });

        if (currentVal) select.value = currentVal;
    }

    /**
     * Format a stage name for display: replace underscores, title case.
     */
    formatStageName(name) {
        return name.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
    }

    /**
     * Format a zone name for display: strip stage prefix, replace underscores, title case.
     */
    formatZoneDisplayName(zoneName, stageName) {
        let display = zoneName;
        if (stageName && display.startsWith(stageName + '_')) {
            display = display.slice(stageName.length + 1);
        }
        return display.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
    }

    // === Slider / Toggle Helpers ===

    setSliderValue(inputId, displayId, value, formatFn) {
        const input = document.getElementById(inputId);
        const display = document.getElementById(displayId);
        if (input) input.value = value;
        if (display) display.textContent = formatFn(value);
    }

    setToggleValue(elementId, value) {
        const toggle = document.getElementById(elementId);
        if (toggle) toggle.checked = value;
    }

    setupControl(inputId, displayId, callback, formatFn) {
        const input = document.getElementById(inputId);
        const display = document.getElementById(displayId);

        if (input && display) {
            const debouncedCallback = debounce(callback, 50);
            input.addEventListener('input', () => {
                const val = parseFloat(input.value);
                display.textContent = formatFn(val);
                debouncedCallback(val);
            });
        }
    }

    setupZoneControl(inputId, displayId, callback, formatFn) {
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

    setupToggle(elementId, stateKey, onChange) {
        const toggle = document.getElementById(elementId);
        if (toggle) {
            toggle.addEventListener('change', () => {
                this.state.zone[stateKey] = toggle.checked;
                onChange();
            });
        }
    }

    updateFaderDisplay(fader, value) {
        const display = fader.querySelector('.fader-value');
        if (display) {
            display.textContent = `${value}%`;
        }
    }

    updateBlockCountDisplay() {
        const slider = document.getElementById('ctrl-blocks');
        const valueDisplay = document.getElementById('val-blocks');
        if (slider) slider.value = this.state.blockCount;
        if (valueDisplay) valueDisplay.textContent = `${this.state.blockCount}`;
    }
}
