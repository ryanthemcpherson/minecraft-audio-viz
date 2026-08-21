/**
 * PatternManager - Pattern grid rendering, highlighting, preset management.
 */

import { filterAndRankPatterns, updateRecentIds } from '../utils/pattern-library.js';

const FAVORITES_STORAGE_KEY = 'mcav-pattern-favorites';
const RECENTS_STORAGE_KEY = 'mcav-pattern-recents';

export class PatternManager {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;
        this.searchQuery = '';
        this.storage = this._resolveStorage();
        this.favoriteIds = this._readStoredIds(FAVORITES_STORAGE_KEY);
        this.recentIds = this._readStoredIds(RECENTS_STORAGE_KEY);
    }

    handlePatterns(data) {
        // Deduplicate by ID as a safety net
        const seen = new Set();
        const raw = data.patterns || [];
        this.state.patterns = raw.filter(p => {
            if (seen.has(p.id)) return false;
            seen.add(p.id);
            return true;
        });
        this.state.currentPattern = data.current || data.current_pattern;
        this.renderPatternGrid();
        this.updateCurrentPattern(this.state.currentPattern);
    }

    setPattern(patternId) {
        this.recentIds = updateRecentIds(this.recentIds, patternId);
        this._writeStoredIds(RECENTS_STORAGE_KEY, this.recentIds);
        this.renderPatternGrid();

        const msg = { type: 'set_pattern', pattern: patternId };
        const selected = Array.from(this.state.selectedZones);
        if (selected.length > 0) {
            msg.zones = selected;
        }
        this.ws.send(msg);
    }

    setSearchQuery(query) {
        this.searchQuery = String(query ?? '');
        this.renderPatternGrid();
    }

    toggleFavorite(patternId) {
        if (!patternId) return;
        this.favoriteIds = this.favoriteIds.includes(patternId)
            ? this.favoriteIds.filter((id) => id !== patternId)
            : [...this.favoriteIds, patternId];
        this._writeStoredIds(FAVORITES_STORAGE_KEY, this.favoriteIds);
        this.renderPatternGrid();
    }

    _resolveStorage() {
        try {
            return globalThis.localStorage ?? null;
        } catch (error) {
            console.warn('[Patterns] Local storage unavailable', error);
            return null;
        }
    }

    _readStoredIds(key) {
        try {
            const value = JSON.parse(this.storage?.getItem(key) || '[]');
            return Array.isArray(value)
                ? [...new Set(value.filter((id) => typeof id === 'string'))]
                : [];
        } catch (error) {
            console.warn(`[Patterns] Could not read ${key}`, error);
            return [];
        }
    }

    _writeStoredIds(key, ids) {
        try {
            this.storage?.setItem(key, JSON.stringify(ids));
        } catch (error) {
            console.warn(`[Patterns] Could not write ${key}`, error);
        }
    }

    setPreset(preset) {
        this.ws.send({ type: 'set_preset', preset: preset });
    }

    handlePatternChanged(data) {
        this.state.currentPattern = data.pattern;
        this.updateCurrentPattern(data.pattern);

        // Sync entity counts from pattern recommended_entities
        const patternList = data.patterns || this.state.patterns || [];
        if (data.patterns) this.state.patterns = patternList;
        const patternMap = {};
        patternList.forEach(p => { patternMap[p.id] = p; });

        // Update per-zone pattern map and sync entity counts
        if (data.zone_patterns) {
            this.state.zonePatterns = data.zone_patterns;

            // Update allZones entity counts to match each zone's pattern
            (this.state.allZones || []).forEach(zone => {
                const zp = data.zone_patterns[zone.name];
                const patId = zp && typeof zp === 'object' ? zp.pattern : zp;
                const info = patId && patternMap[patId];
                if (info && info.recommended_entities) {
                    zone.entity_count = info.recommended_entities;
                }
            });

            this.app.zones.syncBitmapStateFromZonePatterns();
            this.app.zones.renderStageZoneList();
            this.app.zones.renderZoneChips();
        }

        // Update current zone's entity count slider
        const currentPatInfo = patternMap[data.pattern];
        if (currentPatInfo && currentPatInfo.recommended_entities) {
            this.state.zone.entityCount = currentPatInfo.recommended_entities;
            this.app.ui.setSliderValue('zone-entity-count', 'val-entity-count',
                currentPatInfo.recommended_entities, v => `${v}`);
        }

        // Highlight based on selected zones
        if (this.state.selectedZones.size > 0) {
            this.updatePatternHighlightForZones();
        } else {
            this.highlightCurrentPattern(data.pattern);
        }

        // Sync bitmap section highlight for selected zones in bitmap mode
        if (data.zone_patterns) {
            const selected = Array.from(this.state.selectedZones);
            if (selected.length > 0) {
                const zpEntry = data.zone_patterns[selected[0]];
                const patId = zpEntry && typeof zpEntry === 'object' ? zpEntry.pattern : zpEntry;
                if (patId && patId.startsWith('bmp_')) {
                    this.state.bitmap.activePattern = patId;
                    this.app.bitmap.highlightPattern(patId);
                }
            }
        }

        // Show transition status if transitioning
        if (data.transitioning && this.elements.transitionStatus) {
            this.elements.transitionStatus.classList.remove('hidden');
            if (data.transition_duration) {
                setTimeout(() => {
                    if (this.elements.transitionStatus) {
                        this.elements.transitionStatus.classList.add('hidden');
                    }
                }, data.transition_duration * 1000);
            }
        } else if (this.elements.transitionStatus) {
            this.elements.transitionStatus.classList.add('hidden');
        }
    }

    handlePresetChanged(data) {
        this.state.currentPreset = data.preset;
        this.updateCurrentPreset(data.preset);
        this.highlightActivePreset(data.preset);

        if (data.settings) {
            this.app.audio.syncControlsFromSettings(data.settings);
        }
    }

    handleStateSnapshot(data) {
        if (data.pattern) {
            this.state.currentPattern = data.pattern;
            this.updateCurrentPattern(data.pattern);
            this.highlightCurrentPattern(data.pattern);
        }

        if (data.preset) {
            this.state.currentPreset = data.preset;
            this.updateCurrentPreset(data.preset);
            this.highlightActivePreset(data.preset);
        }

        if (data.parameters) {
            this.app.audio.syncControlsFromSettings(data.parameters);
        }
    }

    // === UI Updates ===

    updateCurrentPattern(pattern) {
        this.elements.currentPattern.textContent = pattern || '--';
        this.highlightCurrentPattern(pattern);
    }

    updatePatternDisplay() {
        this.renderPatternGrid();
        this.updateCurrentPattern(this.state.currentPattern);
    }

    updateCurrentPreset(preset) {
        this.elements.currentPreset.textContent = preset || '--';
    }

    renderPatternGrid() {
        const grid = this.elements.patternGrid;
        if (!grid) return;

        while (grid.firstChild) {
            grid.removeChild(grid.firstChild);
        }

        const rankedPatterns = filterAndRankPatterns(this.state.patterns, {
            query: this.searchQuery,
            favoriteIds: this.favoriteIds,
            recentIds: this.recentIds,
        });

        if (rankedPatterns.length === 0) {
            const empty = document.createElement('p');
            empty.className = 'pattern-library-empty';
            empty.textContent = 'No patterns match this search.';
            grid.appendChild(empty);
            return;
        }

        const groupGrid = document.createElement('div');
        groupGrid.className = 'pattern-category-grid pattern-library-grid';

        rankedPatterns.forEach(pattern => {
                const item = document.createElement('div');
                item.className = 'pattern-library-item';

                const btn = document.createElement('button');
                btn.className = 'pattern-btn';
                btn.dataset.pattern = pattern.id;
                btn.dataset.requiresConnection = '';
                btn.title = [pattern.description, pattern.category].filter(Boolean).join(' · ');
                btn.setAttribute('aria-label', `Launch ${pattern.name}`);

                const name = document.createElement('span');
                name.className = 'pattern-name';
                name.textContent = pattern.name;
                btn.appendChild(name);

                if (pattern.category) {
                    const category = document.createElement('span');
                    category.className = 'pattern-category';
                    category.textContent = pattern.category;
                    btn.appendChild(category);
                }

                if (pattern.id === this.state.currentPattern) {
                    btn.classList.add('active');
                    btn.setAttribute('aria-current', 'true');
                }

                btn.addEventListener('click', () => this.setPattern(pattern.id));
                item.appendChild(btn);

                const favorite = document.createElement('button');
                const isFavorite = this.favoriteIds.includes(pattern.id);
                favorite.className = 'pattern-favorite';
                favorite.dataset.patternFavorite = pattern.id;
                favorite.setAttribute('aria-label', `${isFavorite ? 'Remove' : 'Add'} ${pattern.name} ${isFavorite ? 'from' : 'to'} favorites`);
                favorite.setAttribute('aria-pressed', String(isFavorite));
                favorite.title = isFavorite ? 'Remove favorite' : 'Add favorite';
                favorite.textContent = isFavorite ? '★' : '☆';
                favorite.addEventListener('click', (event) => {
                    event.stopPropagation();
                    this.toggleFavorite(pattern.id);
                });
                item.appendChild(favorite);
                groupGrid.appendChild(item);
        });

        grid.appendChild(groupGrid);
        this.app.ui?.applyControlState?.();
    }

    highlightCurrentPattern(patternId = this.state.currentPattern) {
        document.querySelectorAll('.pattern-btn').forEach(btn => {
            const isActive = btn.dataset.pattern === patternId;
            btn.classList.toggle('active', isActive);
            if (isActive) {
                btn.setAttribute('aria-current', 'true');
                btn.classList.remove('just-selected');
                void btn.offsetWidth;
                btn.classList.add('just-selected');
                setTimeout(() => btn.classList.remove('just-selected'), 400);
            } else {
                btn.removeAttribute('aria-current');
            }
        });
    }

    highlightActivePattern(patternId) {
        this.highlightCurrentPattern(patternId);
    }

    highlightActivePreset(preset) {
        this.elements.presetButtons.forEach(btn => {
            const isActive = btn.dataset.preset === preset;
            btn.classList.toggle('active', isActive);
            if (isActive) {
                btn.classList.remove('just-selected');
                void btn.offsetWidth;
                btn.classList.add('just-selected');
                setTimeout(() => btn.classList.remove('just-selected'), 400);
            }
        });
    }

    updatePatternHighlightForZones() {
        const selected = Array.from(this.state.selectedZones);
        if (selected.length === 0) {
            this.highlightCurrentPattern(this.state.currentPattern);
            return;
        }

        const patternsInUse = new Set();
        selected.forEach(zn => {
            const zp = this.state.zonePatterns[zn];
            const patId = zp && typeof zp === 'object' ? zp.pattern : zp;
            if (patId) patternsInUse.add(patId);
        });

        const allSame = patternsInUse.size === 1;

        document.querySelectorAll('.pattern-btn').forEach(btn => {
            const patternId = btn.dataset.pattern;
            btn.classList.remove('active', 'partial');
            if (patternId === this.state.currentPattern) {
                btn.setAttribute('aria-current', 'true');
            } else {
                btn.removeAttribute('aria-current');
            }

            if (patternsInUse.has(patternId)) {
                if (allSame) {
                    btn.classList.add('active');
                } else {
                    btn.classList.add('partial');
                }
            }
        });
    }
}
