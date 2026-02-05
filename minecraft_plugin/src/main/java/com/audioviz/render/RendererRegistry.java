package com.audioviz.render;

import com.audioviz.AudioVizPlugin;
import com.google.gson.JsonObject;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for renderer backend selection, capabilities, and per-zone settings.
 *
 * This is intentionally lightweight in the first phase. It provides stable
 * control-plane behavior while renderer implementations are migrated behind a
 * common backend interface.
 */
public class RendererRegistry {

    private final AudioVizPlugin plugin;

    private final Set<RendererBackendType> supportedBackends = EnumSet.of(
        RendererBackendType.DISPLAY_ENTITIES,
        RendererBackendType.PARTICLES
    );

    // Backends that are selectable but not considered production-ready.
    private final Set<RendererBackendType> experimentalBackends = EnumSet.noneOf(RendererBackendType.class);

    private final Map<String, RendererBackendType> activeBackendByZone = new ConcurrentHashMap<>();
    private final Map<String, RendererBackendType> fallbackBackendByZone = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> hologramConfigByZone = new ConcurrentHashMap<>();

    private boolean hologramProviderAvailable = false;
    private String hologramProviderName = "";
    private boolean hologramBackendImplemented = false;

    public RendererRegistry(AudioVizPlugin plugin) {
        this.plugin = plugin;
        detectOptionalBackends();
    }

    private void detectOptionalBackends() {
        // Provider probing is best-effort. We only mark availability if one is present.
        String[] providerCandidates = new String[] {
            "minecraft-hologram",
            "MinecraftHologram",
            "HolographicDisplays",
            "DecentHolograms"
        };

        for (String candidate : providerCandidates) {
            Plugin provider = plugin.getServer().getPluginManager().getPlugin(candidate);
            if (provider != null && provider.isEnabled()) {
                hologramProviderAvailable = true;
                hologramProviderName = provider.getName();
                break;
            }
        }

        if (hologramProviderAvailable) {
            supportedBackends.add(RendererBackendType.HOLOGRAM);
            experimentalBackends.add(RendererBackendType.HOLOGRAM);
            plugin.getLogger().info(
                "Detected hologram provider '" + hologramProviderName + "'. Hologram backend available (experimental)."
            );
        }
    }

    private String normalizeZone(String zoneName) {
        return zoneName.toLowerCase();
    }

    public boolean isBackendSupported(RendererBackendType backendType) {
        return backendType != null && supportedBackends.contains(backendType);
    }

    public boolean isBackendImplemented(RendererBackendType backendType) {
        if (backendType == RendererBackendType.HOLOGRAM) {
            return hologramProviderAvailable && hologramBackendImplemented;
        }
        return backendType == RendererBackendType.DISPLAY_ENTITIES
            || backendType == RendererBackendType.PARTICLES;
    }

    public void setZoneBackends(String zoneName, RendererBackendType active, RendererBackendType fallback) {
        String zoneKey = normalizeZone(zoneName);
        RendererBackendType safeActive = active == null ? RendererBackendType.DISPLAY_ENTITIES : active;
        RendererBackendType safeFallback = fallback == null ? RendererBackendType.DISPLAY_ENTITIES : fallback;

        if (!isBackendSupported(safeActive)) {
            safeActive = RendererBackendType.DISPLAY_ENTITIES;
        }
        if (!isBackendSupported(safeFallback)) {
            safeFallback = RendererBackendType.DISPLAY_ENTITIES;
        }

        activeBackendByZone.put(zoneKey, safeActive);
        fallbackBackendByZone.put(zoneKey, safeFallback);
    }

    public RendererBackendType getActiveBackend(String zoneName) {
        return activeBackendByZone.getOrDefault(
            normalizeZone(zoneName),
            RendererBackendType.DISPLAY_ENTITIES
        );
    }

    public RendererBackendType getFallbackBackend(String zoneName) {
        return fallbackBackendByZone.getOrDefault(
            normalizeZone(zoneName),
            RendererBackendType.DISPLAY_ENTITIES
        );
    }

    public RendererBackendType getEffectiveBackend(String zoneName) {
        RendererBackendType active = getActiveBackend(zoneName);
        if (isBackendSupported(active) && isBackendImplemented(active)) {
            return active;
        }

        RendererBackendType fallback = getFallbackBackend(zoneName);
        if (isBackendSupported(fallback) && isBackendImplemented(fallback)) {
            return fallback;
        }

        return RendererBackendType.DISPLAY_ENTITIES;
    }

    public void setHologramConfig(String zoneName, JsonObject config) {
        if (config == null) {
            return;
        }
        hologramConfigByZone.put(normalizeZone(zoneName), config.deepCopy());
    }

    public JsonObject getHologramConfig(String zoneName) {
        JsonObject config = hologramConfigByZone.get(normalizeZone(zoneName));
        return config == null ? new JsonObject() : config.deepCopy();
    }

    public void removeZone(String zoneName) {
        String zoneKey = normalizeZone(zoneName);
        activeBackendByZone.remove(zoneKey);
        fallbackBackendByZone.remove(zoneKey);
        hologramConfigByZone.remove(zoneKey);
    }

    public void clearAll() {
        activeBackendByZone.clear();
        fallbackBackendByZone.clear();
        hologramConfigByZone.clear();
    }

    public List<String> getSupportedBackendKeys() {
        List<String> keys = new ArrayList<>();
        for (RendererBackendType backendType : supportedBackends) {
            keys.add(backendType.key());
        }
        return keys;
    }

    public List<String> getExperimentalBackendKeys() {
        List<String> keys = new ArrayList<>();
        for (RendererBackendType backendType : experimentalBackends) {
            keys.add(backendType.key());
        }
        return keys;
    }

    public boolean isHologramProviderAvailable() {
        return hologramProviderAvailable;
    }

    public String getHologramProviderName() {
        return hologramProviderName;
    }

    public boolean isHologramBackendImplemented() {
        return hologramBackendImplemented;
    }

    /**
     * Enables the backend execution path once an adapter implementation exists.
     */
    public void setHologramBackendImplemented(boolean implemented) {
        this.hologramBackendImplemented = implemented;
    }
}
