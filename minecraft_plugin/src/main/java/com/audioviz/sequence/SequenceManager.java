package com.audioviz.sequence;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bitmap.BitmapPatternManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages sequence CRUD, persistence, and active playback.
 */
public class SequenceManager {

    private final AudioVizPlugin plugin;
    private final Map<String, Sequence> sequences = new LinkedHashMap<>();
    private final Map<String, SequencePlayer> activePlayers = new ConcurrentHashMap<>();
    private final File sequencesFile;
    private BukkitTask tickTask;

    public SequenceManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.sequencesFile = new File(plugin.getDataFolder(), "sequences.yml");
    }

    public void start() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        activePlayers.clear();
    }

    // ========== Playback ==========

    public boolean startSequence(String sequenceName, String slotName) {
        Sequence seq = sequences.get(sequenceName);
        if (seq == null || seq.getSteps().isEmpty()) return false;

        var player = new SequencePlayer(seq);
        activePlayers.put(slotName, player);

        // Apply first step immediately
        SequenceStep firstStep = seq.getSteps().get(player.getCurrentStepIndex());
        applyStep(firstStep);
        return true;
    }

    public void stopSequence(String slotName) {
        activePlayers.remove(slotName);
    }

    public SequencePlayer.StepTransition skipStep(String slotName) {
        var player = activePlayers.get(slotName);
        if (player == null) return null;
        var transition = player.skip();
        if (transition != null) {
            applyTransition(transition);
        }
        if (player.isFinished()) {
            activePlayers.remove(slotName);
        }
        return transition;
    }

    public boolean isPlaying(String slotName) {
        return activePlayers.containsKey(slotName);
    }

    public int getActiveCount() {
        return activePlayers.size();
    }

    // ========== Tick ==========

    private void tick() {
        for (var entry : new ArrayList<>(activePlayers.entrySet())) {
            var player = entry.getValue();
            var transition = player.tick();
            if (transition != null) {
                applyTransition(transition);
            }
            if (player.isFinished()) {
                activePlayers.remove(entry.getKey());
            }
        }
    }

    private void applyStep(SequenceStep step) {
        BitmapPatternManager bpm = plugin.getBitmapPatternManager();
        if (bpm == null) return;
        for (var entry : step.zonePatterns().entrySet()) {
            bpm.setPattern(entry.getKey(), entry.getValue());
        }
    }

    private void applyTransition(SequencePlayer.StepTransition transition) {
        BitmapPatternManager bpm = plugin.getBitmapPatternManager();
        if (bpm == null) return;
        for (var entry : transition.zonePatterns().entrySet()) {
            bpm.setPattern(entry.getKey(), entry.getValue(),
                transition.transitionId(), transition.transitionDuration());
        }
    }

    // ========== CRUD ==========

    public void addSequence(Sequence sequence) {
        sequences.put(sequence.getName(), sequence);
    }

    public Sequence getSequence(String name) {
        return sequences.get(name);
    }

    public void removeSequence(String name) {
        sequences.remove(name);
        activePlayers.remove(name);
    }

    public Collection<String> getSequenceNames() {
        return sequences.keySet();
    }

    // ========== Persistence ==========

    public void loadSequences() {
        if (!sequencesFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(sequencesFile);
        sequences.clear();

        for (String name : config.getKeys(false)) {
            var section = config.getConfigurationSection(name);
            if (section == null) continue;

            Sequence seq = new Sequence(name);
            seq.setMode(PlaybackMode.valueOf(
                section.getString("mode", "LOOP").toUpperCase()));
            seq.setDefaultStepDuration(section.getInt("default_step_duration", 600));
            seq.setDefaultTransition(section.getString("default_transition", "crossfade"));
            seq.setDefaultTransitionDuration(section.getInt("default_transition_duration", 20));

            var stepsList = section.getMapList("steps");
            for (var stepMap : stepsList) {
                @SuppressWarnings("unchecked")
                Map<String, String> patterns = (Map<String, String>) stepMap.get("patterns");
                int duration = stepMap.containsKey("duration")
                    ? ((Number) stepMap.get("duration")).intValue() : 0;
                String transId = (String) stepMap.get("transition");
                int transDuration = stepMap.containsKey("transition_duration")
                    ? ((Number) stepMap.get("transition_duration")).intValue() : 0;
                seq.addStep(new SequenceStep(
                    patterns != null ? patterns : Map.of(),
                    duration, transId, transDuration));
            }
            sequences.put(name, seq);
        }
        plugin.getLogger().info("Loaded " + sequences.size() + " sequences");
    }

    public void saveSequences() {
        YamlConfiguration config = new YamlConfiguration();
        for (var seq : sequences.values()) {
            var section = config.createSection(seq.getName());
            section.set("mode", seq.getMode().name());
            section.set("default_step_duration", seq.getDefaultStepDuration());
            section.set("default_transition", seq.getDefaultTransition());
            section.set("default_transition_duration", seq.getDefaultTransitionDuration());

            List<Map<String, Object>> stepsList = new ArrayList<>();
            for (var step : seq.getSteps()) {
                Map<String, Object> stepMap = new LinkedHashMap<>();
                stepMap.put("patterns", new LinkedHashMap<>(step.zonePatterns()));
                if (step.durationTicks() > 0) stepMap.put("duration", step.durationTicks());
                if (step.transitionId() != null) stepMap.put("transition", step.transitionId());
                if (step.transitionDuration() > 0) stepMap.put("transition_duration", step.transitionDuration());
                stepsList.add(stepMap);
            }
            section.set("steps", stepsList);
        }
        try {
            config.save(sequencesFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save sequences", e);
        }
    }

    public void reloadSequences() {
        activePlayers.clear();
        loadSequences();
    }
}
