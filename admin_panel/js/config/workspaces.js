export const DEFAULT_WORKSPACE = 'live';
export const WORKSPACE_STORAGE_KEY = 'mcav-active-workspace';

export const WORKSPACES = Object.freeze([
  { id: 'live', label: 'Live', shortcut: 'Alt+1' },
  { id: 'visuals', label: 'Visuals', shortcut: 'Alt+2' },
  { id: 'zones', label: 'Zones', shortcut: 'Alt+3' },
  { id: 'djs', label: 'DJs', shortcut: 'Alt+4' },
  { id: 'system', label: 'System', shortcut: 'Alt+5' },
]);

const workspaceNames = new Set(WORKSPACES.map(({ id }) => id));

export function isWorkspaceName(value) {
  return typeof value === 'string' && workspaceNames.has(value);
}
