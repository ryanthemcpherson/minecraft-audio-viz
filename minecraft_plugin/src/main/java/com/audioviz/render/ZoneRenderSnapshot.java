package com.audioviz.render;

import com.audioviz.protocol.MessageQueue;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One preallocated, exclusively owned render snapshot slot.
 *
 * <p>Writers may mutate the primitive arrays only while the slot is in the
 * {@link #WRITING} state. Publication and read transitions provide the memory
 * barriers that make those writes visible to the tick thread.</p>
 */
public final class ZoneRenderSnapshot {

    static final int FREE = 0;
    static final int WRITING = 1;
    static final int PUBLISHED = 2;
    static final int READING = 3;

    public static final byte ENTITY_VISIBLE = 1;
    public static final byte ENTITY_GLOW = 1 << 1;

    private final ZoneSnapshotMailbox owner;
    private final String zoneName;
    private final int defaultMaterialId;
    private final AtomicInteger state = new AtomicInteger(FREE);
    private final float[] x;
    private final float[] y;
    private final float[] z;
    private final float[] scale;
    private final float[] rotation;
    private final byte[] brightness;
    private final byte[] interpolationTicks;
    private final byte[] entityFlags;
    private final String[] entityIds;
    private final String[] materialNames;
    private final int[] materialIds;
    private final double[] bands = new double[RenderProtocolLimits.BAND_COUNT];

    private long connectionEpoch;
    private long dictionaryRevision;
    private long ingressOrdinal;
    private long frameSequence;
    private long sourceTimeNanos;
    private long generatedTimeNanos;
    private long receivedNanos;
    private int entityCount;
    private boolean densePool;
    private double amplitude;
    private boolean beat;
    private boolean kick;
    private double beatIntensity;
    private double bpm;
    private double tempoConfidence;
    private double beatPhase;
    private MessageQueue.MessageGuard connectionGuard;

    ZoneRenderSnapshot(
            ZoneSnapshotMailbox owner,
            String zoneName,
            int entityCapacity,
            int defaultMaterialId
    ) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.zoneName = Objects.requireNonNull(zoneName, "zoneName");
        if (entityCapacity < 1) {
            throw new IllegalArgumentException("entityCapacity must be positive");
        }
        if (defaultMaterialId < 0) {
            throw new IllegalArgumentException("defaultMaterialId must not be negative");
        }
        this.defaultMaterialId = defaultMaterialId;
        x = new float[entityCapacity];
        y = new float[entityCapacity];
        z = new float[entityCapacity];
        scale = new float[entityCapacity];
        rotation = new float[entityCapacity];
        brightness = new byte[entityCapacity];
        interpolationTicks = new byte[entityCapacity];
        entityFlags = new byte[entityCapacity];
        entityIds = new String[entityCapacity];
        materialNames = new String[entityCapacity];
        materialIds = new int[entityCapacity];
        Arrays.fill(materialIds, defaultMaterialId);
    }

    boolean tryClaim(long nextIngressOrdinal) {
        if (!state.compareAndSet(FREE, WRITING)) {
            return false;
        }
        resetForWrite(nextIngressOrdinal);
        return true;
    }

    void markPublished() {
        transition(WRITING, PUBLISHED, "publish");
    }

    void markReading() {
        transition(PUBLISHED, READING, "take for read");
    }

    void releasePublished() {
        transition(PUBLISHED, FREE, "release published");
    }

    void releaseReading() {
        transition(READING, FREE, "release read");
    }

    void releaseWriting() {
        transition(WRITING, FREE, "release write");
    }

    boolean ownedBy(ZoneSnapshotMailbox expectedOwner) {
        return owner == expectedOwner;
    }

    private void transition(int expected, int update, String operation) {
        if (!state.compareAndSet(expected, update)) {
            throw new IllegalStateException(
                    "Cannot " + operation + " snapshot in state " + state.get());
        }
    }

    private void resetForWrite(long nextIngressOrdinal) {
        ingressOrdinal = nextIngressOrdinal;
        connectionEpoch = 0;
        dictionaryRevision = 0;
        frameSequence = 0;
        sourceTimeNanos = 0;
        generatedTimeNanos = 0;
        receivedNanos = 0;
        entityCount = 0;
        densePool = false;
        amplitude = 0;
        beat = false;
        kick = false;
        beatIntensity = 0;
        bpm = 0;
        tempoConfidence = 0;
        beatPhase = 0;
        connectionGuard = null;
        Arrays.fill(bands, 0);
    }

    public String zoneName() {
        return zoneName;
    }

    public int entityCapacity() {
        return x.length;
    }

    public int defaultMaterialId() {
        return defaultMaterialId;
    }

    public float[] x() {
        return x;
    }

    public float[] y() {
        return y;
    }

    public float[] z() {
        return z;
    }

    public float[] scale() {
        return scale;
    }

    public float[] rotation() {
        return rotation;
    }

    public byte[] brightness() {
        return brightness;
    }

    public byte[] interpolationTicks() {
        return interpolationTicks;
    }

    public byte[] entityFlags() {
        return entityFlags;
    }

    public String[] entityIds() {
        return entityIds;
    }

    public String[] materialNames() {
        return materialNames;
    }

    public int[] materialIds() {
        return materialIds;
    }

    public double[] bands() {
        return bands;
    }

    public long connectionEpoch() {
        return connectionEpoch;
    }

    public void connectionEpoch(long value) {
        connectionEpoch = value;
    }

    public long dictionaryRevision() {
        return dictionaryRevision;
    }

    public void dictionaryRevision(long value) {
        dictionaryRevision = value;
    }

    public long ingressOrdinal() {
        return ingressOrdinal;
    }

    public long frameSequence() {
        return frameSequence;
    }

    public void frameSequence(long value) {
        frameSequence = value;
    }

    public long sourceTimeNanos() {
        return sourceTimeNanos;
    }

    public void sourceTimeNanos(long value) {
        sourceTimeNanos = value;
    }

    public long generatedTimeNanos() {
        return generatedTimeNanos;
    }

    public void generatedTimeNanos(long value) {
        generatedTimeNanos = value;
    }

    public long receivedNanos() {
        return receivedNanos;
    }

    public void receivedNanos(long value) {
        receivedNanos = value;
    }

    public int entityCount() {
        return entityCount;
    }

    public void entityCount(int value) {
        if (value < 0 || value > entityCapacity()) {
            throw new IllegalArgumentException("entityCount exceeds snapshot capacity");
        }
        entityCount = value;
    }

    public boolean densePool() {
        return densePool;
    }

    public void densePool(boolean value) {
        densePool = value;
    }

    public double amplitude() {
        return amplitude;
    }

    public void amplitude(double value) {
        amplitude = value;
    }

    public boolean beat() {
        return beat;
    }

    public void beat(boolean value) {
        beat = value;
    }

    public boolean kick() {
        return kick;
    }

    public void kick(boolean value) {
        kick = value;
    }

    public double beatIntensity() {
        return beatIntensity;
    }

    public void beatIntensity(double value) {
        beatIntensity = value;
    }

    public double bpm() {
        return bpm;
    }

    public void bpm(double value) {
        bpm = value;
    }

    public double tempoConfidence() {
        return tempoConfidence;
    }

    public void tempoConfidence(double value) {
        tempoConfidence = value;
    }

    public double beatPhase() {
        return beatPhase;
    }

    public void beatPhase(double value) {
        beatPhase = value;
    }

    public MessageQueue.MessageGuard connectionGuard() {
        return connectionGuard;
    }

    public void connectionGuard(MessageQueue.MessageGuard value) {
        connectionGuard = value;
    }
}
