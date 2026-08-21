import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { existsSync, readFileSync } from 'node:fs';
import test from 'node:test';

const projectRequire = createRequire(new URL('../package.json', import.meta.url));
const typescript = projectRequire('typescript');

function compiledDataUrl(source) {
  const output = typescript.transpileModule(source, {
    compilerOptions: {
      module: typescript.ModuleKind.ES2022,
      target: typescript.ScriptTarget.ES2021,
    },
  }).outputText;
  return `data:text/javascript;base64,${Buffer.from(output).toString('base64')}`;
}

const runtimeUrl = compiledDataUrl(`
  let activeRenderer = null;

  export function useState(initialValue) {
    const renderer = activeRenderer;
    const slot = renderer.cursor++;
    if (!(slot in renderer.slots)) {
      renderer.slots[slot] = typeof initialValue === 'function' ? initialValue() : initialValue;
    }
    return [renderer.slots[slot], (nextValue) => {
      renderer.slots[slot] = typeof nextValue === 'function'
        ? nextValue(renderer.slots[slot])
        : nextValue;
      renderer.render();
    }];
  }

  export function useRef(initialValue) {
    const renderer = activeRenderer;
    const slot = renderer.cursor++;
    if (!(slot in renderer.slots)) {
      renderer.slots[slot] = { current: initialValue };
    }
    return renderer.slots[slot];
  }

  export function useEffect(effect, dependencies) {
    const renderer = activeRenderer;
    const slot = renderer.cursor++;
    const previous = renderer.effects[slot];
    const changed = !previous || dependencies.some(
      (dependency, index) => !Object.is(dependency, previous.dependencies[index]),
    );
    renderer.effects[slot] = { dependencies };
    if (changed) renderer.pendingEffects.push(effect);
  }

  export function mount(callback) {
    const renderer = {
      slots: [],
      effects: [],
      pendingEffects: [],
      current: undefined,
      cursor: 0,
      render() {
        this.cursor = 0;
        this.pendingEffects = [];
        activeRenderer = this;
        this.current = callback();
        activeRenderer = null;
        for (const effect of this.pendingEffects) effect();
      },
    };
    renderer.render();
    return renderer;
  }
`);

const profileUrl = compiledDataUrl(
  readFileSync(new URL('../src/lib/connectionProfile.ts', import.meta.url), 'utf8'),
);
const hookFileUrl = new URL('../src/hooks/useTlsFingerprintProfile.ts', import.meta.url);
const hookExists = existsSync(hookFileUrl);
let hooks = null;
let runtime = null;

if (hookExists) {
  const hookSource = readFileSync(hookFileUrl, 'utf8');
  const compiledHook = typescript.transpileModule(hookSource, {
    compilerOptions: {
      module: typescript.ModuleKind.ES2022,
      target: typescript.ScriptTarget.ES2021,
    },
  }).outputText
    .replace("'react'", `'${runtimeUrl}'`)
    .replace("'../lib/connectionProfile'", `'${profileUrl}'`);
  hooks = await import(
    `data:text/javascript;base64,${Buffer.from(compiledHook).toString('base64')}`
  );
  runtime = await import(runtimeUrl);
}

function createStorage(initial = {}) {
  const values = new Map(Object.entries(initial));
  return {
    getItem(key) {
      return values.has(key) ? values.get(key) : null;
    },
    setItem(key, value) {
      values.set(key, value);
    },
    removeItem(key) {
      values.delete(key);
    },
    has(key) {
      return values.has(key);
    },
    value(key) {
      return values.get(key);
    },
  };
}

test('mounted fingerprint persistence preserves legacy absence, migrates, and saves user edits', () => {
  assert.equal(hookExists, true, 'the executable fingerprint persistence hook must exist');

  const absentStorage = createStorage();
  runtime.mount(() => hooks.useTlsFingerprintProfile(absentStorage));
  assert.equal(absentStorage.has('mcav.tlsFingerprint'), false);
  runtime.mount(() => hooks.useTlsFingerprintProfile(absentStorage));
  assert.equal(absentStorage.has('mcav.tlsFingerprint'), false);

  const storedFingerprint = `${'ab:'.repeat(31)}ab`;
  const migratedStorage = createStorage({ 'mcav.tlsFingerprint': storedFingerprint });
  const mounted = runtime.mount(() => hooks.useTlsFingerprintProfile(migratedStorage));
  assert.equal(mounted.current.tlsFingerprint, 'AB'.repeat(32));
  assert.equal(migratedStorage.value('mcav.tlsFingerprint'), 'AB'.repeat(32));

  mounted.current.setTlsFingerprint(`${'cd '.repeat(31)}cd`);
  assert.equal(mounted.current.tlsFingerprint, 'CD'.repeat(32));
  assert.equal(migratedStorage.value('mcav.tlsFingerprint'), 'CD'.repeat(32));

  mounted.current.setTlsFingerprint('');
  assert.equal(migratedStorage.has('mcav.tlsFingerprint'), false);
});
