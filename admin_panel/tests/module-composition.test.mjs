import assert from 'node:assert/strict';
import test from 'node:test';
import { readPanelFile } from './helpers/panel-source.mjs';

const managerNames = [
  'UIHelpers', 'AudioManager', 'PatternManager', 'ActionsManager',
  'ParticleEffectsManager', 'SceneManager', 'ZoneManager',
  'ConnectCodeManager', 'VoiceChatManager', 'DJManager', 'BannerManager',
  'BitmapManager', 'PreviewManager', 'MessageRouter',
];

test('AdminApp composes every extracted manager', async () => {
  const source = await readPanelFile('js/admin-app.js');
  const lifecycleSource = await readPanelFile('js/modules/ConnectionLifecycle.js');
  for (const managerName of managerNames) {
    assert.match(source, new RegExp(`import \\{ ${managerName} \\}`));
    assert.match(source, new RegExp(`new ${managerName}\\(this\\)`));
  }
  assert.match(source, /this\.state = createInitialState\(\)/);
  assert.match(source, /setupConnectionLifecycle\(this\)/);
  assert.match(lifecycleSource, /app\.router\.handleMessage\(event\.detail\)/);
});

test('AdminApp composes the workspace controller after the preview manager', async () => {
  const source = await readPanelFile('js/admin-app.js');
  const previewIndex = source.indexOf('this.preview = new PreviewManager(this)');
  const workspaceIndex = source.indexOf('this.workspaces = new WorkspaceManager(');

  assert.match(source, /import \{ WorkspaceManager \}/);
  assert.ok(previewIndex >= 0 && workspaceIndex > previewIndex);
  assert.match(source, /this\.workspaces\.setup\(\)/);
});

test('AdminApp no longer contains extracted domain implementations', async () => {
  const source = await readPanelFile('js/admin-app.js');
  const prohibitedMethods = [
    '_cacheElements', '_setupEventListeners', '_handleMessage',
    '_renderPatternGrid', '_renderDJQueue', '_handleAudioState',
    '_sendZoneConfig', '_renderStageZoneList', '_initPreview',
    '_initBitmapControls', '_setupBannerListeners', '_showToast',
  ];
  for (const method of prohibitedMethods) {
    assert.doesNotMatch(source, new RegExp(`\\n\\s+${method}\\(`), `${method} still lives in AdminApp`);
  }
  for (const field of ['tapTimes', 'tapTimeout']) {
    assert.doesNotMatch(source, new RegExp(`this\\.${field}\\b`), `${field} still lives on the AdminApp root`);
  }
  assert.doesNotMatch(source, /cleanup-task-7:/);
  assert.ok(source.split(/\r?\n/).length < 700, 'AdminApp is still a domain monolith');
});

test('legacy layout selectors are gone', async () => {
  const css = await readPanelFile('css/admin.css');
  for (const selector of ['#tab-bar', '#tab-content', 'tab-fade-in', '#zone-overview', '#main-content', '#work-area']) {
    assert.equal(css.includes(selector), false, `${selector} remains in legacy CSS`);
  }
});
