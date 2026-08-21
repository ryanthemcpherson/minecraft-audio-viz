import {
    DEFAULT_WORKSPACE,
    WORKSPACES,
    WORKSPACE_STORAGE_KEY,
    isWorkspaceName,
} from '../config/workspaces.js';

const editableTags = new Set(['INPUT', 'TEXTAREA', 'SELECT']);

export function workspaceFromShortcutEvent(event) {
    if (!event?.altKey || event.ctrlKey || event.metaKey) return null;
    const target = event.target;
    if (target?.isContentEditable || editableTags.has(target?.tagName)) return null;

    const index = Number.parseInt(event.key, 10) - 1;
    return Number.isInteger(index) ? WORKSPACES[index]?.id ?? null : null;
}

export class WorkspaceManager {
    constructor({ root = document, storage = window.localStorage, onChange = () => {} } = {}) {
        this.root = root;
        this.storage = storage;
        this.onChange = onChange;
        this.buttons = [];
        this.panels = [];
        this.labels = [];
        this.activeWorkspace = DEFAULT_WORKSPACE;
    }

    setup() {
        this.buttons = [...this.root.querySelectorAll('[data-workspace-nav]')];
        this.panels = [...this.root.querySelectorAll('[data-workspace-panel]')];
        this.labels = [...this.root.querySelectorAll('[data-workspace-label]')];
        this.relocateControls();
        for (const button of this.buttons) {
            button.addEventListener('click', () => {
                this.activate(button.dataset.workspace, { focus: true });
            });
        }

        let saved = null;
        try {
            saved = this.storage?.getItem(WORKSPACE_STORAGE_KEY) ?? null;
        } catch (error) {
            console.warn('[Workspace] Preference read failed', error);
        }
        this.activate(isWorkspaceName(saved) ? saved : DEFAULT_WORKSPACE, { persist: false });
    }

    relocateControls() {
        const panelsByName = new Map(
            this.panels.map((panel) => [panel.dataset.workspace, panel]),
        );
        const controls = this.root.querySelectorAll('[data-workspace-destination]');
        for (const control of controls) {
            const destination = control.dataset.workspaceDestination;
            if (!isWorkspaceName(destination)) continue;
            panelsByName.get(destination)?.append(control);
        }
    }

    activate(name, { focus = false, persist = true } = {}) {
        if (!isWorkspaceName(name)) return false;

        this.activeWorkspace = name;
        this.root.documentElement.dataset.workspace = name;
        const workspace = WORKSPACES.find(({ id }) => id === name);
        for (const label of this.labels) {
            label.textContent = workspace.label;
        }
        for (const button of this.buttons) {
            const active = button.dataset.workspace === name;
            button.setAttribute('aria-selected', String(active));
            button.setAttribute('tabindex', active ? '0' : '-1');
            if (active && focus) button.focus();
        }
        for (const panel of this.panels) {
            panel.hidden = panel.dataset.workspace !== name;
        }
        if (persist) {
            try {
                this.storage?.setItem(WORKSPACE_STORAGE_KEY, name);
            } catch (error) {
                console.warn('[Workspace] Preference write failed', error);
            }
        }
        this.onChange(name);
        return true;
    }
}
