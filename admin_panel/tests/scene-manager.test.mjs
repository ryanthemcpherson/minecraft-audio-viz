import assert from 'node:assert/strict';
import test from 'node:test';

import { SceneManager } from '../js/modules/SceneManager.js';

function fakeElement(tagName = 'div') {
    const listeners = new Map();
    const classes = new Set();
    return {
        tagName: tagName.toUpperCase(),
        children: [],
        dataset: {},
        attributes: new Map(),
        className: '',
        textContent: '',
        classList: {
            add(...names) { names.forEach((name) => classes.add(name)); },
            contains(name) { return classes.has(name) || this.owner?.className?.split(' ').includes(name); },
        },
        appendChild(child) { this.children.push(child); child.parentElement = this; return child; },
        replaceChildren(...children) { this.children = children; },
        setAttribute(name, value) { this.attributes.set(name, String(value)); },
        getAttribute(name) { return this.attributes.get(name); },
        addEventListener(type, listener) { listeners.set(type, listener); },
        querySelectorAll(selector) {
            const className = selector.startsWith('.') ? selector.slice(1) : null;
            return descendants(this).filter((node) => className && node.className.split(' ').includes(className));
        },
        dispatch(type, event = {}) {
            listeners.get(type)?.({
                target: this,
                stopPropagation() {},
                ...event,
            });
        },
    };
}

function descendants(node) {
    return node.children.flatMap((child) => [child, ...descendants(child)]);
}

test('scene launchers are labeled native buttons and remain distinct from delete actions', () => {
    const originalDocument = globalThis.document;
    const scenesGrid = fakeElement();
    const sent = [];
    globalThis.document = { createElement: (tagName) => fakeElement(tagName) };
    const app = {
        state: {
            scenes: [{ name: 'My Set', pattern: 'aurora', preset: 'edm', entity_count: 32 }],
            currentScene: null,
        },
        elements: { scenesGrid },
        ws: { send: (message) => sent.push(message) },
        ui: { applyControlState() {} },
    };

    try {
        new SceneManager(app).renderScenes();
        const launch = descendants(scenesGrid).find((node) => node.className.includes('scene-card-launch'));
        const remove = descendants(scenesGrid).find((node) => node.className.includes('scene-card-delete'));

        assert.equal(launch?.tagName, 'BUTTON');
        assert.equal(launch?.getAttribute('aria-label'), 'Launch My Set');
        assert.equal(Object.hasOwn(launch.dataset, 'requiresConnection'), true);
        assert.equal(remove?.tagName, 'BUTTON');
        assert.equal(remove?.getAttribute('aria-label'), 'Delete My Set');

        launch.dispatch('click');
        assert.deepEqual(sent, [{ type: 'load_scene', name: 'My Set' }]);
    } finally {
        globalThis.document = originalDocument;
    }
});
