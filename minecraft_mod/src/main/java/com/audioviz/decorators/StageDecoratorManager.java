package com.audioviz.decorators;

import com.audioviz.AudioVizMod;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.audioviz.decorators.impl.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all stage decorator instances.
 *
 * <p>Ported from Paper: BukkitTask → tick-based (called from AudioVizMod),
 * AudioVizPlugin → AudioVizMod, plugin.getLogger() → SLF4J.
 */
public class StageDecoratorManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");

    private final AudioVizMod mod;
    private final Map<String, List<StageDecorator>> stageDecorators;
    private final Map<String, DecoratorFactory> factories = new HashMap<>();

    private volatile AudioState currentAudioState;
    private volatile DJInfo currentDJInfo = DJInfo.none();
    private volatile BannerConfig currentBannerConfig;
    private volatile boolean transitionInProgress = false;
    private int tickCounter = 0;

    @FunctionalInterface
    public interface DecoratorFactory {
        StageDecorator create(String id, Stage stage, DecoratorConfig config, AudioVizMod mod);
    }

    public StageDecoratorManager(AudioVizMod mod) {
        this.mod = mod;
        this.stageDecorators = new ConcurrentHashMap<>();
        registerBuiltInFactories();
    }

    /**
     * Register all built-in decorator factories.
     */
    private void registerBuiltInFactories() {
        registerFactory("billboard", (id, stage, config, m) ->
            new DJBillboardDecorator(stage, m));
        registerFactory("text_fx", (id, stage, config, m) ->
            new BeatTextFXDecorator(stage, m));
        registerFactory("spotlight", (id, stage, config, m) ->
            new SpotlightDecorator(stage, m));
        registerFactory("floor_tiles", (id, stage, config, m) ->
            new FloorTileDecorator(stage, m));
        registerFactory("crowd", (id, stage, config, m) ->
            new CrowdInteractionDecorator(stage, m));
        registerFactory("transition", (id, stage, config, m) ->
            new DJTransitionDecorator(stage, m));
        registerFactory("banner", (id, stage, config, m) ->
            new DJBannerDecorator(stage, m));
    }

    /**
     * Register a factory for a decorator type. Call during initialization
     * to make decorator types available for stage activation.
     */
    public void registerFactory(String type, DecoratorFactory factory) {
        factories.put(type.toLowerCase(), factory);
        LOGGER.info("Registered decorator factory: {}", type);
    }

    /**
     * Tick the decorator manager. Called every server tick from AudioVizMod.
     * Decorators run at half rate (every 2 ticks = 10 FPS).
     */
    public void tick() {
        tickCounter++;
        if (tickCounter % 2 != 0) return; // 10 FPS

        AudioState audio = currentAudioState;
        DJInfo dj = currentDJInfo;
        if (audio == null) audio = AudioState.silent();

        for (Map.Entry<String, List<StageDecorator>> entry : stageDecorators.entrySet()) {
            for (StageDecorator decorator : entry.getValue()) {
                if (!decorator.isEnabled()) continue;
                try {
                    decorator.incrementTick();
                    decorator.tick(audio, dj);
                } catch (Exception e) {
                    LOGGER.warn("Decorator tick error '{}': {}", decorator.getId(), e.getMessage());
                }
            }
        }
    }

    // ========== Lifecycle ==========

    public void activateDecorators(Stage stage) {
        String stageName = stage.getName().toLowerCase();
        deactivateDecorators(stage);

        List<StageDecorator> decorators = new ArrayList<>();
        Map<String, DecoratorConfig> configs = stage.getDecoratorConfigs();

        for (Map.Entry<String, DecoratorConfig> entry : configs.entrySet()) {
            DecoratorConfig config = entry.getValue();
            if (!config.isEnabled()) continue;

            String type = config.getString("type", entry.getKey());
            DecoratorFactory factory = factories.get(type.toLowerCase());
            if (factory != null) {
                try {
                    StageDecorator decorator = factory.create(entry.getKey(), stage, config, mod);
                    decorator.setConfig(config);
                    decorator.onActivate();
                    decorators.add(decorator);
                    LOGGER.info("Activated decorator '{}' (type={}) for stage '{}'",
                        entry.getKey(), type, stage.getName());
                } catch (Exception e) {
                    LOGGER.warn("Failed to create decorator '{}' for stage '{}': {}",
                        entry.getKey(), stage.getName(), e.getMessage());
                }
            } else {
                LOGGER.warn("No factory registered for decorator type '{}' in stage '{}'",
                    type, stage.getName());
            }
        }

        if (!decorators.isEmpty()) {
            stageDecorators.put(stageName, decorators);
        }
    }

    public void deactivateDecorators(Stage stage) {
        String stageName = stage.getName().toLowerCase();
        List<StageDecorator> decorators = stageDecorators.remove(stageName);
        if (decorators == null) return;

        for (StageDecorator decorator : decorators) {
            try {
                decorator.onDeactivate();
                LOGGER.info("Deactivated decorator '{}' for stage '{}'",
                    decorator.getDisplayName(), stage.getName());
            } catch (Exception e) {
                LOGGER.warn("Error deactivating decorator {}: {}", decorator.getId(), e.getMessage());
            }
        }
    }

    // ========== State Updates ==========

    public void updateAudioState(AudioState state) {
        this.currentAudioState = state;
    }

    public void updateDJInfo(DJInfo info) {
        this.currentDJInfo = info;
    }

    // ========== Queries ==========

    public List<StageDecorator> getDecorators(String stageName) {
        return stageDecorators.getOrDefault(stageName.toLowerCase(), Collections.emptyList());
    }

    public boolean hasActiveDecorators(String stageName) {
        return stageDecorators.containsKey(stageName.toLowerCase());
    }

    public DJInfo getCurrentDJInfo() { return currentDJInfo; }

    public BannerConfig getCurrentBannerConfig() { return currentBannerConfig; }
    public void setCurrentBannerConfig(BannerConfig config) { this.currentBannerConfig = config; }

    public boolean isTransitionInProgress() { return transitionInProgress; }
    public void setTransitionInProgress(boolean inProgress) { this.transitionInProgress = inProgress; }

    public void shutdown() {
        for (Map.Entry<String, List<StageDecorator>> entry : stageDecorators.entrySet()) {
            for (StageDecorator decorator : entry.getValue()) {
                try { decorator.onDeactivate(); } catch (Exception ignored) {}
            }
        }
        stageDecorators.clear();
        LOGGER.info("StageDecoratorManager stopped");
    }
}
