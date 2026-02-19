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
  calculateEntities(audio: AudioState): EntityData[];
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
 */
class PendingPattern implements PatternInstance {
  config: PatternConfig;
  private _inner: LuaPatternInstance | null = null;
  private _libSource: string;
  private _patternSource: string;

  constructor(libSource: string, patternSource: string, config?: Partial<PatternConfig>) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this._libSource = libSource;
    this._patternSource = patternSource;

    if (isFengariReady()) {
      this._inner = new LuaPatternInstance(libSource, patternSource, config);
    } else {
      ensureFengari().then(() => {
        if (!this._inner) {
          this._inner = new LuaPatternInstance(this._libSource, this._patternSource, config);
        }
      });
    }
  }

  update(dt: number): void {
    this._inner?.update(dt);
  }

  calculateEntities(audio: AudioState): EntityData[] {
    if (this._inner) return this._inner.calculateEntities(audio);
    return [];
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
    createPattern: () => new PendingPattern(LIB_LUA, def.source),
  }));
}

export function getPattern(name: string): PatternInstance {
  const def = LUA_PATTERNS.find((p) => p.id === name);
  const source = def ? def.source : LUA_PATTERNS[0].source;
  return new PendingPattern(LIB_LUA, source);
}
