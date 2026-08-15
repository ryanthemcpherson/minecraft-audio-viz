# SBE Codec Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a reproducible SBE `sbe.v1` schema, generated Java codecs, dependency-free Python encoder, runtime Agrona capability probe, and exact cross-language golden vectors.

**Architecture:** The SBE XML under `protocol/sbe/` is the binary source of truth. Maven forks the official SBE `1.39.0` tool with its required module opening and compiles generated Java sources against Agrona `2.5.0`; the production JAR contains Agrona but not the compiler. A schema-specific WSL Python generator emits checked-in layout constants used by a reusable `struct.pack_into` encoder, and golden vectors prove Java/Python agreement.

**Tech Stack:** Java 21, SBE 1.39.0, Agrona 2.5.0, Maven, Python 3.11+ `struct`/`xml.etree`, pytest

**Spec:** `docs/superpowers/specs/2026-08-15-low-latency-render-pipeline-design.md`

## Global Constraints

- Complete the render-foundation plan before this plan; its snapshot and limit types are consumed here.
- The SBE compiler is build-time only and must not be shaded into the plugin JAR.
- Agrona is relocated in the shaded plugin to avoid classpath collisions.
- Binary capability is advertised only when `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` is effective.
- Missing module access disables binary capability but never fails plugin startup or JSON rendering.
- Java and Python decode repeating groups strictly in schema order.
- Python work runs inside WSL using a project-local `.venv`; never install Python packages into native Windows Python.
- Generated Java files live under `target/` and are not committed.
- Generated Python layout is committed and CI verifies it is fresh.
- Use exact little-endian primitive layouts; no Bukkit ordinals or temporal deltas appear on the wire.

---

## File map

- `protocol/sbe/render-frame-v1.xml`: canonical SBE schema ID 4100, template ID 1.
- `protocol/sbe/README.md`: field order, null semantics, group layout, and generation commands.
- `protocol/fixtures/render-frame-v1.json`: human-readable semantic golden fixture.
- `protocol/fixtures/render-frame-v1.bin`: exact generated golden bytes.
- `protocol/tools/generate_sbe_python.py`: schema-specific layout generator.
- `vj_server/render_protocol/__init__.py`: public Python render-codec exports.
- `vj_server/render_protocol/quantization.py`: canonical formula implementation.
- `vj_server/render_protocol/sbe_layout.py`: generated offsets, formats, and block lengths.
- `vj_server/render_protocol/sbe_encoder.py`: reusable Python buffer encoder.
- `minecraft_plugin/pom.xml`: SBE code generation, Agrona runtime, shade relocation, test JVM configuration.
- `minecraft_plugin/src/main/java/com/audioviz/render/AgronaRuntimeSupport.java`: safe runtime probe.
- `minecraft_plugin/src/main/java/com/audioviz/render/RenderQuantization.java`: Java formula implementation.
- `minecraft_plugin/src/main/java/com/audioviz/render/SbeRenderFrameCodec.java`: structural validator and diagnostic fixture adapter around generated flyweights; live mailbox publication is added only after session state exists in the integration plan.
- Java and Python tests prove schema, formulas, capability modes, malformed input handling, and golden bytes.

### Task 1: Agrona runtime dependency and capability probe

**Files:**
- Modify: `minecraft_plugin/pom.xml`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/AgronaRuntimeSupport.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/AgronaRuntimeSupportTest.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/AgronaProbeMain.java`

**Interfaces:**
- Produces: `AgronaRuntimeSupport.isAvailable()` and `AgronaRuntimeSupport.requiredJvmArgument()`.

- [ ] **Step 1: Write a failing in-process capability test**

```java
@Test
void requiredJvmArgumentIsExactAndProbeNeverThrows() {
    assertEquals(
        "--add-opens java.base/jdk.internal.misc=ALL-UNNAMED",
        AgronaRuntimeSupport.requiredJvmArgument()
    );
    assertDoesNotThrow(AgronaRuntimeSupport::isAvailable);
}
```

`AgronaProbeMain` exits zero only when `new UnsafeBuffer(new byte[8]).getLong(0, LITTLE_ENDIAN)` succeeds and exits two after printing the caught throwable class otherwise.

- [ ] **Step 2: Run the probe test and verify missing dependency/classes fail**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=AgronaRuntimeSupportTest test`

Expected: FAIL because Agrona and the probe class do not exist.

- [ ] **Step 3: Pin compatible SBE/Agrona properties and runtime dependency**

Add:

```xml
<sbe.version>1.39.0</sbe.version>
<agrona.version>2.5.0</agrona.version>
```

Add `org.agrona:agrona:${agrona.version}` as a production dependency. Add this shade relocation:

```xml
<relocation>
    <pattern>org.agrona</pattern>
    <shadedPattern>com.audioviz.libs.agrona</shadedPattern>
</relocation>
```

- [ ] **Step 4: Implement the safe runtime probe**

```java
public final class AgronaRuntimeSupport {
    private static final String REQUIRED_ARGUMENT =
        "--add-opens java.base/jdk.internal.misc=ALL-UNNAMED";

    public static boolean isAvailable() {
        try {
            UnsafeBuffer buffer = new UnsafeBuffer(new byte[Long.BYTES]);
            buffer.putLong(0, 0x0102030405060708L, ByteOrder.LITTLE_ENDIAN);
            return buffer.getLong(0, ByteOrder.LITTLE_ENDIAN) == 0x0102030405060708L;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    public static String requiredJvmArgument() { return REQUIRED_ARGUMENT; }
    private AgronaRuntimeSupport() { }
}
```

Do not initialize this class from a static field in another render class. The plugin startup path calls it deliberately and can fall back.

- [ ] **Step 5: Add forked-JVM proof for both startup modes**

From JUnit, construct a `ProcessBuilder` using the current Java executable and test classpath. Run once without the flag and assert the process reports unavailable without an uncaught crash; run once with the exact flag and assert exit zero. Do not assume the parent Surefire JVM's module state.

- [ ] **Step 6: Run the capability tests**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=AgronaRuntimeSupportTest test`

Expected: PASS in both child-process modes.

- [ ] **Step 7: Commit the Agrona dependency and probe**

```powershell
git add minecraft_plugin/pom.xml minecraft_plugin/src/main/java/com/audioviz/render/AgronaRuntimeSupport.java minecraft_plugin/src/test/java/com/audioviz/render/AgronaRuntimeSupportTest.java minecraft_plugin/src/test/java/com/audioviz/render/AgronaProbeMain.java
git commit -m "build: add SBE and Agrona toolchain"
```

### Task 2: Canonical SBE schema and generated Java codecs

**Files:**
- Create: `protocol/sbe/render-frame-v1.xml`
- Create: `protocol/sbe/README.md`
- Modify: `minecraft_plugin/pom.xml`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/SbeSchemaGenerationTest.java`

**Interfaces:**
- Produces: generated `MessageHeaderEncoder/Decoder`, `RenderFrameEncoder/Decoder`, `FrameFlags`, `EntityFlags`, and `ZoneFlags` under `com.audioviz.protocol.sbe`.
- Produces: schema ID `4100`, schema version `0`, semantic version `1.0.0`, render template ID `1`.

- [ ] **Step 1: Add a failing generated-code contract test**

```java
@Test
void generatedMetadataMatchesNegotiatedProtocol() {
    assertEquals(4100, RenderFrameDecoder.SCHEMA_ID);
    assertEquals(0, RenderFrameDecoder.SCHEMA_VERSION);
    assertEquals(1, RenderFrameDecoder.TEMPLATE_ID);
    assertEquals(8, MessageHeaderEncoder.ENCODED_LENGTH);
    assertEquals(61, RenderFrameEncoder.BLOCK_LENGTH);
}
```

- [ ] **Step 2: Run the contract test and verify generated codecs are absent**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=SbeSchemaGenerationTest test`

Expected: FAIL at test compilation because the generated codec classes do not exist yet.

- [ ] **Step 3: Define common SBE types**

Create a little-endian message schema with the standard eight-byte header and four-byte group header:

```xml
<composite name="messageHeader" description="SBE message header">
    <type name="blockLength" primitiveType="uint16"/>
    <type name="templateId" primitiveType="uint16"/>
    <type name="schemaId" primitiveType="uint16"/>
    <type name="version" primitiveType="uint16"/>
</composite>
<composite name="groupSizeEncoding" description="SBE group dimensions">
    <type name="blockLength" primitiveType="uint16"/>
    <type name="numInGroup" primitiveType="uint16" minValue="0" maxValue="65534"/>
</composite>
<type name="Unit16" primitiveType="uint16"/>
<type name="BpmCenti" primitiveType="uint16" minValue="0" maxValue="30000" nullValue="65535"/>
<type name="SourceTime" primitiveType="uint64" nullValue="18446744073709551615"/>
<set name="FrameFlags" encodingType="uint8">
    <choice name="beat">0</choice>
    <choice name="kick">1</choice>
</set>
<set name="EntityFlags" encodingType="uint8">
    <choice name="visible">0</choice>
    <choice name="glow">1</choice>
</set>
<set name="ZoneFlags" encodingType="uint8">
    <choice name="bitmapAudioOnly">0</choice>
</set>
<type name="AudioBands" primitiveType="uint16" length="5"/>
```

- [ ] **Step 4: Define `RenderFrame` in exact streaming order**

Use root fixed fields in this order: `connectionEpoch:uint32`, `dictionaryRevision:uint32`, `frameSequence:uint64`, `sourceTimeNanos:SourceTime`, `generatedTimeNanos:uint64`, `bands:AudioBands`, `amplitude:Unit16`, `beatIntensity:Unit16`, `bpmCenti:BpmCenti`, `tempoConfidence:Unit16`, `beatPhase:Unit16`, `frameFlags:FrameFlags`, `eventSequence:uint64`.

Then add a `zones` group with fixed block `zoneId:uint16`, `zoneFlags:ZoneFlags`, followed in order by:

```xml
<group name="entities" id="102" dimensionType="groupSizeEncoding">
    <field name="x" id="103" type="Unit16"/>
    <field name="y" id="104" type="Unit16"/>
    <field name="z" id="105" type="Unit16"/>
    <field name="scale" id="106" type="Unit16"/>
    <field name="rotation" id="107" type="Unit16"/>
    <field name="materialId" id="108" type="uint16"/>
    <field name="brightness" id="109" type="uint8" minValue="0" maxValue="15"/>
    <field name="interpolationTicks" id="110" type="uint8" minValue="0" maxValue="100"/>
    <field name="entityFlags" id="111" type="EntityFlags"/>
</group>
<group name="particles" id="112" dimensionType="groupSizeEncoding">
    <field name="eventId" id="113" type="uint64"/>
    <field name="particleTypeId" id="114" type="uint16"/>
    <field name="x" id="115" type="Unit16"/>
    <field name="y" id="116" type="Unit16"/>
    <field name="z" id="117" type="Unit16"/>
    <field name="count" id="118" type="uint16"/>
</group>
```

The resulting fixed lengths are header `8`, frame `61`, zone `3`, entity `15`, and particle `18`. Document that the entity group index is the dense pool slot.

- [ ] **Step 5: Configure forked SBE generation and generate Java flyweights**

Add Maven properties for `exec-maven-plugin` `3.6.3` and `build-helper-maven-plugin` `3.6.1`. Use `maven-dependency-plugin` in `generate-sources` to copy `uk.co.real-logic:sbe-all:${sbe.version}` to `${project.build.directory}/tools/sbe-all.jar`. Then run `exec-maven-plugin:exec` in the same phase after the copy:

```xml
<executable>${java.home}/bin/java</executable>
<arguments>
    <argument>--add-opens</argument>
    <argument>java.base/jdk.internal.misc=ALL-UNNAMED</argument>
    <argument>-Dsbe.output.dir=${project.build.directory}/generated-sources/sbe</argument>
    <argument>-Dsbe.target.language=Java</argument>
    <argument>-Dsbe.generate.precedence.checks=true</argument>
    <argument>-Dsbe.validation.stop.on.error=true</argument>
    <argument>-Dsbe.validation.warnings.fatal=true</argument>
    <argument>-jar</argument>
    <argument>${project.build.directory}/tools/sbe-all.jar</argument>
    <argument>${project.basedir}/../protocol/sbe/render-frame-v1.xml</argument>
</arguments>
```

Add `${project.build.directory}/generated-sources/sbe` with `build-helper-maven-plugin:add-source`. Do not add `sbe-tool` or `sbe-all` to project dependencies.

Run: `mvn -f minecraft_plugin/pom.xml -DskipTests generate-sources`

Expected: generated files under `minecraft_plugin/target/generated-sources/sbe/com/audioviz/protocol/sbe/` and no schema warning.

- [ ] **Step 6: Run metadata tests with precedence checks enabled**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=SbeSchemaGenerationTest -Dsbe.enable.precedence.checks=true test`

Expected: PASS.

- [ ] **Step 7: Commit the canonical SBE schema**

```powershell
git add protocol/sbe minecraft_plugin/pom.xml minecraft_plugin/src/test/java/com/audioviz/render/SbeSchemaGenerationTest.java
git commit -m "feat: define render frame SBE schema"
```

### Task 3: Shared quantization formulas

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/RenderQuantization.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/RenderQuantizationTest.java`
- Create: `vj_server/render_protocol/__init__.py`
- Create: `vj_server/render_protocol/quantization.py`
- Test: `vj_server/tests/test_render_quantization.py`

**Interfaces:**
- Produces in Java: `encodeUnit(double)`, `decodeUnit(int)`, `encodeRange(double,double,double)`, `decodeRange(int,double,double)`, and `wrapDegrees(double)`.
- Produces equivalent Python functions with the same names in snake case.

- [ ] **Step 1: Write Java endpoint and error-bound tests**

```java
@ParameterizedTest
@ValueSource(doubles = {0.0, 0.1, 0.5, 0.9, 1.0})
void unitRoundTripStaysInsideHalfStep(double value) {
    int encoded = RenderQuantization.encodeUnit(value);
    double decoded = RenderQuantization.decodeUnit(encoded);
    assertTrue(Math.abs(value - decoded) <= RenderQuantization.MAX_UNIT_ERROR);
}

@Test
void nonFiniteValuesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> RenderQuantization.encodeUnit(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> RenderQuantization.wrapDegrees(Double.POSITIVE_INFINITY));
}
```

- [ ] **Step 2: Write equivalent failing Python tests**

```python
@pytest.mark.parametrize("value", [0.0, 0.1, 0.5, 0.9, 1.0])
def test_unit_round_trip_stays_inside_half_step(value: float) -> None:
    encoded = encode_unit(value)
    assert abs(value - decode_unit(encoded)) <= MAX_UNIT_ERROR

def test_degree_wrap_is_formula_driven() -> None:
    assert wrap_degrees(450.0) == pytest.approx(90.0)
    assert wrap_degrees(-90.0) == pytest.approx(270.0)
```

- [ ] **Step 3: Run both suites and verify missing functions fail**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=RenderQuantizationTest test`

Run in WSL:

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
source .venv/bin/activate
pytest tests/test_render_quantization.py -q
```

Expected: both FAIL at import/compile time.

- [ ] **Step 4: Implement identical formulas**

Use the unsigned width to derive the code maximum:

```java
public static final int UNIT_CODE_MAX = (1 << Short.SIZE) - 1;
public static final double MAX_UNIT_ERROR = 1.0 / (2.0 * UNIT_CODE_MAX);

public static int encodeUnit(double value) {
    requireFinite(value);
    return (int)Math.round(Math.clamp(value, 0.0, 1.0) * UNIT_CODE_MAX);
}

public static double decodeUnit(int code) {
    if (code < 0 || code > UNIT_CODE_MAX) throw new IllegalArgumentException("code");
    return code / (double)UNIT_CODE_MAX;
}
```

Use `min + decodeUnit(code) * (max - min)` for ranges and `((degrees % 360.0) + 360.0) % 360.0` for rotation. Python uses `math.isfinite`, `round`, and the same derived `(1 << 16) - 1`. Add explicit half-away/half-even tie fixtures if Java and Python rounding differ; the canonical encoder rule is nearest integer with ties toward positive infinity, implemented explicitly as `floor(scaled + 0.5)` in both languages.

- [ ] **Step 5: Run Java and WSL Python formula tests**

Expected: PASS.

- [ ] **Step 6: Commit shared quantization**

```powershell
git add minecraft_plugin/src/main/java/com/audioviz/render/RenderQuantization.java minecraft_plugin/src/test/java/com/audioviz/render/RenderQuantizationTest.java vj_server/render_protocol vj_server/tests/test_render_quantization.py
git commit -m "feat: add canonical render quantization"
```

### Task 4: Generated Python layout and reusable encoder

**Files:**
- Create: `protocol/tools/generate_sbe_python.py`
- Create: `vj_server/render_protocol/sbe_layout.py`
- Create: `vj_server/render_protocol/sbe_encoder.py`
- Test: `vj_server/tests/test_sbe_layout_generation.py`
- Test: `vj_server/tests/test_sbe_encoder.py`

**Interfaces:**
- Produces: generated `MESSAGE_HEADER`, `FRAME_BLOCK`, `GROUP_HEADER`, `ZONE_BLOCK`, `ENTITY_BLOCK`, `PARTICLE_BLOCK`, schema/template constants, and calculated fixed sizes.
- Produces: `SbeRenderFrameEncoder.encode(RenderFrameInput) -> memoryview` and `encoded_length(RenderFrameInput) -> int`.

- [ ] **Step 1: Write a failing freshness test**

```python
def test_checked_in_layout_matches_generator(tmp_path: Path) -> None:
    generated = tmp_path / "sbe_layout.py"
    subprocess.run(
        [sys.executable, str(GENERATOR), "--schema", str(SCHEMA), "--output", str(generated)],
        check=True,
    )
    assert generated.read_bytes() == CHECKED_IN_LAYOUT.read_bytes()
```

- [ ] **Step 2: Write failing size and buffer-reuse tests**

```python
def test_exact_nested_group_length_and_buffer_reuse() -> None:
    encoder = SbeRenderFrameEncoder(initial_capacity=64)
    frame = representative_frame(zone_count=2, entity_count=3, particle_count=1)
    first = encoder.encode(frame)
    first_object = first.obj
    assert len(first) == encoded_length(frame)
    second = encoder.encode(frame)
    assert second.obj is first_object
```

Also assert group block lengths `3`, `15`, and `18`, little-endian headers, maximum count rejection, non-dense entity rejection, non-finite rejection, and geometric buffer growth capped by the caller-provided negotiated maximum.

- [ ] **Step 3: Run WSL tests and verify missing generator/encoder fails**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
source .venv/bin/activate
pytest tests/test_sbe_layout_generation.py tests/test_sbe_encoder.py -q
```

Expected: FAIL at import/file lookup.

- [ ] **Step 4: Implement schema-specific layout generation**

Parse XML with `xml.etree.ElementTree`. Validate schema ID/version, byte order, field names, primitive types, fixed array length, and group nesting. Emit deterministic Python containing:

```python
MESSAGE_HEADER = struct.Struct("<HHHH")
FRAME_BLOCK = struct.Struct("<IIQQQ5H5HBQ")
GROUP_HEADER = struct.Struct("<HH")
ZONE_BLOCK = struct.Struct("<HB")
ENTITY_BLOCK = struct.Struct("<6H3B")
PARTICLE_BLOCK = struct.Struct("<Q5H")
SCHEMA_ID = 4100
SCHEMA_VERSION = 0
TEMPLATE_ID = 1
```

The generator must derive each format from XML field types rather than copying the expected format strings into output logic. Reject unknown types instead of guessing.

- [ ] **Step 5: Implement typed Python input records and exact sizing**

Use frozen, slotted dataclasses for public boundaries:

```python
@dataclass(frozen=True, slots=True)
class EntityFrameInput:
    x: float
    y: float
    z: float
    scale: float
    rotation: float
    material_id: int
    brightness: int
    interpolation_ticks: int
    visible: bool
    glow: bool

@dataclass(frozen=True, slots=True)
class ZoneFrameInput:
    zone_id: int
    flags: int
    entities: Sequence[EntityFrameInput]
    particles: Sequence[ParticleFrameInput] = ()
```

`encoded_length` implements the generated-size formula. `encode` grows one `bytearray` geometrically up to `max_frame_bytes`, uses `pack_into` in schema order, and returns a view limited to encoded bytes. It never calls `bytes()` internally.

- [ ] **Step 6: Generate and test the checked-in layout**

Run in WSL:

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz
source vj_server/.venv/bin/activate
python protocol/tools/generate_sbe_python.py --schema protocol/sbe/render-frame-v1.xml --output vj_server/render_protocol/sbe_layout.py
cd vj_server
pytest tests/test_sbe_layout_generation.py tests/test_sbe_encoder.py -q
```

Expected: PASS.

- [ ] **Step 7: Commit Python SBE generation and encoding**

```powershell
git add protocol/tools/generate_sbe_python.py vj_server/render_protocol vj_server/tests/test_sbe_layout_generation.py vj_server/tests/test_sbe_encoder.py
git commit -m "feat: encode SBE render frames in Python"
```

### Task 5: Java codec adapter and cross-language golden vectors

**Files:**
- Create: `protocol/fixtures/render-frame-v1.json`
- Create via generator: `protocol/fixtures/render-frame-v1.bin`
- Create: `minecraft_plugin/src/main/java/com/audioviz/render/SbeRenderFrameCodec.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/SbeRenderFrameCodecTest.java`
- Test: `minecraft_plugin/src/test/java/com/audioviz/render/SbeGoldenVectorTest.java`
- Test: `vj_server/tests/test_sbe_golden_vector.py`

**Interfaces:**
- Consumes: generated Java decoder, quantization, and Python encoder.
- Produces for tests/diagnostics: `SbeRenderFrameCodec.inspect(ByteBuffer payload, RenderProtocolLimits limits) -> DecodedRenderFrame`.
- Produces for tests: `SbeRenderFrameCodec.encodeFixture(DecodedRenderFrame fixture) -> byte[]`.
- Defers the allocation-free `decode(..., RenderProtocolSessionView, ...)` overload until the integration plan has created authenticated session and dictionary state.

- [ ] **Step 1: Define one complete semantic fixture**

The JSON fixture contains epoch `0x10203040`, dictionary revision `3`, frame sequence `42`, source/generation timestamps, all five bands including endpoints, BPM `128.25`, confidence, phase, beat and kick flags, two zones, visible/hidden/glowing entities, two material IDs, and one particle event. Decimal values avoid ambiguous ties except the dedicated quantization tests.

- [ ] **Step 2: Write failing Java golden decode assertions**

```java
@Test
void pythonGoldenVectorDecodesToCanonicalSemantics() throws IOException {
    byte[] payload = Files.readAllBytes(fixture("render-frame-v1.bin"));
    DecodedRenderFrame frame = codec.inspect(ByteBuffer.wrap(payload), fixtureLimits());

    assertEquals(0x10203040L, frame.connectionEpoch());
    assertEquals(42L, frame.frameSequence());
    assertEquals(2, frame.zones().size());
    assertEquals(128.25, frame.bpm(), 0.01);
    assertTrue(frame.beat());
    assertTrue(frame.kick());
    assertEquals(1, frame.zones().get(1).particles().size());
}
```

Add tests for truncated header, wrong template/schema/version, acting block shorter than required fields, forged group count, remaining-byte underflow, excessive calculated counts, trailing bytes, and no retention of the source `ByteBuffer`. Epoch, dictionary revision, and connection-sequence admission tests belong to the integration plan, where the authenticated session owns that state.

- [ ] **Step 3: Write failing Python golden-byte assertion**

```python
def test_semantic_fixture_encodes_exact_golden_bytes() -> None:
    fixture = load_fixture(FIXTURE_JSON)
    encoded = encoder.encode(fixture)
    assert encoded.tobytes() == FIXTURE_BIN.read_bytes()
```

- [ ] **Step 4: Run tests and verify missing fixture/adapter fails**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=SbeRenderFrameCodecTest,SbeGoldenVectorTest test`

Run in WSL: `pytest vj_server/tests/test_sbe_golden_vector.py -q`

Expected: FAIL.

- [ ] **Step 5: Implement strict Java header and group validation**

Use an Agrona `UnsafeBuffer` over the source bytes and validate header metadata before `RenderFrameDecoder.wrap(...)`. `inspect` walks the payload synchronously and returns nested immutable diagnostic records used only by tests, fixture tooling, and troubleshooting; it is deliberately not the production hot path.

Walk every group in schema order. Before each loop, verify `count <= calculatedLimit`; rely on Agrona bounds checks and add explicit remaining-byte checks so callers receive a stable structural rejection reason. Require the final decoder limit to equal the input limit. The later integration overload repeats this validator while decoding directly into claimed zone snapshots, so it does not construct diagnostic records in production.

- [ ] **Step 6: Generate the golden binary with the Python encoder**

Add a `--fixture` mode to the generator or a tiny test helper that loads the semantic JSON and writes the exact encoder view. Generate using WSL, then never edit the binary by hand:

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz
source vj_server/.venv/bin/activate
python -m vj_server.render_protocol.sbe_encoder \
  --fixture protocol/fixtures/render-frame-v1.json \
  --output protocol/fixtures/render-frame-v1.bin
```

- [ ] **Step 7: Make the Java generated encoder match the same vector**

Encode the semantic fixture with generated `RenderFrameEncoder`, call `checkEncodingIsComplete()` in tests, slice exactly `MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength()`, and assert byte-for-byte equality.

- [ ] **Step 8: Run cross-language tests**

Run: `mvn -f minecraft_plugin/pom.xml -Dtest=SbeRenderFrameCodecTest,SbeGoldenVectorTest -Dsbe.enable.precedence.checks=true test`

Run in WSL: `pytest vj_server/tests/test_sbe_golden_vector.py -q`

Expected: PASS.

- [ ] **Step 9: Commit golden-vector compatibility**

```powershell
git add protocol/fixtures minecraft_plugin/src/main/java/com/audioviz/render/SbeRenderFrameCodec.java minecraft_plugin/src/test/java/com/audioviz/render/SbeRenderFrameCodecTest.java minecraft_plugin/src/test/java/com/audioviz/render/SbeGoldenVectorTest.java vj_server/tests/test_sbe_golden_vector.py
git commit -m "test: add cross-language SBE golden vectors"
```

### Task 6: Codec and shaded-JAR verification

**Files:**
- Modify only if verification exposes a codec/build defect.

**Interfaces:**
- Produces: independently verified codecs ready for transport integration.

- [ ] **Step 1: Verify Python layout freshness and codec tests in WSL**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server
source .venv/bin/activate
pytest tests/test_render_quantization.py tests/test_sbe_layout_generation.py tests/test_sbe_encoder.py tests/test_sbe_golden_vector.py -q
```

Expected: PASS.

- [ ] **Step 2: Verify generated Java codecs and plugin tests**

Run: `mvn -f minecraft_plugin/pom.xml clean test -Dsbe.enable.precedence.checks=true`

Expected: PASS.

- [ ] **Step 3: Build and inspect the shaded JAR**

Run: `mvn -f minecraft_plugin/pom.xml package`

Run:

```powershell
jar tf minecraft_plugin/target/audioviz-plugin-1.0.0-SNAPSHOT.jar | Select-String 'sbe-all|SbeTool|org/agrona|com/audioviz/libs/agrona|com/audioviz/protocol/sbe'
```

Expected: generated codecs and relocated `com/audioviz/libs/agrona` classes are present; `SbeTool`, `sbe-all`, and unrelocated `org/agrona` classes are absent.

- [ ] **Step 4: Verify startup probe behavior in both child JVM modes**

Run the probe main without the module flag and then with it. Expected: clean unavailable result first, success second, no uncaught `ExceptionInInitializerError`.

- [ ] **Step 5: Inspect scope**

Run: `git status --short`

Expected: unrelated user files remain untracked and no generated Java files under `target/` are staged.
