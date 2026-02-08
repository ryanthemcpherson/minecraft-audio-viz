package com.audioviz.decorators;

import com.audioviz.AudioVizPlugin;
import com.audioviz.decorators.impl.DJTransitionDecorator;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages all stage decorator instances. Orchestrates lifecycle (activate/deactivate),
 * runs the shared audio-reactive tick loop, and bridges DJ info to decorators.
 */
public class StageDecoratorManager {

    private final AudioVizPlugin plugin;
    private final Logger logger;

    // Stage name -> list of active decorators
    private final Map<String, List<StageDecorator>> stageDecorators;

    // Audio and DJ state (updated from WebSocket messages)
    private volatile AudioState currentAudioState;
    private volatile DJInfo currentDJInfo = DJInfo.none();
    private DJInfo previousDJInfo = DJInfo.none();

    // Transition control
    private volatile boolean transitionInProgress = false;

    // Tick loop
    private BukkitTask tickTask;

    public StageDecoratorManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.stageDecorators = new ConcurrentHashMap<>();
    }

    /**
     * Start the decorator tick loop (every 2 ticks = 10 FPS).
     */
    public void start() {
        tickTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin, this::renderTick, 2L, 2L
        );
        logger.info("StageDecoratorManager started");
    }

    /**
     * Stop the tick loop and clean up all decorators.
     */
    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }

        // Deactivate all
        for (Map.Entry<String, List<StageDecorator>> entry : stageDecorators.entrySet()) {
            for (StageDecorator decorator : entry.getValue()) {
                try {
                    decorator.onDeactivate();
                } catch (Exception e) {
                    logger.warning("Error deactivating decorator " + decorator.getId() + ": " + e.getMessage());
                }
            }
        }
        stageDecorators.clear();

        logger.info("StageDecoratorManager stopped");
    }

    // ========== Lifecycle ==========

    /**
     * Activate decorators for a stage based on its decorator configs.
     * Called from StageManager.activateStage().
     */
    public void activateDecorators(Stage stage) {
        String stageName = stage.getName().toLowerCase();

        // Deactivate existing decorators for this stage first (safety)
        deactivateDecorators(stage);

        List<StageDecorator> decorators = new ArrayList<>();
        Map<String, DecoratorConfig> configs = stage.getDecoratorConfigs();

        for (DecoratorType type : DecoratorType.values()) {
            DecoratorConfig config = configs.get(type.getId());

            // Create decorator if config exists and is enabled, or if no config exists (use defaults)
            if (config == null) {
                // No config for this type — skip (decorators are opt-in)
                continue;
            }

            if (!config.isEnabled()) {
                continue;
            }

            try {
                StageDecorator decorator = type.create(stage, plugin);
                decorator.setConfig(config);
                decorator.setEnabled(true);
                decorator.onActivate();
                decorators.add(decorator);
                logger.info("Activated decorator '" + type.getDisplayName() + "' for stage '" + stage.getName() + "'");
            } catch (Exception e) {
                logger.warning("Failed to activate decorator '" + type.getId() + "' for stage '"
                    + stage.getName() + "': " + e.getMessage());
            }
        }

        if (!decorators.isEmpty()) {
            stageDecorators.put(stageName, decorators);
        }
    }

    /**
     * Deactivate all decorators for a stage.
     * Called from StageManager.deactivateStage().
     */
    public void deactivateDecorators(Stage stage) {
        String stageName = stage.getName().toLowerCase();
        List<StageDecorator> decorators = stageDecorators.remove(stageName);
        if (decorators == null) return;

        for (StageDecorator decorator : decorators) {
            try {
                decorator.onDeactivate();
                logger.info("Deactivated decorator '" + decorator.getDisplayName() + "' for stage '" + stage.getName() + "'");
            } catch (Exception e) {
                logger.warning("Error deactivating decorator " + decorator.getId() + ": " + e.getMessage());
            }
        }
    }

    // ========== Audio & DJ State ==========

    /**
     * Update the current audio state. Called from MessageHandler after batch_update processing.
     */
    public void updateAudioState(AudioState state) {
        this.currentAudioState = state;
    }

    /**
     * Update DJ info. Called from MessageHandler on dj_info messages.
     * Detects DJ changes and triggers transition decorators.
     */
    public void updateDJInfo(DJInfo info) {
        this.previousDJInfo = this.currentDJInfo;
        this.currentDJInfo = info;

        // Check for DJ change
        if (info.isDifferentDJ(previousDJInfo) && info.isPresent()) {
            onDJChanged(previousDJInfo, info);
        }
    }

    /**
     * Called when the active DJ changes. Triggers transition effects.
     */
    private void onDJChanged(DJInfo oldDJ, DJInfo newDJ) {
        logger.info("DJ changed: '" + oldDJ.djName() + "' -> '" + newDJ.djName() + "'");

        // Find and trigger transition decorators across all active stages
        for (List<StageDecorator> decorators : stageDecorators.values()) {
            for (StageDecorator decorator : decorators) {
                if (decorator instanceof DJTransitionDecorator transition && decorator.isEnabled()) {
                    transition.triggerTransition(oldDJ, newDJ, this);
                }
            }
        }
    }

    // ========== Tick Loop ==========

    /**
     * Main render tick — updates all active decorators with current audio state.
     */
    private void renderTick() {
        AudioState audio = currentAudioState;
        DJInfo dj = currentDJInfo;
        if (audio == null) audio = AudioState.silent();

        for (Map.Entry<String, List<StageDecorator>> entry : stageDecorators.entrySet()) {
            for (StageDecorator decorator : entry.getValue()) {
                if (!decorator.isEnabled()) continue;

                // Skip normal ticks during transitions (transition decorator handles itself)
                if (transitionInProgress && !(decorator instanceof DJTransitionDecorator)) {
                    continue;
                }

                try {
                    decorator.incrementTick();
                    decorator.tick(audio, dj);
                } catch (Exception e) {
                    logger.warning("Decorator tick error '" + decorator.getId() + "': " + e.getMessage());
                }
            }
        }
    }

    // ========== Transition Control ==========

    public void setTransitionInProgress(boolean inProgress) {
        this.transitionInProgress = inProgress;
    }

    public boolean isTransitionInProgress() {
        return transitionInProgress;
    }

    // ========== Queries ==========

    /**
     * Get all decorators for a stage.
     */
    public List<StageDecorator> getDecorators(String stageName) {
        return stageDecorators.getOrDefault(stageName.toLowerCase(), Collections.emptyList());
    }

    /**
     * Check if any decorator is active for a stage.
     */
    public boolean hasActiveDecorators(String stageName) {
        return stageDecorators.containsKey(stageName.toLowerCase());
    }

    /**
     * Get current DJ info.
     */
    public DJInfo getCurrentDJInfo() {
        return currentDJInfo;
    }
}
