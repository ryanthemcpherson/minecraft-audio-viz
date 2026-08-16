# Paper 26.2 Reference-Machine Release Evidence

## Decision

G4 is accepted under the user's 2026-08-15 revision: the automated performance and cleanup gate must pass, and at least six hours of continuous operation with scheduled reconnects must be observed. The endurance run was intentionally stopped after more than six hours because it was materially affecting workstation performance. It is not represented as an eight-hour automated pass.

## Candidate under test

- Branch commit at build: `ba84b268920014ed2f8ea99072a3283b2feeb5af`
- Plugin: `mcav-paper-1.1.0.jar`
- Plugin SHA-256: `24a7344c73cc47c05381e2e78e15f598810d873dddc0c1f9de19dfd60c93e4f7`
- Paper: `paper-26.2-112.jar`
- Paper SHA-256: `bd3a58cf96874e5ea6643f5f6fe9b4f5bf9e34b795fa078c2f0ee8b98b2f907e`
- Paper API: `io.papermc.paper:paper-api:26.2.build.112-stable`
- Java runtime: Eclipse Temurin OpenJDK `25.0.3+9` LTS

## Completed automated performance and cleanup gate

- Raw report: `build/reports/paper-performance-smoke.json` (generated evidence, not committed)
- Raw report SHA-256: `6d1cfe6bd2f06447ebd9fefb5ba7058b76196e8507be6b21fb9c8fd9305eea2d`
- Start: `2026-08-15T20:30:54.303603+00:00`
- End: `2026-08-15T20:41:12.456000+00:00`
- Measured duration: `600.079` seconds
- Status: `PASS`; all 11 checks passed
- Samples: `11,827`
- Applied-frame latency: p50 `36.375 ms`, p95 `48.664 ms`, p99 `49.462 ms`, maximum `4,198.871 ms`
- Plugin main-thread update p95 maximum: `3.231 ms`
- Minimum one-minute TPS: `19.9`
- Maximum queue depth: parsed `1`, raw `1`
- Dropped-frame delta: `0`
- Entity cleanup, queue cleanup, and process-stop checks: `PASS`

The single 4.2-second maximum pause does not breach the approved p95 latency gate. The p95 remained below half of the 100 ms threshold, TPS stayed above 19.8, queues remained effectively empty, and no frames were dropped.

Resource snapshots were taken while the server remained available for cleanup verification. Committed heap stayed at `1,073,741,824` bytes; used heap moved from `560,795,648` to `833,256,448` bytes. JVM thread count decreased from `115` to `94`; MCAV had zero non-daemon threads after cleanup. The separate process-stop check passed.

## Sustained endurance observation

- Workload started: `2026-08-15T20:43:46.177000+00:00`
- Last confirmed scheduled reconnect: `2026-08-16T03:28:45.191000+00:00`
- Confirmed continuous duration: at least `6h45m`
- Scheduled reconnects confirmed: `27` at 15-minute intervals
- Reconnect result: every observed disconnect reconnected successfully, generally in about `103-110 ms`
- Runtime result: no assertion, authentication, queue, TPS, latency, or subprocess failure was emitted before the user-requested stop
- Stop reason: explicit user request because the workload was affecting computer performance
- Automated endurance JSON: not produced; the process was interrupted before final report materialization
- Post-stop cleanup: no Paper, VJ, performance-harness, or integration-harness process remained

This observation satisfies the revised six-hour endurance criterion. It does not supply the automated final metrics or `release_soak_eligible` flag that an uninterrupted eight-hour harness run would have produced.

## Reference machine

- CPU: 13th Gen Intel Core i5-13600KF, 14 cores / 20 logical processors
- Host RAM: `34,189,053,952` bytes (approximately 31.84 GiB)
- Windows: Windows 11 Pro `10.0.26200`, build `26200`
- WSL: `2.7.11.0`; kernel `6.18.33.2-2`; Ubuntu `22.04.5`
- WSL-visible RAM: `8,324,292,608` bytes; swap `2,147,483,648` bytes
- Docker Desktop: `4.55.0`
- Docker Engine/client: `29.1.3`, API `1.52`
- Java 25 image digest: `sha256:c42fecf62f32725c65cfea284c012526d6fb31cc78123c740ebdc1cfd2dced12`

No pairing secret or full environment dump is included in this evidence.
