#!/usr/bin/env node
/**
 * Build script: reads patterns/*.lua and generates src/lib/patterns/generated.ts
 * Run: node scripts/generate-patterns.mjs
 */

import { readFileSync, readdirSync, writeFileSync, existsSync } from "fs";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const SITE_DIR = join(__dirname, "..");
const PATTERNS_DIR = join(SITE_DIR, "..", "patterns");
const OUTPUT = join(SITE_DIR, "src", "lib", "patterns", "generated.ts");

// Category sort order for consistent display
const CATEGORY_ORDER = ["Original", "Epic", "Cosmic", "Organic", "Spectrum"];

function escapeForTemplateLiteral(s) {
  return s.replace(/\\/g, "\\\\").replace(/`/g, "\\`").replace(/\$\{/g, "\\${");
}

function extractMeta(source, filename) {
  const name = source.match(/^name\s*=\s*"([^"]+)"/m)?.[1];
  const description = source.match(/^description\s*=\s*"([^"]+)"/m)?.[1];
  const category = source.match(/^category\s*=\s*"([^"]+)"/m)?.[1];
  const staticCameraMatch = source.match(/^static_camera\s*=\s*(true|false)/m);
  const staticCamera = staticCameraMatch?.[1] === "true";

  if (!name || !description) {
    console.warn(`  WARN: ${filename} missing name or description, skipping`);
    return null;
  }
  if (!category) {
    console.warn(`  WARN: ${filename} missing category, defaulting to "Original"`);
  }

  return { name, description, category: category || "Original", staticCamera };
}

function main() {
  // On Railway, rootDirectory is /site so ../patterns doesn't exist.
  // Fall back to the committed generated.ts (kept in git).
  if (!existsSync(PATTERNS_DIR)) {
    if (existsSync(OUTPUT)) {
      console.log("Patterns dir not found (Railway build). Using committed generated.ts.");
      return;
    }
    console.error("ERROR: patterns/ dir missing and no generated.ts — cannot build.");
    process.exit(1);
  }

  console.log("Generating pattern bundle from", PATTERNS_DIR);

  // Read lib.lua
  const libPath = join(PATTERNS_DIR, "lib.lua");
  const libSource = readFileSync(libPath, "utf-8");
  console.log("  Loaded lib.lua");

  // Read all pattern files
  const files = readdirSync(PATTERNS_DIR)
    .filter((f) => f.endsWith(".lua") && f !== "lib.lua")
    .sort();

  const patterns = [];

  for (const file of files) {
    const source = readFileSync(join(PATTERNS_DIR, file), "utf-8");
    const id = file.replace(".lua", "");
    const meta = extractMeta(source, file);
    if (!meta) continue;

    patterns.push({ id, ...meta, source });
    console.log(`  ${id}: "${meta.name}" [${meta.category}]`);
  }

  // Sort by category order, then by id within each category
  patterns.sort((a, b) => {
    const ca = CATEGORY_ORDER.indexOf(a.category);
    const cb = CATEGORY_ORDER.indexOf(b.category);
    if (ca !== cb) return (ca === -1 ? 99 : ca) - (cb === -1 ? 99 : cb);
    return a.id.localeCompare(b.id);
  });

  // Generate TypeScript
  const lines = [
    "// AUTO-GENERATED from patterns/*.lua — DO NOT EDIT",
    "// Run: node scripts/generate-patterns.mjs",
    "",
    "export const LIB_LUA = `" + escapeForTemplateLiteral(libSource) + "`;",
    "",
    "export interface LuaPatternDef {",
    "  id: string;",
    "  name: string;",
    "  description: string;",
    "  category: string;",
    "  staticCamera: boolean;",
    "  source: string;",
    "}",
    "",
    "export const PATTERNS: LuaPatternDef[] = [",
  ];

  for (const p of patterns) {
    lines.push("  {");
    lines.push(`    id: ${JSON.stringify(p.id)},`);
    lines.push(`    name: ${JSON.stringify(p.name)},`);
    lines.push(`    description: ${JSON.stringify(p.description)},`);
    lines.push(`    category: ${JSON.stringify(p.category)},`);
    lines.push(`    staticCamera: ${p.staticCamera},`);
    lines.push("    source: `" + escapeForTemplateLiteral(p.source) + "`,");
    lines.push("  },");
  }

  lines.push("];");
  lines.push("");

  writeFileSync(OUTPUT, lines.join("\n"), "utf-8");
  console.log(`\nGenerated ${OUTPUT} with ${patterns.length} patterns`);
}

main();
