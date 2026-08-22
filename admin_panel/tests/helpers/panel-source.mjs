import { readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const panelRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

export function readPanelFile(relativePath) {
  return readFile(resolve(panelRoot, relativePath), 'utf8');
}

export function extractIds(html) {
  return [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
}

export function extractLiteralElementIds(source) {
  return [...source.matchAll(/(?:getElementById|setupControl|setupZoneControl|setupToggle)\(\s*['"]([^'"]+)['"]/g)]
    .map((match) => match[1]);
}
