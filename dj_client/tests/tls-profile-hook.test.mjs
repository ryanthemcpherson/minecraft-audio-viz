import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import React, { StrictMode, useEffect } from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { createServer } from 'vite';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const clientRoot = fileURLToPath(new URL('..', import.meta.url));
const vite = await createServer({
  root: clientRoot,
  appType: 'custom',
  logLevel: 'silent',
  server: { middlewareMode: true },
});
const { useTlsFingerprintProfile } = await vite.ssrLoadModule(
  '/src/hooks/useTlsFingerprintProfile.ts',
);

test.after(async () => {
  await vite.close();
});

function createStorage(initial = {}) {
  const values = new Map(Object.entries(initial));
  const operations = [];
  return {
    getItem(key) {
      return values.has(key) ? values.get(key) : null;
    },
    setItem(key, value) {
      values.set(key, value);
      operations.push({ type: 'set', key, value });
    },
    removeItem(key) {
      values.delete(key);
      operations.push({ type: 'remove', key });
    },
    has(key) {
      return values.has(key);
    },
    value(key) {
      return values.get(key);
    },
    operations,
  };
}

function ProfileHarness({ storage, observe, lifecycle }) {
  const profile = useTlsFingerprintProfile(storage);

  useEffect(() => {
    lifecycle.mounts += 1;
    return () => {
      lifecycle.cleanups += 1;
    };
  }, [lifecycle]);

  useEffect(() => {
    observe(profile);
  }, [observe, profile]);

  return React.createElement('output', null, profile.tlsFingerprint);
}

async function mountProfile(storage) {
  const lifecycle = { mounts: 0, cleanups: 0 };
  let currentProfile = null;
  const observe = (profile) => {
    currentProfile = profile;
  };
  let renderer;

  await act(async () => {
    renderer = TestRenderer.create(
      React.createElement(
        StrictMode,
        null,
        React.createElement(ProfileHarness, { storage, observe, lifecycle }),
      ),
    );
  });

  return {
    lifecycle,
    renderer,
    profile() {
      return currentProfile;
    },
  };
}

test('StrictMode mount and reload preserve an absent legacy fingerprint without writes', async () => {
  const storage = createStorage();
  const firstMount = await mountProfile(storage);

  assert.equal(firstMount.lifecycle.mounts, 2);
  assert.equal(firstMount.lifecycle.cleanups, 1);
  assert.equal(firstMount.profile().tlsFingerprint, '');
  assert.equal(storage.has('mcav.tlsFingerprint'), false);
  assert.deepEqual(storage.operations, []);

  await act(async () => firstMount.renderer.unmount());
  assert.equal(firstMount.lifecycle.cleanups, 2);
  assert.deepEqual(storage.operations, []);

  const reload = await mountProfile(storage);
  assert.equal(reload.lifecycle.mounts, 2);
  assert.equal(reload.profile().tlsFingerprint, '');
  assert.equal(storage.has('mcav.tlsFingerprint'), false);
  assert.deepEqual(storage.operations, []);

  await act(async () => reload.renderer.unmount());
  assert.equal(reload.lifecycle.cleanups, 2);
  assert.deepEqual(storage.operations, []);
});

test('real effects migrate once, persist edits, delete clears, and clean up safely', async () => {
  const storage = createStorage({
    'mcav.tlsFingerprint': `${'ab:'.repeat(31)}ab`,
  });
  const mounted = await mountProfile(storage);

  assert.equal(mounted.lifecycle.mounts, 2);
  assert.equal(mounted.profile().tlsFingerprint, 'AB'.repeat(32));
  assert.equal(storage.value('mcav.tlsFingerprint'), 'AB'.repeat(32));
  assert.deepEqual(storage.operations, [
    {
      type: 'set',
      key: 'mcav.tlsFingerprint',
      value: 'AB'.repeat(32),
    },
  ]);

  await act(async () => {
    mounted.profile().setTlsFingerprint(`${'cd '.repeat(31)}cd`);
  });
  assert.equal(mounted.profile().tlsFingerprint, 'CD'.repeat(32));
  assert.equal(storage.value('mcav.tlsFingerprint'), 'CD'.repeat(32));
  assert.deepEqual(storage.operations.at(-1), {
    type: 'set',
    key: 'mcav.tlsFingerprint',
    value: 'CD'.repeat(32),
  });

  await act(async () => {
    mounted.profile().setTlsFingerprint('');
  });
  assert.equal(mounted.profile().tlsFingerprint, '');
  assert.equal(storage.has('mcav.tlsFingerprint'), false);
  assert.deepEqual(storage.operations.at(-1), {
    type: 'remove',
    key: 'mcav.tlsFingerprint',
  });
  assert.equal(storage.operations.length, 3);

  await act(async () => mounted.renderer.unmount());
  assert.equal(mounted.lifecycle.cleanups, 2);
  assert.equal(storage.operations.length, 3);
});
