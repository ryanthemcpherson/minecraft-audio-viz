/**
 * PatternManager - Pattern grid rendering, highlighting, preset management.
 */

export class PatternManager {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;
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
        const msg = { type: 'set_pattern', pattern: patternId };
        const selected = Array.from(this.state.selectedZones);
        if (selected.length > 0) {
            msg.zones = selected;
        }
        this.ws.send(msg);
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
            this.highlightActivePattern(data.pattern);
        }

        // Sync bitmap section highlight for selected zones in bitmap mode
        if (data.zone_patterns) {
            const selected = Array.from(this.state.selectedZones);
            if (selected.length > 0) {
                const zpEntry = data.zone_patterns[selected[0]];
                const patId = zpEntry && typeof zpEntry === 'object' ? zpEntry.pattern : zpEntry;
                if (patId && patId.startsWith('bmp_')) {
                    this.state.bitmap.activePattern = patId;
                    this.app.bitmap.highlightBitmapPattern(patId);
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
            this.highlightActivePattern(data.pattern);
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
        this.highlightActivePattern(pattern);
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

        while (grid.firstChild) {
            grid.removeChild(grid.firstChild);
        }

        // Group patterns by category
        const groups = {};
        this.state.patterns.forEach(pattern => {
            const cat = pattern.category || 'Other';
            if (!groups[cat]) groups[cat] = [];
            groups[cat].push(pattern);
        });

        const sortedCategories = Object.keys(groups).sort((a, b) => {
            if (a === 'Other') return 1;
            if (b === 'Other') return -1;
            return a.localeCompare(b);
        });

        sortedCategories.forEach(category => {
            const label = document.createElement('div');
            label.className = 'pattern-category-label';
            label.textContent = category;
            grid.appendChild(label);

            const groupGrid = document.createElement('div');
            groupGrid.className = 'pattern-category-grid';

            groups[category].forEach(pattern => {
                const btn = document.createElement('button');
                btn.className = 'pattern-btn';
                btn.dataset.pattern = pattern.id;
                btn.textContent = pattern.name;
                btn.title = pattern.description || '';

                if (pattern.id === this.state.currentPattern) {
                    btn.classList.add('active');
                }

                btn.addEventListener('click', () => this.setPattern(pattern.id));
                groupGrid.appendChild(btn);
            });

            grid.appendChild(groupGrid);
        });
    }

    highlightActivePattern(patternId) {
        document.querySelectorAll('.pattern-btn').forEach(btn => {
            const isActive = btn.dataset.pattern === patternId;
            btn.classList.toggle('active', isActive);
            if (isActive) {
                btn.classList.remove('just-selected');
                void btn.offsetWidth;
                btn.classList.add('just-selected');
                setTimeout(() => btn.classList.remove('just-selected'), 400);
            }
        });
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
            this.highlightActivePattern(this.state.currentPattern);
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
