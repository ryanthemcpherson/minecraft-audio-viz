/**
 * ParticleEffectsManager - Minecraft particle effects (beat/ambient toggles).
 */

import { debounce } from '../utils/debounce.js';

export class ParticleEffectsManager {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;
    }

    handleParticleEffects(data) {
        this.state.particleEffects = data.effects || [];
        this.state.enabledParticleEffects = new Set(
            data.effects?.filter(e => e.enabled).map(e => e.id) || []
        );
        this.state.particleGlobalIntensity = data.global_intensity || 1.0;

        if (this.elements.particleGlobalIntensity) {
            this.elements.particleGlobalIntensity.value = Math.round(this.state.particleGlobalIntensity * 100);
            document.getElementById('val-particle-intensity').textContent =
                `${Math.round(this.state.particleGlobalIntensity * 100)}%`;
        }

        this._renderParticleEffects();
    }

    handleParticleEffectChanged(data) {
        if (data.enabled) {
            this.state.enabledParticleEffects.add(data.effect);
        } else {
            this.state.enabledParticleEffects.delete(data.effect);
        }

        const toggle = document.querySelector(`.particle-toggle[data-effect="${data.effect}"]`);
        if (toggle) {
            toggle.classList.toggle('active', data.enabled);
        }
    }

    handleParticleConfigChanged(data) {
        if (data.global_intensity !== undefined) {
            this.state.particleGlobalIntensity = data.global_intensity;
        }
    }

    _renderParticleEffects() {
        const beatContainer = this.elements.particleBeatEffects;
        const ambientContainer = this.elements.particleAmbientEffects;

        if (!beatContainer || !ambientContainer) return;

        while (beatContainer.firstChild) {
            beatContainer.removeChild(beatContainer.firstChild);
        }
        while (ambientContainer.firstChild) {
            ambientContainer.removeChild(ambientContainer.firstChild);
        }

        this.state.particleEffects.forEach(effect => {
            const toggle = this._createParticleToggle(effect);
            if (effect.category === 'beat') {
                beatContainer.appendChild(toggle);
            } else {
                ambientContainer.appendChild(toggle);
            }
        });
    }

    _createParticleToggle(effect) {
        const isEnabled = this.state.enabledParticleEffects.has(effect.id);

        const toggle = document.createElement('label');
        toggle.className = `particle-toggle ${effect.category}${isEnabled ? ' active' : ''}`;
        toggle.dataset.effect = effect.id;

        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.checked = isEnabled;

        const indicator = document.createElement('span');
        indicator.className = 'toggle-indicator';

        const name = document.createElement('span');
        name.className = 'toggle-name';
        name.textContent = effect.name;

        toggle.appendChild(checkbox);
        toggle.appendChild(indicator);
        toggle.appendChild(name);

        toggle.addEventListener('click', (e) => {
            e.preventDefault();
            this._toggleParticleEffect(effect.id);
        });

        return toggle;
    }

    _toggleParticleEffect(effectId) {
        const isEnabled = this.state.enabledParticleEffects.has(effectId);
        this.ws.send({
            type: 'set_particle_effect',
            zone: this.state.zone.name || 'main',
            effect: effectId,
            enabled: !isEnabled
        });
    }

    sendParticleConfig(config) {
        this.ws.send({
            type: 'set_particle_config',
            zone: this.state.zone.name || 'main',
            ...config
        });
    }
}
