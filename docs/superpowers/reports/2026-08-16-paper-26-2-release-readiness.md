# Paper 26.2 Pre-Candidate Release Readiness

Reviewed at `2026-08-16T04:16:43Z` against branch commit `40eb1831a25e95b176103e8088de565e050f69a9`.

Overall status: **READY FOR PROTECTED PR; NOT YET AUTHORIZED FOR CANDIDATE CREATION OR PUBLICATION.** Exact-main CI and security results, including Paper OWASP Dependency-Check, remain mandatory before the candidate workflow can run. Protected-environment human approval remains mandatory before publication.

## Expected candidate identity

- Artifact: `mcav-paper-1.1.0.jar`
- Paper API: `io.papermc.paper:paper-api:26.2.build.112-stable`
- Java release: `25`
- Reproducible timestamp commit: repository root `a1fb59ac58021a8b362625b460aa589818e77c85`
- Reproducible timestamp: `1771516763`
- Expected JAR SHA-256: `b515c859a4da82961b524e515451315d91d67f3d5e0389019c1e55f51f618de5`
- Reproducibility result: `PASS`; two clean Java 25 builds produced identical bytes

The candidate workflow records exact current-main identity separately from the immutable repository-root commit used for archive timestamps. Documentation, workflow, rebase, squash, or merge metadata therefore cannot perturb the JAR bytes.

## G0 — recovery and durable history

Status: **PASS**. Reviewer: Codex.

- Requirement: every local change is mapped to durable history with a recovery path.
- Evidence: `docs/superpowers/reports/2026-08-15-git-recovery-ledger.md` at commit `3044f65ff7eb91645f6cf52e483ad40a032911fb`.
- Evidence: branch `release/paper-26.2` is clean and all release work is represented by atomic conventional commits.
- Remote publication of this branch is intentionally deferred until the final rebase in the protected-PR sequence.

## G1 — repository governance

Status: **PASS**. Reviewer: Codex plus live GitHub API verification.

- Dependabot policy: `PASS`; nine ecosystems, pull-request limit three, required groups present.
- Workflow policy: `PASS`; all 12 workflows use full action SHAs and bounded permissions.
- Live repository policy: `PASS`; protected `main`, tag creation/immutability, protected `plugin-release` environment, one scoped write deploy key, and required environment secret verified.
- Fabric remains quarantined and on Java 21. Paper jobs are on Java 25.

## G2 — Paper 26.2 runtime correctness

Status: **PASS**. Reviewer: Codex.

- Port evidence: `docs/superpowers/reports/2026-08-15-paper-26-2-port-verification.md` at commit `bd8c423f59e277f25d1e8b089ab028bfdf0c80eb`.
- Java 25 clean verification: `976` tests, zero failures, zero errors, zero skips; shaded build successful.
- WSL Python 3.12 VJ and release-harness verification: `485` tests passed; three known `websockets` deprecation warnings.
- Protocol contracts: `5` tests passed.
- Real Paper 26.2 lifecycle: `17` checks passed, including bad-secret rejection, valid pairing, frame application, cleanup, reconnect rehydration, optional-integration absence, and process shutdown. The sanitized report contains no pairing secret.
- Candidate probe regression: the staged JAR is installed into Maven before probe compilation without rebuilding candidate bytes; workflow/manifest contracts pass.

## G3 — security and dependency state

Status: **LOCAL GATES PASS; AUTHORITATIVE REMOTE OWASP RESULT REQUIRED.** Reviewer: Codex locally; GitHub Actions required for final status.

- npm audit: root, `dj_client`, `site`, and `worker` each report zero total vulnerabilities.
- Python `pip-audit`: no known vulnerabilities in isolated VJ/coordinator or community-bot Python 3.12 environments. The three local MCAV packages are correctly skipped as unpublished package identities.
- Rust `cargo audit`: exit zero, 589 locked dependencies, zero vulnerabilities. Advisory DB commit `69f93e1d081d8b6fbee010e48f0b5e0d13661415`. Informational warnings remain for 18 unmaintained and 3 unsound transitive crates; these are disclosed and are not reported by the scanner as vulnerabilities.
- Paper OWASP Dependency-Check: the uncached local scanner was stopped after 3% of a 378,296-record NVD update because no local NVD API key was available and the user requested reduced workstation impact. No local Java vulnerability conclusion is inferred from that interrupted update.
- Required resolution: exact-main `security.yml` must complete successfully with generated JSON/SARIF reports using the repository NVD secret/cache. Candidate preflight independently requires that successful exact-SHA security run.

## G4 — performance, cleanup, and endurance

Status: **PASS UNDER USER-APPROVED SIX-HOUR CRITERION.** Reviewer: user and Codex.

- Evidence: `docs/superpowers/reports/2026-08-15-paper-26-2-reference-machine.md` at commit `db25e2da710db438e405ba9a5f5459abf28ee1f9`.
- Automated 600.079-second gate: all 11 checks passed over 11,827 samples.
- Applied-frame p95: `48.664 ms` against a `100 ms` maximum.
- Plugin main-thread p95 maximum: `3.231 ms` against a `10 ms` maximum.
- Minimum one-minute TPS: `19.9` against a `19.8` minimum.
- Maximum queues: parsed `1`, raw `1`; dropped-frame delta `0`.
- Cleanup and process-stop checks passed.
- Endurance: more than six hours of operation and 27 scheduled reconnects were observed without an emitted runtime failure. The user ended the workload because of workstation impact. It is explicitly not represented as an eight-hour automated pass.

## G5 — operator readiness and rollback

Status: **PASS**. Reviewer: Codex.

- Operator guide: `docs/PAPER_26_2_OPERATOR_GUIDE.md`.
- Public release notes: `docs/releases/plugin-v1.1.0.md`.
- Documentation covers Paper 26.2, Java 25+, authenticated loopback VJ pairing, source VJ installation, checksum verification, backup, uninstall, rollback, cleanup, optional integrations, and unsupported compatibility boundaries.
- Published tags/assets are immutable; corrections require a new patch release.

## G6 — candidate identity, SBOM, and provenance

Status: **PREPARED; FINAL ARTIFACT EVIDENCE PENDING CANDIDATE RUN.** Reviewer: Codex locally; GitHub attestation service required for final status.

- Two local clean builds at the repository-root timestamp are byte-identical at the expected SHA-256 above.
- Candidate manifest schema is closed and validates version, exact main commit, repository-root timestamp identity, Java release, Paper coordinate/hash, JAR hash, SBOM hash, and endurance-evidence hash.
- Candidate workflow builds twice, compares bytes, creates a CycloneDX JSON SBOM, runs real Paper integration, attests the JAR/SBOM, and retains one bundle for 90 days.
- Promotion downloads and reverifies that retained bundle and its attestations. It never rebuilds.
- Final G6 completion requires the candidate-run manifest, checksum, SBOM, and GitHub attestations to match the expected JAR bytes.

## Required protected sequence

1. Rebuild after this documentation commit and require the same expected JAR SHA-256.
2. Rebase once onto current `origin/main`, push `release/paper-26.2`, and open the protected PR.
3. Require green `CI Passed` and `Security Summary`, including successful Paper OWASP reports.
4. Merge only through the protected `main` ruleset, then require green exact-main push runs.
5. Dispatch `paper-candidate.yml` for exact current `main` and verify retained bytes/attestations.
6. Dispatch `release-plugin.yml`; the user approves `plugin-release` before the immutable tag can be created.
7. Verify the public tag, release metadata, and every published asset against the retained candidate.

Known non-blocking warnings are limited to Maven runtime native/deprecation notices, the three Python `websockets` deprecations, Maven shade overlap notices already covered by artifact tests, and the disclosed Rust informational advisories.
