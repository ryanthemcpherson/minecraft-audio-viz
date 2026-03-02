package com.audioviz.sequence;

import com.audioviz.AudioVizMod;
import com.audioviz.bitmap.BitmapPatternManager;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages sequence CRUD, persistence, and active playback.
 * Fabric port: uses Gson for persistence, tick() called from AudioVizMod.
 */
public class SequenceManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final AudioVizMod mod;
    private final Map<String, Sequence> sequences = new LinkedHashMap<>();
    private final Map<String, SequencePlayer> activePlayers = new ConcurrentHashMap<>();
    private final Path sequencesFile;

    public SequenceManager(AudioVizMod mod) {
        this.mod = mod;
        this.sequencesFile = mod.getConfigDir().resolve("sequences.json");
    }

    /** Called from AudioVizMod.tick() every server tick. */
    public void tick() {
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

    private void applyStep(SequenceStep step) {
        BitmapPatternManager bpm = mod.getBitmapPatternManager();
        if (bpm == null) return;
        for (var entry : step.zonePatterns().entrySet()) {
            bpm.setPattern(entry.getKey(), entry.getValue());
        }
    }

    private void applyTransition(SequencePlayer.StepTransition transition) {
        BitmapPatternManager bpm = mod.getBitmapPatternManager();
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
        if (!Files.exists(sequencesFile)) return;
        try {
            String json = Files.readString(sequencesFile);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return;
            sequences.clear();

            for (var entry : root.entrySet()) {
                String name = entry.getKey();
                JsonObject seqObj = entry.getValue().getAsJsonObject();
                Sequence seq = new Sequence(name);

                if (seqObj.has("mode")) {
                    seq.setMode(PlaybackMode.valueOf(seqObj.get("mode").getAsString().toUpperCase()));
                }
                if (seqObj.has("default_step_duration")) {
                    seq.setDefaultStepDuration(seqObj.get("default_step_duration").getAsInt());
                }
                if (seqObj.has("default_transition")) {
                    seq.setDefaultTransition(seqObj.get("default_transition").getAsString());
                }
                if (seqObj.has("default_transition_duration")) {
                    seq.setDefaultTransitionDuration(seqObj.get("default_transition_duration").getAsInt());
                }

                if (seqObj.has("steps")) {
                    JsonArray steps = seqObj.getAsJsonArray("steps");
                    for (JsonElement stepEl : steps) {
                        JsonObject stepObj = stepEl.getAsJsonObject();
                        Map<String, String> patterns = new LinkedHashMap<>();
                        if (stepObj.has("patterns")) {
                            JsonObject patternsObj = stepObj.getAsJsonObject("patterns");
                            for (var pe : patternsObj.entrySet()) {
                                patterns.put(pe.getKey(), pe.getValue().getAsString());
                            }
                        }
                        int duration = stepObj.has("duration") ? stepObj.get("duration").getAsInt() : 0;
                        String transId = stepObj.has("transition") ? stepObj.get("transition").getAsString() : null;
                        int transDuration = stepObj.has("transition_duration") ? stepObj.get("transition_duration").getAsInt() : 0;
                        seq.addStep(new SequenceStep(patterns, duration, transId, transDuration));
                    }
                }
                sequences.put(name, seq);
            }
            LOGGER.info("Loaded {} sequences", sequences.size());
        } catch (IOException e) {
            LOGGER.error("Failed to load sequences.json", e);
        }
    }

    public void saveSequences() {
        try {
            Files.createDirectories(sequencesFile.getParent());
            JsonObject root = new JsonObject();
            for (var seq : sequences.values()) {
                JsonObject seqObj = new JsonObject();
                seqObj.addProperty("mode", seq.getMode().name());
                seqObj.addProperty("default_step_duration", seq.getDefaultStepDuration());
                seqObj.addProperty("default_transition", seq.getDefaultTransition());
                seqObj.addProperty("default_transition_duration", seq.getDefaultTransitionDuration());

                JsonArray steps = new JsonArray();
                for (var step : seq.getSteps()) {
                    JsonObject stepObj = new JsonObject();
                    JsonObject patterns = new JsonObject();
                    for (var pe : step.zonePatterns().entrySet()) {
                        patterns.addProperty(pe.getKey(), pe.getValue());
                    }
                    stepObj.add("patterns", patterns);
                    if (step.durationTicks() > 0) stepObj.addProperty("duration", step.durationTicks());
                    if (step.transitionId() != null) stepObj.addProperty("transition", step.transitionId());
                    if (step.transitionDuration() > 0) stepObj.addProperty("transition_duration", step.transitionDuration());
                    steps.add(stepObj);
                }
                seqObj.add("steps", steps);
                root.add(seq.getName(), seqObj);
            }
            Files.writeString(sequencesFile, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.error("Failed to save sequences.json", e);
        }
    }

    public void reloadSequences() {
        activePlayers.clear();
        loadSequences();
    }

    public void stop() {
        activePlayers.clear();
    }
}
