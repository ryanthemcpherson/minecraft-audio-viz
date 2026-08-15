# Low-Latency Render Pipeline Design

Status: Proposed for review

Date: 2026-08-15

Scope: Existing Python VJ server and Java 21 Paper plugin

## 1. Decision summary

The existing WebSocket connection remains in place. JSON remains the authenticated control plane and the backward-compatible render fallback. A negotiated `sbe.v1` binary render plane carries the high-frequency `batch_update` state.

The Paper plugin decodes binary frames with generated SBE Java codecs backed by Agrona buffers. The Python VJ server writes the same schema into reusable `bytearray` buffers with `struct.pack_into`; it does not add an unmaintained Python SBE runtime. Cross-language golden vectors make the XML schema, Java decoder, and Python encoder one tested contract.

The plugin stores only the newest complete render snapshot for each zone. Beat and other transient events use separate durable latches so replacing a stale snapshot cannot erase a beat. At the start of each Minecraft tick, the main thread atomically takes the newest snapshot and accumulated events, then performs one batch apply.

This design deliberately optimizes the existing runtime. It does not supersede `2026-07-10-mcav-v2-platform-design.md`, whose greenfield transport remains QUIC plus FlatBuffers. The two designs share semantic contracts and test fixtures, but their wire codecs are independent.

## 2. Goals

- Deliver each zone's freshest complete state on the first available Minecraft tick.
- Avoid an additional application-level tick, queue, or scheduler delay after a frame is decoded.
- Keep transient beat and particle events durable when intermediate state frames are replaced.
- Reduce representative render payloads by at least 75 percent relative to current JSON.
- Eliminate per-entity strings, object trees, and allocations from the steady-state binary decode path.
- Preserve current JSON clients without requiring a coordinated deployment.
- Establish canonical units, ranges, formulas, and limits shared by schemas and implementations.
- Measure receive-to-apply latency and every important discard or fallback reason.
- Prove byte compatibility, mailbox concurrency behavior, and malformed-input handling in automated tests.

## 3. Non-goals

- Replacing the current WebSocket connection with QUIC, Aeron, Netty, or a second server port.
- Moving the VJ server from Python to Java or Rust.
- Sending raw audio samples to the plugin.
- Increasing Minecraft's 20 TPS simulation rate.
- Binary-encoding authentication, administration, zone editing, bitmap control, or other low-rate messages.
- Temporal delta compression that requires the receiver to observe every preceding frame.
- Compressing binary render frames. The payload is already compact and compression would add latency and jitter.
- Making Bukkit `Material.ordinal()` or any other runtime ordinal part of the wire protocol.

## 4. Constraints and invariants

1. Bukkit and Paper entity mutations occur only on the server main thread.
2. WebSocket callbacks and decoder workers never call Bukkit APIs.
3. A render frame is an absolute snapshot. It may be decoded and applied without any earlier render frame.
4. State is latest-wins per zone; control messages remain ordered.
5. Transient events are accumulated independently from latest-wins state.
6. The receiver never retains the `ByteBuffer` supplied by Java-WebSocket after the callback returns.
7. Every array, count, and payload is bounded before allocation or iteration.
8. Durations use a monotonic clock. Wall-clock timestamps are diagnostic metadata only.
9. All protocol limits have one named source and are derived from wire widths, configured budgets, or Minecraft constraints.
10. Unknown protocol versions, dictionary revisions, zones, materials, or particle types fail closed for that frame without closing a healthy connection unless violations repeat.

## 5. Data flow

The fast path is:

```text
VJ scene calculation
  -> reusable Python binary encoder
  -> one WebSocket binary message
  -> plugin validation and SBE decode
  -> per-zone latest snapshot mailbox + transient event latch
  -> beginning of next Paper tick
  -> coordinate transform and pooled entity batch apply
```

The plugin has two logical lanes:

- The render lane accepts JSON `batch_update` or binary `RenderFrame`, normalizes either into the same internal snapshot model, and publishes per-zone latest state.
- The control lane retains bounded, ordered handling for authentication, dictionary changes, pool initialization, zone configuration, and all other commands.

The lanes share authentication and connection lifecycle state, but render traffic cannot sit behind a backlog of old render frames.

## 6. Capability negotiation and compatibility

### 6.1 Connection advertisement

The plugin adds optional fields to its existing JSON `connected` message:

```json
{
  "type": "connected",
  "auth_required": false,
  "server_type": "minecraft_plugin",
  "render_protocols": ["json.v1", "sbe.v1"],
  "preferred_render_protocol": "sbe.v1",
  "render_protocol_epoch": 1842370912
}
```

Older clients ignore the new fields and continue sending JSON. A client that does not explicitly select a protocol remains on `json.v1`.

`render_protocol_epoch` is an unpredictable unsigned 32-bit connection value generated by the plugin. It prevents a decoded frame from an old connection or dictionary generation from being applied to a new session.

### 6.2 Selection

After authentication succeeds, or immediately after `connected` when authentication is disabled, the VJ server may send:

```json
{
  "type": "select_render_protocol",
  "protocol": "sbe.v1",
  "epoch": 1842370912
}
```

The plugin replies with `render_protocol_selected` or a structured `error`. Selection is idempotent. Binary messages received before successful selection are rejected and counted. An authenticated connection may return to `json.v1` without reconnecting.

### 6.3 Dictionaries

Binary frames use connection-local numeric identifiers:

- Zone IDs are assigned by the plugin and returned in `render_protocol_selected` and zone-list responses.
- Entity slots are dense pool indices. Binary entity element zero addresses `block_0`, element one addresses `block_1`, and so on.
- Material and particle IDs come from plugin-validated dictionaries established with JSON control messages.
- ID zero means `unchanged/default` only where explicitly allowed. It never means a Bukkit ordinal.

Every accepted dictionary change increments a `dictionary_revision`. `RenderFrame` includes that revision. A frame with a different revision is discarded and prompts one rate-limited dictionary refresh response.

Changing zones, rebuilding a pool, or reconnecting creates a new epoch or dictionary revision. The sender must receive the acknowledgement before using the new mapping.

### 6.4 Unsupported render data

`sbe.v1` targets dense pooled block-display updates. A frame requiring arbitrary string entity IDs, text payloads, or another field not represented by `sbe.v1` is sent as current JSON. Control and binary messages may coexist on one selected connection; the WebSocket opcode distinguishes them.

This fallback is per message, not per process. It keeps compatibility while the hot path stays compact.

## 7. Canonical units and formula discipline

### 7.1 Normalized values

Positions, five frequency bands, amplitude, beat intensity, tempo confidence, and beat phase use the closed interval `[0, 1]` internally and on the semantic protocol. Values are clamped at the encoding boundary.

For an unsigned integer with maximum value `M`:

```text
encodeUnit(value) = round(clamp(value, 0, 1) * M)
decodeUnit(code)  = code / M
maximumError      = 1 / (2 * M)
```

`sbe.v1` uses unsigned 16-bit codes, so `M` is derived as `(1 << 16) - 1`, not repeated as a literal throughout the code.

### 7.2 Bounded values

For a semantic interval `[minimum, maximum]`:

```text
normalized        = (value - minimum) / (maximum - minimum)
encoded           = encodeUnit(normalized)
decoded           = minimum + decodeUnit(encoded) * (maximum - minimum)
```

Scale uses `[0, max_entity_scale]`, where `max_entity_scale` is canonical protocol policy and initially matches the existing schema value of `4.0`. Rotation is normalized to `[0, 360)` before encoding:

```text
wrappedDegrees = ((degrees % fullTurn) + fullTurn) % fullTurn
```

`fullTurn` is derived from the selected angular unit definition and named once.

### 7.3 Time and smoothing

Minecraft durations are expressed as integer ticks at the Bukkit boundary. Milliseconds are derived from the measured or configured tick duration for telemetry; they are not assumed to be exactly 50 ms during a lag spike.

Time-correct exponential smoothing uses:

```text
alpha = 1 - exp(-deltaTime / timeConstant)
next  = current + alpha * (target - current)
```

The coefficient is recomputed only when `deltaTime` materially changes or the configured time constant changes. A hardcoded frame-dependent blend factor is not used.

### 7.4 Limits

`ProtocolLimits` is the Java authority for plugin-side validation. Equivalent values are generated or imported by the Python encoder and JSON Schemas. Limits are classified as:

- Wire-derived: maximum values representable by an SBE primitive or group dimension.
- Resource-derived: maximum zones, entities, particles, and frame bytes computed from configured memory and tick-work budgets.
- Minecraft-derived: brightness and interpolation bounds required by the Paper API.
- Creative policy: glow duration, beat response, and smoothing presets exposed by configuration.

The maximum accepted frame size is calculated from the fixed SBE header, fixed frame block, group dimension headers, per-zone block, entity block, particle block, and configured maximum counts. No independent frame-byte literal is maintained.

## 8. Binary protocol

### 8.1 Schema ownership

The canonical SBE XML schema lives under `protocol/sbe/`. Maven runs the official SBE tool during code generation and compiles the generated Java flyweights. Generated source is not hand-edited.

The Java runtime depends on Agrona. The shaded plugin relocates Agrona and the generated codec package to avoid server-plugin classpath collisions. The SBE compiler remains build-time only.

The Python encoder uses a small checked-in layout module generated from the same SBE schema. It writes into a reusable `bytearray` using `struct.pack_into`. This keeps the runtime dependency-free and avoids adopting an unofficial Python codec. A generation check fails CI if the layout module is stale.

### 8.2 SBE message header

Every binary WebSocket message starts with the standard SBE message header:

- `blockLength`
- `templateId`
- `schemaId`
- `version`

The receiver validates all four before reading the body. Schema evolution follows SBE append-only rules. Required incompatible changes receive a new protocol name such as `sbe.v2` and require a new capability selection.

### 8.3 RenderFrame fixed block

`RenderFrame` contains one audio state shared by all zone snapshots:

- `connectionEpoch`: unsigned 32-bit connection epoch.
- `dictionaryRevision`: unsigned 32-bit mapping revision.
- `frameSequence`: unsigned 64-bit sender sequence, monotonically increasing within an epoch.
- `sourceTimeNanos`: monotonic source timestamp when available, or the SBE null value.
- `generatedTimeNanos`: sender monotonic timestamp immediately before encoding.
- `bands[5]`: five unsigned 16-bit normalized band values.
- `amplitude`: unsigned 16-bit normalized amplitude.
- `beatIntensity`: unsigned 16-bit normalized beat intensity.
- `bpmCenti`: unsigned 16-bit BPM multiplied by 100, with the SBE null value for unknown.
- `tempoConfidence`: unsigned 16-bit normalized confidence.
- `beatPhase`: unsigned 16-bit normalized phase.
- `frameFlags`: bit set containing `BEAT`, `KICK`, and future event flags.
- `eventSequence`: unsigned 64-bit sequence for transient-event deduplication.

The current `batch_update` schema and VJ fast helper are updated so JSON and binary paths expose the same complete audio state. In particular, BPM, confidence, phase, sequence, and timestamps may no longer be constructed and silently dropped.

### 8.4 Zone snapshots

The frame contains an SBE repeating group of zone snapshots. Each zone begins with:

- `zoneId`: connection-local unsigned 16-bit ID.
- `zoneFlags`: zone-level state bits.
- An entity repeating group.
- A particle-event repeating group.

Each entity group element is a complete state for the corresponding dense pool slot and contains:

- normalized `x`, `y`, and `z` as unsigned 16-bit values;
- bounded `scale` as an unsigned 16-bit value;
- wrapped Y-axis `rotation` as an unsigned 16-bit value;
- connection-local `materialId` as unsigned 16-bit;
- `brightness` in the Paper-supported integer range;
- `interpolationTicks` in the Paper-supported integer range;
- state bits including `VISIBLE` and `GLOW`.

An element's group index is its entity slot; the wire does not repeat `block_N`. The number of elements is the active dense prefix. Slots that were active in the previous applied snapshot but are outside the new active prefix are hidden during the same tick.

This is deliberately a full snapshot. There is no field-presence mask and no dependency on a previous frame.

Each particle event contains:

- `eventId`: sender-unique unsigned 64-bit ID within the epoch;
- `particleTypeId`: connection-local unsigned 16-bit ID;
- normalized `x`, `y`, and `z`;
- bounded unsigned count.

Particle events are copied into a separate bounded event accumulator during decode. Replacing a zone snapshot cannot discard them. Duplicate event IDs in the same epoch are ignored.

### 8.5 Payload size

The encoder computes the exact required size before writing:

```text
frameBytes = messageHeaderBytes
           + fixedFrameBlockBytes
           + groupHeaderBytes
           + sum(zoneBlockBytes
                 + groupHeaderBytes
                 + entityCount * entityBlockBytes
                 + groupHeaderBytes
                 + particleCount * particleBlockBytes)
```

All terms come from generated codec constants. The encoder grows its reusable buffer to the next geometric capacity only when required, subject to the negotiated maximum. Normal frames do not allocate.

## 9. Plugin receive architecture

### 9.1 WebSocket boundary

`VizWebSocketServer` implements `onMessage(WebSocket, ByteBuffer)`. The callback:

1. verifies connection authentication and selected binary protocol;
2. verifies the payload is no larger than the negotiated calculated limit;
3. assigns a monotonically increasing local ingress ordinal;
4. decodes or copies into an owned reusable frame buffer before returning;
5. records receive and decode timing with `System.nanoTime()`.

Malformed frames increment a reason-specific counter and return a rate-limited JSON error. Repeated violations use the existing abuse policy. A single incompatible frame does not crash the decoder thread or main tick.

### 9.2 Owned snapshots

Generated SBE decoders are flyweights over the supplied buffer, so no decoder or source buffer crosses the callback boundary. Decoded values are copied into preallocated primitive arrays owned by a snapshot slot.

Each zone uses a bounded `ZoneRenderSnapshot` slot pool sized from maximum publisher concurrency plus producer and consumer headroom. A slot moves explicitly through `FREE`, `WRITING`, `PUBLISHED`, and `READING` states, so a producer can never overwrite arrays while the main thread applies them. A producer must claim a slot exclusively before decoding; publishing a newer slot safely releases any older still-pending slot. Publication exposes the slot only after all fields are written. There are no per-entity records, maps, strings, `Location` objects, or `Transformation` objects in the off-thread binary representation.

JSON fallback is normalized into the same snapshot interface. It may allocate while parsing, but it receives the same latest-wins and event-latching semantics.

### 9.3 Latest-frame mailbox

Every decoded zone snapshot carries:

- connection epoch;
- sender frame sequence;
- local ingress ordinal;
- dictionary revision;
- receive timestamp;
- decoded audio and entity arrays.

Ingress rejects a sender frame sequence that is not newer within the selected connection epoch. Publication then accepts a snapshot only if its epoch and dictionary revision match and its ingress ordinal is newer than the currently published ordinal. The ingress ordinal resolves out-of-order completion when multiple decoder workers parse messages concurrently. A stale worker may never overwrite a newer completed snapshot.

The mailbox holds exactly one pending state snapshot per zone. Replacing it increments `render_frames_superseded`; it is not an error and does not produce backpressure.

### 9.4 Event latch

Beat and kick flags are OR-latched per zone between main-thread drains. Beat intensity takes the maximum observed value, and the newest complete audio state remains in the snapshot. Event sequence tracking prevents duplicate application.

Particle events enter a bounded per-zone MPSC accumulator. The bound is derived from the configured particle work budget. When full, identical events are coalesced where safe; otherwise the newest event is rejected and counted rather than allowing unbounded memory growth.

### 9.5 Tick drain

A single task already scheduled every tick performs this order:

1. capture tick-start monotonic time;
2. atomically take each zone's newest render snapshot and event latch;
3. transform and apply the snapshot once;
4. apply latched transient effects once;
5. drain control work within its configured budget;
6. record apply duration and receive-to-apply age.

If multiple render frames arrive during a tick, only the newest state is applied. If a frame arrives after the zone is drained, it remains pending for the next tick. No second scheduler hop is introduced.

## 10. Main-thread rendering optimizations

### 10.1 Coordinate transform

Zone rotation trigonometry and basis vectors are recomputed only when zone origin, size, or rotation changes. Per entity, local coordinates are transformed with the cached basis:

```text
world = origin
      + right   * (x * width)
      + up      * (y * height)
      + forward * (z * depth)
```

The hot loop writes primitive coordinates directly into reusable Bukkit objects where the API permits. It does not call `Math.sin`, `Math.cos`, `Location.clone`, or parse entity IDs for every entity on every tick.

### 10.2 Pool addressing

The entity pool exposes indexed access for the dense binary path. JSON retains string lookup. The pool validates the active prefix once per zone snapshot rather than checking each string ID.

### 10.3 Interpolation policy

One authoritative configuration replaces the current mismatch between a configured one-tick default and a hardcoded two-tick application:

- `responsive`: zero ticks, for minimum visual lag and deliberate stepping;
- `balanced`: one tick, the default;
- `smooth`: a configurable duration greater than one tick.

The selected tick count is encoded explicitly in complete binary snapshots or inherited from a negotiated zone default. The sender and plugin never maintain contradictory hidden defaults.

Beat glow duration is a named configuration expressed in milliseconds or beat fraction and converted to ticks with a ceiling formula. The existing hardcoded three-tick value is removed during implementation only after its replacement and compatibility tests exist.

## 11. VJ sender behavior

The VJ connection begins in JSON mode, observes advertised capabilities, authenticates, selects `sbe.v1`, installs dictionaries, and only then sends binary frames. Any rejection keeps or returns the connection to JSON without interrupting rendering.

The sender maintains one reusable encoder buffer and at most one unsent render frame. If a newer scene is produced while the socket is not ready, it replaces the pending state while merging transient events. It never accumulates a FIFO of obsolete render frames.

All active zones for one scene calculation are placed in one `RenderFrame`, so audio values are encoded once and frame sequence represents a coherent scene. If the calculated frame exceeds the negotiated byte limit, the sender rejects it locally with a reason-specific metric; it does not silently truncate entities.

The Python encoder validates and clamps at its external boundary. Internal scene code uses canonical semantic values and named policy. `msgspec` remains available for the JSON path.

## 12. Error handling and lifecycle

- A disconnect invalidates the epoch, pending snapshots, event latches, and dictionaries for that connection.
- Zone removal or pool rebuild invalidates affected pending snapshots before Bukkit entities are changed.
- Unknown optional SBE fields are handled through normal SBE versioning; unknown required templates are rejected.
- Non-finite Python or JSON floating-point inputs are rejected before quantization.
- Counts are validated against remaining bytes and negotiated limits before loops begin.
- Material and particle names are validated on the JSON control plane before numeric IDs are assigned.
- Decoder exceptions are contained at the message boundary and never escape into the WebSocket selector or Paper tick loop.
- Log messages are structured and rate-limited by connection, error reason, and time window.

## 13. Telemetry

The plugin records bounded, allocation-conscious counters and rolling histograms for:

- binary and JSON render frames received;
- frames decoded, published, superseded, stale, malformed, unauthorized, or revision-mismatched;
- payload bytes by protocol;
- decode duration;
- mailbox wait duration;
- main-thread apply duration;
- receive-to-apply duration;
- snapshot age at apply;
- entity and particle counts;
- beat events latched, applied, and deduplicated;
- particle events coalesced or rejected;
- protocol selections and fallbacks.

Local latency uses monotonic receive timestamps. Cross-process source-to-plugin latency is reported only when clocks are known to be comparable; otherwise sender timestamps are exposed separately and not presented as an exact one-way measurement.

The implementation does not add HdrHistogram initially. A fixed-memory rolling histogram is sufficient and avoids an unnecessary runtime dependency while current dependency advisories remain disputed. JMH results use its normal benchmark statistics outside the production plugin.

## 14. Libraries

Production dependencies are intentionally narrow:

- Real Logic SBE for the canonical schema and generated Java codecs.
- Agrona for direct-buffer flyweights and low-allocation primitives.
- Existing Java-WebSocket for the connection and binary callback.
- Existing Gson and Python `msgspec` for JSON control and fallback traffic.

Test and benchmark dependencies:

- JMH in a separate benchmark module so it is not shaded into the plugin JAR.
- jcstress for mailbox and event-latch concurrency verification.
- Existing JUnit and pytest suites for unit and integration coverage.

Netty, Disruptor, JCTools, simdjson-java, compression libraries, and Jackson are not added without benchmark evidence that a remaining bottleneck requires them.

## 15. Verification strategy

### 15.1 Protocol tests

- Generate one canonical golden frame containing multiple zones, all audio fields, visibility states, multiple materials, and particle events.
- Assert the Python encoder bytes decode to exact semantic values in Java.
- Assert Java-generated encoder bytes match the checked-in golden vector where the schema defines identical values.
- Verify quantization endpoints, midpoint error bounds, wraparound rotation, null BPM, and maximum legal counts.
- Verify stale generated Python layout detection.
- Verify JSON Schema and implementation ranges agree.

### 15.2 Compatibility tests

- An unchanged legacy VJ client connects and renders through JSON.
- A new VJ client connects to an old plugin and remains on JSON.
- A new client negotiates binary with a new plugin and may fall back to JSON per unsupported message.
- Authentication-required and authentication-disabled flows both gate selection correctly.
- Reconnect, epoch replacement, dictionary revision, and protocol downgrade are covered.

### 15.3 Concurrency and failure tests

- Decoder worker A begins first but completes after worker B; B remains published.
- Many frames for one zone collapse to one newest snapshot.
- A beat in a superseded frame is still applied once.
- Duplicate event IDs are applied once.
- Disconnect and zone deletion race safely with publication and tick drain.
- Truncated groups, forged counts, oversized frames, unknown templates, non-selected binary traffic, and fuzzed payloads cannot allocate beyond limits or escape exceptions.
- jcstress exercises snapshot publication visibility and latch drain races.

### 15.4 Benchmarks

JMH runs outside the production module with 64, 160, and 256 entities, one and multiple zones. It compares:

- current Gson JSON parse and normalization;
- SBE validation, decode, and publication;
- payload size;
- bytes and objects allocated per operation;
- zone transform and entity apply preparation.

The Python suite benchmarks JSON serialization against reusable binary encoding for the same fixtures. Benchmark commands and reference hardware are documented so results are reproducible.

### 15.5 End-to-end test

An integration fixture starts the actual plugin WebSocket layer with a test-safe scheduler, connects the Python VJ client, negotiates `sbe.v1`, installs dictionaries, sends a golden scene, advances one tick, and asserts the normalized applied snapshot plus latched beat behavior. The same fixture runs once with forced JSON fallback.

## 16. Performance acceptance criteria

On documented reference hardware and the 256-entity representative fixture:

- The SBE payload is no more than 25 percent of the equivalent current JSON payload.
- Steady-state binary decode performs zero per-entity heap allocations.
- Binary validation, decode, and publication p95 is at most 1 ms after warmup.
- Plugin main-thread render work p95 remains within the existing 3 ms Paper adapter budget.
- A decoded frame available before a zone's tick drain is applied during that drain, with no extra application scheduler cycle.
- A render burst cannot grow a FIFO of stale state frames.
- A beat contained only in a superseded frame is still observed exactly once.

These are release gates, not promises about total capture-to-photon latency. Total latency still includes audio capture, analysis, network, Minecraft tick phase, client rendering, and display scanout.

## 17. Rollout

Implementation proceeds in reversible stages:

1. Canonicalize current JSON units, fields, configuration keys, timing policy, and telemetry without changing the wire default.
2. Add plugin mailboxes and event latches behind JSON, proving the scheduling behavior before binary is introduced.
3. Add the SBE schema, generated Java codecs, Python layout generation, and golden vectors.
4. Add capability negotiation and plugin binary decode while keeping JSON as default.
5. Add Python binary encoding and opt-in configuration.
6. Run compatibility, concurrency, malformed-input, benchmark, and end-to-end suites.
7. Make `sbe.v1` preferred when both peers advertise it; retain explicit JSON override.
8. Observe production metrics before considering removal of any legacy optimization or dependency.

No existing file, protocol, or behavior is deleted as part of an intermediate stage. Any later removal requires separate approval and evidence that compatibility is no longer needed.

## 18. Definition of done

The goal is complete when:

- protocol schemas and generated artifacts are reproducible from source;
- Java and Python agree on golden binary vectors;
- the plugin safely handles negotiated binary frames and unchanged JSON frames;
- the VJ server automatically negotiates binary and falls back cleanly;
- newest-state mailboxes and durable event latches are active for both protocols;
- interpolation, units, limits, and configuration names have one authoritative definition;
- receive, decode, wait, apply, fallback, discard, and event metrics are observable;
- affected Maven and WSL Python tests pass;
- JMH, jcstress, Python encoding benchmarks, and the end-to-end fixture pass their documented gates;
- user-facing protocol and deployment documentation is updated;
- a real Paper server smoke test renders a live VJ scene through `sbe.v1` and through forced JSON fallback.
