import assert from 'node:assert/strict';
import test from 'node:test';

function installCanvasDocument() {
  globalThis.document = {
    createElement: () => ({
      getContext: () => ({
        createRadialGradient: () => ({ addColorStop: () => {} }),
        fillRect: () => {},
        set fillStyle(_value) {},
      }),
      height: 0,
      width: 0,
    }),
  };
}

function emulateR128VertexPrefix(material) {
  if (!material.vertexColors) return '';
  return '#define USE_COLOR\nattribute vec3 color;\n';
}

test('production Three r128 accepts the particle color buffer without a shader symbol collision', async () => {
    installCanvasDocument();
    globalThis.window = globalThis;
    await import('../js/vendor/three-r128.min.js');
  await import(`../js/particles.js?r128-regression=${Date.now()}`);

  assert.equal(THREE.REVISION, '128');
  const scene = new THREE.Scene();
  const particles = new window.ParticleSystem(scene, 4);
  const colorAttribute = particles.geometry.getAttribute('particleColor');

  assert.ok(colorAttribute instanceof THREE.BufferAttribute);
  assert.ok(colorAttribute.array instanceof Float32Array);
  assert.equal(colorAttribute.itemSize, 3);
  assert.equal(colorAttribute.count, 4);
  assert.equal(colorAttribute.version, 0);
  assert.equal(particles.geometry.getAttribute('color'), undefined);

  particles.update(0.016);
  assert.equal(colorAttribute.version, 1);

  assert.equal(particles.material.vertexColors, false);
  assert.equal(particles.material.defines?.USE_COLOR, undefined);
  assert.equal((particles.material.vertexShader.match(/attribute vec3 particleColor;/g) ?? []).length, 1);
  assert.match(particles.material.vertexShader, /vColor\s*=\s*particleColor;/);

  const oldShaderContract = 'attribute vec3 color;\nvoid main() {}';
  const oldPrefixedShader = `${emulateR128VertexPrefix({ vertexColors: true })}${oldShaderContract}`;
  assert.equal((oldPrefixedShader.match(/attribute vec3 color;/g) ?? []).length, 2);

  const productionPrefixedShader = `${emulateR128VertexPrefix(particles.material)}${particles.material.vertexShader}`;
  assert.equal((productionPrefixedShader.match(/attribute vec3 color;/g) ?? []).length, 0);
  assert.equal((productionPrefixedShader.match(/attribute vec3 particleColor;/g) ?? []).length, 1);
});
