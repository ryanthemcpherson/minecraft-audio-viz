/**
 * UIHelpers - Toast notifications, service indicators, tab switching,
 * keyboard shortcuts, collapsible sections, and misc UI utilities.
 */

import { ModalDialog } from '../ui/ModalDialog.js';
import { debounce } from '../utils/debounce.js';

export class UIHelpers {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;
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
        el.className = `status ${status}`;

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

        el.textContent = statusText;

        // Show/hide reconnect button
        if (this.elements.btnReconnect) {
            this.elements.btnReconnect.classList.toggle('hidden', status !== 'failed');
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
    }

    updateMCDependentControls() {
        const mcDependent = [this.elements.btnReinitPool, this.elements.btnCleanupZone];
        mcDependent.forEach(btn => {
            if (btn) {
                btn.disabled = !this.state.minecraftConnected;
                btn.title = this.state.minecraftConnected ? '' : 'Minecraft not connected';
            }
        });
    }

    // === Tab Switching ===

    switchTab(tabName) {
        // Update tab buttons
        this.elements.tabs.forEach(tab => {
            const isActive = tab.dataset.tab === tabName;
            tab.classList.toggle('active', isActive);
            tab.setAttribute('aria-selected', String(isActive));
        });

        // Slide tab indicator
        this.updateTabIndicator();

        // Update panels
        this.elements.tabPanels.forEach(panel => {
            panel.classList.toggle('active', panel.id === `${tabName}-panel`);
        });
    }

    updateTabIndicator() {
        // cleanup-task-7: compatibility wrapper for callers retained during extraction.
        return this.app.workspaces?.activeWorkspace ?? null;
    }

    // === Keyboard Shortcuts ===

    handleKeyboard(e) {
        // Escape key closes any open modal regardless of focus
        if (e.key === 'Escape') {
            this.elements.generatedCodeDisplay?.classList.add('hidden');
            return;
        }

        // Ignore if typing in an input
        if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') {
            return;
        }

        switch (e.key) {
            case 'b':
            case 'B':
                this.app.actions.toggleBlackout();
                break;

            case 'f':
            case 'F':
                this.app.actions.toggleFreeze();
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
