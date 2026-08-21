import {
    DEFAULT_WORKSPACE,
    WORKSPACES,
    WORKSPACE_STORAGE_KEY,
    isWorkspaceName,
} from '../config/workspaces.js';

const editableTags = new Set(['INPUT', 'TEXTAREA', 'SELECT']);

export function isWorkspaceShortcutEvent(event) {
    if (!event?.altKey || event.ctrlKey || event.metaKey) return false;
    return /^[1-5]$/.test(event.key);
}

export function workspaceFromShortcutEvent(event) {
    if (!isWorkspaceShortcutEvent(event)) return null;
    const target = event.target;
    if (target?.isContentEditable || editableTags.has(target?.tagName)) return null;

    const index = Number.parseInt(event.key, 10) - 1;
    return Number.isInteger(index) ? WORKSPACES[index]?.id ?? null : null;
}

export class WorkspaceManager {
    constructor({ root = document, storage, onChange = () => {} } = {}) {
        this.root = root;
        this.storage = storage;
        this.storageResolved = storage !== undefined;
        this.onChange = onChange;
        this.tablist = null;
        this.buttons = [];
        this.panels = [];
        this.labels = [];
        this.activeWorkspace = DEFAULT_WORKSPACE;
    }

    setup() {
        this.tablist = this.root.querySelector?.('[data-workspace-tablist]') ?? null;
        this.buttons = [...this.root.querySelectorAll('[data-workspace-nav]')];
        this.panels = [...this.root.querySelectorAll('[data-workspace-panel]')];
        this.labels = [...this.root.querySelectorAll('[data-workspace-label]')];
        this.setupTabSemantics();
        this.relocateControls();
        for (const button of this.buttons) {
            button.addEventListener('click', () => {
                this.activate(button.dataset.workspace, { focus: true });
            });
            button.addEventListener('keydown', (event) => {
                this.handleTabKeydown(event, button);
            });
        }

        let saved = null;
        try {
            saved = this.resolveStorage()?.getItem(WORKSPACE_STORAGE_KEY) ?? null;
        } catch (error) {
            console.warn('[Workspace] Preference read failed', error);
        }
        this.activate(isWorkspaceName(saved) ? saved : DEFAULT_WORKSPACE, { persist: false });
    }

    resolveStorage() {
        if (this.storageResolved) return this.storage;
        this.storageResolved = true;
        try {
            this.storage = this.root.defaultView?.localStorage
                ?? globalThis.window?.localStorage
                ?? null;
        } catch (error) {
            this.storage = null;
            console.warn('[Workspace] Preference storage unavailable', error);
        }
        return this.storage;
    }

    setupTabSemantics() {
        this.tablist?.setAttribute('role', 'tablist');
        this.tablist?.setAttribute('aria-orientation', 'vertical');
        for (const button of this.buttons) {
            const name = button.dataset.workspace;
            button.id ||= `workspace-tab-${name}`;
            button.setAttribute('role', 'tab');
            button.setAttribute('aria-controls', `workspace-${name}`);
        }
        for (const panel of this.panels) {
            const name = panel.dataset.workspace;
            panel.id ||= `workspace-${name}`;
            panel.setAttribute('role', 'tabpanel');
            panel.setAttribute('aria-labelledby', `workspace-tab-${name}`);
        }
    }

    handleTabKeydown(event, button) {
        const currentIndex = this.buttons.indexOf(button);
        if (currentIndex < 0) return false;

        let nextIndex;
        switch (event.key) {
            case 'ArrowDown':
            case 'ArrowRight':
                nextIndex = (currentIndex + 1) % this.buttons.length;
                break;
            case 'ArrowUp':
            case 'ArrowLeft':
                nextIndex = (currentIndex - 1 + this.buttons.length) % this.buttons.length;
                break;
            case 'Home':
                nextIndex = 0;
                break;
            case 'End':
                nextIndex = this.buttons.length - 1;
                break;
            default:
                return false;
        }

        event.preventDefault();
        this.activate(this.buttons[nextIndex].dataset.workspace, { focus: true });
        return true;
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
                this.resolveStorage()?.setItem(WORKSPACE_STORAGE_KEY, name);
            } catch (error) {
                console.warn('[Workspace] Preference write failed', error);
            }
        }
        this.onChange(name);
        return true;
    }
}
