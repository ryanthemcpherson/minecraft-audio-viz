import assert from 'node:assert/strict';
import { readdir } from 'node:fs/promises';
import test from 'node:test';
import { DEFAULT_WORKSPACE, WORKSPACES, isWorkspaceName } from '../js/config/workspaces.js';
import { extractIds, extractLiteralElementIds, readPanelFile } from './helpers/panel-source.mjs';

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
