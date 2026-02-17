/**
 * BlockTextureManager — Procedural 16x16 Minecraft-style block textures
 * Generates pixel-art canvas textures with NearestFilter for authentic look.
 * Caches textures and materials for reuse across all blocks of the same type.
 */
class BlockTextureManager {
    constructor() {
        this._textureCache = new Map();  // "blockName:face" → THREE.Texture
        this._materialCache = new Map(); // "blockName" → MeshStandardMaterial (entity, emissive)
        this._envMaterialCache = new Map(); // "blockName:face" → MeshStandardMaterial (environment, non-emissive)

        // Dominant colors for emissive tinting (used by entity materials)
        this._emissiveColors = {
            DIAMOND_BLOCK:   0x2cb5a5,
            EMERALD_BLOCK:   0x17ab4a,
            GOLD_BLOCK:      0xf5c842,
            IRON_BLOCK:      0xd0d0d0,
            REDSTONE_BLOCK:  0xa01010,
            LAPIS_BLOCK:     0x1a3c8a,
            COPPER_BLOCK:    0xc06030,
            AMETHYST_BLOCK:  0x7b4baa,
            GLOWSTONE:       0xd4a13b,
            SEA_LANTERN:     0xc8dfe3,
            SHROOMLIGHT:     0xe89040,
            NETHERITE_BLOCK: 0x3c3232,
            OBSIDIAN:        0x1a0a2e,
            PRISMARINE:      0x4b8b6a,
            PURPUR_BLOCK:    0xa867a8,
            QUARTZ_BLOCK:    0xe8e0d4,
            WHITE_CONCRETE:  0xe0e0e0,
            BLACK_CONCRETE:  0x101010,
            GRASS_BLOCK:     0x5d9c3a,
            DIRT:            0x8b6745,
            STONE:           0x7f7f7f,
            OAK_LOG:         0x5c3d1e
        };
    }

    /** Generate all textures upfront */
    preload() {
        const blocks = [
            'DIAMOND_BLOCK', 'EMERALD_BLOCK', 'GOLD_BLOCK', 'IRON_BLOCK',
            'REDSTONE_BLOCK', 'LAPIS_BLOCK', 'COPPER_BLOCK', 'AMETHYST_BLOCK',
            'GLOWSTONE', 'SEA_LANTERN', 'SHROOMLIGHT', 'NETHERITE_BLOCK',
            'OBSIDIAN', 'PRISMARINE', 'PURPUR_BLOCK', 'QUARTZ_BLOCK',
            'WHITE_CONCRETE', 'BLACK_CONCRETE', 'GRASS_BLOCK', 'DIRT',
            'STONE', 'OAK_LOG'
        ];
        for (const name of blocks) {
            this.getTexture(name, 'front');
        }
    }

    /** Get a cached MeshStandardMaterial for entity blocks (has emissive) */
    getMaterial(blockName) {
        if (!blockName) return null;
        const key = blockName.toUpperCase();
        if (this._materialCache.has(key)) return this._materialCache.get(key);

        const texture = this.getTexture(key, 'front');
        if (!texture) return null;

        const emissiveColor = this._emissiveColors[key] || 0x888888;
        const mat = new THREE.MeshStandardMaterial({
            map: texture,
            emissive: new THREE.Color(emissiveColor),
            emissiveIntensity: 0.2,
            roughness: 0.4,
            metalness: 0.1
        });

        this._materialCache.set(key, mat);
        return mat;
    }

    /** Get a non-emissive material for environment/stage blocks */
    getEnvironmentMaterial(blockName, face) {
        if (!blockName) return null;
        const key = `${blockName.toUpperCase()}:${face || 'front'}`;
        if (this._envMaterialCache.has(key)) return this._envMaterialCache.get(key);

        const texture = this.getTexture(blockName, face);
        if (!texture) return null;

        const mat = new THREE.MeshStandardMaterial({
            map: texture,
            roughness: 0.85,
            metalness: 0.0
        });

        this._envMaterialCache.set(key, mat);
        return mat;
    }

    /** Get raw THREE.Texture for a block (cached) */
    getTexture(blockName, face) {
        if (!blockName) return null;
        const key = `${blockName.toUpperCase()}:${face || 'front'}`;
        if (this._textureCache.has(key)) return this._textureCache.get(key);

        const canvas = this._drawTexture(blockName.toUpperCase(), face || 'front');
        if (!canvas) return null;

        const texture = new THREE.CanvasTexture(canvas);
        texture.magFilter = THREE.NearestFilter;
        texture.minFilter = THREE.NearestFilter;
        texture.generateMipmaps = false;

        this._textureCache.set(key, texture);
        return texture;
    }

    /** Dispose all cached textures and materials */
    dispose() {
        for (const tex of this._textureCache.values()) tex.dispose();
        for (const mat of this._materialCache.values()) mat.dispose();
        for (const mat of this._envMaterialCache.values()) mat.dispose();
        this._textureCache.clear();
        this._materialCache.clear();
        this._envMaterialCache.clear();
    }

    // ── Internal drawing ──────────────────────────────────────────────

    _drawTexture(blockName, face) {
        const canvas = document.createElement('canvas');
        canvas.width = 16;
        canvas.height = 16;
        const ctx = canvas.getContext('2d');

        const drawFn = this._getDrawFunction(blockName, face);
        if (!drawFn) return null;

        drawFn(ctx);
        return canvas;
    }

    _getDrawFunction(blockName, face) {
        const recipes = {
            DIAMOND_BLOCK:   (ctx) => this._drawOreBlock(ctx, '#2cb5a5', '#1a8a7d', '#5cdbd0'),
            EMERALD_BLOCK:   (ctx) => this._drawOreBlock(ctx, '#17ab4a', '#0d7a32', '#3ddb6f'),
            GOLD_BLOCK:      (ctx) => this._drawStripedBlock(ctx, '#f5c842', '#c9a020', '#ffe070'),
            IRON_BLOCK:      (ctx) => this._drawCrossBlock(ctx, '#d0d0d0', '#a8a8a8', '#e8e8e8'),
            REDSTONE_BLOCK:  (ctx) => this._drawSpeckledBlock(ctx, '#a01010', '#801010', '#d03030'),
            LAPIS_BLOCK:     (ctx) => this._drawSpeckledBlock(ctx, '#1a3c8a', '#0e2860', '#3060c0'),
            COPPER_BLOCK:    (ctx) => this._drawOxidizedBlock(ctx, '#c06030', '#a05020', '#50a080'),
            AMETHYST_BLOCK:  (ctx) => this._drawFacetBlock(ctx, '#7b4baa', '#5a3080', '#a070d0'),
            GLOWSTONE:       (ctx) => this._drawGlowstone(ctx),
            SEA_LANTERN:     (ctx) => this._drawSeaLantern(ctx),
            SHROOMLIGHT:     (ctx) => this._drawShroomlight(ctx),
            NETHERITE_BLOCK: (ctx) => this._drawStreakBlock(ctx, '#3c3232', '#2a2222', '#504545'),
            OBSIDIAN:        (ctx) => this._drawObsidian(ctx),
            PRISMARINE:      (ctx) => this._drawPrismarine(ctx),
            PURPUR_BLOCK:    (ctx) => this._drawPurpur(ctx),
            QUARTZ_BLOCK:    (ctx) => this._drawQuartz(ctx),
            WHITE_CONCRETE:  (ctx) => this._drawConcrete(ctx, '#e0e0e0', '#d5d5d5'),
            BLACK_CONCRETE:  (ctx) => this._drawConcrete(ctx, '#101010', '#181818'),
            DIRT:            (ctx) => this._drawDirt(ctx),
            STONE:           (ctx) => this._drawStone(ctx),
            OAK_LOG:         (ctx) => this._drawOakLog(ctx, face),
        };

        // GRASS_BLOCK has different textures per face
        if (blockName === 'GRASS_BLOCK') {
            if (face === 'top') return (ctx) => this._drawGrassTop(ctx);
            if (face === 'bottom') return (ctx) => this._drawDirt(ctx);
            return (ctx) => this._drawGrassSide(ctx);
        }

        return recipes[blockName] || null;
    }

    // ── Texture drawing recipes ───────────────────────────────────────

    _fillBase(ctx, color) {
        ctx.fillStyle = color;
        ctx.fillRect(0, 0, 16, 16);
    }

    _drawOreBlock(ctx, base, dark, light) {
        this._fillBase(ctx, base);
        // Dark edges
        ctx.fillStyle = dark;
        for (let i = 0; i < 16; i++) {
            ctx.fillRect(i, 0, 1, 1);
            ctx.fillRect(0, i, 1, 1);
            ctx.fillRect(15, i, 1, 1);
            ctx.fillRect(i, 15, 1, 1);
        }
        // Cross-hatch facets
        ctx.fillStyle = light;
        for (let i = 2; i < 14; i += 3) {
            ctx.fillRect(i, i, 2, 2);
            ctx.fillRect(14 - i, i, 2, 2);
        }
        // Center highlight
        ctx.fillStyle = light;
        ctx.fillRect(6, 6, 4, 4);
        ctx.fillStyle = base;
        ctx.fillRect(7, 7, 2, 2);
    }

    _drawStripedBlock(ctx, base, dark, light) {
        this._fillBase(ctx, base);
        // Horizontal stripes
        for (let y = 0; y < 16; y += 4) {
            ctx.fillStyle = dark;
            ctx.fillRect(0, y, 16, 1);
            ctx.fillStyle = light;
            ctx.fillRect(0, y + 2, 16, 1);
        }
        // Edge darkening
        ctx.fillStyle = dark;
        ctx.fillRect(0, 0, 16, 1);
        ctx.fillRect(0, 15, 16, 1);
    }

    _drawCrossBlock(ctx, base, dark, light) {
        this._fillBase(ctx, base);
        // Cross pattern
        ctx.fillStyle = light;
        ctx.fillRect(7, 1, 2, 14);
        ctx.fillRect(1, 7, 14, 2);
        // Subtle pitting
        ctx.fillStyle = dark;
        const pits = [[3,3],[11,4],[5,11],[13,9],[2,8],[9,2],[12,13]];
        for (const [px, py] of pits) {
            ctx.fillRect(px, py, 1, 1);
        }
    }

    _drawSpeckledBlock(ctx, base, dark, light) {
        this._fillBase(ctx, base);
        // Random speckles
        const rng = this._seededRng(base.charCodeAt(1));
        for (let i = 0; i < 30; i++) {
            const x = Math.floor(rng() * 16);
            const y = Math.floor(rng() * 16);
            ctx.fillStyle = rng() > 0.5 ? light : dark;
            ctx.fillRect(x, y, 1, 1);
        }
    }

    _drawOxidizedBlock(ctx, base, dark, oxidized) {
        this._fillBase(ctx, base);
        // Base texture
        ctx.fillStyle = dark;
        for (let y = 0; y < 16; y += 4) {
            ctx.fillRect(0, y, 16, 1);
        }
        // Oxidized patches
        ctx.fillStyle = oxidized;
        ctx.fillRect(10, 2, 3, 3);
        ctx.fillRect(1, 10, 4, 3);
    }

    _drawFacetBlock(ctx, base, dark, light) {
        this._fillBase(ctx, base);
        // Crystal facet lines
        ctx.fillStyle = light;
        for (let i = 0; i < 16; i += 4) {
            ctx.fillRect(i, 0, 1, 16);
            ctx.fillRect(0, i, 16, 1);
        }
        // Dark intersections
        ctx.fillStyle = dark;
        for (let x = 0; x < 16; x += 4) {
            for (let y = 0; y < 16; y += 4) {
                ctx.fillRect(x, y, 1, 1);
            }
        }
    }

    _drawGlowstone(ctx) {
        this._fillBase(ctx, '#d4a13b');
        // Brighter center fragments
        ctx.fillStyle = '#f0c050';
        ctx.fillRect(4, 4, 8, 8);
        ctx.fillStyle = '#e0b040';
        ctx.fillRect(2, 6, 3, 4);
        ctx.fillRect(11, 5, 3, 5);
        // Dark cracks
        ctx.fillStyle = '#8a6820';
        const cracks = [[3,3],[7,2],[12,4],[5,12],[10,13],[1,8],[14,9]];
        for (const [x, y] of cracks) {
            ctx.fillRect(x, y, 1, 1);
        }
    }

    _drawSeaLantern(ctx) {
        this._fillBase(ctx, '#9cc0c8');
        // Bright center star
        ctx.fillStyle = '#d8f0f4';
        ctx.fillRect(5, 7, 6, 2);
        ctx.fillRect(7, 5, 2, 6);
        // Brighter core
        ctx.fillStyle = '#f0ffff';
        ctx.fillRect(7, 7, 2, 2);
        // Corner dimness
        ctx.fillStyle = '#7aa8b0';
        ctx.fillRect(0, 0, 3, 3);
        ctx.fillRect(13, 0, 3, 3);
        ctx.fillRect(0, 13, 3, 3);
        ctx.fillRect(13, 13, 3, 3);
    }

    _drawShroomlight(ctx) {
        this._fillBase(ctx, '#e89040');
        // Veiny pattern from center
        ctx.fillStyle = '#f0b060';
        ctx.fillRect(7, 3, 2, 10);
        ctx.fillRect(3, 7, 10, 2);
        ctx.fillRect(5, 5, 2, 2);
        ctx.fillRect(9, 9, 2, 2);
        ctx.fillRect(5, 10, 2, 2);
        ctx.fillRect(9, 4, 2, 2);
        // Dark spots
        ctx.fillStyle = '#c07030';
        ctx.fillRect(2, 2, 1, 1);
        ctx.fillRect(13, 3, 1, 1);
        ctx.fillRect(3, 13, 1, 1);
        ctx.fillRect(12, 12, 1, 1);
    }

    _drawStreakBlock(ctx, base, dark, light) {
        this._fillBase(ctx, base);
        // Subtle darker streaks
        ctx.fillStyle = dark;
        for (let y = 2; y < 16; y += 5) {
            ctx.fillRect(1, y, 14, 1);
        }
        ctx.fillStyle = light;
        ctx.fillRect(4, 5, 2, 1);
        ctx.fillRect(9, 10, 3, 1);
    }

    _drawObsidian(ctx) {
        this._fillBase(ctx, '#1a0a2e');
        // Occasional purple pixels
        const rng = this._seededRng(42);
        ctx.fillStyle = '#2d1050';
        for (let i = 0; i < 12; i++) {
            const x = Math.floor(rng() * 16);
            const y = Math.floor(rng() * 16);
            ctx.fillRect(x, y, 1, 1);
        }
        // Very subtle highlight
        ctx.fillStyle = '#301860';
        ctx.fillRect(6, 6, 1, 1);
        ctx.fillRect(10, 3, 1, 1);
    }

    _drawPrismarine(ctx) {
        this._fillBase(ctx, '#4b8b6a');
        // Organic patches
        const rng = this._seededRng(77);
        for (let i = 0; i < 25; i++) {
            const x = Math.floor(rng() * 16);
            const y = Math.floor(rng() * 16);
            ctx.fillStyle = rng() > 0.5 ? '#5aa080' : '#3a7058';
            ctx.fillRect(x, y, 1, 1);
        }
        // Lighter accent
        ctx.fillStyle = '#6ab898';
        ctx.fillRect(3, 3, 2, 2);
        ctx.fillRect(10, 9, 2, 2);
    }

    _drawPurpur(ctx) {
        this._fillBase(ctx, '#a867a8');
        // Cross pattern with lighter borders
        ctx.fillStyle = '#c080c0';
        for (let i = 0; i < 16; i += 4) {
            ctx.fillRect(i, 0, 1, 16);
            ctx.fillRect(0, i, 16, 1);
        }
        // Inner fill
        ctx.fillStyle = '#9858a0';
        for (let x = 1; x < 16; x += 4) {
            for (let y = 1; y < 16; y += 4) {
                ctx.fillRect(x, y, 3, 3);
            }
        }
    }

    _drawQuartz(ctx) {
        this._fillBase(ctx, '#e8e0d4');
        // Subtle horizontal banding
        ctx.fillStyle = '#ddd5c8';
        for (let y = 0; y < 16; y += 3) {
            ctx.fillRect(0, y, 16, 1);
        }
        ctx.fillStyle = '#f0ece4';
        ctx.fillRect(0, 5, 16, 1);
        ctx.fillRect(0, 11, 16, 1);
    }

    _drawConcrete(ctx, base, variation) {
        this._fillBase(ctx, base);
        // Very slight variation
        const rng = this._seededRng(base.charCodeAt(1) + base.charCodeAt(3));
        ctx.fillStyle = variation;
        for (let i = 0; i < 15; i++) {
            const x = Math.floor(rng() * 16);
            const y = Math.floor(rng() * 16);
            ctx.fillRect(x, y, 1, 1);
        }
    }

    _drawGrassTop(ctx) {
        this._fillBase(ctx, '#5d9c3a');
        // Random darker pixels
        const rng = this._seededRng(101);
        ctx.fillStyle = '#4a8030';
        for (let i = 0; i < 25; i++) {
            const x = Math.floor(rng() * 16);
            const y = Math.floor(rng() * 16);
            ctx.fillRect(x, y, 1, 1);
        }
        ctx.fillStyle = '#6db048';
        for (let i = 0; i < 12; i++) {
            const x = Math.floor(rng() * 16);
            const y = Math.floor(rng() * 16);
            ctx.fillRect(x, y, 1, 1);
        }
    }

    _drawGrassSide(ctx) {
        // Dirt base
        this._drawDirt(ctx);
        // Green strip on top (2px)
        ctx.fillStyle = '#5d9c3a';
        ctx.fillRect(0, 0, 16, 2);
        ctx.fillStyle = '#4a8030';
        const rng = this._seededRng(55);
        for (let x = 0; x < 16; x++) {
            if (rng() > 0.6) {
                ctx.fillRect(x, 2, 1, 1);
            }
        }
    }

    _drawDirt(ctx) {
        this._fillBase(ctx, '#8b6745');
        const rng = this._seededRng(200);
        ctx.fillStyle = '#765535';
        for (let i = 0; i < 25; i++) {
            const x = Math.floor(rng() * 16);
            const y = Math.floor(rng() * 16);
            ctx.fillRect(x, y, 1, 1);
        }
        ctx.fillStyle = '#9a7555';
        for (let i = 0; i < 15; i++) {
            const x = Math.floor(rng() * 16);
            const y = Math.floor(rng() * 16);
            ctx.fillRect(x, y, 1, 1);
        }
    }

    _drawStone(ctx) {
        this._fillBase(ctx, '#7f7f7f');
        const rng = this._seededRng(300);
        for (let i = 0; i < 30; i++) {
            const x = Math.floor(rng() * 16);
            const y = Math.floor(rng() * 16);
            ctx.fillStyle = rng() > 0.5 ? '#8a8a8a' : '#707070';
            ctx.fillRect(x, y, 1, 1);
        }
        // Subtle crack
        ctx.fillStyle = '#686868';
        ctx.fillRect(3, 5, 5, 1);
        ctx.fillRect(8, 10, 4, 1);
    }

    _drawOakLog(ctx, face) {
        if (face === 'top' || face === 'bottom') {
            // Rings pattern
            this._fillBase(ctx, '#a08050');
            ctx.fillStyle = '#806030';
            ctx.strokeStyle = '#806030';
            // Simple concentric ring approximation
            for (let r = 2; r <= 6; r += 2) {
                for (let angle = 0; angle < 16; angle++) {
                    const a = (angle / 16) * Math.PI * 2;
                    const x = Math.round(7.5 + Math.cos(a) * r);
                    const y = Math.round(7.5 + Math.sin(a) * r);
                    if (x >= 0 && x < 16 && y >= 0 && y < 16) {
                        ctx.fillRect(x, y, 1, 1);
                    }
                }
            }
            // Center
            ctx.fillStyle = '#604020';
            ctx.fillRect(7, 7, 2, 2);
        } else {
            // Bark - vertical lines
            this._fillBase(ctx, '#5c3d1e');
            ctx.fillStyle = '#4a3018';
            for (let x = 0; x < 16; x += 3) {
                ctx.fillRect(x, 0, 1, 16);
            }
            ctx.fillStyle = '#6b4c2a';
            for (let x = 1; x < 16; x += 5) {
                for (let y = 0; y < 16; y += 4) {
                    ctx.fillRect(x, y, 1, 2);
                }
            }
        }
    }

    /**
     * Get a fallback color for any Minecraft material name.
     * Used when getEnvironmentMaterial() returns null (no procedural texture available).
     * Returns a MeshStandardMaterial with an approximate color.
     */
    static getBlockColor(materialName) {
        const name = (materialName || '').toUpperCase();

        const colorPrefixes = {
            RED: 0xb02020, ORANGE: 0xd06020, YELLOW: 0xc0a020,
            LIME: 0x50b020, GREEN: 0x207020, CYAN: 0x207070,
            LIGHT_BLUE: 0x4080c0, BLUE: 0x2020b0, PURPLE: 0x7030a0,
            MAGENTA: 0xa030a0, PINK: 0xd06080, WHITE: 0xe0e0e0,
            LIGHT_GRAY: 0xa0a0a0, GRAY: 0x606060, BLACK: 0x202020,
            BROWN: 0x704020,
        };

        if (/WOOL|CARPET|CONCRETE|TERRACOTTA|GLAZED|STAINED|CANDLE|BED|BANNER|SHULKER/.test(name)) {
            for (const [prefix, color] of Object.entries(colorPrefixes)) {
                if (name.startsWith(prefix + '_')) {
                    return new THREE.MeshStandardMaterial({ color, roughness: 0.85 });
                }
            }
        }

        let color = 0x888888;

        if (/STONE|COBBLE|BRICK|ANDESITE|DIORITE|GRANITE|DEEPSLATE|TUFF|BASALT|BLACKSTONE/.test(name)) {
            color = 0x7f7f7f;
        } else if (/WOOD|PLANK|LOG|STRIPPED|FENCE|TRAPDOOR|DOOR|SIGN|BARREL|BOOKSHELF|CHEST|CRAFTING|COMPOSTER/.test(name)) {
            color = 0x8b6745;
        } else if (/SAND(?!STONE)|GRAVEL/.test(name)) {
            color = 0xc2b280;
        } else if (/SANDSTONE/.test(name)) {
            color = 0xc2b280;
        } else if (/GLASS|ICE/.test(name)) {
            color = 0xb0d0e8;
        } else if (/IRON|CHAIN|ANVIL|RAIL|HOPPER|HEAVY|LIGHT_WEIGHTED/.test(name)) {
            color = 0xd0d0d0;
        } else if (/GOLD/.test(name)) {
            color = 0xf5c842;
        } else if (/DIAMOND/.test(name)) {
            color = 0x2cb5a5;
        } else if (/EMERALD/.test(name)) {
            color = 0x17ab4a;
        } else if (/LAPIS/.test(name)) {
            color = 0x1a3c8a;
        } else if (/COPPER/.test(name)) {
            color = 0xc06030;
        } else if (/AMETHYST/.test(name)) {
            color = 0x7b4baa;
        } else if (/LEAVES|VINE|MOSS|AZALEA|FERN|GRASS(?!_BLOCK)|BUSH|LILY|SEAGRASS|KELP|DRIPLEAF/.test(name)) {
            color = 0x4a7a3a;
        } else if (/GRASS_BLOCK|PODZOL|MYCELIUM/.test(name)) {
            color = 0x5d9c3a;
        } else if (/DIRT|MUD|SOUL_SOIL|FARMLAND|ROOTED/.test(name)) {
            color = 0x8b6745;
        } else if (/WATER/.test(name)) {
            color = 0x3355aa;
        } else if (/LAVA|MAGMA/.test(name)) {
            color = 0xcc4400;
        } else if (/REDSTONE/.test(name)) {
            color = 0xa01010;
        } else if (/GLOWSTONE|LANTERN|TORCH|SHROOMLIGHT|END_ROD|FROGLIGHT|OCHRE|VERDANT|PEARLESCENT/.test(name)) {
            color = 0xd4a13b;
        } else if (/NETHERRACK|NETHER_BRICK|CRIMSON|WARPED_WART/.test(name)) {
            color = 0x6b3030;
        } else if (/WARPED/.test(name)) {
            color = 0x1a6a5a;
        } else if (/PRISMARINE/.test(name)) {
            color = 0x4b8b6a;
        } else if (/PURPUR|END_STONE/.test(name)) {
            color = 0xa867a8;
        } else if (/QUARTZ/.test(name)) {
            color = 0xe8e0d4;
        } else if (/OBSIDIAN|CRYING/.test(name)) {
            color = 0x1a0a2e;
        } else if (/SNOW|POWDER_SNOW/.test(name)) {
            color = 0xf0f0f0;
        } else if (/SPONGE/.test(name)) {
            color = 0xc0c040;
        } else if (/CLAY/.test(name)) {
            color = 0x9a9aab;
        }

        return new THREE.MeshStandardMaterial({ color, roughness: 0.85 });
    }

    /** Simple seeded RNG for deterministic patterns */
    _seededRng(seed) {
        let s = seed;
        return () => {
            s = (s * 16807 + 0) % 2147483647;
            return (s - 1) / 2147483646;
        };
    }
}
