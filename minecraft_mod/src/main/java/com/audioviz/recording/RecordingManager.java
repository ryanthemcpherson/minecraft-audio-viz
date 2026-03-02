package com.audioviz.recording;

import com.audioviz.AudioVizMod;
import com.audioviz.patterns.AudioState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Manages recording sessions and playback of recorded audio state.
 * Fabric port: playback via tick counter instead of BukkitTask.
 */
public class RecordingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");

    private final Path recordingsDir;

    // Recording state
    private Recording activeRecording = null;
    private int recordingTickCounter = 0;

    // Playback state
    private Recording playbackRecording = null;
    private int playbackTickIndex = 0;
    private boolean replaying = false;

    private static final int TICK_RATE = 20;
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");

    public RecordingManager(Path configDir) {
        this.recordingsDir = configDir.resolve("recordings");
        try {
            Files.createDirectories(recordingsDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create recordings directory", e);
        }
    }

    // ========== Recording ==========

    public boolean startRecording(String name) {
        if (activeRecording != null) return false;
        if (!SAFE_NAME.matcher(name).matches()) return false;

        Map<String, String> zonePatterns = Map.of();

        activeRecording = new Recording(name, zonePatterns, TICK_RATE);
        recordingTickCounter = 0;
        LOGGER.info("Started recording: {}", name);
        return true;
    }

    public boolean stopRecording() {
        if (activeRecording == null) return false;

        try {
            Path file = recordingsDir.resolve(activeRecording.getName() + ".mcavrec");
            try (OutputStream fos = Files.newOutputStream(file)) {
                activeRecording.writeTo(fos);
            }
            LOGGER.info("Saved recording: {} ({} ticks)", activeRecording.getName(),
                activeRecording.getDurationTicks());
        } catch (IOException e) {
            LOGGER.error("Failed to save recording", e);
        }

        activeRecording = null;
        return true;
    }

    /**
     * Called from AudioVizMod tick loop to capture a frame.
     */
    public void captureFrame(AudioState audio) {
        if (activeRecording == null) return;
        activeRecording.addFrame(RecordingFrame.fromValues(
            audio.getBands(), audio.getAmplitude(), audio.isBeat(),
            audio.getBeatIntensity(), audio.getTempoConfidence(),
            audio.getBeatPhase(), recordingTickCounter++));
    }

    // ========== Playback ==========

    public boolean startPlayback(String name) {
        if (replaying) return false;
        if (!SAFE_NAME.matcher(name).matches()) return false;

        Path file = recordingsDir.resolve(name + ".mcavrec");
        if (!Files.exists(file)) return false;

        try (InputStream fis = Files.newInputStream(file)) {
            playbackRecording = Recording.readFrom(fis);
        } catch (IOException e) {
            LOGGER.error("Failed to load recording: {}", name, e);
            return false;
        }

        playbackTickIndex = 0;
        replaying = true;

        LOGGER.info("Playing recording: {} ({} ticks)", name,
            playbackRecording.getDurationTicks());
        return true;
    }

    /**
     * Called from AudioVizMod tick loop during playback.
     * Returns the current playback AudioState, or null if not playing.
     */
    public AudioState tickPlayback() {
        if (!replaying || playbackRecording == null) return null;

        if (playbackTickIndex >= playbackRecording.getDurationTicks()) {
            stopPlayback();
            return null;
        }

        RecordingFrame frame = playbackRecording.getFrame(playbackTickIndex);
        playbackTickIndex++;

        return new AudioState(
            frame.bands(), frame.amplitude(), frame.isBeat(),
            frame.beatIntensity(), frame.tempoConfidence(),
            frame.beatPhase(), playbackTickIndex);
    }

    public void stopPlayback() {
        playbackRecording = null;
        playbackTickIndex = 0;
        replaying = false;
    }

    // ========== File Management ==========

    public List<String> listRecordings() {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(recordingsDir)) {
            stream.filter(p -> p.toString().endsWith(".mcavrec"))
                .forEach(p -> {
                    String fileName = p.getFileName().toString();
                    names.add(fileName.substring(0, fileName.length() - ".mcavrec".length()));
                });
        } catch (IOException e) {
            LOGGER.error("Failed to list recordings", e);
        }
        Collections.sort(names);
        return names;
    }

    public boolean deleteRecording(String name) {
        if (!SAFE_NAME.matcher(name).matches()) return false;
        Path file = recordingsDir.resolve(name + ".mcavrec");
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.error("Failed to delete recording: {}", name, e);
            return false;
        }
    }

    // ========== Accessors ==========

    public boolean isRecording() { return activeRecording != null; }
    public boolean isReplaying() { return replaying; }
    public String getActiveRecordingName() {
        return activeRecording != null ? activeRecording.getName() : null;
    }
    public String getPlaybackName() {
        return playbackRecording != null ? playbackRecording.getName() : null;
    }

    public void stop() {
        if (activeRecording != null) stopRecording();
        stopPlayback();
    }
}
