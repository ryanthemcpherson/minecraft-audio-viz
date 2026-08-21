/**
 * ActionsManager - Quick actions (blackout, freeze, tap tempo, effects).
 */

export class ActionsManager {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;

        // Tap tempo tracking
        this.tapTimes = [];
        this.tapTimeout = null;
        this._emergencySequence = 0;
        this._pendingEmergency = new Map();
        this._lastEmergencyRevision = null;
        this.emergencyTimeoutMs = 5000;

        this._renderEmergencyState('blackout');
        this._renderEmergencyState('freeze');
    }

    toggleBlackout() {
        return this._requestEmergencyState('blackout', !this.state.blackout);
    }

    toggleFreeze() {
        return this._requestEmergencyState('freeze', !this.state.freeze);
    }

    setBlackoutState(enabled) {
        this.state.blackout = Boolean(enabled);
        this._renderEmergencyState('blackout');
    }

    setFreezeState(enabled) {
        this.state.freeze = Boolean(enabled);
        this._renderEmergencyState('freeze');
    }

    applyEmergencyState(data) {
        const hasRevision = data.emergency_revision !== undefined;
        const pendingName = data.request_id
            ? this._findPendingByRequestId(data.request_id)
            : null;
        if (hasRevision
            && (!Number.isInteger(data.emergency_revision) || data.emergency_revision < 0)) {
            return false;
        }
        if (!hasRevision && this._lastEmergencyRevision !== null) {
            return false;
        }
        if (hasRevision
            && this._lastEmergencyRevision !== null
            && data.emergency_revision < this._lastEmergencyRevision) {
            return false;
        }
        if (hasRevision
            && data.emergency_revision === this._lastEmergencyRevision
            && data.type !== 'vj_state'
            && !pendingName) {
            return false;
        }
        if (hasRevision) {
            this._lastEmergencyRevision = data.emergency_revision;
        }

        const reconciledPending = data.type === 'vj_state'
            ? this._clearAllEmergencyPending()
            : false;
        if (typeof data.blackout === 'boolean') {
            this.setBlackoutState(data.blackout);
        }
        if (typeof data.freeze === 'boolean') {
            this.setFreezeState(data.freeze);
        }

        if (data.request_id) {
            if (pendingName) {
                this._clearEmergencyPending(pendingName);
                const enabled = Boolean(this.state[pendingName]);
                this._setEmergencyStatus(`${this._emergencyLabel(pendingName)} ${enabled ? 'on' : 'off'}`);
            }
        }
        if (reconciledPending) {
            this._setEmergencyStatus('Emergency state synchronized');
        }
        return true;
    }

    handleEmergencyError(requestId, message) {
        const pendingName = this._findPendingByRequestId(requestId);
        if (!pendingName) return false;
        this._clearEmergencyPending(pendingName);
        this._setEmergencyStatus(message || `${this._emergencyLabel(pendingName)} could not be delivered`);
        return true;
    }

    handleConnectionLost() {
        this._lastEmergencyRevision = null;
        if (!this._clearAllEmergencyPending()) return false;
        this._setEmergencyStatus('Emergency request cancelled: connection lost');
        return true;
    }

    _requestEmergencyState(name, enabled) {
        if (this._pendingEmergency.has(name)) return false;

        const requestId = `emergency-${Date.now()}-${++this._emergencySequence}`;
        const delivered = this.ws.sendImmediate({
            type: name === 'blackout' ? 'set_blackout' : 'set_freeze',
            enabled,
            request_id: requestId,
        });
        if (!delivered) {
            this._setEmergencyStatus(`${this._emergencyLabel(name)} could not be delivered`);
            this._renderEmergencyState(name);
            return false;
        }

        const timeoutId = setTimeout(() => {
            const pending = this._pendingEmergency.get(name);
            if (pending?.requestId !== requestId) return;
            this._clearEmergencyPending(name);
            this._setEmergencyStatus(`${this._emergencyLabel(name)} change was not confirmed`);
        }, this.emergencyTimeoutMs);
        this._pendingEmergency.set(name, { requestId, enabled, timeoutId });
        this._renderEmergencyState(name);
        this._setEmergencyStatus(`${this._emergencyLabel(name)} change pending`);
        return true;
    }

    _findPendingByRequestId(requestId) {
        for (const [name, pending] of this._pendingEmergency) {
            if (pending.requestId === requestId) return name;
        }
        return null;
    }

    _clearEmergencyPending(name) {
        const pending = this._pendingEmergency.get(name);
        if (pending) clearTimeout(pending.timeoutId);
        this._pendingEmergency.delete(name);
        this._renderEmergencyState(name);
    }

    _clearAllEmergencyPending() {
        const pendingNames = [...this._pendingEmergency.keys()];
        for (const name of pendingNames) {
            this._clearEmergencyPending(name);
        }
        return pendingNames.length > 0;
    }

    _emergencyLabel(name) {
        return name === 'blackout' ? 'Blackout' : 'Freeze';
    }

    _setEmergencyStatus(message) {
        const status = document.getElementById('emergency-control-status');
        if (status) status.textContent = message;
    }

    _renderEmergencyState(name) {
        const enabled = Boolean(this.state[name]);
        const button = name === 'blackout' ? this.elements.btnBlackout : this.elements.btnFreeze;
        button?.classList.toggle('active', enabled);
        button?.setAttribute('aria-pressed', String(enabled));
        button?.setAttribute('aria-busy', String(this._pendingEmergency.has(name)));
        button?.classList.toggle('pending', this._pendingEmergency.has(name));
        document.getElementById('app')?.classList.toggle(`mode-${name}`, enabled);
    }

    tapTempo() {
        const now = Date.now();

        // Visual tap feedback
        const tapBtn = this.elements.btnTapTempo;
        if (tapBtn) {
            tapBtn.classList.add('tapped');
            setTimeout(() => tapBtn.classList.remove('tapped'), 100);
        }

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

            // BPM display pulse
            this.elements.tapBpm.classList.remove('pulse');
            void this.elements.tapBpm.offsetWidth;
            this.elements.tapBpm.classList.add('pulse');
        }

        // Reset after 2 seconds of no taps
        this.tapTimeout = setTimeout(() => {
            this.tapTimes = [];
        }, 2000);
    }

    triggerEffect(effect) {
        const btn = document.querySelector(`.effect-btn[data-effect="${effect}"]`);
        if (btn) {
            btn.classList.add('firing');
            setTimeout(() => btn.classList.remove('firing'), 200);
        }

        const delivered = this.ws.send({
            type: 'trigger_effect',
            effect: effect,
            intensity: 1.0,
            duration: 2000
        });
        const status = document.getElementById('effect-result-status');
        if (status) {
            const label = effect.replaceAll('_', ' ');
            status.textContent = delivered === false
                ? `${label} could not be delivered`
                : `${label} triggered`;
        }
    }
}
