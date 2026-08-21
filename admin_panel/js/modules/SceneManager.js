/**
 * SceneManager - Scene preset save/load/delete and rendering.
 */

import { ModalDialog } from '../ui/ModalDialog.js';

export class SceneManager {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;
    }

    async saveScene() {
        const name = this.elements.sceneNameInput.value.trim();
        if (!name) {
            await ModalDialog.alert('Save Scene', 'Please enter a scene name');
            return;
        }

        this.ws.send({ type: 'save_scene', name });
        this.elements.sceneNameInput.value = '';
    }

    loadScene(name) {
        this.ws.send({ type: 'load_scene', name });
    }

    async deleteScene(name) {
        if (await ModalDialog.confirm('Delete Scene', `Delete scene "${name}"?`, { destructive: true })) {
            this.ws.send({ type: 'delete_scene', name });
        }
    }

    renderScenes() {
        if (!this.elements.scenesGrid) return;

        const builtInScenes = ['Chill Lounge', 'EDM Stage', 'Rock Arena', 'Ambient'];

        this.elements.scenesGrid.replaceChildren();
        this.state.scenes.forEach(scene => {
            const isBuiltIn = builtInScenes.includes(scene.name);
            const isActive = this.state.currentScene === scene.name;

            const card = document.createElement('div');
            card.className = `scene-card${isActive ? ' active' : ''}${isBuiltIn ? ' built-in' : ''}`;
            card.dataset.scene = scene.name;

            const launchButton = document.createElement('button');
            launchButton.type = 'button';
            launchButton.className = 'scene-card-launch';
            launchButton.dataset.scene = scene.name;
            launchButton.dataset.requiresConnection = '';
            launchButton.setAttribute('aria-label', `Launch ${scene.name}`);

            if (!isBuiltIn) {
                const deleteBtn = document.createElement('button');
                deleteBtn.className = 'scene-card-delete';
                deleteBtn.dataset.scene = scene.name;
                deleteBtn.textContent = '\u00d7';
                deleteBtn.type = 'button';
                deleteBtn.dataset.requiresConnection = '';
                deleteBtn.setAttribute('aria-label', `Delete ${scene.name}`);
                card.appendChild(deleteBtn);
            }

            const nameDiv = document.createElement('div');
            nameDiv.className = 'scene-card-name';
            nameDiv.textContent = scene.name;
            launchButton.appendChild(nameDiv);

            const detailsDiv = document.createElement('div');
            detailsDiv.className = 'scene-card-details';

            const patternDiv = document.createElement('div');
            patternDiv.className = 'scene-card-pattern';
            patternDiv.textContent = scene.pattern;
            detailsDiv.appendChild(patternDiv);

            const infoDiv = document.createElement('div');
            infoDiv.textContent = `${scene.preset} \u00b7 ${scene.entity_count} blocks`;
            detailsDiv.appendChild(infoDiv);

            launchButton.appendChild(detailsDiv);
            card.appendChild(launchButton);
            this.elements.scenesGrid.appendChild(card);
        });

        // Native launch buttons preserve keyboard activation and a single action.
        this.elements.scenesGrid.querySelectorAll('.scene-card-launch').forEach(button => {
            button.addEventListener('click', () => {
                this.loadScene(button.dataset.scene);
            });
        });

        // Add click handlers for delete buttons
        this.elements.scenesGrid.querySelectorAll('.scene-card-delete').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.deleteScene(btn.dataset.scene);
            });
        });

        this.app.ui?.applyControlState?.();
    }
}
