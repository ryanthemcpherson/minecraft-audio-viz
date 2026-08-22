import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

export class FakeCanvasContext {
  constructor() {
    this.fillStyle = '#000';
    this.putCalls = 0;
    this.lastImageData = null;
  }

  createImageData(width, height) {
    return { width, height, data: new Uint8ClampedArray(width * height * 4) };
  }

  putImageData(imageData) {
    this.putCalls += 1;
    this.lastImageData = {
      width: imageData.width,
      height: imageData.height,
      data: new Uint8ClampedArray(imageData.data),
    };
  }

  getImageData(_x, _y, width, height) {
    return this.createImageData(width, height);
  }

  fillRect() {}
}

class FakeDisposable {
  constructor(...args) {
    this.args = args;
    this.disposed = false;
  }

  dispose() {
    this.disposed = true;
  }
}

class FakeVector {
  set(x, y, z) {
    this.x = x;
    this.y = y;
    this.z = z;
  }
}

class FakeGroup {
  constructor() {
    this.children = [];
    this.position = new FakeVector();
    this.rotation = {};
  }

  add(child) {
    child.parent = this;
    this.children.push(child);
  }

  remove(child) {
    child.parent = null;
    this.children = this.children.filter((candidate) => candidate !== child);
  }
}

class FakeMesh {
  constructor(geometry, material) {
    this.geometry = geometry;
    this.material = material;
    this.position = new FakeVector();
    this.visible = true;
    this.parent = null;
  }
}

class FakeLineSegments extends FakeMesh {}

class FakeCanvasTexture extends FakeDisposable {
  constructor(canvas) {
    super(canvas);
    this.canvas = canvas;
    this.needsUpdate = false;
  }
}

export function createFakeThree() {
  return {
    CanvasTexture: FakeCanvasTexture,
    NearestFilter: 'nearest',
    PlaneGeometry: FakeDisposable,
    MeshBasicMaterial: FakeDisposable,
    DoubleSide: 'double',
    Mesh: FakeMesh,
    Group: FakeGroup,
    BoxGeometry: FakeDisposable,
    EdgesGeometry: FakeDisposable,
    LineBasicMaterial: FakeDisposable,
    LineSegments: FakeLineSegments,
  };
}

export function createFakeDocument() {
  return {
    createElement(tagName) {
      if (tagName !== 'canvas') throw new Error(`Unexpected element: ${tagName}`);
      const context = new FakeCanvasContext();
      return {
        width: 0,
        height: 0,
        context,
        getContext: () => context,
      };
    },
    getElementById: () => null,
  };
}

export function createScene() {
  return {
    children: [],
    add(child) {
      child.parent = this;
      this.children.push(child);
    },
    remove(child) {
      child.parent = null;
      this.children = this.children.filter((candidate) => candidate !== child);
    },
  };
}

export function createZoneGroup({ sizeX = 10, sizeY = 8, sizeZ = 2 } = {}) {
  return {
    group: new FakeGroup(),
    blocks: [],
    wireframe: { visible: true },
    sizeX,
    sizeY,
    sizeZ,
  };
}

export async function loadBitmapPreview(clock = { now: 0 }) {
  const source = await readFile(
    new URL('../../js/bitmap-preview.js', import.meta.url),
    'utf8',
  );
  const sandbox = {
    atob,
    console,
    document: createFakeDocument(),
    performance: { now: () => clock.now },
    THREE: createFakeThree(),
  };
  sandbox.globalThis = sandbox;
  vm.runInNewContext(`${source}\nglobalThis.BitmapPreviewForTest = BitmapPreview;`, sandbox);
  return { BitmapPreview: sandbox.BitmapPreviewForTest, sandbox };
}
