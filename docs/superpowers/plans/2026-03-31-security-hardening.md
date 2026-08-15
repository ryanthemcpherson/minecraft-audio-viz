# Security Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close all identified supply chain security gaps: cargo-deny, blocking npm audit, container scanning, SLSA provenance, full SBOM coverage, secret scanning in CI, CodeQL for Java, and branch protection documentation.

**Architecture:** Config-only changes to CI workflows, pre-commit hooks, and Rust tooling config. No application code changes. Each task is independent and can be implemented in any order.

**Tech Stack:** GitHub Actions, cargo-deny, Trivy, CycloneDX, TruffleHog, gitleaks, CodeQL, actions/attest-build-provenance

---

### Task 1: Add cargo-deny for Rust supply chain control

**Files:**
- Create: `dj_client/src-tauri/deny.toml`

- [ ] **Step 1: Create deny.toml**

```toml
# cargo-deny configuration
# https://embarkstudios.github.io/cargo-deny/

[advisories]
version = 2
db-path = "~/.cargo/advisory-db"
db-urls = ["https://github.com/rustsec/advisory-db"]
# Deny any crate with a known vulnerability or that is unmaintained
unmaintained = "warn"

[licenses]
version = 2
# Allow common permissive licenses
allow = [
    "MIT",
    "Apache-2.0",
    "Apache-2.0 WITH LLVM-exception",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "ISC",
    "Unicode-3.0",
    "Unicode-DFS-2016",
    "Zlib",
    "BSL-1.0",
    "OpenSSL",
    "MPL-2.0",
]
# Deny copyleft licenses
deny = [
    "GPL-2.0",
    "GPL-3.0",
    "AGPL-3.0",
]
confidence-threshold = 0.8

[[licenses.clarify]]
name = "ring"
expression = "MIT AND ISC AND OpenSSL"
license-files = [{ path = "LICENSE", hash = 0xbd0eed23 }]

[bans]
multiple-versions = "warn"
wildcards = "allow"
highlight = "all"

[sources]
unknown-registry = "deny"
unknown-git = "deny"
allow-registry = ["https://github.com/rust-lang/crates.io-index"]
allow-git = []
```

- [ ] **Step 2: Add cargo-deny to CI security workflow**

In `.github/workflows/security.yml`, add a step in the `rust-security` job after `cargo-audit`:

After the existing `cargo audit` step, add:

```yaml
      - name: Install cargo-deny
        run: cargo install cargo-deny

      - name: cargo-deny check
        working-directory: dj_client/src-tauri
        run: cargo deny check
```

- [ ] **Step 3: Add cargo-deny to CI workflow**

In `.github/workflows/ci.yml`, modify the `rust-audit` job. After the existing `cargo audit` step, add:

```yaml
      - name: Install cargo-deny
        run: cargo install cargo-deny

      - name: cargo-deny check
        working-directory: dj_client/src-tauri
        run: cargo deny check
```

- [ ] **Step 4: Commit**

```bash
git add dj_client/src-tauri/deny.toml .github/workflows/security.yml .github/workflows/ci.yml
git commit -m "feat(security): add cargo-deny for Rust supply chain control

Denies unknown registries/git sources, copyleft licenses, and
known vulnerable crates. Warns on duplicate versions and
unmaintained crates."
```

---

### Task 2: Make npm audit blocking in CI

**Files:**
- Modify: `.github/workflows/ci.yml` (npm-audit job, lines 315-348; ci-passed job, line 478)
- Modify: `.github/workflows/security.yml` (npm-security job, lines 80-131)

- [ ] **Step 1: Update ci.yml npm-audit job to fail on high-severity vulns**

Replace each `npm audit` invocation's `|| true` with proper error handling. The pattern for each workspace:

```yaml
      - name: Audit root package
        run: |
          npm install --package-lock-only
          npm audit --audit-level=high

      - name: Audit dj_client package
        working-directory: dj_client
        run: |
          npm install --package-lock-only
          npm audit --audit-level=high

      - name: Audit site package
        working-directory: site
        run: |
          npm install --package-lock-only
          npm audit --audit-level=high

      - name: Audit worker package
        working-directory: worker
        run: |
          npm install --package-lock-only
          npm audit --audit-level=high
```

- [ ] **Step 2: Add npm-audit to the CI gate**

In the `ci-passed` job, add `npm-audit` to the `needs` array (it's already there) and add it to the blocking check:

In the "Check required results" step, add to the failure condition:

```bash
"${{ needs.npm-audit.result }}" == "failure" ||
```

This goes inside the existing `if [[ ... ]]` block alongside the other blocking checks.

- [ ] **Step 3: Update security.yml npm-security job to fail on high-severity vulns**

Replace each audit step's `|| true` pattern. Each workspace audit becomes:

```yaml
      - name: Audit root package
        run: |
          npm install --package-lock-only
          npm audit --audit-level=high --json > npm-audit-root.json
          npm audit --audit-level=high

      - name: Audit dj_client package
        working-directory: dj_client
        run: |
          npm install --package-lock-only
          npm audit --audit-level=high --json > npm-audit-djclient.json
          npm audit --audit-level=high

      - name: Audit site package
        working-directory: site
        run: |
          npm install --package-lock-only
          npm audit --audit-level=high --json > npm-audit-site.json
          npm audit --audit-level=high

      - name: Audit worker package
        working-directory: worker
        run: |
          npm install --package-lock-only
          npm audit --audit-level=high --json > npm-audit-worker.json
          npm audit --audit-level=high
```

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml .github/workflows/security.yml
git commit -m "feat(security): make npm audit blocking for high-severity vulns

Remove || true from all npm audit steps. High/critical vulns
now fail CI instead of being advisory-only."
```

---

### Task 3: Add container image scanning with Trivy

**Files:**
- Modify: `.github/workflows/docker.yml` (after "Build and push" step, line 55)

- [ ] **Step 1: Add Trivy scan step after Docker build**

After the "Build and push" step and before the "Test Docker image" step in `docker.yml`, add:

```yaml
      - name: Build image for scanning
        uses: docker/build-push-action@v6
        with:
          context: .
          load: true
          tags: audioviz-scan:latest
          cache-from: type=gha

      - name: Run Trivy vulnerability scanner
        uses: aquasecurity/trivy-action@master
        with:
          image-ref: 'audioviz-scan:latest'
          format: 'table'
          exit-code: '1'
          severity: 'HIGH,CRITICAL'
          ignore-unfixed: true

      - name: Run Trivy (SARIF output)
        if: always()
        uses: aquasecurity/trivy-action@master
        with:
          image-ref: 'audioviz-scan:latest'
          format: 'sarif'
          output: 'trivy-results.sarif'
          severity: 'HIGH,CRITICAL'

      - name: Upload Trivy SARIF
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: trivy-sarif
          path: trivy-results.sarif
          retention-days: 30
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/docker.yml
git commit -m "feat(security): add Trivy container image scanning

Scans Docker image for HIGH/CRITICAL OS and library vulnerabilities.
Fails the build on findings. Uploads SARIF report as artifact."
```

---

### Task 4: Add SLSA provenance attestation to release workflows

**Files:**
- Modify: `.github/workflows/release.yml` (release job, after "Create Release" step)
- Modify: `.github/workflows/release-dj-client.yml` (release job)
- Modify: `.github/workflows/release-plugin.yml` (release job)
- Modify: `.github/workflows/release-mod.yml` (release job)

All four release workflows need `id-token: write` permission added and an attestation step after the release creation.

- [ ] **Step 1: Update release.yml permissions and add attestation**

Change the top-level permissions from:

```yaml
permissions:
  contents: write
```

to:

```yaml
permissions:
  contents: write
  id-token: write
  attestations: write
```

Then after the "Create Release" step in the `release` job, add:

```yaml
      - name: Attest mod artifacts
        uses: actions/attest-build-provenance@v2
        with:
          subject-path: 'mod/*.jar'

      - name: Attest plugin artifacts
        uses: actions/attest-build-provenance@v2
        with:
          subject-path: 'plugin/*.jar'

      - name: Attest DJ Client artifacts (Windows)
        if: hashFiles('dj-client/windows/*') != ''
        uses: actions/attest-build-provenance@v2
        with:
          subject-path: 'dj-client/windows/*'

      - name: Attest DJ Client artifacts (Linux)
        if: hashFiles('dj-client/linux/*') != ''
        uses: actions/attest-build-provenance@v2
        with:
          subject-path: 'dj-client/linux/*'

      - name: Attest DJ Client artifacts (macOS x64)
        if: hashFiles('dj-client/macos-x64/*') != ''
        uses: actions/attest-build-provenance@v2
        with:
          subject-path: 'dj-client/macos-x64/*'

      - name: Attest DJ Client artifacts (macOS ARM64)
        if: hashFiles('dj-client/macos-arm64/*') != ''
        uses: actions/attest-build-provenance@v2
        with:
          subject-path: 'dj-client/macos-arm64/*'
```

- [ ] **Step 2: Update release-dj-client.yml permissions and add attestation**

Change the top-level permissions from:

```yaml
permissions:
  contents: write
```

to:

```yaml
permissions:
  contents: write
  id-token: write
  attestations: write
```

After the "Create Release" step in the `release` job, add:

```yaml
      - name: Attest release artifacts
        uses: actions/attest-build-provenance@v2
        with:
          subject-path: |
            artifacts/dj-client-windows-x64/*
            artifacts/dj-client-linux-x64/*
            artifacts/dj-client-macos-x64/*
            artifacts/dj-client-macos-arm64/*
```

- [ ] **Step 3: Update release-plugin.yml permissions and add attestation**

Change the top-level permissions from:

```yaml
permissions:
  contents: write
```

to:

```yaml
permissions:
  contents: write
  id-token: write
  attestations: write
```

After the "Create Release" step in the `release` job, add:

```yaml
      - name: Attest plugin artifacts
        uses: actions/attest-build-provenance@v2
        with:
          subject-path: 'plugin/*.jar'
```

- [ ] **Step 4: Update release-mod.yml permissions and add attestation**

Change the top-level permissions from:

```yaml
permissions:
  contents: write
```

to:

```yaml
permissions:
  contents: write
  id-token: write
  attestations: write
```

After the "Create Release" step in the `release` job, add:

```yaml
      - name: Attest mod artifacts
        uses: actions/attest-build-provenance@v2
        with:
          subject-path: 'mod/*.jar'
```

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/release.yml .github/workflows/release-dj-client.yml .github/workflows/release-plugin.yml .github/workflows/release-mod.yml
git commit -m "feat(security): add SLSA provenance attestation to all releases

All release artifacts now get signed build provenance via
actions/attest-build-provenance. Users can verify with
gh attestation verify <artifact>."
```

---

### Task 5: Expand SBOM generation to all ecosystems

**Files:**
- Modify: `.github/workflows/ci.yml` (sbom job, lines 414-441)

- [ ] **Step 1: Add npm, Java, and Rust SBOM generation**

Replace the existing `sbom` job with an expanded version. The current job only generates Python SBOM. The new version adds npm, Java (Maven), and Rust:

```yaml
  sbom:
    name: Generate SBOM
    runs-on: ubuntu-latest
    needs: [java-build]
    steps:
      - uses: actions/checkout@v4

      - name: Set up Python
        uses: actions/setup-python@v5
        with:
          python-version: '3.12'

      - name: Set up Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven

      - name: Install Rust toolchain
        uses: dtolnay/rust-toolchain@stable

      - name: Install Python deps + CycloneDX
        run: |
          python -m pip install --upgrade pip
          pip install -e "vj_server/[full]" -e "coordinator/[dev]" cyclonedx-bom

      - name: Generate Python SBOM
        run: cyclonedx-py environment -o sbom-python.json --output-format json

      - name: Generate npm SBOMs
        run: |
          npx @cyclonedx/cyclonedx-npm --output-file sbom-npm-root.json --package-lock-only || true
          cd dj_client && npx @cyclonedx/cyclonedx-npm --output-file ../sbom-npm-dj-client.json --package-lock-only || true
          cd ../site && npx @cyclonedx/cyclonedx-npm --output-file ../sbom-npm-site.json --package-lock-only || true
          cd ../worker && npx @cyclonedx/cyclonedx-npm --output-file ../sbom-npm-worker.json --package-lock-only || true

      - name: Generate Java SBOM (Maven)
        working-directory: minecraft_plugin
        continue-on-error: true
        run: |
          ./mvnw org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom -B -q
          cp target/bom.json ../sbom-java-plugin.json

      - name: Generate Rust SBOM
        run: |
          cargo install cargo-cyclonedx
          cd dj_client/src-tauri && cargo cyclonedx --format json
          cp dj_client/src-tauri/bom.json sbom-rust-dj-client.json || true

      - name: Upload SBOMs
        uses: actions/upload-artifact@v7
        with:
          name: sbom-${{ github.sha }}
          path: |
            sbom-*.json
          retention-days: 90
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "feat(security): expand SBOM generation to npm, Java, and Rust

Previously only generated Python SBOM. Now covers all ecosystems
using CycloneDX: npm (4 workspaces), Maven (plugin), Cargo (DJ client)."
```

---

### Task 6: Add secret scanning to CI with TruffleHog

**Files:**
- Modify: `.github/workflows/ci.yml` (add new job)
- Modify: `.pre-commit-config.yaml` (add gitleaks hook)

- [ ] **Step 1: Add TruffleHog job to ci.yml**

Add this new job before the `ci-passed` job:

```yaml
  # ---------------------------------------------------------------------------
  # Secret scanning (TruffleHog)
  # ---------------------------------------------------------------------------
  secret-scan:
    name: Secret Scan
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: TruffleHog scan
        uses: trufflesecurity/trufflehog@main
        with:
          extra_args: --only-verified --results=verified,unknown
```

Also add `secret-scan` to the `ci-passed` job's `needs` array and add `"${{ needs.secret-scan.result }}" == "failure" ||` to the blocking check condition in the "Check required results" step.

- [ ] **Step 2: Add gitleaks to pre-commit config**

Add to `.pre-commit-config.yaml` after the bandit hook:

```yaml
  - repo: https://github.com/gitleaks/gitleaks
    rev: v8.24.0
    hooks:
      - id: gitleaks
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml .pre-commit-config.yaml
git commit -m "feat(security): add secret scanning with TruffleHog and gitleaks

TruffleHog scans PR diffs in CI for verified secrets.
gitleaks runs locally via pre-commit to catch secrets before push."
```

---

### Task 7: Add CodeQL analysis for Java

**Files:**
- Create: `.github/workflows/codeql.yml`

- [ ] **Step 1: Create CodeQL workflow**

```yaml
name: CodeQL

on:
  push:
    branches: [main]
    paths:
      - 'minecraft_plugin/**'
      - 'minecraft_mod/**'
  pull_request:
    branches: [main]
    paths:
      - 'minecraft_plugin/**'
      - 'minecraft_mod/**'
  schedule:
    - cron: '0 8 * * 1'

permissions:
  security-events: write
  contents: read

jobs:
  analyze:
    name: CodeQL Analysis (Java)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven

      - name: Initialize CodeQL
        uses: github/codeql-action/init@v3
        with:
          languages: java-kotlin
          queries: security-extended

      - name: Build Paper Plugin
        working-directory: minecraft_plugin
        run: ./mvnw package -q -DskipTests

      - name: Perform CodeQL Analysis
        uses: github/codeql-action/analyze@v3
        with:
          category: "/language:java-kotlin"
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/codeql.yml
git commit -m "feat(security): add CodeQL static analysis for Java

Runs on PRs touching minecraft_plugin/ or minecraft_mod/ and
weekly. Uses security-extended queries for deeper analysis."
```

---

### Task 8: Document branch protection recommendations

**Files:**
- Create: `docs/branch-protection.md`

- [ ] **Step 1: Create branch protection documentation**

```markdown
# Branch Protection Setup

Recommended GitHub branch protection rules for the `main` branch.

## Settings

### Require pull request reviews
- Required approving reviews: **1**
- Dismiss stale PR reviews on new pushes: **Yes**
- Require review from code owners: **Yes**

### Require status checks
- Require branches be up to date before merging: **Yes**
- Required checks:
  - `CI Passed` (the aggregate gate job)
  - `Secret Scan`

### Require signed commits
- **Yes** (GPG key already configured at `.github/GPG-PUBLIC-KEY.asc`)

### Other
- Require linear history: **No** (squash merges already enforced by convention)
- Include administrators: **No** (allows admin override for Dependabot merges)
- Restrict who can push: **Not configured** (rely on PR requirement)
- Allow force pushes: **Never**
- Allow deletions: **No**

## Setup Steps

1. Go to **Settings > Branches > Branch protection rules**
2. Click **Add rule**
3. Branch name pattern: `main`
4. Enable each setting as listed above
5. Click **Create** / **Save changes**

## Notes

- Admin override is intentionally left available for batch Dependabot merges
- The `CI Passed` job aggregates all required checks, so only one status check entry is needed
- Secret Scan is listed separately since it's a security-critical gate
```

- [ ] **Step 2: Commit**

```bash
git add docs/branch-protection.md
git commit -m "docs: add branch protection setup recommendations

Documents recommended GitHub branch protection rules including
required reviews, status checks, and signed commits."
```
