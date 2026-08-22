import assert from 'node:assert/strict';
import test from 'node:test';

class FakeBufferGeometry {
  constructor() {
    this.attributes = {};
  }

  setAttribute(name, attribute) {
    this.attributes[name] = attribute;
  }
}

class FakeShaderMaterial {
  constructor(options) {
    Object.assign(this, options);
  }
}

function createParticleHarness() {
  globalThis.window = {};
  globalThis.document = {
    createElement: () => ({
      getContext: () => ({
        createRadialGradient: () => ({ addColorStop: () => {} }),
        fillRect: () => {},
        set fillStyle(_value) {},
      }),
    }),
  };
  globalThis.THREE = {
    AdditiveBlending: 2,
    BufferAttribute: class FakeBufferAttribute {},
    BufferGeometry: FakeBufferGeometry,
    CanvasTexture: class FakeCanvasTexture {},
    Points: class FakePoints {},
    ShaderMaterial: FakeShaderMaterial,
  };
  return { add: () => {} };
}

test('particle preview shader uses a non-reserved custom color attribute', async () => {
  const scene = createParticleHarness();
  await import(`../js/particles.js?shader-regression=${Date.now()}`);

  const particles = new window.ParticleSystem(scene, 4);

  assert.ok(particles.geometry.attributes.particleColor);
  assert.match(particles.material.vertexShader, /attribute vec3 particleColor;/);
  assert.doesNotMatch(particles.material.vertexShader, /attribute vec3 color;/);
  assert.equal(particles.material.vertexColors, false);
});
