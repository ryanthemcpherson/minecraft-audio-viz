import assert from 'node:assert/strict';
import { readdir } from 'node:fs/promises';
import test from 'node:test';
import { DEFAULT_WORKSPACE, WORKSPACES, isWorkspaceName } from '../js/config/workspaces.js';
import { extractIds, extractLiteralElementIds, readPanelFile } from './helpers/panel-source.mjs';

export function extractWorkspaceSlice(html, workspace) {
  const start = html.indexOf(`data-workspace="${workspace}"`, html.indexOf('data-workspace-panel'));
  if (start < 0) throw new Error(`Missing workspace ${workspace}`);
  const next = html.indexOf('data-workspace-panel', start + 1);
  return html.slice(start, next < 0 ? html.length : next);
}

test('defines the approved workspace order', () => {
  assert.equal(DEFAULT_WORKSPACE, 'live');
  assert.deepEqual(WORKSPACES.map(({ id }) => id), ['live', 'visuals', 'zones', 'djs', 'system']);
  assert.equal(isWorkspaceName('zones'), true);
  assert.equal(isWorkspaceName('legacy'), false);
});

test('panel IDs are unique and every literal getElementById binding exists', async () => {
  const html = await readPanelFile('index.html');
  const ids = extractIds(html);
  assert.equal(new Set(ids).size, ids.length, 'index.html contains duplicate IDs');

  const sourceFiles = await readdir(new URL('../js/', import.meta.url), { recursive: true });
  for (const filename of sourceFiles.filter((name) => name.endsWith('.js') && !name.includes('vendor'))) {
    const source = await readPanelFile(`js/${filename}`);
    for (const id of extractLiteralElementIds(source)) {
      assert.ok(ids.includes(id), `${filename} binds missing #${id}`);
    }
  }
});

test('panel exposes exactly five semantic workspaces and stable emergency controls', async () => {
  const html = await readPanelFile('index.html');
  const expected = WORKSPACES.map(({ id }) => id);
  const navigation = [...html.matchAll(/\bdata-workspace-nav\b[^>]*\bdata-workspace="([^"]+)"/g)]
    .map((match) => match[1]);
  const panels = [...html.matchAll(/\bdata-workspace-panel\b[^>]*\bdata-workspace="([^"]+)"/g)]
    .map((match) => match[1]);

  assert.deepEqual(navigation, expected);
  assert.deepEqual(panels, expected);
  assert.ok(html.indexOf('id="btn-blackout"') < html.indexOf('class="workspace-stage"'));
  assert.ok(html.indexOf('id="btn-freeze"') < html.indexOf('class="workspace-stage"'));
  assert.equal(/\bid="tab-(?:mixer|zone|banner)"/.test(html), false);
  assert.equal(/\bid="(?:mixer|zone|banner)-panel"/.test(html), false);
});

test('every secondary capability is in its approved destination workspace', async () => {
  const html = await readPanelFile('index.html');
  const expectedIds = {
    visuals: ['bitmap-pattern-grid', 'bitmap-palette-grid', 'ledwall-effects-section', 'ledwall-text-section', 'ledwall-layers-section', 'particle-global-intensity', 'dj-logo-section'],
    zones: ['stage-zone-list', 'mode-entities', 'mode-particles', 'mode-hybrid', 'zone-entity-count', 'zone-block-type', 'band-materials-section', 'zone-size-x', 'btn-cleanup-zone'],
    djs: ['dj-queue', 'dj-pending-section', 'btn-generate-code', 'active-codes', 'banner-dj-select', 'banner-mode-text', 'banner-mode-image', 'btn-save-banner-profile'],
    system: ['sync-mode', 'ctrl-visual-delay', 'btn-sync-test', 'parity-check-btn', 'voice-chat-section', 'sync-dashboard'],
  };

  for (const [workspace, ids] of Object.entries(expectedIds)) {
    const slice = extractWorkspaceSlice(html, workspace);
    for (const id of ids) {
      assert.match(slice, new RegExp(`id="${id}"`), `#${id} must be in ${workspace}`);
    }
  }
});

test('Visuals and Zones expose the only compact preview destinations', async () => {
  const html = await readPanelFile('index.html');

  assert.equal((html.match(/data-preview-slot="visuals"/g) ?? []).length, 1);
  assert.equal((html.match(/data-preview-slot="zones"/g) ?? []).length, 1);
  assert.equal((html.match(/data-preview-slot="(?:visuals|zones)"/g) ?? []).length, 2);
  assert.equal(extractIds(html).filter((id) => id === 'preview-strip').length, 1);
  assert.equal(extractIds(html).filter((id) => id === 'preview-canvas').length, 1);
});

test('every section-index action targets a real top-level section', async () => {
  const html = await readPanelFile('index.html');
  const targets = [...html.matchAll(/<button\b[^>]*\bdata-section-target="([^"]+)"[^>]*>/g)]
    .map((match) => match[1]);

  assert.ok(targets.length > 0);
  assert.equal(/<nav class="section-index"[^>]*>[\s\S]*?<a\b/.test(html), false);
  for (const target of targets) {
    assert.match(
      html,
      new RegExp(`<section\\b[^>]*\\bid="${target}"`),
      `section-index target #${target} must be a section`,
    );
  }
});
