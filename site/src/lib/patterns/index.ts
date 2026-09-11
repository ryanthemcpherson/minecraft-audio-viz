/**
 * Pattern Registry
 * Auto-generated pattern list backed by Lua pattern library via fengari.
 */

import type { EntityData, AudioState, PatternConfig } from "./base";
import { DEFAULT_CONFIG } from "./base";
import { PATTERNS as LUA_PATTERNS, LIB_LUA } from "./generated";
import { ensureFengari, isFengariReady, LuaPatternInstance } from "./luaAdapter";

export type { EntityData, AudioState, PatternConfig };

/** Minimal duck-typed interface that PatternScene/PatternCard need */
export interface PatternInstance {
  config: PatternConfig;
  update(dt: number): void;
  calculateEntities(audio: AudioState, dt?: number): EntityData[];
  dispose?(): void;
}

export interface PatternMeta {
  id: string;
  name: string;
  description: string;
  category: string;
  staticCamera: boolean;
  startBlocks: number | null;
  createPattern: () => PatternInstance;
}

/**
 * Wrapper that returns empty entities while fengari loads,
 * then seamlessly delegates to the real LuaPatternInstance.
 *
 * The Lua state is created lazily on the first calculateEntities() call and
 * again after dispose() if the wrapper is used once more. React StrictMode
 * mounts, unmounts, and remounts components in development, so a wrapper
 * that dies on the first dispose() would leave every preview empty.
 */
class PendingPattern implements PatternInstance {
  config: PatternConfig;
  private _inner: LuaPatternInstance | null = null;
  private readonly _libSource: string;
  private readonly _patternSource: string;
  private readonly _config?: Partial<PatternConfig>;

  constructor(libSource: string, patternSource: string, config?: Partial<PatternConfig>) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this._libSource = libSource;
    this._patternSource = patternSource;
    this._config = config;

    if (typeof window !== "undefined") {
      // Kick off the load; the instance is created on first use.
      ensureFengari().catch((error) => {
        console.error("[MCAV] Failed to load the Lua runtime for pattern previews:", error);
      });
    }
  }

  private _ensureInner(): LuaPatternInstance | null {
    if (!this._inner && isFengariReady()) {
      this._inner = new LuaPatternInstance(this._libSource, this._patternSource, this._config);
    }
    return this._inner;
  }

  update(dt: number): void {
    this._ensureInner()?.update(dt);
  }

  calculateEntities(audio: AudioState, dt?: number): EntityData[] {
    const inner = this._ensureInner();
    if (inner) return inner.calculateEntities(audio, dt);
    return [];
  }

  dispose(): void {
    this._inner?.dispose();
    this._inner = null;
  }
}

// Eagerly start loading fengari when this module is first imported in the browser
if (typeof window !== "undefined") {
  ensureFengari();
}

export function listPatterns(): PatternMeta[] {
  return LUA_PATTERNS.map((def) => ({
    id: def.id,
    name: def.name,
    description: def.description,
    category: def.category,
    staticCamera: def.staticCamera,
    startBlocks: def.startBlocks,
    createPattern: () => new PendingPattern(LIB_LUA, def.source, {
      entityCount: def.startBlocks ?? DEFAULT_CONFIG.entityCount,
    }),
  }));
}

export function getPattern(name: string): PatternInstance {
  const def = LUA_PATTERNS.find((p) => p.id === name);
  const source = def ? def.source : LUA_PATTERNS[0].source;
  return new PendingPattern(LIB_LUA, source);
}
