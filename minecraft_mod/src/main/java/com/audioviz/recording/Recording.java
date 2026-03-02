package com.audioviz.recording;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A complete recording: header metadata + list of audio frames.
 * Binary format: [magic][version][header][frame0][frame1]...
 */
public class Recording {

    private static final byte[] MAGIC = "MCAV".getBytes(StandardCharsets.UTF_8);
    private static final int FORMAT_VERSION = 1;

    private final String name;
    private final Map<String, String> zonePatterns;
    private final int tickRate;
    private final List<RecordingFrame> frames = new ArrayList<>();

    public Recording(String name, Map<String, String> zonePatterns, int tickRate) {
        this.name = name;
        this.zonePatterns = new LinkedHashMap<>(zonePatterns);
        this.tickRate = tickRate;
    }

    public String getName() { return name; }
    public Map<String, String> getZonePatterns() { return Collections.unmodifiableMap(zonePatterns); }
    public int getTickRate() { return tickRate; }
    public int getDurationTicks() { return frames.size(); }
    public double getDurationSeconds() { return tickRate > 0 ? (double) frames.size() / tickRate : 0; }

    public void addFrame(RecordingFrame frame) { frames.add(frame); }

    public RecordingFrame getFrame(int index) {
        if (index < 0 || index >= frames.size()) return RecordingFrame.silent(index);
        return frames.get(index);
    }

    // ========== Binary I/O ==========

    public void writeTo(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.write(MAGIC);
        dos.writeInt(FORMAT_VERSION);

        writeString(dos, name);
        dos.writeInt(tickRate);
        dos.writeInt(zonePatterns.size());
        for (var entry : zonePatterns.entrySet()) {
            writeString(dos, entry.getKey());
            writeString(dos, entry.getValue());
        }

        dos.writeInt(frames.size());
        for (var frame : frames) {
            dos.write(frame.toBytes());
        }
        dos.flush();
    }

    public static Recording readFrom(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);

        byte[] magic = new byte[4];
        dis.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Invalid recording file (bad magic)");
        }
        int version = dis.readInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported recording version: " + version);
        }

        String name = readString(dis);
        int tickRate = dis.readInt();
        int patternCount = dis.readInt();
        Map<String, String> patterns = new LinkedHashMap<>();
        for (int i = 0; i < patternCount; i++) {
            patterns.put(readString(dis), readString(dis));
        }

        int frameCount = dis.readInt();
        Recording rec = new Recording(name, patterns, tickRate);
        byte[] frameBytes = new byte[RecordingFrame.BYTE_SIZE];
        for (int i = 0; i < frameCount; i++) {
            dis.readFully(frameBytes);
            rec.addFrame(RecordingFrame.fromBytes(frameBytes));
        }
        return rec;
    }

    private static void writeString(DataOutputStream dos, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        dos.writeShort(bytes.length);
        dos.write(bytes);
    }

    private static String readString(DataInputStream dis) throws IOException {
        int len = dis.readUnsignedShort();
        byte[] bytes = new byte[len];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
