import assert from 'node:assert/strict';
import test from 'node:test';
import { MessageRouter } from '../js/modules/MessageRouter.js';

test('ignores malformed message envelopes at the router boundary', () => {
  const notices = [];
  const router = new MessageRouter({
    ui: { showToast: (message, type) => notices.push({ message, type }) },
  });

  assert.doesNotThrow(() => router.handleMessage(null));
  assert.doesNotThrow(() => router.handleMessage({}));
  assert.equal(notices.length, 2);
  assert.equal(notices.every(({ type }) => type === 'warning'), true);
});
