package com.audioviz.render;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves material names (e.g. "SEA_LANTERN", "GOLD_BLOCK") to Minecraft BlockStates.
 * Thread-safe with an unbounded cache — safe because the key space (valid block names) is finite.
 */
public final class MaterialResolver {

    private static final ConcurrentHashMap<String, BlockState> CACHE = new ConcurrentHashMap<>();

    private MaterialResolver() {}

    /**
     * Resolve a material name to a BlockState.
     * Accepts formats: "SEA_LANTERN", "sea_lantern", "minecraft:sea_lantern".
     * Returns WHITE_CONCRETE for null/empty, fallback for unknown blocks.
     */
    public static BlockState resolve(String name) {
        if (name == null || name.isEmpty()) {
            return Blocks.WHITE_CONCRETE.getDefaultState();
        }
        return CACHE.computeIfAbsent(name, MaterialResolver::doResolve);
    }

    private static BlockState doResolve(String name) {
        String normalized = name.toLowerCase().replace(' ', '_');
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }
        var block = Registries.BLOCK.get(Identifier.of(normalized));
        if (block == Blocks.AIR && !"minecraft:air".equals(normalized)) {
            return Blocks.WHITE_CONCRETE.getDefaultState();
        }
        return block.getDefaultState();
    }
}
