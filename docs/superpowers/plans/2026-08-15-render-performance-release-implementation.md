# Render Performance and Release Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Prove the completed JSON/SBE renderer meets its correctness, allocation, payload, concurrency, latency, compatibility, documentation, and real Paper smoke-test release gates.

**Architecture:** Production code exposes fixed-memory reason-specific telemetry. JMH and jcstress live in independent Maven modules so neither harness enters the plugin JAR. Python uses a deterministic stdlib benchmark/gate, while a PowerShell harness launches a disposable real Paper server and drives both negotiated SBE and forced JSON from the WSL VJ client.

**Tech Stack:** Java 21, JMH 1.37, jcstress 0.16, Maven, Paper 1.21.11, PowerShell, WSL Python 3.11+, pytest

**Spec:** `docs/superpowers/specs/2026-08-15-low-latency-render-pipeline-design.md`

## Global Constraints

- Complete the foundation, codec, and binary-integration plans first.
- Benchmark and concurrency harness dependencies must not appear in the production plugin JAR.
- Reference hardware, JVM, command line, warmup, measurement, forks, entity counts, payloads, and raw results are recorded.
- Performance gates run after warmup and fail loudly; they are not inferred from a single manual observation.
- SBE decode p95 is at most 1 ms for the 256-entity fixture on documented reference hardware.
- Main-thread render work p95 is at most 3 ms for the 256-entity real Paper fixture.
- SBE payload bytes are at most 25 percent of equivalent current JSON bytes.
- Steady-state SBE decode performs zero per-entity heap allocations.
- Every compatibility and malformed-input requirement has an explicit automated test.
- Real Paper smoke covers SBE-enabled startup, forced JSON, and no-module-open JSON-only startup.
- The smoke harness operates only inside a newly created temporary directory and never modifies an existing server.
- Python commands run in WSL `.venv`; PowerShell orchestrates Windows Maven and Paper.
- Raw benchmark/smoke result files are ignored; the checked-in report records commands and summarized evidence.

---

## File map

- `minecraft_plugin/src/main/java/com/audioviz/render/RenderTelemetry.java`: final binary/JSON counters and rolling latency segments.
- `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`: metrics export and reason-coded errors.
- `minecraft_plugin/benchmarks/pom.xml`: isolated JMH build.
- `minecraft_plugin/benchmarks/src/main/java/com/audioviz/benchmarks/RenderDecodeBenchmark.java`: JSON versus SBE decode/publication.
- `minecraft_plugin/benchmarks/src/main/java/com/audioviz/benchmarks/RenderTransformBenchmark.java`: cached transform/application preparation.
- `minecraft_plugin/concurrency-tests/pom.xml`: isolated jcstress build.
- `minecraft_plugin/concurrency-tests/src/main/java/com/audioviz/stress/ZoneSnapshotMailboxStress.java`: publication visibility.
- `minecraft_plugin/concurrency-tests/src/main/java/com/audioviz/stress/RenderEventLatchStress.java`: event drain races.
- `vj_server/tools/benchmark_render_encoding.py`: deterministic JSON/SBE size and encoding benchmark.
- `vj_server/tests/test_render_performance_gate.py`: Python result and payload gates.
- `vj_server/tools/render_smoke.py`: protocol negotiation, dictionary, SBE, JSON, and metrics driver.
- `scripts/smoke-render-pipeline.ps1`: disposable real Paper orchestration.
- `scripts/verify-render-pipeline.ps1`: one repeatable verification entry point.
- `docs/RENDER_PROTOCOL.md`: operator/developer protocol guide.
- `docs/PERFORMANCE.md`: reference hardware, benchmark commands, gates, and measured results.
- `docs/CONNECTIVITY.md`: module flag, capability fallback, and troubleshooting.

### Task 1: Finalize reason-specific telemetry and metrics export

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/render/RenderTelemetry.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/render/RenderTelemetrySnapshot.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/render/RenderTelemetryTest.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerBinaryTest.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerRoutingTest.java`

**Interfaces:**
- Produces all counters and latency segments named in design section 13.
- Produces stable nested JSON under `ws_metrics.render` with `counters`, `bytes`, `latency_ms`, and `current`.

- [ ] **Step 1: Write a failing exact metrics-shape test**

```java
@Test
void metricsExposeEveryRequiredDispositionAndLatencySegment() {
    JsonObject render = server.getMetrics().getAsJsonObject("render");
    JsonObject counters = render.getAsJsonObject("counters");
    for (String name : List.of(
        "json_received", "binary_received", "decoded", "published", "superseded",
        "stale", "malformed", "unauthorized", "revision_mismatch",
        "beat_latched", "beat_applied", "beat_deduplicated",
        "particle_coalesced", "particle_rejected", "selection_succeeded",
        "selection_fallback"
    )) {
        assertTrue(counters.has(name), name);
    }
    JsonObject latency = render.getAsJsonObject("latency_ms");
    for (String segment : List.of("decode", "mailbox_wait", "apply", "receive_to_apply", "snapshot_age")) {
        assertTrue(latency.getAsJsonObject(segment).has("p95"), segment);
    }
}
```

- [ ] **Step 2: Run telemetry tests and identify missing fields**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=RenderTelemetryTest,VizWebSocketServerBinaryTest,VizWebSocketServerRoutingTest test`

Expected: FAIL listing any incomplete counters or segments.

- [ ] **Step 3: Wire every disposition at its authoritative boundary**

Increment receive/byte counters at WebSocket entry, validation failures at the exact rejecting check, decoded after full structural validation, published/superseded/stale from mailbox disposition, event counters from latching/drain, and selections/fallbacks from negotiation. Do not infer one counter from another.

Record `current.pending_zones`, `current.active_protocols`, last accepted frame sequence by connection, and current dictionary revision without exposing secrets or remote payloads.

- [ ] **Step 4: Export fixed-window statistics only on request**

For each latency segment return count, average, p50, p95, p99, and max in milliseconds. Copy/sort the bounded window only inside `writeJson`; frame recording remains allocation-free.

- [ ] **Step 5: Run telemetry tests**

Expected: PASS.

- [ ] **Step 6: Commit final telemetry**

```powershell
git add minecraft_plugin/src/main/java/com/audioviz/render minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java minecraft_plugin/src/test/java/com/audioviz/render minecraft_plugin/src/test/java/com/audioviz/websocket
git commit -m "feat: complete render pipeline telemetry"
```

### Task 2: Isolated JMH decode and transform benchmarks

**Files:**
- Create: `minecraft_plugin/benchmarks/pom.xml`
- Create: `minecraft_plugin/benchmarks/src/main/java/com/audioviz/benchmarks/RenderBenchmarkState.java`
- Create: `minecraft_plugin/benchmarks/src/main/java/com/audioviz/benchmarks/RenderDecodeBenchmark.java`
- Create: `minecraft_plugin/benchmarks/src/main/java/com/audioviz/benchmarks/RenderTransformBenchmark.java`
- Create: `minecraft_plugin/benchmarks/src/test/java/com/audioviz/benchmarks/BenchmarkFixtureTest.java`
- Modify: `.gitignore`

**Interfaces:**
- Produces executable `minecraft_plugin/benchmarks/target/benchmarks.jar`.
- Produces JMH JSON containing sample-time percentiles and allocation rates for 64, 160, and 256 entities across one and four zones.

- [ ] **Step 1: Write a failing benchmark fixture parity test**

```java
@ParameterizedTest
@ValueSource(ints = {64, 160, 256})
void jsonAndSbeFixturesDecodeToEquivalentSnapshots(int entityCount) {
    RenderBenchmarkState state = RenderBenchmarkState.create(entityCount, 4);
    DecodedScene json = state.decodeJsonOnce();
    DecodedScene sbe = state.decodeSbeOnce();
    assertEquals(json.semanticDigest(), sbe.semanticDigest());
    assertTrue(state.sbeBytes().length <= state.jsonBytes().length / 4.0);
}
```

- [ ] **Step 2: Run fixture test and verify module is absent**

Run: `mvn -f minecraft_plugin/benchmarks/pom.xml test`

Expected: FAIL because the benchmark module does not exist.

- [ ] **Step 3: Create an independent JMH module**

Use `org.openjdk.jmh:jmh-core:1.37`, `jmh-generator-annprocess:1.37`, the installed `com.audioviz:audioviz-plugin:1.0.0-SNAPSHOT`, Gson, Paper API, and JUnit only. Configure the shade plugin with `org.openjdk.jmh.Main` and output `benchmarks.jar`. Do not make the production plugin POM a multimodule parent.

- [ ] **Step 4: Build equivalent representative fixtures**

Generate complete JSON and SBE payloads with identical values, warmed dictionaries, dense IDs, all five bands, audio metadata, materials, visibility, and one beat particle. Drain and release mailbox slots after every invocation so the benchmark never measures slot exhaustion.

- [ ] **Step 5: Implement sample-time decode/publication benchmarks**

```java
@Benchmark
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public long decodeSbe(RenderBenchmarkState state) {
    return state.decodeSbeAndDrainDigest();
}

@Benchmark
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public long decodeJson(RenderBenchmarkState state) {
    return state.decodeJsonAndDrainDigest();
}
```

Use `@Warmup(iterations=5,time=1)`, `@Measurement(iterations=8,time=1)`, `@Fork(3)`, and `@Param({"64","160","256"})`. A returned digest prevents dead-code elimination.

- [ ] **Step 6: Benchmark cached coordinate/transform preparation**

Compare the old formula-equivalent allocation path in benchmark-only code with `VisualizationZone.writeWorld` and reusable render scratch. Do not keep an intentionally slow implementation in production sources.

- [ ] **Step 7: Run fixture tests and a short benchmark smoke**

Run: `mvn -f minecraft_plugin/pom.xml install -DskipTests`

Run: `mvn -f minecraft_plugin/benchmarks/pom.xml clean test package`

Run:

```powershell
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -jar minecraft_plugin/benchmarks/target/benchmarks.jar RenderDecodeBenchmark -wi 1 -i 1 -f 1
```

Expected: fixture tests PASS and every benchmark executes.

- [ ] **Step 8: Ignore raw outputs and commit harness**

Add `minecraft_plugin/benchmarks/results/` to `.gitignore`.

```powershell
git add .gitignore minecraft_plugin/benchmarks
git commit -m "perf: add render pipeline JMH benchmarks"
```

### Task 3: jcstress mailbox and event-latch proofs

**Files:**
- Create: `minecraft_plugin/concurrency-tests/pom.xml`
- Create: `minecraft_plugin/concurrency-tests/src/main/java/com/audioviz/stress/ZoneSnapshotMailboxStress.java`
- Create: `minecraft_plugin/concurrency-tests/src/main/java/com/audioviz/stress/StalePublicationStress.java`
- Create: `minecraft_plugin/concurrency-tests/src/main/java/com/audioviz/stress/RenderEventLatchStress.java`
- Modify: `.gitignore`

**Interfaces:**
- Produces executable `minecraft_plugin/concurrency-tests/target/jcstress.jar`.
- Proves array writes are visible after publication, stale writers cannot replace newer state, and drain races do not duplicate valid events.

- [ ] **Step 1: Define accepted publication-visibility outcomes**

```java
@JCStressTest
@Outcome(id = "42, -1", expect = Expect.ACCEPTABLE, desc = "Published value read once")
@Outcome(id = "-1, 42", expect = Expect.ACCEPTABLE, desc = "Reader missed first poll and read later")
@Outcome(expect = Expect.FORBIDDEN, desc = "Default data, duplicate reads, or a lost publication")
@State
public class ZoneSnapshotMailboxStress {
    private final ZoneSnapshotMailbox mailbox = new ZoneSnapshotMailbox("main", 1, 0, 3);

    @Actor
    public void publish() {
        ZoneRenderSnapshot snapshot = requireNonNull(mailbox.tryClaim(1));
        snapshot.entityCount(1);
        snapshot.x()[0] = 42.0f;
        if (!mailbox.publish(snapshot)) {
            throw new AssertionError("first publication was rejected");
        }
    }

    @Actor
    public void readFirst(II_Result result) {
        ZoneRenderSnapshot snapshot = mailbox.takeLatest();
        result.r1 = snapshot == null ? -1 : (int) snapshot.x()[0];
        if (snapshot != null) mailbox.releaseAfterRead(snapshot);
    }

    @Arbiter
    public void readRemaining(II_Result result) {
        ZoneRenderSnapshot snapshot = mailbox.takeLatest();
        result.r2 = snapshot == null ? -1 : (int) snapshot.x()[0];
        if (snapshot != null) mailbox.releaseAfterRead(snapshot);
    }
}
```

Write exact forbidden outcomes for default/uninitialized array visibility, lower ingress replacing higher, duplicate event drain, and event loss when publish races drain.

- [ ] **Step 2: Build and verify the missing module fails**

Run: `mvn -f minecraft_plugin/concurrency-tests/pom.xml package`

Expected: FAIL because the module is absent.

- [ ] **Step 3: Create an independent jcstress module**

Use `org.openjdk.jcstress:jcstress-core:0.16`, its annotation processor, the installed plugin artifact, and Maven Shade with `org.openjdk.jcstress.Main`. Name the output `jcstress.jar`. Keep the harness out of the plugin dependency graph.

- [ ] **Step 4: Implement all three stress suites without sleeps**

Use actors and arbiters only; do not coordinate with `Thread.sleep`, latches, or timing assumptions. Every outcome annotation explains why it is accepted or forbidden.

- [ ] **Step 5: Run concurrency stress**

Run: `mvn -f minecraft_plugin/pom.xml install -DskipTests`

Run: `mvn -f minecraft_plugin/concurrency-tests/pom.xml clean package`

Run:

```powershell
java -jar minecraft_plugin/concurrency-tests/target/jcstress.jar -t 'com.audioviz.stress.*' -time 1000
```

Expected: zero `FORBIDDEN` or `ERROR` outcomes.

- [ ] **Step 6: Ignore raw results and commit concurrency harness**

Add `minecraft_plugin/concurrency-tests/results/` to `.gitignore`.

```powershell
git add .gitignore minecraft_plugin/concurrency-tests
git commit -m "test: stress render mailbox concurrency"
```

### Task 4: Python encoding benchmark and machine-readable gates

**Files:**
- Create: `vj_server/tools/benchmark_render_encoding.py`
- Create: `vj_server/tests/test_render_performance_gate.py`
- Modify: `.gitignore`

**Interfaces:**
- Produces JSON results for JSON/SBE byte length, median/p95 encode microseconds, and peak traced allocation across 64, 160, and 256 entities and one/four zones.
- Produces a nonzero process exit when the payload gate fails.

- [ ] **Step 1: Write failing deterministic result-schema tests**

```python
def test_representative_result_meets_payload_gate() -> None:
    result = run_case(entity_count=256, zone_count=4, iterations=2_000)
    assert result["sbe_bytes"] <= result["json_bytes"] * 0.25
    assert result["iterations"] == 2_000
    assert result["sbe_encode_us"]["p95"] >= result["sbe_encode_us"]["median"]
```

Add semantic parity and invalid iteration/count argument tests. Do not assert a machine-independent Python timing threshold in unit tests.

- [ ] **Step 2: Run WSL tests and verify benchmark module is missing**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
source .venv/bin/activate
pytest tests/test_render_performance_gate.py -q
```

Expected: FAIL.

- [ ] **Step 3: Implement stdlib benchmark sampling**

Warm each encoder, disable GC only during timed inner loops, use `time.perf_counter_ns`, restore GC in `finally`, sort a fixed sample array for percentiles, and use `tracemalloc` in a separate untimed pass. Serialize equivalent data with current `msgspec.json.encode` and the reusable SBE encoder.

- [ ] **Step 4: Add CLI and payload gate**

```bash
python -m vj_server.tools.benchmark_render_encoding \
  --entities 64 160 256 --zones 1 4 --iterations 10000 \
  --max-sbe-json-ratio 0.25 --output results/python-render.json
```

Exit two with a clear case-specific message if any payload ratio exceeds the gate. Timing is recorded for comparison and documentation, not treated as portable CI truth.

- [ ] **Step 5: Run tests and a representative benchmark**

Expected: tests PASS and the command exits zero.

- [ ] **Step 6: Ignore raw results and commit Python harness**

Add `vj_server/results/` to `.gitignore`.

```powershell
git add .gitignore vj_server/tools/benchmark_render_encoding.py vj_server/tests/test_render_performance_gate.py
git commit -m "perf: benchmark Python render encoding"
```

### Task 5: Real Paper SBE/JSON smoke driver

**Files:**
- Create: `vj_server/tools/render_smoke.py`
- Create: `vj_server/tests/test_render_smoke_driver.py`
- Create: `scripts/smoke-render-pipeline.ps1`
- Modify: `.gitignore`

**Interfaces:**
- Produces: `python -m vj_server.tools.render_smoke --host ... --protocol auto|json --output ...`.
- Produces: `scripts/smoke-render-pipeline.ps1 -PaperJar <absolute-path> [-KeepWorkdir]`.
- Smoke result includes selected protocol, applied frame sequence, entity count, beat applied count, payload bytes, decode p95, receive-to-apply p95, and apply p95.

- [ ] **Step 1: Write failing smoke-driver tests against a fake plugin**

```python
async def test_driver_initializes_pool_renders_and_reads_metrics(fake_plugin) -> None:
    result = await run_smoke(host=fake_plugin.host, port=fake_plugin.port, protocol="auto")
    assert result.selected_protocol == "sbe.v1"
    assert result.entity_count == 256
    assert result.beat_applied >= 1
    assert result.last_frame_sequence == result.sent_frame_sequence
```

Add forced JSON, fallback, timeout, malformed metrics, and nonzero exit tests.

- [ ] **Step 2: Run WSL driver tests and verify missing module fails**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
source .venv/bin/activate
pytest tests/test_render_smoke_driver.py -q
```

Expected: FAIL.

- [ ] **Step 3: Implement the protocol smoke driver**

Connect with `VizClient`, assert zone `main` exists, initialize a 256-entity pool, warm with 50 frames, then send at least 500 deterministic frames including a beat that is superseded before the next expected tick. Poll `get_ws_metrics` until the final sequence is applied or timeout. Run once with automatic SBE and once with forced JSON; write structured JSON and exit nonzero on any unmet semantic assertion.

- [ ] **Step 4: Write a disposable Paper orchestration script**

The script must:

1. resolve and verify the user-provided Paper JAR is a file;
2. create a unique directory beneath `[System.IO.Path]::GetTempPath()` and verify its resolved path before writes;
3. build the plugin and copy only the resulting JAR into the temporary `plugins` directory;
4. write `eula.txt`, `server.properties`, plugin `config.yml`, and `plugins/AudioViz/zones.yml` with a deterministic `main` zone in world `world`;
5. launch Paper hidden with Java 21, the required `--add-opens`, and redirected logs/stdin;
6. wait for both Paper ready and AudioViz WebSocket ready with a bounded timeout;
7. invoke the WSL smoke driver in automatic SBE and forced JSON modes;
8. send `stop`, wait for clean process exit, and collect log/result paths;
9. launch a second temporary server without `--add-opens`, verify its welcome advertises only JSON, then stop it;
10. retain the work directory on failure and remove only its verified self-created directory on success unless `-KeepWorkdir` is supplied.

Use `Start-Process -WindowStyle Hidden`; never target an existing Minecraft directory.

- [ ] **Step 5: Add script self-tests that do not launch Paper**

Support `-ValidateOnly` to check Java, WSL, Maven, Paper JAR, path containment, and generated fixture files. Test missing JAR, non-Java-21 runtime, and unsafe workdir rejection.

- [ ] **Step 6: Run driver tests and smoke validation**

Run WSL pytest as above.

Run: `powershell -File scripts/smoke-render-pipeline.ps1 -PaperJar C:\path\to\paper.jar -ValidateOnly`

Expected: tests PASS and validation reports every prerequisite without launching a server.

- [ ] **Step 7: Commit the real Paper smoke harness**

```powershell
git add .gitignore vj_server/tools/render_smoke.py vj_server/tests/test_render_smoke_driver.py scripts/smoke-render-pipeline.ps1
git commit -m "test: add real Paper render smoke harness"
```

### Task 6: Documentation and one-command verification

**Files:**
- Create: `docs/RENDER_PROTOCOL.md`
- Modify: `docs/CONNECTIVITY.md`
- Modify: `docs/PERFORMANCE.md`
- Create: `scripts/verify-render-pipeline.ps1`
- Modify: `README.md`

**Interfaces:**
- Produces operator setup, codec/versioning, fallback, observability, benchmark, troubleshooting, and migration documentation.
- Produces one noninteractive verification entry point excluding the separately parameterized real Paper run.

- [ ] **Step 1: Write a documentation completeness check**

Add a PowerShell validation block to `verify-render-pipeline.ps1` that fails if the docs omit exact strings `sbe.v1`, `json.v1`, `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED`, `dictionary_revision`, `render_protocol_epoch`, and the forced-JSON option.

- [ ] **Step 2: Document the wire and lifecycle contract**

`RENDER_PROTOCOL.md` covers JSON authentication, advertisement, selection, dictionary revision, standard SBE header, every fixed field/group, quantization formulas and maximum error, absolute snapshots, dense slot semantics, event durability, limits, versioning, fallback, and golden fixtures. Link the design rather than duplicating its rationale.

- [ ] **Step 3: Document operator deployment and troubleshooting**

Update connectivity docs with the JVM flag, startup probe, expected warning, JSON-only behavior, metrics fields, reconnect epoch behavior, and exact checks for malformed/revision errors. Update README quick start with automatic negotiation and JSON override.

- [ ] **Step 4: Document reproducible performance evidence**

`PERFORMANCE.md` records CPU model, core count, RAM, OS build, Java version, Python version, Maven version, SBE/Agrona versions, commands, warmup/measurement/forks, entity/zone fixtures, raw-result locations, p50/p95/p99, allocation rate, payload ratio, and real Paper apply metrics. Mark results with their measurement date; do not claim portability to other hardware.

- [ ] **Step 5: Implement one-command automated verification**

`verify-render-pipeline.ps1` runs in order:

```text
node --test protocol/tests/phase0-schemas.test.mjs
mvn -f minecraft_plugin/pom.xml clean test -Dsbe.enable.precedence.checks=true
mvn -f minecraft_plugin/pom.xml package
WSL pytest for the complete vj_server suite
WSL Python layout freshness check
mvn install plus benchmark fixture tests
mvn package plus jcstress execution
WSL Python encoding performance gate
shaded-JAR compiler/relocation inspection
documentation completeness checks
```

Stop at the first failure and propagate its exit code. Print raw result paths at the end.

- [ ] **Step 6: Run the verification entry point**

Run: `powershell -File scripts/verify-render-pipeline.ps1`

Expected: exit zero.

- [ ] **Step 7: Commit docs and verification**

```powershell
git add README.md docs/RENDER_PROTOCOL.md docs/CONNECTIVITY.md docs/PERFORMANCE.md scripts/verify-render-pipeline.ps1
git commit -m "docs: document and verify binary rendering"
```

### Task 7: Execute release gates and record evidence

**Files:**
- Modify: `docs/PERFORMANCE.md` with measured results.
- Modify production/tests only when a gate provides reproducible evidence of a defect.

**Interfaces:**
- Produces authoritative completion evidence for every explicit goal requirement.

- [ ] **Step 1: Run full automated verification from a clean generated-output state**

Run: `powershell -File scripts/verify-render-pipeline.ps1`

Expected: exit zero after all protocol, Java, WSL Python, golden, compatibility, malformed-input, concurrency, packaging, and documentation checks.

- [ ] **Step 2: Run full JMH measurements with allocation profiler**

```powershell
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED `
  -jar minecraft_plugin/benchmarks/target/benchmarks.jar `
  'RenderDecodeBenchmark|RenderTransformBenchmark' `
  -prof gc -rf json -rff minecraft_plugin/benchmarks/results/render-jmh.json
```

Expected for 256 entities: SBE decode/publication p95 at most `1000 us/op`; allocation growth is constant per frame and zero per entity; payload fixture ratio at most `0.25`.

- [ ] **Step 3: Run complete jcstress duration**

Run:

```powershell
java -jar minecraft_plugin/concurrency-tests/target/jcstress.jar `
  -t 'com.audioviz.stress.*' -time 10000 `
  -r minecraft_plugin/concurrency-tests/results/full
```

Expected: zero forbidden/error outcomes.

- [ ] **Step 4: Run the real Paper smoke in all required modes**

Run: `powershell -File scripts/smoke-render-pipeline.ps1 -PaperJar <verified-paper-1.21.11-jar> -KeepWorkdir`

Expected:

- automatic run selects `sbe.v1`, applies the final 256-entity frame, and applies the superseded beat exactly once;
- forced run selects `json.v1` and applies semantically equivalent state;
- SBE payload is at most 25 percent of JSON;
- SBE decode p95 is at most 1 ms;
- plugin apply p95 is at most 3 ms;
- receive-to-next-tick shows no extra application scheduler cycle;
- no-module-open startup advertises only `json.v1` and logs one exact actionable warning.

- [ ] **Step 5: Record reference evidence**

Copy summarized values and exact commands—not raw bulky output—into `docs/PERFORMANCE.md`. Include the retained smoke directory and raw benchmark result paths for local audit, but do not commit machine-specific absolute paths.

- [ ] **Step 6: Re-run full build/tests after any gate-driven fix**

Run: `powershell -File scripts/verify-render-pipeline.ps1`

Expected: exit zero.

- [ ] **Step 7: Commit measured release evidence**

```powershell
git add docs/PERFORMANCE.md
git commit -m "docs: record render pipeline performance gates"
```

- [ ] **Step 8: Perform requirement-by-requirement completion audit**

For every item in design section 18, cite a current file, test, command result, benchmark result, or real Paper smoke result. Any missing or indirect evidence keeps the overall goal active. Only after every item is proven should the active goal be marked complete.
