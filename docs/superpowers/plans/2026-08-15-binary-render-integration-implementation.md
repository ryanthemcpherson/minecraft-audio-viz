# Binary Render Integration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Negotiate `sbe.v1`, receive and apply binary render frames in the Java Paper plugin, send coherent multi-zone frames from Python, and fall back automatically to unchanged JSON peers and unsupported render data.

**Architecture:** JSON authentication remains unchanged. The plugin advertises optional render capabilities per connection, accepts a JSON protocol-selection and dictionary handshake, then decodes authenticated WebSocket binary messages synchronously into the existing render hub before the callback returns. The VJ client partitions eligible dense block-display zones into one latest-wins binary scene frame and sends unsupported zones through the existing JSON method.

**Tech Stack:** Java 21, Paper 1.21.11, Java-WebSocket 1.6.0, SBE 1.39.0, Agrona 2.5.0, Python 3.11+, websockets, msgspec, pytest, JUnit 5

**Spec:** `docs/superpowers/specs/2026-08-15-low-latency-render-pipeline-design.md`

## Global Constraints

- Complete the render-foundation and SBE-codec plans first.
- `connected`, authentication, selection, dictionaries, errors, and all other control messages remain JSON text.
- A connection remains on `json.v1` unless it explicitly selects `sbe.v1` after authentication.
- The plugin advertises `sbe.v1` only when the Agrona runtime probe succeeds.
- Binary input is rejected before authentication, before selection, after session invalidation, above the calculated limit, or with the wrong epoch/revision/version.
- The Java-WebSocket callback buffer is never retained after `onMessage(WebSocket, ByteBuffer)` returns.
- One binary frame contains the shared audio state and every eligible active zone from one VJ scene calculation.
- The sender holds no FIFO of obsolete render frames; one current send and one replaceable pending state are the maximum.
- Unsupported entity fields or unknown dictionaries trigger per-message JSON fallback, not connection failure.
- Existing `batch_update_fast()` remains public and behaves as before except that complete canonical audio metadata is no longer dropped.
- Python changes and tests run through WSL `.venv`.
- No existing protocol or method is removed.

---

## File map

- JSON schemas under `protocol/schemas/messages/`: capability advertisement, selection, dictionary request, and acknowledgements.
- `minecraft_plugin/src/main/java/com/audioviz/render/RenderProtocolSession.java`: per-connection epoch, selected codec, sequence, revision, limits, and mappings.
- `minecraft_plugin/src/main/java/com/audioviz/render/RenderProtocol.java`: negotiated JSON/SBE protocol enum.
- `minecraft_plugin/src/main/java/com/audioviz/render/RenderProtocolSessionView.java`: immutable decoder-facing session state.
- `minecraft_plugin/src/main/java/com/audioviz/render/RenderProtocolNegotiator.java`: JSON selection and dictionary state machine.
- `minecraft_plugin/src/main/java/com/audioviz/render/RenderDictionary.java`: immutable numeric zone/material/particle mappings.
- `minecraft_plugin/src/main/java/com/audioviz/render/SbeRenderFrameCodec.java`: live decode into foundation mailboxes.
- `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`: capability advertisement, control interception, and binary callback.
- `minecraft_plugin/src/main/java/com/audioviz/entities/EntityPoolManager.java`: prevalidated material-ID application.
- `vj_server/render_protocol/session.py`: client-side negotiated state and dictionary maps.
- `vj_server/render_protocol/latest_sender.py`: one-current/one-pending render send coalescer.
- `vj_server/viz_client.py`: negotiation, dictionary installation, binary/JSON partition, and complete metadata.
- `vj_server/relay.py`: prepare all zones and call one multi-zone render API.
- `vj_server/vj_server.py`: immediate beat-aware pacing and frame/source timing.
- Java and WSL Python tests cover compatibility, negotiation, binary routing, fallback, reconnect, and coalescing.

### Task 1: Versioned JSON negotiation schemas

**Files:**
- Modify: `protocol/schemas/messages/connected.schema.json`
- Create: `protocol/schemas/messages/select-render-protocol.schema.json`
- Create: `protocol/schemas/messages/render-protocol-selected.schema.json`
- Create: `protocol/schemas/messages/define-render-dictionary.schema.json`
- Create: `protocol/schemas/messages/render-dictionary-defined.schema.json`
- Modify: `protocol/schemas/index.json`
- Modify: `protocol/tests/phase0-schemas.test.mjs`

**Interfaces:**
- Produces exact JSON message names `select_render_protocol`, `render_protocol_selected`, `define_render_dictionary`, and `render_dictionary_defined`.
- Produces optional `connected.render_protocols`, `preferred_render_protocol`, and `render_protocol_epoch`.

- [ ] **Step 1: Write failing schema inventory and validation tests**

```javascript
test("render protocol negotiation is versioned and inventoried", () => {
  const inventory = readJson(schemaIndexPath);
  for (const name of [
    "select_render_protocol",
    "render_protocol_selected",
    "define_render_dictionary",
    "render_dictionary_defined",
  ]) {
    assert.equal(typeof inventory.messages[name], "string");
    assert.equal(existsSync(resolve(repositoryRoot, "protocol/schemas", inventory.messages[name])), true);
  }
});

test("legacy connected payload remains valid without binary fields", () => {
  const schema = readHandshakeSchema("connected");
  assertValid(schema, { type: "connected", auth_required: false, server_type: "paper" });
});
```

Extend the test helper to validate arrays, integers, minima/maxima, and required nested object properties used by these schemas.

- [ ] **Step 2: Run protocol tests and verify missing inventory entries fail**

Run: `node --test protocol/tests/phase0-schemas.test.mjs`

Expected: FAIL.

- [ ] **Step 3: Define capability advertisement without breaking legacy peers**

Add optional connected fields:

```json
"render_protocols": {
  "type": "array",
  "minItems": 1,
  "uniqueItems": true,
  "items": { "enum": ["json.v1", "sbe.v1"] }
},
"preferred_render_protocol": { "enum": ["json.v1", "sbe.v1"] },
"render_protocol_epoch": { "type": "integer", "minimum": 0, "maximum": 4294967295 }
```

Do not add them to `required`.

- [ ] **Step 4: Define selection and dictionary contracts**

`select_render_protocol` requires `type`, `protocol`, and `epoch`. The selected response requires protocol, epoch, `dictionary_revision`, calculated frame/count limits, and a zone array containing numeric `id`, string `name`, and `pool_size`.

`define_render_dictionary` requires epoch, `expected_revision`, unique material strings, and unique particle strings. The response returns the new revision and arrays of `{id,name}` mappings. IDs are unsigned 16-bit values greater than zero; zero remains the protocol default sentinel.

- [ ] **Step 5: Run schema tests**

Run: `node --test protocol/tests/phase0-schemas.test.mjs`

Expected: PASS, including unchanged legacy `connected` fixtures.

- [ ] **Step 6: Commit negotiation contracts**

```powershell
git add protocol/schemas protocol/tests/phase0-schemas.test.mjs
git commit -m "feat: define binary render negotiation contracts"
```

### Task 2: Per-connection render session and dictionary state machine

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderProtocolSession.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderProtocol.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderProtocolSessionView.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderDictionary.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderProtocolNegotiator.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/RenderProtocolSessionTest.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/RenderProtocolNegotiatorTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/entities/EntityPoolManager.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/entities/DenseRenderApplyTest.java`

**Interfaces:**
- Produces: `RenderProtocolSession.create(long epoch, RenderProtocolLimits limits)`.
- Produces: `select(String protocol, long epoch)`, `installDictionary(...)`, `runAdmittedFrame(long epoch, int revision, long sequence, FrameAction action)`, `invalidate()`, and immutable `view()`.
- Produces: `RenderProtocolNegotiator.handle(JsonObject request, RenderProtocolSession session) -> JsonObject`.
- Produces: `EntityPoolManager.applyMaterialId(BlockDisplay display, int materialId, RenderDictionary dictionary)`.

- [ ] **Step 1: Write failing session transition tests**

```java
@Test
void defaultsToJsonAndRequiresMatchingEpochForSbe() {
    RenderProtocolSession session = RenderProtocolSession.create(1234, limits);
    assertEquals(RenderProtocol.JSON_V1, session.view().protocol());
    assertFalse(session.select("sbe.v1", 999));
    assertTrue(session.select("sbe.v1", 1234));
    assertEquals(RenderProtocol.SBE_V1, session.view().protocol());
}

@Test
void sequenceAndDictionaryRevisionAreMonotonic() {
    RenderProtocolSession session = selectedSession();
    assertTrue(session.runAdmittedFrame(EPOCH, REVISION, 10, () -> { }));
    assertFalse(session.runAdmittedFrame(EPOCH, REVISION, 10, () -> { }));
    assertFalse(session.runAdmittedFrame(EPOCH, REVISION, 9, () -> { }));
    assertTrue(session.runAdmittedFrame(EPOCH, REVISION, 11, () -> { }));
    assertThrows(IllegalStateException.class,
        () -> session.installDictionary(99, List.of("SEA_LANTERN"), List.of("NOTE")));
}
```

Add invalidation and unsigned epoch boundary tests.

- [ ] **Step 2: Write failing dictionary validation tests**

Assert duplicate names, invalid/non-block materials, unknown particles, excessive entries, stale expected revision, and Bukkit validation off the main thread are rejected. Assert IDs are stable within a revision and never use zero.

- [ ] **Step 3: Run tests and verify missing classes fail**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=RenderProtocolSessionTest,RenderProtocolNegotiatorTest,DenseRenderApplyTest test`

Expected: FAIL at test compilation.

- [ ] **Step 4: Implement an explicit session state machine**

Use an enum:

```java
public enum RenderProtocol { JSON_V1("json.v1"), SBE_V1("sbe.v1"); }
```

Keep epoch, protocol, revision, last sequence, validity, calculated limits, and dictionary behind synchronized mutation methods; expose an immutable `RenderProtocolSessionView` record to the decoder. `runAdmittedFrame` holds the per-connection session monitor while it validates epoch/revision/sequence, runs the synchronous decode-and-publish action, and advances the unsigned last sequence only after the action returns successfully. This prevents two callbacks for one connection from committing out of order without adding a global lock. Invalidate clears mappings and makes all future selections/frames fail.

- [ ] **Step 5: Validate dictionaries on the Paper main thread**

`RenderProtocolNegotiator` uses the plugin's existing main-thread call mechanism for low-rate dictionary validation. Convert material names to immutable `BlockData[]` and particle names to immutable `Particle[]`; return only validated numeric maps. Never encode `Material.ordinal()` or `Particle.ordinal()`.

Build zone IDs deterministically by sorting current zone names case-insensitively and assigning IDs from one upward for the current revision. Include each current block pool size in the selection response.

- [ ] **Step 6: Apply material IDs without string lookup**

In the dense snapshot hot path, resolve `BlockData` directly from the session dictionary array. Cache the last numeric material ID by indexed entity slot and call `setBlock` only when it changes. JSON fallback continues using material strings.

- [ ] **Step 7: Run session, dictionary, and dense-apply tests**

Expected: PASS.

- [ ] **Step 8: Commit session and dictionaries**

```powershell
git add minecraft_plugin/src/main/java/com/audioviz/render minecraft_plugin/src/main/java/com/audioviz/entities/EntityPoolManager.java minecraft_plugin/src/test/java/com/audioviz/render minecraft_plugin/src/test/java/com/audioviz/entities/DenseRenderApplyTest.java
git commit -m "feat: add binary render sessions and dictionaries"
```

### Task 3: Plugin capability advertisement and control interception

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerAuthTest.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerRoutingTest.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerNegotiationTest.java`

**Interfaces:**
- Consumes: `AgronaRuntimeSupport`, `RenderProtocolSession`, and `RenderProtocolNegotiator`.
- Produces: one session owned by each `ClientInfo` and JSON selection responses scoped to that connection.

- [ ] **Step 1: Write failing advertisement-mode tests**

```java
@Test
void welcomeAdvertisesSbeOnlyWhenRuntimeIsAvailable() {
    server = newServer(runtimeAvailable(true));
    server.onOpen(connection, handshake);
    JsonObject welcome = firstSentJson();
    assertEquals(List.of("json.v1", "sbe.v1"), strings(welcome, "render_protocols"));
    assertEquals("sbe.v1", welcome.get("preferred_render_protocol").getAsString());
}

@Test
void unavailableAgronaKeepsJsonAndLogsOneActionableWarning() {
    server = newServer(runtimeAvailable(false));
    server.onOpen(connection, handshake);
    assertEquals(List.of("json.v1"), strings(firstSentJson(), "render_protocols"));
    assertLogContains(AgronaRuntimeSupport.requiredJvmArgument());
}
```

Add authenticated selection, pre-auth rejection, wrong epoch, repeated idempotent selection, dictionary revision, and per-connection isolation tests.

- [ ] **Step 2: Run negotiation tests and verify absent capabilities fail**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=VizWebSocketServerNegotiationTest,VizWebSocketServerAuthTest test`

Expected: FAIL.

- [ ] **Step 3: Create each session during `onOpen`**

Generate a nonzero unsigned 32-bit epoch with `SecureRandom.nextInt()` converted through `Integer.toUnsignedLong`. Store the session in `ClientInfo`; add advertised fields to the welcome before authentication. Runtime unavailability must be detected once during plugin/server initialization, not for every connection.

- [ ] **Step 4: Intercept selection controls after JSON parsing**

In `dispatchParsedMessage`, before generic `MessageHandler` routing:

```java
if (RenderProtocolNegotiator.isNegotiationType(type)) {
    JsonObject response = negotiator.handle(message, info.renderSession());
    if (seq >= 0) response.addProperty("_seq", seq);
    sendToActiveClient(conn, info, gson.toJson(response));
    return;
}
```

The existing active-client operation lease surrounds this call. `onClose` invalidates the session and removes only snapshots/events carrying its epoch; it must not clear a newer epoch published by another connection.

- [ ] **Step 5: Run negotiation, auth, routing, and shutdown tests**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=VizWebSocketServerNegotiationTest,VizWebSocketServerAuthTest,VizWebSocketServerRoutingTest,WebSocketStartupManagerTest test`

Expected: PASS.

- [ ] **Step 6: Commit plugin negotiation**

```powershell
git add minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java minecraft_plugin/src/test/java/com/audioviz/websocket
git commit -m "feat: negotiate binary render sessions"
```

### Task 4: Authenticated Java-WebSocket binary receive path

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/render/SbeRenderFrameCodec.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerBinaryTest.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/render/SbeRenderFrameCodecTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/render/RenderTelemetry.java`

**Interfaces:**
- Produces: `onMessage(WebSocket, ByteBuffer)` with the same connection-operation lease discipline as text input.
- Produces: `SbeRenderFrameCodec.decode(ByteBuffer payload, RenderProtocolSessionView view, long ingressOrdinal, long receivedNanos)` as the allocation-free production overload deferred by the codec plan.
- Consumes: `RenderProtocolSessionView`, `RenderFrameHub`, and calculated maximum frame bytes inside the session admission action.

- [ ] **Step 1: Write failing binary boundary tests**

Cover valid selected frame publication and each independent rejection: unknown connection, pending authentication, active JSON-only session, oversized payload, wrong epoch, wrong dictionary revision, stale frame sequence, malformed/truncated SBE, closed generation, and runtime unavailable.

```java
@Test
void selectedAuthenticatedBinaryFramePublishesBeforeCallbackReturns() {
    openAuthenticateSelectAndDefineDictionary();
    ByteBuffer payload = goldenFrameFor(sessionView());

    server.onMessage(connection, payload);

    assertNotNull(renderHub.peekLatest("main"));
    assertEquals(1, metrics.binaryFramesPublished());
}
```

Also mutate the source byte array after return and assert the owned snapshot does not change.

- [ ] **Step 2: Run binary tests and verify callback is absent**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=VizWebSocketServerBinaryTest,SbeRenderFrameCodecTest test`

Expected: FAIL.

- [ ] **Step 3: Implement the binary callback with fail-closed ordering**

The callback order is exact:

1. increment binary-received metrics and capture `System.nanoTime()`;
2. locate active `ClientInfo` and acquire its operation lease;
3. require selected `SBE_V1` and runtime capability;
4. compare `payload.remaining()` to session-calculated maximum;
5. structurally read the fixed header, then enter `session.runAdmittedFrame(...)`;
6. decode synchronously through `SbeRenderFrameCodec`, validate the full body, and publish claimed zone slots and latched events while admission is held;
7. advance the session sequence only after successful publication;
8. release the operation lease before returning.

Catch decoder/runtime exceptions at this boundary. Emit a structured, rate-limited JSON error with a stable reason code such as `render_binary_malformed`; never echo bytes or credentials.

- [ ] **Step 4: Make multi-zone publication atomic for an invalid frame**

The codec claims and fills every referenced zone slot first. It validates the entire frame and session sequence before publishing any slot. On any failure it returns every claimed slot to `FREE`. After full validation, latch transient events and publish zones. Valid publication may be latest-wins per zone; malformed input is all-or-nothing.

- [ ] **Step 5: Run binary, security, and malformed-input tests**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=VizWebSocketServerBinaryTest,SbeRenderFrameCodecTest,VizWebSocketServerFrameLimitTest,WebSocketSecurityPolicyTest test`

Expected: PASS.

- [ ] **Step 6: Commit the Java binary receiver**

```powershell
git add minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java minecraft_plugin/src/main/java/com/audioviz/render minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerBinaryTest.java minecraft_plugin/src/test/java/com/audioviz/render/SbeRenderFrameCodecTest.java
git commit -m "feat: receive authenticated SBE render frames"
```

### Task 5: Python negotiated render session

**Files:**
- Create: `vj_server/render_protocol/session.py`
- Modify: `vj_server/render_protocol/__init__.py`
- Modify: `vj_server/viz_client.py`
- Modify: `vj_server/tests/test_viz_client_auth.py`
- Create: `vj_server/tests/test_viz_client_render_negotiation.py`

**Interfaces:**
- Produces: `RenderSessionState.reset()`, `selected`, `epoch`, `dictionary_revision`, zone/material/particle maps, and negotiated limits.
- Produces: `VizClient.render_protocol` read-only property.
- Produces: `_negotiate_render_protocol(welcome)` and `_ensure_render_dictionary(materials, particles)`.

- [ ] **Step 1: Write failing old/new peer compatibility tests**

```python
async def test_old_plugin_without_capabilities_stays_json(fake_plugin) -> None:
    fake_plugin.welcome = {"type": "connected", "auth_required": False, "server_type": "paper"}
    client = await connected_client(fake_plugin)
    assert client.render_protocol == "json.v1"
    assert fake_plugin.received_types == []

async def test_new_plugin_selects_sbe_after_authentication(fake_plugin) -> None:
    fake_plugin.require_auth = True
    fake_plugin.advertise_sbe(epoch=123)
    fake_plugin.accept_selection(dictionary_revision=0)
    client = await connected_client(fake_plugin, auth_token="secret")
    assert fake_plugin.received_types[:2] == ["auth", "select_render_protocol"]
    assert client.render_protocol == "sbe.v1"
```

Add selection rejection, wrong/malformed response, missing required limits, dictionary update, reconnect reset, and JSON override tests.

- [ ] **Step 2: Run WSL negotiation tests and verify failure**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
source .venv/bin/activate
pytest tests/test_viz_client_render_negotiation.py tests/test_viz_client_auth.py -q
```

Expected: FAIL.

- [ ] **Step 3: Implement resettable negotiated state**

Use a slotted dataclass containing protocol, epoch, revision, calculated limits, and immutable dicts. Validate every peer-provided integer as a real `int` rather than a `bool`, enforce unsigned bounds, unique IDs/names, and maximum counts before installing.

Reset render state in `_drain_transport`, `_close_failed_connection`, reconnect startup, and any receive-loop transport failure.

- [ ] **Step 4: Negotiate after authentication without introducing a second receiver**

Pass the parsed welcome into `_negotiate_render_protocol`. Skip when `sbe.v1` is absent or explicit client option `render_protocol="json.v1"` is set. With the receive loop enabled, use the existing `_seq` future router; without it, use the existing single `send`/`recv` path. A negotiation failure logs one warning and leaves the overall connection healthy on JSON.

- [ ] **Step 5: Install dictionaries under one async lock**

Before binary encoding, compare required names to current maps. If names are missing, reliably send `define_render_dictionary` with `expected_revision`, validate the response, and atomically replace maps. Concurrent callers share one `_dictionary_lock` and recheck after acquiring it.

- [ ] **Step 6: Run WSL negotiation/auth tests**

Expected: PASS.

- [ ] **Step 7: Commit Python negotiation**

```powershell
git add vj_server/render_protocol vj_server/viz_client.py vj_server/tests/test_viz_client_render_negotiation.py vj_server/tests/test_viz_client_auth.py
git commit -m "feat: negotiate SBE rendering from the VJ client"
```

### Task 6: One-current/one-pending Python render sender

**Files:**
- Create: `vj_server/render_protocol/latest_sender.py`
- Test: `vj_server/tests/test_latest_render_sender.py`
- Modify: `vj_server/viz_client.py`
- Create: `vj_server/tests/test_viz_client_binary_render.py`

**Interfaces:**
- Produces: `LatestRenderSender.submit(RenderSubmission)`, `reset()`, and `sending`.
- Produces: `VizClient.render_frame_fast(zones: Sequence[ZoneFrame], audio: Mapping[str, Any]) -> None`.
- Keeps: `VizClient.batch_update_fast(...)` as JSON compatibility API.

- [ ] **Step 1: Write failing coalescing and event-merge tests**

```python
async def test_slow_socket_keeps_only_current_and_latest_pending() -> None:
    transport = BlockingTransport()
    sender = LatestRenderSender(transport.send)
    await sender.submit(frame(sequence=1, scale=0.1))
    await transport.started.wait()
    await sender.submit(frame(sequence=2, scale=0.2, beat=True, intensity=0.4))
    await sender.submit(frame(sequence=3, scale=0.3, beat=False, intensity=0.0))
    transport.release.set()
    await sender.idle()
    assert [item.sequence for item in transport.sent] == [1, 3]
    assert transport.sent[1].beat is True
    assert transport.sent[1].beat_intensity == pytest.approx(0.4)
```

Add particle deduplication, bounded overflow, disconnect reset, send exception, and no background-task leak tests.

- [ ] **Step 2: Write failing binary/fallback partition tests**

Assert eligible dense zones share one binary send; a text entity or sparse ID falls back through `batch_update_fast`; unknown material triggers one dictionary roundtrip; dictionary failure falls back to JSON; old plugin produces only text frames; and all canonical audio metadata appears in both paths.

- [ ] **Step 3: Run WSL sender tests and verify failure**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
source .venv/bin/activate
pytest tests/test_latest_render_sender.py tests/test_viz_client_binary_render.py -q
```

Expected: FAIL.

- [ ] **Step 4: Implement event-loop-atomic coalescing**

No await occurs between testing and setting the sending flag:

```python
async def submit(self, submission: RenderSubmission) -> None:
    if self._sending:
        self._pending = merge_latest_state_and_events(self._pending, submission)
        return
    self._sending = True
    try:
        current: RenderSubmission | None = submission
        while current is not None:
            await self._send(current)
            current, self._pending = self._pending, None
    finally:
        self._sending = False
```

Replacement takes the newest complete state and merges beat/kick by OR, intensity by maximum, and unique particle event IDs up to the negotiated limit. Overflow is counted and rejects newest excess events.

- [ ] **Step 5: Implement binary eligibility and multi-zone encoding**

`render_frame_fast` validates every entity ID against its dense index, rejects unsupported fields such as `text`, resolves material/particle IDs, and constructs one `RenderFrameInput` for eligible zones. It submits encoded bytes to `LatestRenderSender`. Unsupported zones call the existing text `batch_update_fast` individually.

Complete the legacy JSON audio copy:

```python
for key in (
    "bands", "amplitude", "is_beat", "beat_intensity", "bpm",
    "tempo_confidence", "beat_phase", "frame", "source_time_ns", "generated_time_ns",
):
    if key in audio:
        message[key] = audio[key]
```

Clamp canonical normalized values to one rather than five.

- [ ] **Step 6: Run WSL sender and compatibility tests**

Expected: PASS.

- [ ] **Step 7: Commit latest-wins binary sending**

```powershell
git add vj_server/render_protocol/latest_sender.py vj_server/viz_client.py vj_server/tests/test_latest_render_sender.py vj_server/tests/test_viz_client_binary_render.py
git commit -m "perf: send latest multi-zone SBE frames"
```

### Task 7: Relay one coherent multi-zone scene and preserve immediate beats

**Files:**
- Modify: `vj_server/relay.py`
- Modify: `vj_server/vj_server.py`
- Modify: `vj_server/tests/test_relay.py`
- Create: `vj_server/tests/test_minecraft_render_pacing.py`

**Interfaces:**
- Consumes: `VizClient.render_frame_fast`.
- Produces: `_prepare_minecraft_zone(...) -> PreparedZoneFrame | None` and one render call per scene.
- Produces: immediate beat send plus stable periodic pacing without duplicate transient IDs.

- [ ] **Step 1: Write failing multi-zone aggregation tests**

```python
async def test_all_zones_from_one_scene_use_one_render_call(relay, viz_client) -> None:
    relay.configure_entity_zones("left", "right")
    await relay.render_minecraft_scene(audio_frame(frame=77))
    assert viz_client.render_frame_fast.await_count == 1
    zones, audio = viz_client.render_frame_fast.await_args.args
    assert [zone.name for zone in zones] == ["left", "right"]
    assert audio["frame"] == 77
```

Add bitmap-audio-only zone, transition high-water hiding, band-material overrides, blackout/freeze, failure isolation, and no mutation of cached `last_entities` tests.

- [ ] **Step 2: Write failing beat-aware pacing tests**

Use an injected monotonic clock. Assert a beat at 17 ms after the previous periodic send triggers an immediate Minecraft render; the next periodic deadline advances without a burst; and a beat occurring while a socket send is active is merged into the one pending frame.

- [ ] **Step 3: Run WSL relay tests and verify current per-zone calls fail**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
source .venv/bin/activate
pytest tests/test_relay.py tests/test_minecraft_render_pacing.py -q
```

Expected: FAIL because the relay currently awaits `batch_update_fast` once per zone and only sends on a 50 ms cadence.

- [ ] **Step 4: Separate zone preparation from transport**

Refactor the current `_update_minecraft_zone` sanitation, pool high-water, hidden-tail, particle, and bitmap logic into `_prepare_minecraft_zone` returning immutable prepared data. Copy lists before appending hidden tail entries so cached pattern output is not mutated. Keep `_update_minecraft_zone` as a compatibility wrapper that prepares one zone and calls `render_frame_fast`.

- [ ] **Step 5: Send one prepared scene**

In the main relay loop, gather prepared zones in stable name order and call `render_frame_fast` once. Build one canonical audio mapping with:

```python
{
    "bands": tuple(clamp_unit(value) for value in visual_bands[:5]),
    "amplitude": clamp_unit(visual_peak),
    "is_beat": bool(is_beat),
    "beat_intensity": clamp_unit(visual_beat_intensity),
    "bpm": clamp(bpm, 0.0, 300.0),
    "tempo_confidence": clamp_unit(tempo_confidence),
    "beat_phase": clamp_unit(beat_phase),
    "frame": self._frame_count,
    "generated_time_ns": time.monotonic_ns(),
}
```

Include `source_time_ns` only when it represents a local monotonic timestamp in the same VJ process.

- [ ] **Step 6: Make beats bypass periodic pacing safely**

Set `should_send_mc_this_frame` when either the periodic deadline is due or a new beat event is present. Assign the transient `event_sequence` from the source/frame sequence and rely on sender/plugin deduplication. Advance the periodic deadline with the existing catch-up formula; do not add another sleep or timer task.

- [ ] **Step 7: Run WSL relay/pacing tests**

Expected: PASS.

- [ ] **Step 8: Commit coherent relay scenes**

```powershell
git add vj_server/relay.py vj_server/vj_server.py vj_server/tests/test_relay.py vj_server/tests/test_minecraft_render_pacing.py
git commit -m "perf: relay coherent multi-zone render frames"
```

### Task 8: Integration regression verification

**Files:**
- Modify only when a failing test proves an integration defect.

**Interfaces:**
- Produces: new-to-new SBE rendering and every required JSON fallback combination.

- [ ] **Step 1: Run protocol contract tests**

Run: `node --test protocol/tests/phase0-schemas.test.mjs`

Expected: PASS.

- [ ] **Step 2: Run complete WSL VJ tests**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
source .venv/bin/activate
pytest -q
```

Expected: PASS.

- [ ] **Step 3: Run complete plugin tests with SBE checks**

Run: `mvn -f minecraft_plugin/pom.xml clean test -Dsbe.enable.precedence.checks=true`

Expected: PASS.

- [ ] **Step 4: Build and inspect the plugin**

Run: `mvn -f minecraft_plugin/pom.xml package`

Expected: BUILD SUCCESS; SBE codecs and relocated Agrona are present, compiler classes absent.

- [ ] **Step 5: Review compatibility evidence**

Confirm tests explicitly prove: old VJ to new plugin JSON, new VJ to old plugin JSON, new-to-new SBE, auth on/off, forced JSON, unsupported-zone JSON, missing JVM flag JSON, reconnect epoch reset, dictionary revision, malformed binary rejection, and latest pending frame event merge.

- [ ] **Step 6: Inspect repository scope**

Run: `git status --short`

Expected: unrelated user files remain untouched and uncommitted.
