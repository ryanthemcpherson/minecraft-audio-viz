# Runtime Trust and Store Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the strict signed-manifest, bounded-download, safe-extraction, immutable-runtime-store, and crash-recovery foundation used by the self-installing Paper plugin.

**Architecture:** Add a pure-Java `com.audioviz.runtime` foundation with injected network, clock, filesystem, and event boundaries. It verifies exact manifest bytes with JDK Ed25519, parses strict bounded JSON through Gson's streaming API, downloads only allowlisted HTTPS artifacts, validates every ZIP member before extraction, and commits version/state metadata atomically. This plan does not launch a process or call Bukkit.

**Tech Stack:** Java 21, Gson 2.13.2, JDK `HttpClient`, JDK Ed25519, JUnit 5.12.1, Mockito 5.23.0, JSON Schema Draft 2020-12, Python 3.12 release tooling with `cryptography` pinned through `vj_server/pyproject.toml`.

**Spec:** `docs/superpowers/specs/2026-08-31-self-installing-paper-release-design.md`

## Global Constraints

- Support Linux AMD64, Linux ARM64, and Windows AMD64 only.
- Target Paper for Minecraft 1.21.11 and Java 21.
- Installer code must have no Bukkit dependency and must never require Paper's main thread.
- Release manifests are schema version `1`, use monotonically increasing generations, and are signed over the exact UTF-8 manifest bytes with Ed25519.
- Release builds never accept unsigned metadata, non-HTTPS artifact URLs, unknown fields, duplicate JSON keys, expired metadata for new activation, or a generation below the embedded minimum.
- All downloads, response bodies, ZIP members, extracted bytes, paths, ratios, counts, and metadata strings have explicit bounds copied into `RuntimeLimits`.
- Version directories are immutable after promotion; active and rollback metadata are same-directory temporary-write, force, and atomic-replace transactions.
- Preserve all pre-existing user changes outside files listed by a task.
- Use WSL-native Python and a project `.venv` for Python tooling; never install packages into Windows Python.
- Run `git status` and `git diff` before every task commit and stage only that task's files.

## File Structure

### Release contract and fixtures

- `protocol/schemas/releases/runtime-manifest-v1.schema.json` — source-of-truth public manifest shape and hard scalar bounds.
- `protocol/schemas/index.json` — adds the `runtime_manifest_v1` release schema inventory entry.
- `protocol/fixtures/runtime-release/valid-manifest.json` — canonical exact-byte Java/Python golden manifest.
- `protocol/fixtures/runtime-release/valid-manifest.sig.json` — test-key signature envelope for the golden manifest.
- `protocol/fixtures/runtime-release/test-public-key.der.b64` — X.509 SubjectPublicKeyInfo test key; filename and contents are explicitly nonproduction.
- `protocol/fixtures/runtime-release/test-private-key.pk8.b64` — PKCS#8 test key used only by release-tool tests.
- `deploy/runtime/sign_manifest.py` — deterministic canonical manifest writer and Ed25519 signer used by builders.
- `deploy/runtime/test_sign_manifest.py` — schema, deterministic output, and golden-vector tests.

### Java release metadata

- `minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimeLimits.java` — one immutable set of all installer bounds.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/release/RuntimePlatform.java` — normalized supported platform enum and host detection.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/release/RuntimeArtifact.java` — one verified platform artifact record.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/release/RuntimeManifest.java` — verified manifest value.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/release/ReleaseDescriptor.java` — plugin-embedded trust roots, origin, API, and minimum generation.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/release/SignatureEnvelope.java` — bounded key ID and Base64 Ed25519 signature.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/release/StrictJson.java` — streaming JSON reader that rejects duplicates, unknown fields, invalid types, and trailing data.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/release/ManifestVerificationException.java` — reason-coded checked verification failure.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/release/RuntimeManifestVerifier.java` — exact-byte signature, time, generation, API, URL, and platform validation.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/release/RuntimeManifestVerifierTest.java` — positive, negative, and golden-vector tests.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/release/RuntimePlatformTest.java` — platform normalization and unsupported-host tests.

### Download and storage

- `minecraft_plugin/src/main/java/com/audioviz/runtime/net/RuntimeHttpSource.java` — fetch interface and JDK implementation with manual redirects and bounded streaming.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/net/RuntimeDownloadException.java` — reason-coded network/download failure.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/net/RuntimeHttpSourceTest.java` — local HTTP server tests for policy and bounds.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/store/RuntimePaths.java` — validated paths below one runtime root.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/store/RuntimeVersionId.java` — safe immutable version/platform identifier.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/store/PackagedFile.java` — expected archive member path, size, digest, and executable bit.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/store/RuntimeArchiveVerifier.java` — two-pass ZIP central-directory and streaming extraction validator.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/store/AtomicStateStore.java` — `current.json`, last-known-good, transaction journal, and recovery.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/store/RuntimeStore.java` — immutable promotion, cache verification, retention, and selection.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/store/RuntimeStoreException.java` — reason-coded storage failure.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/store/RuntimeArchiveVerifierTest.java` — hostile archive and positive extraction matrix.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/store/AtomicStateStoreTest.java` — commit-boundary crash recovery tests.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/store/RuntimeStoreTest.java` — install, idempotence, collision, retention, and offline cache tests.

### Installation orchestration

- `minecraft_plugin/src/main/java/com/audioviz/runtime/install/InstallLock.java` — cross-process file lock owned for one transaction.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/install/RuntimeInstallEvent.java` — bounded state/reason event record.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/install/RuntimeInstaller.java` — check/download/verify/stage/promote transaction with generation cancellation.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/install/RuntimeInstallerTest.java` — orchestration, cancellation, and active-version preservation tests.

---

### Task 1: Define and sign the runtime release contract

**Files:**
- Create: `protocol/schemas/releases/runtime-manifest-v1.schema.json`
- Modify: `protocol/schemas/index.json`
- Create: `protocol/fixtures/runtime-release/valid-manifest.json`
- Create: `protocol/fixtures/runtime-release/valid-manifest.sig.json`
- Create: `protocol/fixtures/runtime-release/test-public-key.der.b64`
- Create: `protocol/fixtures/runtime-release/test-private-key.pk8.b64`
- Create: `deploy/runtime/sign_manifest.py`
- Create: `deploy/runtime/test_sign_manifest.py`
- Modify: `vj_server/pyproject.toml`
- Modify: `uv.lock`

**Interfaces:**
- Consumes: JSON Schema Draft 2020-12 and Ed25519 primitives from `cryptography.hazmat.primitives.asymmetric.ed25519`.
- Produces: `canonical_manifest_bytes(document: dict[str, object]) -> bytes`, `sign_manifest(document: dict[str, object], private_key: Ed25519PrivateKey, key_id: str) -> tuple[bytes, bytes]`, plus golden fixture bytes consumed by Java tests.

- [ ] **Step 1: Add the release-tool dependency and write failing deterministic-signature tests**

Add `cryptography==46.0.3` to a new `release` optional dependency group in `vj_server/pyproject.toml`, lock it through WSL, and start `deploy/runtime/test_sign_manifest.py` with exact-byte assertions:

```python
from base64 import b64decode
from pathlib import Path

from cryptography.hazmat.primitives.serialization import load_der_private_key

from deploy.runtime.sign_manifest import canonical_manifest_bytes, sign_manifest

FIXTURES = Path(__file__).parents[2] / "protocol" / "fixtures" / "runtime-release"


def test_golden_manifest_is_canonical_and_signature_is_stable() -> None:
    document = load_fixture_document(FIXTURES / "valid-manifest.json")
    key = load_der_private_key(b64decode((FIXTURES / "test-private-key.pk8.b64").read_text()))
    payload, envelope = sign_manifest(document, key, "test-only-2026")
    assert payload == (FIXTURES / "valid-manifest.json").read_bytes()
    assert envelope == (FIXTURES / "valid-manifest.sig.json").read_bytes()


def test_canonical_writer_rejects_float_and_unknown_manifest_member() -> None:
    document = valid_document()
    document["generation"] = 1.5
    document["surprise"] = True
    with pytest.raises(ManifestBuildError, match="generation must be an integer"):
        canonical_manifest_bytes(document)
```

- [ ] **Step 2: Run the release-tool tests and confirm the missing module failure**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; python3 -m venv .release-venv; .release-venv/bin/pip install -e "./vj_server[release]"; .release-venv/bin/python -m pytest deploy/runtime/test_sign_manifest.py -q'
```

Expected: FAIL during collection because `deploy.runtime.sign_manifest` does not exist.

- [ ] **Step 3: Implement the exact schema and canonical writer**

The manifest JSON must use this top-level shape and reject every additional property:

```json
{
  "schema_version": 1,
  "generation": 1,
  "release_version": "1.2.0",
  "runtime_api_min": 1,
  "runtime_api_max": 1,
  "published_at": "2026-08-31T00:00:00Z",
  "expires_at": "2026-09-30T00:00:00Z",
  "signing_key_id": "test-only-2026",
  "artifacts": {
    "linux-x86_64": {
      "url": "https://releases.mcav.live/runtime/1.2.0/linux-x86_64.zip",
      "archive_size": 4096,
      "uncompressed_size": 8192,
      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "entrypoint": "bin/audioviz-vj",
      "files_manifest_sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    },
    "linux-aarch64": {},
    "windows-x86_64": {}
  }
}
```

Populate all three artifact objects completely in the fixture. Write canonical JSON as UTF-8, sorted object keys, compact separators, escaped control characters, no ASCII-only escaping, and exactly one trailing newline. Accept only strings, booleans, null, arrays, objects, and signed 64-bit integers; floats are invalid. `sign_manifest()` signs the exact payload and writes a compact envelope containing only `key_id`, `algorithm: "Ed25519"`, and unpadded Base64 `signature`.

- [ ] **Step 4: Generate the visibly test-only key fixtures and golden files**

Add a `--generate-test-fixture` CLI that refuses any key ID not starting with `test-only-`, generates one Ed25519 test pair, writes DER Base64 files beneath the fixture directory, and emits the manifest/signature pair. Run it once, then run it a second time in verify-only mode so normal tests never regenerate keys.

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python deploy/runtime/sign_manifest.py --generate-test-fixture protocol/fixtures/runtime-release'
```

Expected: six fixture/contract files exist and the signer reports `test-only-2026`.

- [ ] **Step 5: Run the schema, signer, protocol-index, and full VJ tests**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python -m pytest deploy/runtime/test_sign_manifest.py -q; .release-venv/bin/python -m pytest vj_server/tests -q'
```

Expected: PASS. Confirm `protocol/schemas/index.json` contains `"releases": {"runtime_manifest_v1": "releases/runtime-manifest-v1.schema.json"}` without removing existing entries.

- [ ] **Step 6: Commit the release contract**

```powershell
git status --short
git diff -- protocol/schemas deploy/runtime vj_server/pyproject.toml uv.lock
git add -- protocol/schemas/releases/runtime-manifest-v1.schema.json protocol/schemas/index.json protocol/fixtures/runtime-release deploy/runtime/sign_manifest.py deploy/runtime/test_sign_manifest.py vj_server/pyproject.toml uv.lock
git commit -m "feat(release): define signed runtime manifest"
```

### Task 2: Verify strict manifest metadata in Java

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimeLimits.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/release/RuntimePlatform.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/release/RuntimeArtifact.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/release/RuntimeManifest.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/release/ReleaseDescriptor.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/release/SignatureEnvelope.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/release/StrictJson.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/release/ManifestVerificationException.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/release/RuntimeManifestVerifier.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/release/RuntimeManifestVerifierTest.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/release/RuntimePlatformTest.java`

**Interfaces:**
- Consumes: exact manifest/signature/public-key fixture bytes from Task 1 and an injected `java.time.Clock`.
- Produces: `RuntimeManifestVerifier.verify(byte[] manifestBytes, byte[] envelopeBytes, ReleaseDescriptor descriptor, Instant now) -> RuntimeManifest`, `RuntimePlatform.detect(String osName, String osArch) -> RuntimePlatform`, and immutable metadata records used by download/storage tasks.

- [ ] **Step 1: Write failing golden-vector and strictness tests**

Create parameterized tests that load Task 1 fixtures and assert the exact values. Include one mutation per reason code:

```java
@Test
void verifiesGoldenManifestExactBytes() throws Exception {
    RuntimeManifest manifest = verifier.verify(
        fixture("valid-manifest.json"),
        fixture("valid-manifest.sig.json"),
        descriptorWithTestKey(),
        Instant.parse("2026-09-01T00:00:00Z")
    );
    assertEquals(1L, manifest.generation());
    assertEquals("1.2.0", manifest.releaseVersion());
    assertEquals(3, manifest.artifacts().size());
}

@ParameterizedTest
@MethodSource("invalidDocuments")
void rejectsInvalidManifest(byte[] payload, FailureReason reason) {
    ManifestVerificationException failure = assertThrows(
        ManifestVerificationException.class,
        () -> verifier.verify(payload, validEnvelope(), descriptorWithTestKey(), NOW)
    );
    assertEquals(reason, failure.reason());
}
```

`invalidDocuments()` must cover duplicate keys, unknown keys, trailing JSON, invalid UTF-8, float integers, negative sizes, oversized strings, bad timestamps, expiry, future publication, old generation, API mismatch, missing platforms, HTTP URL, userinfo, fragment, unknown key ID, wrong algorithm, malformed Base64, wrong signature, and signature over reserialized rather than exact bytes.

- [ ] **Step 2: Run the focused tests and verify compilation fails**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeManifestVerifierTest,RuntimePlatformTest test
```

Expected: FAIL because `com.audioviz.runtime.release` does not exist.

- [ ] **Step 3: Implement platform detection and immutable value types**

Use exact enum wire values:

```java
public enum RuntimePlatform {
    LINUX_X86_64("linux-x86_64"),
    LINUX_AARCH64("linux-aarch64"),
    WINDOWS_X86_64("windows-x86_64");

    public static RuntimePlatform detect(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        if (os.contains("linux") && Set.of("amd64", "x86_64").contains(arch)) return LINUX_X86_64;
        if (os.contains("linux") && Set.of("aarch64", "arm64").contains(arch)) return LINUX_AARCH64;
        if (os.contains("windows") && Set.of("amd64", "x86_64").contains(arch)) return WINDOWS_X86_64;
        throw new UnsupportedOperationException("MCAV_RUNTIME_UNSUPPORTED_PLATFORM");
    }
}
```

Records defensively copy byte arrays, maps, and key maps. `RuntimeLimits.releaseDefaults()` defines: 1 MiB manifest, 4 KiB signature envelope, 8 trusted keys, 3 artifacts, 128-character IDs/versions/entrypoints, 2 GiB archive, 4 GiB extracted total, 16,384 members, 512-byte UTF-8 member path, 512 MiB member, and 100:1 compression ratio.

- [ ] **Step 4: Implement strict streaming JSON and Ed25519 verification**

`StrictJson` uses `JsonReader` with `Strictness.STRICT`, a `Set<String>` for every object scope, explicit expected fields, integer-only `nextLong()`, maximum depth 8, and an end-of-document check. It never builds an unbounded `JsonElement` tree.

`RuntimeManifestVerifier` performs this order:

```java
SignatureEnvelope envelope = StrictJson.readSignatureEnvelope(envelopeBytes, limits);
PublicKey key = descriptor.trustedKeys().get(envelope.keyId());
if (key == null) throw failure(UNKNOWN_KEY);
Signature signature = Signature.getInstance("Ed25519");
signature.initVerify(key);
signature.update(manifestBytes);
if (!signature.verify(envelope.signature())) throw failure(INVALID_SIGNATURE);
RuntimeManifest manifest = StrictJson.readManifest(manifestBytes, limits);
validateTrustedFields(manifest, envelope, descriptor, now);
return manifest;
```

Never include manifest strings or signature bytes in exception messages. Map all failures to a closed `FailureReason` enum.

- [ ] **Step 5: Run focused and full plugin tests**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeManifestVerifierTest,RuntimePlatformTest test
.\mvnw.cmd -q test
```

Expected: PASS. Confirm the fixture private key is referenced only from tests and release tooling:

```powershell
rg -n "test-private-key|test-only-2026" minecraft_plugin\src\main
```

Expected: no matches.

- [ ] **Step 6: Commit strict metadata verification**

```powershell
git status --short
git diff -- minecraft_plugin/src/main/java/com/audioviz/runtime minecraft_plugin/src/test/java/com/audioviz/runtime
git add -- minecraft_plugin/src/main/java/com/audioviz/runtime minecraft_plugin/src/test/java/com/audioviz/runtime/release
git commit -m "feat(plugin): verify signed runtime manifests"
```

### Task 3: Download manifests and archives with a fail-closed HTTP policy

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/net/RuntimeHttpSource.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/net/RuntimeDownloadException.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/net/RuntimeHttpSourceTest.java`

**Interfaces:**
- Consumes: `ReleaseDescriptor.allowedHosts()`, `RuntimeLimits`, and verified `RuntimeArtifact` values from Task 2.
- Produces: `RuntimeHttpSource.fetchBytes(URI uri, int maximumBytes, Set<String> allowedHosts) -> byte[]` and `RuntimeHttpSource.download(URI uri, Path destination, long expectedBytes, String expectedSha256, Set<String> allowedHosts, CancellationToken token) -> DownloadResult`.

- [ ] **Step 1: Write failing local-server policy tests**

Use JDK `HttpServer` bound to loopback. Test successful exact-byte download plus timeout, truncation, oversize, hash mismatch, cancellation, HTTP origin, userinfo, fragment, nonallowlisted host, redirect loop, more than two redirects, HTTPS-to-HTTP redirect, and redirect to a nonallowlisted host. Inject a transport adapter so tests can model HTTPS policy while the local fixture remains loopback HTTP.

```java
@Test
void truncatedDownloadNeverPublishesDestination() throws Exception {
    Path destination = temp.resolve("downloads/candidate.zip");
    server.respond("/short", 200, new byte[15], Map.of("Content-Length", "20"));
    RuntimeDownloadException failure = assertThrows(
        RuntimeDownloadException.class,
        () -> source.download(policyUri("/short"), destination, 20, sha256(new byte[20]), HOSTS, NEVER_CANCEL)
    );
    assertEquals(FailureReason.TRUNCATED, failure.reason());
    assertFalse(Files.exists(destination));
    assertTrue(Files.list(destination.getParent()).noneMatch(path -> path.getFileName().toString().endsWith(".part")));
}
```

- [ ] **Step 2: Run the focused tests and confirm missing classes**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeHttpSourceTest test
```

Expected: FAIL because `RuntimeHttpSource` is undefined.

- [ ] **Step 3: Implement manual redirect and bounded streaming**

Construct `HttpClient` with `Redirect.NEVER`, a 10-second connect timeout, and an injected executor. Each request uses a 30-second timeout. Validate URI scheme, normalized ASCII host, userinfo, port, query policy, and fragment before the request and after each redirect.

Stream to a uniquely named same-directory `.part` file while counting bytes and updating SHA-256. Reject a declared `Content-Length` outside the expected size before reading. Stop after `expectedBytes + 1`, compare exact count and constant-time digest, force the file, then atomically move it to `destination`. Delete the part file in `finally` on every failure or cancellation.

- [ ] **Step 4: Run focused, full plugin, and SpotBugs checks**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeHttpSourceTest test
.\mvnw.cmd -q test
.\mvnw.cmd -q spotbugs:check
```

Expected: PASS with no ignored certificate, redirect, path, or stream warnings.

- [ ] **Step 5: Commit the bounded downloader**

```powershell
git status --short
git diff -- minecraft_plugin/src/main/java/com/audioviz/runtime/net minecraft_plugin/src/test/java/com/audioviz/runtime/net
git add -- minecraft_plugin/src/main/java/com/audioviz/runtime/net minecraft_plugin/src/test/java/com/audioviz/runtime/net
git commit -m "feat(plugin): download runtime artifacts safely"
```

### Task 4: Validate and extract runtime ZIPs safely

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/store/RuntimePaths.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/store/RuntimeVersionId.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/store/PackagedFile.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/store/RuntimeArchiveVerifier.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/store/RuntimeStoreException.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/store/RuntimeArchiveVerifierTest.java`

**Interfaces:**
- Consumes: verified archive path, signed `RuntimeArtifact`, `RuntimeLimits`, and a signed internal `files.json` whose digest equals `files_manifest_sha256`.
- Produces: `RuntimeArchiveVerifier.extract(Path archive, Path uniqueStaging, RuntimeArtifact artifact, RuntimePlatform platform) -> VerifiedRuntimeLayout` with normalized file records and validated entrypoint.

- [ ] **Step 1: Write failing hostile-archive parameter tests**

Create ZIP bytes in tests without extracting them. Cover `/absolute`, `C:\drive`, UNC, `..`, encoded/alternate separators, empty/dot names, NUL, Windows device names, NTFS ADS, case collisions, duplicate entries, directory/file collisions, symlink Unix mode, undeclared member, missing member, bad member digest, bad member size, excessive count, oversized member, excessive total, excessive compression ratio, invalid UTF-8 name, and an entrypoint absent or not a regular file.

```java
@ParameterizedTest
@MethodSource("hostileArchives")
void rejectsArchiveWithoutWritingOutsideStaging(ArchiveCase archiveCase) throws Exception {
    Path outside = temp.resolve("sentinel.txt");
    Files.writeString(outside, "unchanged");
    RuntimeStoreException failure = assertThrows(
        RuntimeStoreException.class,
        () -> verifier.extract(archiveCase.path(), temp.resolve("stage"), archiveCase.artifact(), PLATFORM)
    );
    assertEquals(archiveCase.reason(), failure.reason());
    assertEquals("unchanged", Files.readString(outside));
}
```

- [ ] **Step 2: Run focused tests and confirm missing extractor**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeArchiveVerifierTest test
```

Expected: FAIL because store classes do not exist.

- [ ] **Step 3: Implement validated paths and two-pass extraction**

`RuntimeVersionId.of(releaseVersion, platform)` accepts only `[0-9A-Za-z][0-9A-Za-z._-]{0,63}` for version and uses the enum wire name for platform. `RuntimePaths.create(root)` resolves every owned child once and rejects a root that is a symlink or non-directory.

Pass one reads the ZIP central directory, normalizes `/` paths without using `ZipFile.extract`, validates flags/method/sizes/ratio/type/collisions, locates `files.json`, and compares its digest to the signed artifact. Parse `files.json` strictly into at most 16,384 `PackagedFile` records. Pass two opens each member, writes through a bounded digesting stream to a new file beneath unique staging, verifies exact bytes/hash, and forces the file. Reject any archive member not declared in `files.json` except `files.json` itself.

On POSIX, set directories to `0700`, regular files to `0600`, and only the declared entrypoint to `0700`. On Windows, do not attempt POSIX permissions. Never preserve ZIP owner, group, ACL, or external attributes beyond using them to reject link/device types.

- [ ] **Step 4: Add generated property cases for cross-platform path normalization**

Generate 10,000 deterministic path combinations from separators, drive prefixes, dot segments, reserved names, case variants, and Unicode normalization forms. Assert every accepted path remains under the staging root when evaluated with both Windows-style policy and POSIX-style policy; assert rejected inputs create no file.

- [ ] **Step 5: Run focused, full plugin, and static-analysis tests**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeArchiveVerifierTest test
.\mvnw.cmd -q test
.\mvnw.cmd -q spotbugs:check
```

Expected: PASS. Review `target/spotbugsXml.xml` and confirm no archive/path finding is excluded.

- [ ] **Step 6: Commit safe extraction**

```powershell
git status --short
git diff -- minecraft_plugin/src/main/java/com/audioviz/runtime/store minecraft_plugin/src/test/java/com/audioviz/runtime/store/RuntimeArchiveVerifierTest.java
git add -- minecraft_plugin/src/main/java/com/audioviz/runtime/store minecraft_plugin/src/test/java/com/audioviz/runtime/store/RuntimeArchiveVerifierTest.java
git commit -m "feat(plugin): extract runtime archives safely"
```

### Task 5: Commit immutable runtime versions and recover atomic state

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/store/RuntimeState.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/store/RuntimeTransaction.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/store/AtomicStateStore.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/store/RuntimeStore.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/store/AtomicStateStoreTest.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/store/RuntimeStoreTest.java`

**Interfaces:**
- Consumes: `VerifiedRuntimeLayout`, `RuntimeVersionId`, verified manifest generation/digest/API, and `RuntimePaths` from Task 4.
- Produces: `RuntimeStore.promote(...) -> InstalledRuntime`, `RuntimeStore.findCompatible(...) -> Optional<InstalledRuntime>`, `RuntimeStore.activateCandidate(...)`, `RuntimeStore.markHealthy(...)`, `RuntimeStore.rollbackTarget(...)`, `RuntimeStore.recover()`, and `RuntimeStore.prune(int retainVersions)`.

- [ ] **Step 1: Write failing transaction-boundary and retention tests**

Use an injected `AtomicFileOps` fault at each boundary: before/after file force, journal write, version move, current temp write, current replace, last-known-good replace, journal cleanup, and prune. Restart a new `RuntimeStore` after each failure and assert it chooses only a complete previously committed state.

```java
@Test
void failedCurrentReplaceRecoversPreviousRuntimeAndKeepsCandidateReusable() throws Exception {
    InstalledRuntime previous = store.installAndMarkHealthy(layout("1.1.0"));
    fileOps.failOnce(Boundary.CURRENT_REPLACE);
    assertThrows(RuntimeStoreException.class, () -> store.activateCandidate(layout("1.2.0")));

    RuntimeStore recovered = newStore(realFileOps());
    recovered.recover();
    assertEquals(previous.id(), recovered.current().orElseThrow().id());
    assertTrue(recovered.installed(runtimeId("1.2.0")).isPresent());
}
```

Retention tests must prove current, last-known-good, staging, journal-referenced, and pinned records are never removed. A collision with identical `files.json` is idempotent; a collision with any different digest fails.

- [ ] **Step 2: Run focused tests and confirm missing state classes**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=AtomicStateStoreTest,RuntimeStoreTest test
```

Expected: FAIL because the state store is undefined.

- [ ] **Step 3: Implement strict state records and atomic file operations**

Use schema-versioned compact JSON with a fixed field set:

```java
public record RuntimeState(
    int schemaVersion,
    RuntimeVersionId id,
    long manifestGeneration,
    String archiveSha256,
    String filesManifestSha256,
    int runtimeApi,
    Instant activatedAt,
    HealthState health
) {}
```

Write temp files in the destination directory, force the file channel, and move with `ATOMIC_MOVE, REPLACE_EXISTING`. If `ATOMIC_MOVE` is unsupported, require the journal protocol before `REPLACE_EXISTING`; tests exercise that branch. State parsing uses the same strict bounded JSON discipline as manifests. Never deserialize an absolute version path from metadata.

- [ ] **Step 4: Implement promotion, activation, health commit, recovery, and retention**

Promotion writes a journal containing operation ID, source staging ID, target version ID, expected file-manifest digest, and phase. Recovery reconciles the journal only against known owned directories and digests. It can finish an idempotent promotion or discard an uncommitted staging directory; it never guesses that an arbitrary directory is valid.

`markHealthy(candidate)` atomically sets candidate current and prior current last-known-good after the supervisor's stability window. `rollbackTarget(pluginApi)` returns last-known-good only if its runtime API range contains `pluginApi` and its files reverify. `prune(3)` keeps protected records plus the newest additional compatible version.

- [ ] **Step 5: Run store, archive, full plugin, and SpotBugs tests**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=AtomicStateStoreTest,RuntimeStoreTest,RuntimeArchiveVerifierTest test
.\mvnw.cmd -q test
.\mvnw.cmd -q spotbugs:check
```

Expected: PASS with no broad filesystem delete and no ignored I/O result.

- [ ] **Step 6: Commit immutable storage**

```powershell
git status --short
git diff -- minecraft_plugin/src/main/java/com/audioviz/runtime/store minecraft_plugin/src/test/java/com/audioviz/runtime/store
git add -- minecraft_plugin/src/main/java/com/audioviz/runtime/store minecraft_plugin/src/test/java/com/audioviz/runtime/store
git commit -m "feat(plugin): store runtime versions atomically"
```

### Task 6: Orchestrate a cancellable install transaction

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/install/CancellationToken.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/install/InstallLock.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/install/RuntimeInstallEvent.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/install/RuntimeInstaller.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/install/RuntimeInstallerTest.java`
- Modify: `minecraft_plugin/pom.xml`

**Interfaces:**
- Consumes: `RuntimeHttpSource`, `RuntimeManifestVerifier`, `RuntimeArchiveVerifier`, `RuntimeStore`, `ReleaseDescriptor`, `RuntimePlatform`, `Clock`, and an event sink.
- Produces: `RuntimeInstaller.install(InstallRequest request, CancellationToken token) -> InstallResult`, where result is `REUSED_CURRENT`, `REUSED_INSTALLED`, or `INSTALLED`, plus reason-coded events `CHECKING`, `DOWNLOADING`, `VERIFYING`, `STAGING`, and `INSTALLED`.

- [ ] **Step 1: Write failing orchestration and cancellation tests**

Cover first install, valid installed reuse, current reuse while manifest refresh fails, expired metadata refusing new activation but allowing installed current, artifact download, cancellation during response/extraction, lock contention, stale operation generation, manifest/API/platform mismatch, and every dependency failure preserving current.

```java
@Test
void cancellationAfterDownloadDoesNotPromoteCandidate() throws Exception {
    CancellationToken token = new CancellationToken();
    source.onDownloadComplete(token::cancel);
    InstallResult result = installer.install(request(), token);
    assertEquals(InstallResult.CANCELLED, result);
    assertEquals(runtimeId("1.1.0"), store.current().orElseThrow().id());
    assertTrue(store.installed(runtimeId("1.2.0")).isEmpty());
    assertFalse(Files.exists(paths.staging()));
}
```

- [ ] **Step 2: Run the focused test and verify the orchestrator is missing**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeInstallerTest test
```

Expected: FAIL because install classes do not exist.

- [ ] **Step 3: Implement the install lock and generation-aware cancellation**

`InstallLock.acquire(Path)` opens `install.lock` with `CREATE, WRITE`, uses `FileChannel.tryLock()`, and returns `AutoCloseable`. An overlapping lock maps to `INSTALL_ALREADY_RUNNING`. It never deletes the lock file as proof of ownership.

`CancellationToken` contains an atomic cancelled flag and operation generation. Every network read, archive member, metadata commit precondition, and promotion boundary checks it. Cancellation after immutable promotion may return the installed version for later reuse but must not activate it.

- [ ] **Step 4: Implement the transaction in one explicit order**

```java
public InstallResult install(InstallRequest request, CancellationToken token) throws InstallException {
    try (InstallLock ignored = InstallLock.acquire(paths.installLock())) {
        store.recover();
        Optional<InstalledRuntime> offline = store.findCompatible(request.pluginRuntimeApi());
        SignedManifestBytes signed = source.fetchManifest(request.descriptor(), token);
        RuntimeManifest manifest = verifier.verify(signed.payload(), signed.signature(), request.descriptor(), clock.instant());
        RuntimeArtifact artifact = resolver.select(manifest, request.platform(), request.pluginRuntimeApi());
        Optional<InstalledRuntime> existing = store.findExactVerified(manifest, artifact);
        if (existing.isPresent()) return InstallResult.reused(existing.get());
        Path archive = source.downloadArtifact(artifact, paths.downloads(), token);
        VerifiedRuntimeLayout layout = archiveVerifier.extract(archive, paths.newStaging(), artifact, request.platform());
        token.throwIfCancelled();
        return InstallResult.installed(store.promote(manifest, artifact, layout));
    } catch (ManifestRefreshException refreshFailure) {
        return reuseVerifiedOfflineOrThrow(refreshFailure, request);
    }
}
```

Do not catch and downgrade signature, generation, compatibility, or corruption failures to network-offline reuse unless an already installed current version independently reverifies and satisfies the embedded descriptor.

- [ ] **Step 5: Add JaCoCo package thresholds for the new pure-Java foundation**

Configure a JaCoCo check at `verify` requiring 90 percent line and 85 percent branch coverage for `com.audioviz.runtime.release`, `.net`, `.store`, and `.install`, excluding only record-generated methods. Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q verify
```

Expected: PASS. Add focused tests rather than lowering thresholds if it fails.

- [ ] **Step 6: Run foundation verification**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeManifestVerifierTest,RuntimePlatformTest,RuntimeHttpSourceTest,RuntimeArchiveVerifierTest,AtomicStateStoreTest,RuntimeStoreTest,RuntimeInstallerTest test
.\mvnw.cmd -q verify
.\mvnw.cmd -q spotbugs:check
Set-Location ..
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python -m pytest deploy/runtime/test_sign_manifest.py -q'
```

Expected: all commands PASS.

- [ ] **Step 7: Commit the install foundation**

```powershell
git status --short
git diff -- minecraft_plugin/src/main/java/com/audioviz/runtime/install minecraft_plugin/src/test/java/com/audioviz/runtime/install minecraft_plugin/pom.xml
git add -- minecraft_plugin/src/main/java/com/audioviz/runtime/install minecraft_plugin/src/test/java/com/audioviz/runtime/install minecraft_plugin/pom.xml
git commit -m "feat(plugin): orchestrate verified runtime installs"
```

## Plan Completion Evidence

Before starting Paper supervision, record:

- the six task commit hashes;
- exact Maven, Java, WSL Python, and dependency versions;
- full `mvn verify` and SpotBugs results;
- release signer/golden-vector results;
- hostile archive case count;
- JaCoCo package coverage; and
- `git status --short`, proving only pre-existing user changes remain.

This plan is complete only when the pure-Java foundation can take signed fixture metadata plus an archive, install one immutable verified runtime, recover every injected transaction failure, and return a typed result without loading Bukkit or starting a process.
