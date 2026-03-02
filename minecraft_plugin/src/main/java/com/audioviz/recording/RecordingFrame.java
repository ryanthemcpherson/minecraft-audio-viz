package com.audioviz.recording;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A single recorded tick of audio state. Binary-serializable for compact storage.
 *
 * Layout (77 bytes):
 *   5 doubles (bands) = 40 bytes
 *   1 double (amplitude) = 8 bytes
 *   1 byte (isBeat)
 *   1 double (beatIntensity) = 8 bytes
 *   1 double (tempoConfidence) = 8 bytes
 *   1 double (beatPhase) = 8 bytes
 *   1 int (tickIndex) = 4 bytes
 */
public record RecordingFrame(
    double[] bands,
    double amplitude,
    boolean isBeat,
    double beatIntensity,
    double tempoConfidence,
    double beatPhase,
    int tickIndex
) {
    public RecordingFrame {
        bands = bands != null ? bands.clone() : new double[5];
    }

    public static final int BYTE_SIZE = 5 * 8 + 8 + 1 + 8 + 8 + 8 + 4;  // 77 bytes

    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 5; i++) {
            buf.putDouble(i < bands.length ? bands[i] : 0.0);
        }
        buf.putDouble(amplitude);
        buf.put((byte) (isBeat ? 1 : 0));
        buf.putDouble(beatIntensity);
        buf.putDouble(tempoConfidence);
        buf.putDouble(beatPhase);
        buf.putInt(tickIndex);
        return buf.array();
    }

    public static RecordingFrame fromBytes(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        double[] bands = new double[5];
        for (int i = 0; i < 5; i++) {
            bands[i] = buf.getDouble();
        }
        double amplitude = buf.getDouble();
        boolean isBeat = buf.get() != 0;
        double beatIntensity = buf.getDouble();
        double tempoConfidence = buf.getDouble();
        double beatPhase = buf.getDouble();
        int tickIndex = buf.getInt();
        return new RecordingFrame(bands, amplitude, isBeat, beatIntensity, tempoConfidence, beatPhase, tickIndex);
    }

    public static RecordingFrame silent(int tickIndex) {
        return new RecordingFrame(new double[5], 0, false, 0, 0, 0, tickIndex);
    }

    public static RecordingFrame fromValues(double[] bands, double amplitude, boolean isBeat,
                                             double beatIntensity, double tempoConfidence,
                                             double beatPhase, int tickIndex) {
        return new RecordingFrame(
            bands != null ? bands.clone() : new double[5],
            amplitude, isBeat, beatIntensity, tempoConfidence, beatPhase, tickIndex);
    }
}
