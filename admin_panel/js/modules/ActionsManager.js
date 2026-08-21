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
    }

    toggleBlackout() {
        this.state.blackout = !this.state.blackout;
        this.elements.btnBlackout.classList.toggle('active', this.state.blackout);
        document.getElementById('app').classList.toggle('mode-blackout', this.state.blackout);

        this.ws.send({
            type: 'trigger_effect',
            effect: 'blackout',
            intensity: this.state.blackout ? 1.0 : 0.0
        });
    }

    toggleFreeze() {
        this.state.freeze = !this.state.freeze;
        this.elements.btnFreeze.classList.toggle('active', this.state.freeze);
        document.getElementById('app').classList.toggle('mode-freeze', this.state.freeze);

        this.ws.send({
            type: 'trigger_effect',
            effect: 'freeze',
            intensity: this.state.freeze ? 1.0 : 0.0
        });
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

        this.ws.send({
            type: 'trigger_effect',
            effect: effect,
            intensity: 1.0,
            duration: 2000
        });
    }
}
