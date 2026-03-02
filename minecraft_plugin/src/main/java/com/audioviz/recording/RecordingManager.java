package com.audioviz.recording;

import com.audioviz.AudioVizPlugin;
import com.audioviz.patterns.AudioState;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Manages recording sessions and playback of recorded audio state.
 */
public class RecordingManager {

    private final AudioVizPlugin plugin;
    private final File recordingsDir;

    // Recording state
    private Recording activeRecording = null;
    private int recordingTickCounter = 0;

    // Playback state
    private Recording playbackRecording = null;
    private int playbackTickIndex = 0;
    private BukkitTask playbackTask = null;
    private boolean replaying = false;

    private static final int TICK_RATE = 20;
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");

    public RecordingManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.recordingsDir = new File(plugin.getDataFolder(), "recordings");
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs();
        }
    }

    // ========== Recording ==========

    public boolean startRecording(String name) {
        if (activeRecording != null) return false;
        if (!SAFE_NAME.matcher(name).matches()) return false;

        // BitmapPatternManager doesn't expose active zone names publicly,
        // so we store an empty map. Zone/pattern context can be added later
        // if the manager grows an accessor.
        Map<String, String> zonePatterns = Map.of();

        activeRecording = new Recording(name, zonePatterns, TICK_RATE);
        recordingTickCounter = 0;
        plugin.getLogger().info("Started recording: " + name);
        return true;
    }

    public boolean stopRecording() {
        if (activeRecording == null) return false;

        try {
            File file = new File(recordingsDir, activeRecording.getName() + ".mcavrec");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                activeRecording.writeTo(fos);
            }
            plugin.getLogger().info("Saved recording: " + activeRecording.getName() +
                " (" + activeRecording.getDurationTicks() + " ticks)");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save recording", e);
        }

        activeRecording = null;
        return true;
    }

    /**
     * Called from BitmapPatternManager tick loop to capture a frame.
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

        File file = new File(recordingsDir, name + ".mcavrec");
        if (!file.exists()) return false;

        try (FileInputStream fis = new FileInputStream(file)) {
            playbackRecording = Recording.readFrom(fis);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load recording: " + name, e);
            return false;
        }

        playbackTickIndex = 0;
        replaying = true;

        playbackTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (playbackRecording == null || playbackTickIndex >= playbackRecording.getDurationTicks()) {
                stopPlayback();
                return;
            }

            RecordingFrame frame = playbackRecording.getFrame(playbackTickIndex);
            AudioState audio = new AudioState(
                frame.bands(), frame.amplitude(), frame.isBeat(),
                frame.beatIntensity(), frame.tempoConfidence(),
                frame.beatPhase(), playbackTickIndex);

            var bpm = plugin.getBitmapPatternManager();
            if (bpm != null) {
                bpm.updateAudioState(audio);
            }

            playbackTickIndex++;
        }, 1L, 1L);

        plugin.getLogger().info("Playing recording: " + name +
            " (" + playbackRecording.getDurationTicks() + " ticks)");
        return true;
    }

    public void stopPlayback() {
        if (playbackTask != null) {
            playbackTask.cancel();
            playbackTask = null;
        }
        playbackRecording = null;
        playbackTickIndex = 0;
        replaying = false;
    }

    // ========== File Management ==========

    public List<String> listRecordings() {
        List<String> names = new ArrayList<>();
        File[] files = recordingsDir.listFiles((dir, name) -> name.endsWith(".mcavrec"));
        if (files != null) {
            for (File f : files) {
                names.add(f.getName().replace(".mcavrec", ""));
            }
        }
        Collections.sort(names);
        return names;
    }

    public boolean deleteRecording(String name) {
        if (!SAFE_NAME.matcher(name).matches()) return false;
        File file = new File(recordingsDir, name + ".mcavrec");
        return file.exists() && file.delete();
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
        stopRecording();
        stopPlayback();
    }
}
