/**
 * VoiceChatManager - Voice chat controls and status display.
 */

import { debounce } from '../utils/debounce.js';

export class VoiceChatManager {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;
    }

    setupListeners() {
        // Stream toggle
        if (this.elements.voiceStreamToggle) {
            this.elements.voiceStreamToggle.addEventListener('change', () => {
                this._toggleVoiceStream();
            });
        }

        // Channel type selector
        if (this.elements.voiceChannelType) {
            this.elements.voiceChannelType.addEventListener('change', () => {
                this._setVoiceChannelType(this.elements.voiceChannelType.value);
            });
        }

        // Distance slider - debounced
        if (this.elements.voiceDistance) {
            const sendVoiceDistance = debounce((val) => {
                this._setVoiceDistance(val);
            }, 50);

            this.elements.voiceDistance.addEventListener('input', () => {
                const val = parseInt(this.elements.voiceDistance.value);
                const display = document.getElementById('val-voice-distance');
                if (display) display.textContent = `${val}`;
                this.state.voiceChat.distance = val;
                sendVoiceDistance(val);
            });
        }

        // Initialize UI as unavailable until we hear from server
        this.updateVoiceChatUI();
    }

    _toggleVoiceStream() {
        this.state.voiceChat.enabled = !this.state.voiceChat.enabled;
        this._sendVoiceConfig();
    }

    _setVoiceChannelType(type) {
        this.state.voiceChat.channelType = type;

        if (this.elements.voiceDistanceRow) {
            this.elements.voiceDistanceRow.classList.toggle('hidden', type !== 'locational');
        }

        this._sendVoiceConfig();
    }

    _setVoiceDistance(distance) {
        this.state.voiceChat.distance = distance;
        this._sendVoiceConfig();
    }

    _sendVoiceConfig() {
        this.ws.send({
            type: 'voice_config',
            enabled: this.state.voiceChat.enabled,
            channel_type: this.state.voiceChat.channelType,
            distance: this.state.voiceChat.distance,
            zone: this.state.zone.name || 'main'
        });
    }

    resetCapabilityStatus() {
        this.state.voiceChat.statusReceived = false;
        this.state.voiceChat.available = false;
        this.state.voiceChat.streaming = false;
        this.updateVoiceChatUI();
        this.app.ui.applyControlState();
    }

    handleVoiceStatus(data) {
        const wasStreaming = this.state.voiceChat.streaming;

        this.state.voiceChat.statusReceived = true;
        this.state.voiceChat.available = data.available || false;
        this.state.voiceChat.streaming = data.streaming || false;
        this.state.voiceChat.connectedPlayers = data.connected_players || 0;

        if (data.channel_type) {
            this.state.voiceChat.channelType = data.channel_type;
        }

        if (data.streaming !== undefined) {
            this.state.voiceChat.enabled = data.streaming;
        }

        this.updateVoiceChatUI();
        this.app.ui.applyControlState();

        if (data.streaming && !wasStreaming) {
            this.app.ui.showToast('Voice chat streaming started', 'success');
        } else if (!data.streaming && wasStreaming) {
            this.app.ui.showToast('Voice chat streaming stopped', 'info');
        }
    }

    updateVoiceChatUI() {
        const vc = this.state.voiceChat;
        const statusPending = !vc.statusReceived;
        const dot = this.elements.voiceDot;
        const statusText = this.elements.voiceStatusText;
        const playersStat = this.elements.voicePlayersStat;
        const unavailableMsg = this.elements.voiceUnavailableMsg;
        const controls = this.elements.voiceControls;
        const streamToggle = this.elements.voiceStreamToggle;
        const channelType = this.elements.voiceChannelType;
        const distanceSlider = this.elements.voiceDistance;
        const distanceRow = this.elements.voiceDistanceRow;

        if (dot) {
            dot.classList.remove('voice-dot-streaming', 'voice-dot-available', 'voice-dot-unavailable');
            if (!statusPending) {
                if (!vc.available) {
                    dot.classList.add('voice-dot-unavailable');
                } else if (vc.streaming) {
                    dot.classList.add('voice-dot-streaming');
                } else {
                    dot.classList.add('voice-dot-available');
                }
            }
        }

        if (statusText) {
            if (statusPending) {
                statusText.textContent = 'Checking…';
            } else if (!vc.available) {
                statusText.textContent = 'Unavailable';
            } else if (vc.streaming) {
                statusText.textContent = 'Streaming';
            } else {
                statusText.textContent = 'Ready';
            }
        }

        if (playersStat) {
            if (vc.available && vc.connectedPlayers > 0) {
                playersStat.textContent = `${vc.connectedPlayers} player${vc.connectedPlayers !== 1 ? 's' : ''}`;
                playersStat.style.display = '';
            } else {
                playersStat.style.display = 'none';
            }
        }

        if (unavailableMsg) {
            unavailableMsg.classList.toggle('hidden', statusPending || vc.available);
        }

        if (controls) {
            controls.classList.toggle('voice-controls-disabled', statusPending || !vc.available);
        }

        if (streamToggle) {
            streamToggle.checked = vc.enabled;
            streamToggle.disabled = statusPending || !vc.available;
        }

        if (channelType) {
            channelType.value = vc.channelType;
            channelType.disabled = statusPending || !vc.available;
        }

        if (distanceSlider) {
            distanceSlider.value = vc.distance;
            distanceSlider.disabled = statusPending || !vc.available;
            const display = document.getElementById('val-voice-distance');
            if (display) display.textContent = `${vc.distance}`;
        }

        if (distanceRow) {
            distanceRow.classList.toggle('hidden', vc.channelType !== 'locational');
        }
    }
}
