# Render Foundation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the plugin's JSON render FIFO with canonical, formula-driven per-zone latest-state mailboxes and durable event latches while preserving the current JSON wire contract.

**Architecture:** JSON parsing remains off-thread, but `batch_update` messages publish normalized primitive snapshots directly into per-zone bounded slot pools instead of entering the ordered control FIFO. The main tick drains render state before control work, applies it through an indexed entity-pool path, and records monotonic latency segments. Existing JSON behavior remains available and no existing handler is deleted.

**Tech Stack:** Java 21, Paper 1.21.11, Gson 2.13.2, Java-WebSocket 1.6.0, JUnit 5, Mockito, MockBukkit

**Spec:** `docs/superpowers/specs/2026-08-15-low-latency-render-pipeline-design.md`

## Global Constraints

- Bukkit and Paper entity mutations occur only on the server main thread.
- WebSocket callbacks and JSON parser workers never call Bukkit APIs.
- The mailbox holds exactly one pending state snapshot per zone.
- Beat, kick, and particle events survive state-snapshot replacement.
- Render traffic cannot grow an obsolete FIFO; control traffic remains bounded and ordered.
- Durations use `System.nanoTime()` or an injected monotonic clock; wall clock remains diagnostic only.
- Existing JSON clients and public Java methods continue to work.
- Do not modify or include the user's untracked `minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/` work.
- Do not delete the legacy batch-processing methods in this phase.
- Use `apply_patch` for source edits and make each task one conventional commit.

---

## File map

- `protocol/schemas/types/audio-state.schema.json`: canonical audio units and complete metadata.
- `protocol/schemas/messages/batch-update.schema.json`: JSON fallback contract matching binary semantics.
- `minecraft_plugin/src/main/resources/config.yml`: authoritative limits and timing policy.
- `minecraft_plugin/src/main/java/com/audioviz/render/RenderProtocolLimits.java`: named semantic and resource limits.
- `minecraft_plugin/src/main/java/com/audioviz/render/RenderTimingPolicy.java`: interpolation, beat, glow, and tick conversion policy.
- `minecraft_plugin/src/main/java/com/audioviz/render/MonotonicClock.java`: injectable nanosecond clock.
- `minecraft_plugin/src/main/java/com/audioviz/render/ZoneRenderSnapshot.java`: primitive state arrays owned by one slot.
- `minecraft_plugin/src/main/java/com/audioviz/render/ZoneSnapshotMailbox.java`: bounded slot lifecycle and latest-state publication.
- `minecraft_plugin/src/main/java/com/audioviz/render/RenderEventLatch.java`: durable beat/kick/particle accumulator.
- `minecraft_plugin/src/main/java/com/audioviz/render/DrainedRenderEvents.java`: caller-owned event drain storage.
- `minecraft_plugin/src/main/java/com/audioviz/render/RenderFrameHub.java`: zone mailbox registry and ingress ordering.
- `minecraft_plugin/src/main/java/com/audioviz/render/ZoneRenderDrain.java`: one zone's tick-owned snapshot and events.
- `minecraft_plugin/src/main/java/com/audioviz/render/JsonRenderFrameDecoder.java`: JSON-to-snapshot normalization.
- `minecraft_plugin/src/main/java/com/audioviz/render/RenderTickProcessor.java`: main-thread drain and effect application.
- `minecraft_plugin/src/main/java/com/audioviz/render/RenderTelemetry.java`: bounded counters and latency windows.
- `minecraft_plugin/src/main/java/com/audioviz/render/RenderProtocolKind.java`: JSON/SBE telemetry dimension.
- `minecraft_plugin/src/main/java/com/audioviz/render/RenderTelemetrySnapshot.java`: immutable admin metrics view.
- `minecraft_plugin/src/main/java/com/audioviz/zones/VisualizationZone.java`: cached zone basis and allocation-free coordinate write.
- `minecraft_plugin/src/main/java/com/audioviz/entities/EntityPoolManager.java`: indexed block pools and direct snapshot apply.
- `minecraft_plugin/src/main/java/com/audioviz/protocol/BeatProjectionUtil.java`: injected monotonic timing.
- `minecraft_plugin/src/main/java/com/audioviz/protocol/MessageQueue.java`: render-first tick drain and JSON publication.
- `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`: route JSON render frames into the mailbox path.
- `minecraft_plugin/src/main/java/com/audioviz/latency/LatencyTracker.java`: monotonic render-segment observations.
- Matching tests under `minecraft_plugin/src/test/java/com/audioviz/render/`, `zones/`, `entities/`, `protocol/`, `websocket/`, and `latency/`.

### Task 1: Canonical semantic limits and timing policy

**Files:**
- Modify: `protocol/schemas/types/audio-state.schema.json`
- Modify: `protocol/schemas/messages/batch-update.schema.json`
- Modify: `protocol/schemas/index.json`
- Modify: `protocol/tests/phase0-schemas.test.mjs`
- Modify: `minecraft_plugin/src/main/resources/config.yml`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderProtocolLimits.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderTimingPolicy.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/MonotonicClock.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/RenderProtocolLimitsTest.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/RenderTimingPolicyTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/protocol/InputSanitizer.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/protocol/InputSanitizerTest.java`

**Interfaces:**
- Produces: `RenderProtocolLimits.from(FileConfiguration)`, named unit/domain constants, and configured resource limits.
- Produces: `RenderTimingPolicy.from(FileConfiguration)`, `interpolationTicks()`, `glowDurationNanos()`, `beatCooldownNanos(double bpm)`, and `durationToTicks(long durationNanos, long measuredTickNanos)`.
- Produces: `MonotonicClock.nanoTime()` and `MonotonicClock.system()`.

- [ ] **Step 1: Extend protocol contract tests with exact canonical ranges**

Add assertions that `bands`, `amplitude`, `beat_intensity`, `tempo_confidence`, and `beat_phase` use `[0, 1]`; `bpm` uses `[0, 300]`; and a batch may carry `frame`, `source_time_ns`, and `generated_time_ns`:

```javascript
test("render audio values have one canonical domain", () => {
  const batch = readJson(resolve(repositoryRoot, "protocol/schemas/messages/batch-update.schema.json"));
  for (const name of ["amplitude", "beat_intensity", "tempo_confidence", "beat_phase"]) {
    assert.equal(batch.properties[name].minimum, 0);
    assert.equal(batch.properties[name].maximum, 1);
  }
  assert.deepEqual(batch.properties.bands.items, {
    type: "number",
    minimum: 0,
    maximum: 1,
  });
  assert.equal(batch.properties.bpm.maximum, 300);
  assert.equal(batch.properties.frame.minimum, 0);
});
```

- [ ] **Step 2: Run the schema test and verify the missing fields/ranges fail**

Run: `node --test protocol/tests/phase0-schemas.test.mjs`

Expected: FAIL because the current batch schema has no maxima for amplitude or beat intensity and omits tempo metadata and timestamps.

- [ ] **Step 3: Make JSON schemas complete and consistent**

Add these properties to both the shared audio state and batch message where applicable:

```json
"amplitude": { "type": "number", "minimum": 0, "maximum": 1 },
"beat_intensity": { "type": "number", "minimum": 0, "maximum": 1 },
"bpm": { "type": "number", "minimum": 0, "maximum": 300 },
"tempo_confidence": { "type": "number", "minimum": 0, "maximum": 1 },
"beat_phase": { "type": "number", "minimum": 0, "maximum": 1 },
"frame": { "type": "integer", "minimum": 0 },
"source_time_ns": { "type": "integer", "minimum": 0 },
"generated_time_ns": { "type": "integer", "minimum": 0 }
```

Keep the existing required fields for backward compatibility. Do not require the new metadata.

- [ ] **Step 4: Write failing Java policy tests**

```java
@Test
void unitDomainsAndResourceLimitsComeFromOneAuthority() {
    FileConfiguration config = new YamlConfiguration();
    config.set("performance.max_entities_per_zone", 256);
    config.set("performance.max_zones", 32);
    config.set("performance.max_particles_per_tick", 2_000);
    config.set("performance.render_decoder_threads", 2);

    RenderProtocolLimits limits = RenderProtocolLimits.from(config);

    assertEquals(5, RenderProtocolLimits.BAND_COUNT);
    assertEquals(4, limits.snapshotSlotCount());
    assertEquals(256, limits.maxEntitiesPerZone());
    assertEquals(32, limits.maxZones());
    assertEquals(2_000, limits.maxParticlesPerTick());
}

@Test
void beatAndTickDurationsAreFormulaDerived() {
    RenderTimingPolicy policy = new RenderTimingPolicy(1, 150_000_000L, 120_000_000L, 0.60);

    assertEquals(300_000_000L, policy.beatCooldownNanos(120.0));
    assertEquals(3, policy.durationToTicks(150_000_000L, 50_000_000L));
    assertEquals(4, policy.durationToTicks(151_000_000L, 50_000_000L));
}
```

- [ ] **Step 5: Run the Java tests and verify the new types are missing**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=RenderProtocolLimitsTest,RenderTimingPolicyTest test`

Expected: FAIL at test compilation because the render policy classes do not exist.

- [ ] **Step 6: Implement named limits, formulas, and configuration**

Use records and exact formulas:

```java
public record RenderProtocolLimits(
    int maxZones,
    int maxEntitiesPerZone,
    int maxParticlesPerTick,
    int decoderThreads
) {
    public static final int BAND_COUNT = 5;
    public static final double UNIT_MIN = 0.0;
    public static final double UNIT_MAX = 1.0;
    public static final float ENTITY_SCALE_MAX = 4.0f;
    public static final double BPM_MAX = 300.0;
    public static final int BRIGHTNESS_MAX = 15;
    public static final int INTERPOLATION_TICKS_MAX = 100;

    public int snapshotSlotCount() {
        return Math.addExact(decoderThreads, 2);
    }
}

public record RenderTimingPolicy(
    int interpolationTicks,
    long glowDurationNanos,
    long minimumBeatCooldownNanos,
    double beatCooldownFraction
) {
    public long beatCooldownNanos(double bpm) {
        if (!Double.isFinite(bpm) || bpm <= 0.0) return minimumBeatCooldownNanos;
        long period = Math.round(60_000_000_000.0 / bpm);
        return Math.max(minimumBeatCooldownNanos, Math.round(period * beatCooldownFraction));
    }

    public int durationToTicks(long durationNanos, long measuredTickNanos) {
        if (durationNanos <= 0) return 0;
        if (measuredTickNanos <= 0) throw new IllegalArgumentException("measuredTickNanos");
        return Math.toIntExact(Math.floorDiv(durationNanos - 1, measuredTickNanos) + 1);
    }
}

@FunctionalInterface
public interface MonotonicClock {
    long nanoTime();
    static MonotonicClock system() { return System::nanoTime; }
}
```

Add `performance.max_zones`, `performance.max_particles_per_tick`, `performance.render_decoder_threads`, `defaults.glow_duration_ms`, `defaults.beat_cooldown_min_ms`, and `defaults.beat_cooldown_fraction` to `config.yml`. Keep `defaults.interpolation_duration: 1` as the balanced default. Validate positive limits and clamp configuration only once in `from(...)`.

- [ ] **Step 7: Route `InputSanitizer` through the canonical domain constants**

Replace repeated literal bounds in coordinate, scale, band, amplitude, beat intensity, BPM, brightness, and interpolation sanitizers with `RenderProtocolLimits` constants. Preserve all existing public methods and default-value behavior.

- [ ] **Step 8: Run schema and Java policy tests**

Run: `node --test protocol/tests/phase0-schemas.test.mjs`

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=RenderProtocolLimitsTest,RenderTimingPolicyTest,InputSanitizerTest test`

Expected: PASS.

- [ ] **Step 9: Commit the canonical contract**

```powershell
git add protocol/schemas protocol/tests/phase0-schemas.test.mjs minecraft_plugin/src/main/resources/config.yml minecraft_plugin/src/main/java/com/audioviz/render minecraft_plugin/src/main/java/com/audioviz/protocol/InputSanitizer.java minecraft_plugin/src/test/java/com/audioviz/render minecraft_plugin/src/test/java/com/audioviz/protocol/InputSanitizerTest.java
git commit -m "feat: canonicalize render protocol limits"
```

### Task 2: Bounded snapshot slots and durable event latching

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/ZoneRenderSnapshot.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/ZoneSnapshotMailbox.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderParticleEvent.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderEventLatch.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/DrainedRenderEvents.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/ZoneSnapshotMailboxTest.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/RenderEventLatchTest.java`

**Interfaces:**
- Consumes: `RenderProtocolLimits.maxEntitiesPerZone()`, `maxParticlesPerTick()`, and `snapshotSlotCount()`.
- Produces: `ZoneSnapshotMailbox.tryClaim(long ingressOrdinal)`, `publish(ZoneRenderSnapshot)`, `takeLatest()`, and `releaseAfterRead(ZoneRenderSnapshot)`.
- Produces: `RenderEventLatch.latchBeat(..., MessageQueue.MessageGuard)`, `offerParticle(..., MessageGuard)`, and `drainInto(DrainedRenderEvents)`.

- [ ] **Step 1: Write failing mailbox lifecycle and ordering tests**

```java
@Test
void newerPublicationSupersedesWithoutOverwritingReadingSlot() {
    ZoneSnapshotMailbox mailbox = new ZoneSnapshotMailbox("main", 8, 0, 3);
    ZoneRenderSnapshot first = requireNonNull(mailbox.tryClaim(10));
    first.entityCount(1);
    first.x()[0] = 0.25f;
    assertTrue(mailbox.publish(first));

    ZoneRenderSnapshot reading = requireNonNull(mailbox.takeLatest());
    ZoneRenderSnapshot second = requireNonNull(mailbox.tryClaim(11));
    second.entityCount(1);
    second.x()[0] = 0.75f;
    assertTrue(mailbox.publish(second));

    assertEquals(0.25f, reading.x()[0]);
    mailbox.releaseAfterRead(reading);
    assertSame(second, mailbox.takeLatest());
}

@Test
void staleCompletionCannotReplaceNewerPublication() {
    ZoneSnapshotMailbox mailbox = new ZoneSnapshotMailbox("main", 8, 0, 4);
    ZoneRenderSnapshot slow = requireNonNull(mailbox.tryClaim(20));
    ZoneRenderSnapshot fast = requireNonNull(mailbox.tryClaim(21));
    assertTrue(mailbox.publish(fast));
    assertFalse(mailbox.publish(slow));
    assertSame(fast, mailbox.takeLatest());
}
```

- [ ] **Step 2: Write failing event durability tests**

```java
@Test
void beatAndParticlesSurviveSnapshotSupersessionAndDeduplicate() {
    RenderEventLatch latch = new RenderEventLatch(4);
    latch.latchBeat(7, true, true, 0.4, ALLOW_ALL);
    latch.latchBeat(8, true, false, 0.9, ALLOW_ALL);
    assertTrue(latch.offerParticle(
        new RenderParticleEvent(12, 1, .5f, .5f, .5f, 20), ALLOW_ALL));
    assertFalse(latch.offerParticle(
        new RenderParticleEvent(12, 1, .5f, .5f, .5f, 20), ALLOW_ALL));

    DrainedRenderEvents drained = new DrainedRenderEvents(4);
    latch.drainInto(drained);

    assertTrue(drained.beat());
    assertTrue(drained.kick());
    assertEquals(0.9, drained.beatIntensity(), 1e-9);
    assertEquals(1, drained.particleCount());
    latch.drainInto(drained);
    assertFalse(drained.beat());
    assertEquals(0, drained.particleCount());
}
```

- [ ] **Step 3: Run tests and verify missing classes fail compilation**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=ZoneSnapshotMailboxTest,RenderEventLatchTest test`

Expected: FAIL at test compilation.

- [ ] **Step 4: Implement preallocated snapshot storage and slot states**

`ZoneRenderSnapshot` owns primitive arrays allocated once at construction:

```java
public final class ZoneRenderSnapshot {
    static final int FREE = 0;
    static final int WRITING = 1;
    static final int PUBLISHED = 2;
    static final int READING = 3;

    private final AtomicInteger state = new AtomicInteger(FREE);
    private final float[] x, y, z, scale, rotation;
    private final byte[] brightness, interpolationTicks, entityFlags;
    private final String[] entityIds, materialNames;
    private final int[] materialIds;
    private final double[] bands = new double[RenderProtocolLimits.BAND_COUNT];
    private long ingressOrdinal;
    private long frameSequence;
    private long receivedNanos;
    private int entityCount;
    private boolean densePool;
    private MessageQueue.MessageGuard connectionGuard;
    // audio scalar fields use primitive members with explicit getters/setters
}
```

`ZoneSnapshotMailbox` preallocates `slotCount` snapshots in an array, claims with `FREE -> WRITING`, marks complete with `WRITING -> PUBLISHED`, and publishes through an `AtomicReference<ZoneRenderSnapshot>`. A failed compare/retry may not expose a half-written slot. `takeLatest()` uses `getAndSet(null)` and `PUBLISHED -> READING`; release uses `READING -> FREE`. Throw on illegal transitions in tests and log/drop at the external decoder boundary.

- [ ] **Step 5: Implement synchronized durable event drain**

Use one short synchronized section because events are sparse and correctness dominates:

```java
public synchronized void latchBeat(
    long eventSequence,
    boolean beat,
    boolean kick,
    double intensity,
    MessageQueue.MessageGuard guard
) {
    for (int index = 0; index < beatCount; index++) {
        if (beatSequences[index] == eventSequence && beatGuards[index] == guard) return;
    }
    if (beatCount == beatSequences.length) {
        rejectedBeatEvents++;
        return;
    }
    int slot = beatCount++;
    beatSequences[slot] = eventSequence;
    beatFlags[slot] = (byte)((beat ? 1 : 0) | (kick ? 2 : 0));
    beatIntensities[slot] = intensity;
    beatGuards[slot] = guard;
}
```

Store particles and their connection guards in fixed arrays; deduplicate event IDs within the current connection generation; reject and count overflow. Keep beat observations as bounded guarded entries so an invalidated connection cannot suppress a valid beat from another connection. `drainInto` copies into a caller-owned `DrainedRenderEvents`, resets the latch atomically, and performs no collection allocation. The tick processor executes each drained event through its stored guard.

- [ ] **Step 6: Run mailbox and latch tests repeatedly**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=ZoneSnapshotMailboxTest,RenderEventLatchTest -Dsurefire.rerunFailingTestsCount=0 test`

Expected: PASS for at least three consecutive invocations.

- [ ] **Step 7: Commit bounded render state**

```powershell
git add minecraft_plugin/src/main/java/com/audioviz/render minecraft_plugin/src/test/java/com/audioviz/render
git commit -m "feat: add bounded render mailboxes"
```

### Task 3: Render hub and JSON normalization

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderFrameHub.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/ZoneRenderDrain.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/JsonRenderFrameDecoder.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderDecodeResult.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/JsonRenderFrameDecoderTest.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/RenderFrameHubTest.java`

**Interfaces:**
- Consumes: snapshot and latch interfaces from Task 2.
- Produces: `RenderFrameHub.publishJson(JsonObject, MessageQueue.MessageGuard, long receivedNanos)`.
- Produces: `RenderFrameHub.take(String zoneName)` returning a `ZoneRenderDrain` containing snapshot and drained events.
- Produces: `RenderFrameHub.invalidateAll()` for shutdown and `removeZone(String)` for zone lifecycle safety.

- [ ] **Step 1: Write failing normalization tests**

```java
@Test
void completeAudioAndDenseEntitiesNormalizeWithoutWorldAccess() {
    JsonObject message = JsonParser.parseString("""
        {"type":"batch_update","zone":"main","frame":42,
         "bands":[0.1,0.2,0.3,0.4,0.5],"amplitude":0.7,
         "is_beat":true,"beat_intensity":0.8,"bpm":128.0,
         "tempo_confidence":0.9,"beat_phase":0.25,
         "entities":[
           {"id":"block_0","x":0.1,"y":0.2,"z":0.3,"scale":0.4,
            "rotation":450,"brightness":12,"glow":true,"interpolation":1,
            "material":"SEA_LANTERN"}]}
        """).getAsJsonObject();

    RenderDecodeResult result = decoder.decode(message, 9, 1000);

    assertTrue(result.accepted());
    ZoneRenderSnapshot snapshot = result.snapshot();
    assertTrue(snapshot.densePool());
    assertEquals(90.0f, snapshot.rotation()[0]);
    assertEquals(128.0, snapshot.bpm(), 1e-9);
    assertEquals(0.9, snapshot.tempoConfidence(), 1e-9);
    assertEquals(0.25, snapshot.beatPhase(), 1e-9);
}
```

Also test missing zone, nonexistent mailbox, excessive entity count, non-finite numeric input, sparse IDs, particle count bounds, guard rejection, and a beat in a subsequently superseded frame.

- [ ] **Step 2: Run decoder tests and verify missing classes fail**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=JsonRenderFrameDecoderTest,RenderFrameHubTest test`

Expected: FAIL at test compilation.

- [ ] **Step 3: Implement strict decode into a claimed slot**

Use one ingress ordinal from an `AtomicLong` in `RenderFrameHub`. Decode normalized coordinates only; never read `VisualizationZone` or construct Bukkit objects off-thread. Detect a dense pool only when IDs are exactly `block_0` through `block_(count-1)` in group order. Preserve arbitrary JSON IDs by storing their validated strings in the fallback arrays.

Use this result shape:

```java
public record RenderDecodeResult(Status status, ZoneRenderSnapshot snapshot, String reason) {
    public enum Status { ACCEPTED, SUPERSEDED, REJECTED }
    public boolean accepted() { return status == Status.ACCEPTED; }
}
```

Latch explicit beat/kick and particle events with the connection guard before publishing the state slot. Use `frame` as the JSON event sequence when present and ingress ordinal otherwise. If state publication is superseded, keep accepted transient events. Store the guard on the snapshot so tick apply is generation-safe.

- [ ] **Step 4: Implement hub lifecycle and guarded publication**

`publishJson` must call the existing `MessageGuard` around the final latch/publication operation:

```java
boolean valid = guard.runIfValid(() -> decoder.decodeAndPublish(
    message,
    ingressSequence.incrementAndGet(),
    receivedNanos
));
```

Register mailboxes on main-thread zone/pool creation, remove and invalidate them before zone cleanup, and invalidate all on WebSocket shutdown. A missing mailbox returns a rejection instead of creating unbounded zone state from peer input.

- [ ] **Step 5: Run normalization and hub tests**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=JsonRenderFrameDecoderTest,RenderFrameHubTest test`

Expected: PASS.

- [ ] **Step 6: Commit JSON normalization**

```powershell
git add minecraft_plugin/src/main/java/com/audioviz/render minecraft_plugin/src/test/java/com/audioviz/render
git commit -m "feat: normalize JSON render snapshots"
```

### Task 4: Cached transforms and indexed entity application

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/zones/VisualizationZone.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/zones/VisualizationZoneTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/entities/EntityPoolManager.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderApplyScratch.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/entities/DenseRenderApplyTest.java`

**Interfaces:**
- Consumes: `ZoneRenderSnapshot` and `RenderTimingPolicy`.
- Produces: `VisualizationZone.writeWorld(double x, double y, double z, Location target)`.
- Produces: `EntityPoolManager.applyRenderSnapshot(String zoneName, VisualizationZone zone, ZoneRenderSnapshot snapshot, RenderTimingPolicy policy)`; main thread only.

- [ ] **Step 1: Add failing cached-transform equivalence tests**

```java
@ParameterizedTest
@CsvSource({"0,0.2,0.3,0.4", "45,0.2,0.3,0.4", "270,1,0,1"})
void writeWorldMatchesLocalToWorld(float rotation, double x, double y, double z) {
    VisualizationZone zone = zoneAt(rotation);
    Location expected = zone.localToWorld(x, y, z);
    Location target = new Location(expected.getWorld(), 0, 0, 0);

    zone.writeWorld(x, y, z, target);

    assertEquals(expected.getX(), target.getX(), 1e-10);
    assertEquals(expected.getY(), target.getY(), 1e-10);
    assertEquals(expected.getZ(), target.getZ(), 1e-10);
}
```

Add a mutation test proving `setOrigin`, both `setSize` overloads, and `setRotation` refresh cached basis values.

- [ ] **Step 2: Add failing dense-pool apply tests**

Use mocked `BlockDisplay` instances registered in a test pool. Assert index zero maps directly to `block_0`, a hidden entity receives zero scale, material changes are skipped when unchanged, per-frame string lookup is not invoked for dense snapshots, and Bukkit work is rejected off the primary thread.

- [ ] **Step 3: Run focused tests and verify failure**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=VisualizationZoneTest,DenseRenderApplyTest test`

Expected: FAIL because `writeWorld` and `applyRenderSnapshot` do not exist.

- [ ] **Step 4: Cache the zone transform basis**

Store scaled right/up/forward basis components and refresh them in constructors and setters. Implement the hot path without trigonometry or cloning:

```java
public void writeWorld(double x, double y, double z, Location target) {
    double worldX = origin.getX() + rightX * x + forwardX * z;
    double worldY = origin.getY() + upY * y;
    double worldZ = origin.getZ() + rightZ * x + forwardZ * z;
    target.setWorld(origin.getWorld());
    target.setX(worldX);
    target.setY(worldY);
    target.setZ(worldZ);
}
```

Make `localToWorld` delegate to `writeWorld` with a newly allocated result so its existing public behavior remains unchanged.

- [ ] **Step 5: Add indexed block-pool storage**

Alongside the existing string map, maintain `Map<String, BlockDisplay[]> blockPoolIndexes`. Build the array at pool initialization using the same `block_N` indices, replace it atomically only when initialization completes, and remove it during cleanup. Existing map APIs remain untouched.

- [ ] **Step 6: Implement direct main-thread snapshot application**

Use one `RenderApplyScratch` per main-thread processor containing a reusable `Location`, `Transformation`, `Vector3f` values, and `AxisAngle4f` values. Mutate scratch values, call the Paper setters synchronously, and never schedule another task from `applyRenderSnapshot`.

For dense binary-compatible snapshots, use the block array. For sparse JSON fallback, use the existing map. Apply the configured interpolation tick value when a snapshot does not specify one. Retain the material cache, but later numeric material dictionaries will bypass string resolution.

Replace the three pool-initialization literals `setInterpolationDuration(2)` with `RenderTimingPolicy.interpolationTicks()`. Do not change decorator-specific creative interpolation values in this task.

- [ ] **Step 7: Run transform and dense-apply tests**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=VisualizationZoneTest,DenseRenderApplyTest,EntityUpdateTest test`

Expected: PASS.

- [ ] **Step 8: Commit indexed application**

```powershell
git add minecraft_plugin/src/main/java/com/audioviz/zones/VisualizationZone.java minecraft_plugin/src/main/java/com/audioviz/entities/EntityPoolManager.java minecraft_plugin/src/main/java/com/audioviz/render/RenderApplyScratch.java minecraft_plugin/src/test/java/com/audioviz/zones/VisualizationZoneTest.java minecraft_plugin/src/test/java/com/audioviz/entities/DenseRenderApplyTest.java
git commit -m "perf: apply render snapshots by pool index"
```

### Task 5: Main-tick render drain and monotonic beat effects

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderTickProcessor.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderAudioEffects.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/protocol/BeatProjectionUtil.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/protocol/BeatProjectionUtilTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/protocol/MessageQueue.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/protocol/MessageQueueTest.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/protocol/MessageQueueBackpressureTest.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/RenderTickProcessorTest.java`

**Interfaces:**
- Consumes: `RenderFrameHub`, `EntityPoolManager.applyRenderSnapshot`, `RenderTimingPolicy`, and `MonotonicClock`.
- Produces: `RenderTickProcessor.processTick(long tickStartNanos, long previousTickStartNanos)`.
- Produces: `MessageQueue.publishRender(JsonObject, MessageGuard, long receivedNanos)`.

- [ ] **Step 1: Write failing render-first and beat-latch tests**

```java
@Test
void newestStateAndSupersededBeatApplyInSameDrain() {
    publishFrame(1, 0.2f, true, 0.7);
    publishFrame(2, 0.8f, false, 0.0);

    processor.processTick(1_050_000_000L, 1_000_000_000L);

    verify(applier).apply(argThat(snapshot -> snapshot.ingressOrdinal() == 2));
    verify(beatSink).processBeat("main", BeatType.BEAT, 0.7);
    assertNull(hub.take("main").snapshot());
}
```

Add tests for measured tick duration conversion, configurable glow-off ticks, bitmap/decorator audio forwarding, and no second scheduler call.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=RenderTickProcessorTest,BeatProjectionUtilTest test`

Expected: FAIL because the tick processor and monotonic beat API do not exist.

- [ ] **Step 3: Make beat projection accept explicit monotonic time**

Add a new overload and keep the old signature delegating to it:

```java
static BeatProjection projectBeat(
    String zoneName,
    boolean explicitBeat,
    double explicitIntensity,
    double bpm,
    double confidence,
    double phase,
    Map<String, Long> lastBeatNanosByZone,
    long nowNanos,
    RenderTimingPolicy timingPolicy
)
```

Perform cooldown math in nanoseconds. Tests inject exact time and never sleep.

- [ ] **Step 4: Extract audio/effect application from JSON parsing**

`RenderAudioEffects` receives normalized primitive audio plus drained event state on the main thread. It triggers beat management, glow, dynamic brightness, particle visualization, bitmap patterns, and decorators only through each snapshot/event's connection guard. Compute brightness with named configured endpoints:

```java
int brightness = (int)Math.round(minBrightness + amplitude * (maxBrightness - minBrightness));
```

Schedule glow off using `durationToTicks(glowDurationNanos, measuredTickNanos)`. No hardcoded three-tick delay remains in the render path.

- [ ] **Step 5: Integrate render drain at the beginning of `MessageQueue.processTick`**

Before polling the ordered message queue, call the render processor. Route public `enqueue(JsonObject, guard)` calls containing `batch_update` to `publishRender` instead of the control FIFO. Keep legacy private batch candidate methods present but unreachable from active WebSocket/public enqueue paths in this phase.

- [ ] **Step 6: Run tick, queue, and beat tests**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=RenderTickProcessorTest,BeatProjectionUtilTest,MessageQueueTest,MessageQueueBackpressureTest test`

Expected: PASS and queue-size assertions show render bursts occupy mailbox slots rather than the 1000-message FIFO.

- [ ] **Step 7: Commit render-first tick processing**

```powershell
git add minecraft_plugin/src/main/java/com/audioviz/render minecraft_plugin/src/main/java/com/audioviz/protocol/BeatProjectionUtil.java minecraft_plugin/src/main/java/com/audioviz/protocol/MessageQueue.java minecraft_plugin/src/test/java/com/audioviz/render minecraft_plugin/src/test/java/com/audioviz/protocol
git commit -m "perf: drain latest render state each tick"
```

### Task 6: WebSocket JSON routing and lifecycle invalidation

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerRoutingTest.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerAuthTest.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerFrameLimitTest.java`

**Interfaces:**
- Consumes: `MessageQueue.publishRender(...)` and `RenderFrameHub.invalidateAll()`.
- Produces: no wire change; `batch_update` text messages use the mailbox after authentication.

- [ ] **Step 1: Replace FIFO assertions with mailbox behavior tests**

Update the current routing test so a `batch_update` never invokes `MessageHandler`, never increases the ordered queue size, and makes one render snapshot available. Add a burst test that sends 100 valid render messages and asserts one pending state plus latched beat from an earlier frame.

- [ ] **Step 2: Add lifecycle tests**

Assert unauthenticated render frames cannot publish; an authenticated frame published before disconnect cannot apply after `onClose` because its guard is invalid; a different still-active client's guarded event remains valid; and shutdown invalidates all pending mailboxes before executor drain completes.

- [ ] **Step 3: Run WebSocket tests and verify they fail against FIFO routing**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=VizWebSocketServerRoutingTest,VizWebSocketServerAuthTest,VizWebSocketServerFrameLimitTest test`

Expected: FAIL because current routing enqueues `batch_update` as bounded FIFO work.

- [ ] **Step 4: Route parsed render JSON directly to the mailbox**

In `dispatchParsedMessage`, handle the exact top-level type before `requiresBoundedQueue`:

```java
if ("batch_update".equals(type)) {
    messageQueue.publishRender(message, guard, System.nanoTime());
    return;
}
```

The guard remains connection-generation aware. `onClose` closes the existing generation so guarded pending state cannot execute. Full server shutdown calls `RenderFrameHub.invalidateAll()` before executor drain. Plan 3 adds explicit epoch-based slot removal once binary sessions exist. Do not weaken authentication or frame-size checks.

- [ ] **Step 5: Run WebSocket routing/security tests**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=VizWebSocketServerRoutingTest,VizWebSocketServerAuthTest,VizWebSocketServerFrameLimitTest,WebSocketSecurityPolicyTest test`

Expected: PASS.

- [ ] **Step 6: Commit JSON WebSocket mailbox routing**

```powershell
git add minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java minecraft_plugin/src/test/java/com/audioviz/websocket
git commit -m "perf: route JSON renders to latest-state mailboxes"
```

### Task 7: Complete foundation telemetry

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderTelemetry.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderProtocolKind.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderTelemetrySnapshot.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/latency/LatencyTracker.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/latency/LatencyTrackerTest.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/RenderTelemetryTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerRoutingTest.java`

**Interfaces:**
- Produces: allocation-free counter methods and rolling observations for decode, mailbox wait, apply, and receive-to-apply durations.
- Produces: `RenderTelemetry.writeJson(JsonObject target)` for existing `get_ws_metrics` output.

- [ ] **Step 1: Write failing bounded telemetry tests**

```java
@Test
void recordsEveryFoundationDispositionAndLatencySegment() {
    RenderTelemetry telemetry = new RenderTelemetry(128);
    telemetry.frameReceived(RenderProtocolKind.JSON, 6400);
    telemetry.framePublished();
    telemetry.frameSuperseded();
    telemetry.recordDecodeNanos(800_000);
    telemetry.recordMailboxWaitNanos(4_000_000);
    telemetry.recordApplyNanos(2_000_000);
    telemetry.recordReceiveToApplyNanos(6_800_000);

    RenderTelemetrySnapshot snapshot = telemetry.snapshot();
    assertEquals(1, snapshot.jsonFramesReceived());
    assertEquals(6400, snapshot.jsonBytesReceived());
    assertEquals(1, snapshot.framesSuperseded());
    assertEquals(0.8, snapshot.decode().averageMillis(), 1e-9);
}
```

Add tests proving an empty window returns zero, snapshot creation is bounded, and source timestamps are not mislabeled as exact network latency.

- [ ] **Step 2: Run telemetry tests and verify failure**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=RenderTelemetryTest,LatencyTrackerTest test`

Expected: FAIL because render telemetry does not exist.

- [ ] **Step 3: Implement fixed-memory counters and rolling windows**

Use `LongAdder` for counters and fixed `long[]` nanosecond windows guarded by short synchronized record/snapshot sections. Sorting may allocate only when an admin requests a metrics snapshot, never during frame ingestion. Record reason-specific counters for received, decoded, published, superseded, stale, malformed, unauthorized, revision mismatch, event deduplication, particle coalescing, and overflow.

Extend `LatencyTracker` with monotonic segment methods while keeping `recordNetworkLatency` for legacy diagnostics. Rename its JSON label to `estimated_clock_adjusted_network_ms` so it is not presented as exact one-way latency.

- [ ] **Step 4: Wire telemetry to decode, publication, tick apply, and WebSocket metrics**

Record `receivedNanos` at WebSocket dispatch, decode start/end in the decoder, publish disposition in the mailbox, and apply timestamps in the tick processor. Add a nested `render` object to `get_ws_metrics`; preserve existing fields.

- [ ] **Step 5: Run telemetry and routing tests**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=RenderTelemetryTest,LatencyTrackerTest,VizWebSocketServerRoutingTest test`

Expected: PASS.

- [ ] **Step 6: Commit foundation telemetry**

```powershell
git add minecraft_plugin/src/main/java/com/audioviz/render minecraft_plugin/src/main/java/com/audioviz/latency/LatencyTracker.java minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java minecraft_plugin/src/test/java/com/audioviz/render minecraft_plugin/src/test/java/com/audioviz/latency minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerRoutingTest.java
git commit -m "feat: add render latency telemetry"
```

### Task 8: Foundation regression verification

**Files:**
- Modify only if a regression test exposes a foundation bug.

**Interfaces:**
- Produces: a working JSON renderer with latest-state mailboxes and durable events.

- [ ] **Step 1: Run protocol tests**

Run: `node --test protocol/tests/phase0-schemas.test.mjs`

Expected: PASS.

- [ ] **Step 2: Run the complete plugin suite**

Run: `mvn -f minecraft_plugin/pom.xml clean test`

Expected: PASS with no failing or skipped render-foundation tests.

- [ ] **Step 3: Build the shaded plugin**

Run: `mvn -f minecraft_plugin/pom.xml package`

Expected: BUILD SUCCESS and `minecraft_plugin/target/audioviz-plugin-1.0.0-SNAPSHOT.jar` exists.

- [ ] **Step 4: Inspect final scope**

Run: `git status --short`

Run: `git diff --stat HEAD~7..HEAD`

Expected: only protocol, plugin render-foundation, tests, and configuration changes from this plan; unrelated untracked files remain uncommitted.
