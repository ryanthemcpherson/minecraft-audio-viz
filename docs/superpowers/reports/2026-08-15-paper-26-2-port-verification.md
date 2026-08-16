# Paper 26.2 Port Verification

Date: 2026-08-15

Result: **PASS — source, unit, and package-contract gate (G2)**

This report records the complete port gate for the MCAV 1.1.0 Paper renderer. Real Paper process, gameplay, soak, uninstall, and rollback validation remain separate release gates and are not implied by this result.

## Verified source

| Item | Value |
|-|-|
| Branch | `release/paper-26.2` |
| Source commit | `ac6e66813dc712c4616e68b3bf2a476243db4bc9` |
| Plugin version | `1.1.0` |
| Paper API | `26.2.build.112-stable` |
| Java target | `25` |
| Artifact | `minecraft_plugin/target/mcav-paper-1.1.0.jar` |
| Java image | `eclipse-temurin:25-jdk` |
| Image digest | `sha256:c42fecf62f32725c65cfea284c012526d6fb31cc78123c740ebdc1cfd2dced12` |

The Docker tag resolved to `eclipse-temurin@sha256:c42fecf62f32725c65cfea284c012526d6fb31cc78123c740ebdc1cfd2dced12` at verification time.

## Java 25 clean verification

Executed from the repository root. `-ntp` only suppressed Maven transfer progress.

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw clean verify -ntp'
```

| Measurement | Result |
|-|-|
| Production sources compiled | 166 |
| Test sources compiled | 49 |
| Tests | 975 passed |
| Failures | 0 |
| Errors | 0 |
| Skipped | 0 |
| JaCoCo classes analyzed | 220 |
| Maven result | `BUILD SUCCESS` |
| Elapsed time | 2 minutes 18 seconds |

The run includes the regression proving the metrics sidebar reads the canonical `performance.max_entities_per_zone` setting. A source search found zero remaining `entities.max-per-zone` lookups.

## VJ server verification

```powershell
wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --locked --python 3.12 pytest vj_server/tests -q'
```

| Measurement | Result |
|-|-|
| Python | 3.12.13 |
| Tests | 440 passed |
| Failures | 0 |
| Skipped | 0 |
| Coverage | 59% |
| Elapsed time | 16.53 seconds |

The suite includes authenticated renderer connection and reconnect-stage rehydration coverage. Dependency resolution used the checked-in `uv.lock` via `--locked`.

## Protocol verification

```powershell
node --test protocol/tests/phase0-schemas.test.mjs
```

| Measurement | Result |
|-|-|
| Tests | 5 passed |
| Suites | 0 |
| Failed | 0 |
| Skipped | 0 |
| Duration | 73.3075 milliseconds |

The checks cover handshake schema inventory, optional protocol versions, connected-message compatibility, and closed/bounded WebSocket authentication messages.

## JAR contract

Inspected with `jar tf`, the .NET ZIP reader, `Get-FileHash`, and `Test-Path` after the clean Java build.

| Contract | Result |
|-|-|
| Embedded `plugin.yml` entries | 1 |
| Embedded plugin version | `1.1.0` |
| Embedded API version | `26.2` |
| Relocated `com/audioviz/libs/websocket/` entries | 84 |
| Unrelocated `org/java_websocket/` entries | 0 |
| Bundled `org/bukkit/` or `io/papermc/` entries | 0 |
| `minecraft_plugin/dependency-reduced-pom.xml` present after clean build | No |
| JAR size | 1,153,007 bytes |
| SHA-256 | `8FB66C6436044B197E654B05CFEB02B172DB19A4F891ED3782B2B18446BCD6AC` |

The embedded descriptor names `com.audioviz.AudioVizPlugin` as the main class and declares Geyser, Floodgate, and Simple Voice Chat as soft dependencies rather than required runtime dependencies.

## Non-failing warnings

- Java 25 reported Jansi's restricted native load and Guava's terminally deprecated `Unsafe.objectFieldOffset` use from Maven's own runtime.
- The compiler reported deprecated API use in `AudioVizPlugin` and WebSocket test fixtures without compilation failures.
- The test runtime reported no SLF4J provider; the affected library used its no-operation fallback during tests.
- Timeout and backpressure warnings emitted by their explicit negative-path tests were expected.
- Maven Shade removed `module-info`, merged duplicate manifests, and reported duplicate versioned `module-info` classes before minimizing 631 classes/resources to 579. The resulting JAR contract was inspected directly and passed.
- The VJ suite emitted three `websockets` deprecation warnings for the legacy server/client protocol imports. They do not fail this gate but remain upgrade work for a later dependency migration.

## Gate conclusion

G2 passes for the Paper 26.2 / Java 25 port: compilation, all Java tests, all VJ tests, protocol contracts, embedded release metadata, dependency relocation, provided Paper API boundary, generated-file hygiene, artifact size, and artifact hash are evidenced above.

This report does not waive the remaining real-server gates: first start and secret persistence, authenticated VJ pairing, stage creation and audio rendering, reconnect grace behavior, queue pressure, sustained soak, clean uninstall, rollback, CI artifact equivalence, and release publication controls.
