import assert from 'node:assert/strict';
import test from 'node:test';
import {
  WorkspaceManager,
  workspaceFromShortcutEvent,
} from '../js/modules/WorkspaceManager.js';

function fakeNode(workspace) {
  const attributes = new Map();
  const listeners = new Map();
  return {
    dataset: { workspace },
    hidden: false,
    focused: false,
    classList: { toggle() {} },
    setAttribute(name, value) { attributes.set(name, String(value)); },
    getAttribute(name) { return attributes.get(name); },
    addEventListener(name, listener) { listeners.set(name, listener); },
    click() { listeners.get('click')?.(); },
    focus() { this.focused = true; },
  };
}

test('activates one workspace and persists only valid names', () => {
  const buttons = ['live', 'visuals', 'zones', 'djs', 'system'].map(fakeNode);
  const panels = ['live', 'visuals', 'zones', 'djs', 'system'].map(fakeNode);
  const labels = [{ textContent: '' }];
  const values = new Map([['mcav-active-workspace', 'invalid']]);
  const root = {
    documentElement: { dataset: {} },
    querySelectorAll(selector) {
      if (selector === '[data-workspace-nav]') return buttons;
      if (selector === '[data-workspace-panel]') return panels;
      if (selector === '[data-workspace-label]') return labels;
      return [];
    },
  };
  const storage = {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
  };

  const manager = new WorkspaceManager({ root, storage });
  manager.setup();
  assert.equal(manager.activeWorkspace, 'live');
  assert.equal(manager.activate('zones', { focus: true }), true);
  assert.equal(root.documentElement.dataset.workspace, 'zones');
  assert.equal(labels[0].textContent, 'Zones');
  assert.equal(panels.find((panel) => panel.dataset.workspace === 'zones').hidden, false);
  assert.equal(values.get('mcav-active-workspace'), 'zones');
  assert.equal(manager.activate('legacy'), false);
});

test('workspace navigation survives unavailable local storage', () => {
  const buttons = ['live', 'visuals', 'zones', 'djs', 'system'].map(fakeNode);
  const panels = ['live', 'visuals', 'zones', 'djs', 'system'].map(fakeNode);
  const root = {
    documentElement: { dataset: {} },
    querySelectorAll: (selector) => selector === '[data-workspace-nav]' ? buttons : panels,
  };
  const storage = {
    getItem() { throw new Error('blocked'); },
    setItem() { throw new Error('blocked'); },
  };
  const manager = new WorkspaceManager({ root, storage });
  assert.doesNotThrow(() => manager.setup());
  assert.doesNotThrow(() => manager.activate('visuals'));
  assert.equal(manager.activeWorkspace, 'visuals');
});

test('maps Alt+1 through Alt+5 while ignoring editable targets', () => {
  const target = { tagName: 'DIV', isContentEditable: false };
  assert.equal(workspaceFromShortcutEvent({ altKey: true, key: '1', target }), 'live');
  assert.equal(workspaceFromShortcutEvent({ altKey: true, key: '5', target }), 'system');
  assert.equal(workspaceFromShortcutEvent({ altKey: false, key: '3', target }), null);
  assert.equal(workspaceFromShortcutEvent({ altKey: true, key: '3', target: { tagName: 'INPUT' } }), null);
  assert.equal(workspaceFromShortcutEvent({ altKey: true, key: '3', target: { tagName: 'DIV', isContentEditable: true } }), null);
});

test('moves each marked control surface into its semantic workspace', () => {
  const buttons = ['live', 'visuals', 'zones', 'djs', 'system'].map(fakeNode);
  const panels = ['live', 'visuals', 'zones', 'djs', 'system'].map((workspace) => ({
    ...fakeNode(workspace),
    children: [],
    append(node) { this.children.push(node); },
  }));
  const controls = [
    { dataset: { workspaceDestination: 'live' } },
    { dataset: { workspaceDestination: 'visuals' } },
    { dataset: { workspaceDestination: 'system' } },
  ];
  const root = {
    documentElement: { dataset: {} },
    querySelectorAll(selector) {
      if (selector === '[data-workspace-nav]') return buttons;
      if (selector === '[data-workspace-panel]') return panels;
      if (selector === '[data-workspace-destination]') return controls;
      return [];
    },
  };

  const manager = new WorkspaceManager({ root, storage: null });
  manager.setup();

  assert.deepEqual(panels.find(({ dataset }) => dataset.workspace === 'live').children, [controls[0]]);
  assert.deepEqual(panels.find(({ dataset }) => dataset.workspace === 'visuals').children, [controls[1]]);
  assert.deepEqual(panels.find(({ dataset }) => dataset.workspace === 'system').children, [controls[2]]);
});
