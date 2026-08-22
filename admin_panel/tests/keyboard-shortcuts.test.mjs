import assert from 'node:assert/strict';
import test from 'node:test';
import { handleGlobalKeyboardEvent } from '../js/modules/EventWiring.js';
import { UIHelpers } from '../js/modules/UIHelpers.js';

function keyboardEvent(overrides = {}) {
  return {
    key: '1',
    altKey: true,
    ctrlKey: false,
    metaKey: false,
    shiftKey: false,
    target: { tagName: 'DIV', isContentEditable: false },
    preventDefault() { this.defaultPrevented = true; },
    defaultPrevented: false,
    ...overrides,
  };
}

test('EventWiring consumes workspace shortcuts on every editable target', () => {
  const calls = [];
  const app = {
    workspaces: { activate: (workspace) => calls.push(['workspace', workspace]) },
    ui: { handleKeyboard: () => calls.push(['legacy']) },
  };
  const editableTargets = [
    { tagName: 'INPUT', isContentEditable: false },
    { tagName: 'TEXTAREA', isContentEditable: false },
    { tagName: 'SELECT', isContentEditable: false },
    { tagName: 'DIV', isContentEditable: true },
  ];

  for (const target of editableTargets) {
    assert.equal(handleGlobalKeyboardEvent(keyboardEvent({ target }), app), true);
  }

  assert.deepEqual(calls, []);
});

test('UIHelpers ignores editable targets and modified action or pattern keys', () => {
  const calls = [];
  const ui = new UIHelpers({
    state: { patterns: [{ id: 'bars' }] },
    ws: {},
    elements: {},
    actions: {
      toggleBlackout: () => calls.push('blackout'),
      toggleFreeze: () => calls.push('freeze'),
      tapTempo: () => calls.push('tempo'),
    },
    patterns: { setPattern: (id) => calls.push(id) },
  });
  const ignoredEvents = [
    keyboardEvent({ altKey: false, key: 'b', target: { tagName: 'SELECT' } }),
    keyboardEvent({ altKey: false, key: '1', target: { tagName: 'DIV', isContentEditable: true } }),
    keyboardEvent({ key: '1', target: { tagName: 'DIV', isContentEditable: false } }),
    keyboardEvent({ altKey: false, ctrlKey: true, key: 'b' }),
    keyboardEvent({ altKey: false, metaKey: true, key: 'f' }),
  ];

  for (const event of ignoredEvents) ui.handleKeyboard(event);

  assert.deepEqual(calls, []);
});
