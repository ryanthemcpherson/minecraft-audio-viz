#!/usr/bin/env node
/**
 * Extract the block textures the site's 3D previews use straight from a
 * Minecraft client jar, so previews render the same pixels players see.
 *
 * Usage:
 *   node scripts/extract-mc-textures.mjs [path/to/client.jar]
 *
 * With no argument the newest release jar under the default launcher
 * directory is used (%APPDATA%\.minecraft\versions on Windows,
 * ~/.minecraft/versions elsewhere). Output goes to public/textures/block/.
 *
 * The jar is a zip file; this reads the central directory and inflates only
 * the entries we need, so there is no dependency on a zip library.
 */

import { readFileSync, writeFileSync, mkdirSync, readdirSync, existsSync, statSync } from "node:fs";
import { inflateRawSync } from "node:zlib";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = join(__dirname, "..", "public", "textures", "block");

/** Block textures the previews need. Keys are output file names. */
const WANTED = [
  // Entity blocks, one per frequency band (bass .. high)
  "orange_concrete",
  "yellow_concrete",
  "lime_concrete",
  "light_blue_concrete",
  "magenta_concrete",
  // Stage
  "grass_block_top",
  "grass_block_side",
  "grass_block_side_overlay",
  "dirt",
  "stone",
];

function defaultVersionsDir() {
  if (process.platform === "win32") {
    return join(process.env.APPDATA ?? "", ".minecraft", "versions");
  }
  if (process.platform === "darwin") {
    return join(process.env.HOME ?? "", "Library", "Application Support", "minecraft", "versions");
  }
  return join(process.env.HOME ?? "", ".minecraft", "versions");
}

/** Newest plain release jar (e.g. 1.21.10), ignoring snapshots and modded profiles. */
function findNewestReleaseJar() {
  const dir = defaultVersionsDir();
  if (!existsSync(dir)) return null;
  const releases = readdirSync(dir)
    .filter((name) => /^\d+\.\d+(\.\d+)?$/.test(name))
    .map((name) => ({ name, jar: join(dir, name, `${name}.jar`) }))
    .filter((v) => existsSync(v.jar))
    .sort((a, b) => compareVersions(b.name, a.name));
  return releases[0] ?? null;
}

function compareVersions(a, b) {
  const pa = a.split(".").map(Number);
  const pb = b.split(".").map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

/** Parse the zip central directory into { name -> entry }. */
function readCentralDirectory(buf) {
  let eocd = buf.length - 22;
  while (eocd >= 0 && buf.readUInt32LE(eocd) !== 0x06054b50) eocd--;
  if (eocd < 0) throw new Error("Not a zip file: end of central directory signature not found");

  const count = buf.readUInt16LE(eocd + 10);
  let offset = buf.readUInt32LE(eocd + 16);
  const entries = new Map();

  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(offset) !== 0x02014b50) {
      throw new Error(`Corrupt central directory at entry ${i}`);
    }
    const method = buf.readUInt16LE(offset + 10);
    const compressedSize = buf.readUInt32LE(offset + 20);
    const uncompressedSize = buf.readUInt32LE(offset + 24);
    const nameLen = buf.readUInt16LE(offset + 28);
    const extraLen = buf.readUInt16LE(offset + 30);
    const commentLen = buf.readUInt16LE(offset + 32);
    const localHeaderOffset = buf.readUInt32LE(offset + 42);
    const name = buf.toString("utf8", offset + 46, offset + 46 + nameLen);
    entries.set(name, { method, compressedSize, uncompressedSize, localHeaderOffset });
    offset += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

/** Read one entry's bytes, honoring the local header's own name/extra lengths. */
function extractEntry(buf, entry) {
  const lh = entry.localHeaderOffset;
  if (buf.readUInt32LE(lh) !== 0x04034b50) throw new Error("Bad local file header");
  const nameLen = buf.readUInt16LE(lh + 26);
  const extraLen = buf.readUInt16LE(lh + 28);
  const start = lh + 30 + nameLen + extraLen;
  const raw = buf.subarray(start, start + entry.compressedSize);
  if (entry.method === 0) return Buffer.from(raw);
  if (entry.method === 8) return inflateRawSync(raw);
  throw new Error(`Unsupported compression method ${entry.method}`);
}

function main() {
  const arg = process.argv[2];
  let jarPath = arg;
  let version = arg ? "custom" : null;

  if (!jarPath) {
    const found = findNewestReleaseJar();
    if (!found) {
      console.error(
        "No Minecraft release jar found. Pass the path to a client jar:\n" +
          "  node scripts/extract-mc-textures.mjs /path/to/1.21.10.jar",
      );
      process.exit(1);
    }
    jarPath = found.jar;
    version = found.name;
  }

  if (!existsSync(jarPath) || !statSync(jarPath).isFile()) {
    console.error(`Jar not found: ${jarPath}`);
    process.exit(1);
  }

  console.log(`Reading ${jarPath}`);
  const buf = readFileSync(jarPath);
  const entries = readCentralDirectory(buf);
  mkdirSync(OUT_DIR, { recursive: true });

  const missing = [];
  for (const name of WANTED) {
    const key = `assets/minecraft/textures/block/${name}.png`;
    const entry = entries.get(key);
    if (!entry) {
      missing.push(name);
      continue;
    }
    const bytes = extractEntry(buf, entry);
    writeFileSync(join(OUT_DIR, `${name}.png`), bytes);
    console.log(`  ${name}.png (${bytes.length} bytes)`);
  }

  writeFileSync(
    join(OUT_DIR, "SOURCE.txt"),
    [
      `Extracted from the Minecraft ${version} client jar by scripts/extract-mc-textures.mjs.`,
      "These textures are Mojang assets used under the Minecraft usage guidelines for a",
      "non-commercial fan project. They are not covered by this repository's MIT license.",
      "",
      "Files: " + WANTED.map((n) => `${n}.png`).join(", "),
      "",
    ].join("\n"),
  );

  if (missing.length > 0) {
    console.error(`Missing in jar: ${missing.join(", ")}`);
    process.exit(1);
  }
  console.log(`Wrote ${WANTED.length} textures to ${OUT_DIR}`);
}

main();
