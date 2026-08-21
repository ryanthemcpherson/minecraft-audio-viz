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
  for (const managerName of managerNames) {
    assert.match(source, new RegExp(`import \\{ ${managerName} \\}`));
    assert.match(source, new RegExp(`new ${managerName}\\(this\\)`));
  }
  assert.match(source, /this\.state = createInitialState\(\)/);
  assert.match(source, /this\.router\.handleMessage\(event\.detail\)/);
});
