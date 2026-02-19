# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| 1.x     | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a Vulnerability

We take security seriously. If you discover a security vulnerability in this project, please report it responsibly.

### How to Report

**Please DO NOT open a public GitHub issue for security vulnerabilities.**

Instead, use one of these methods:

1. **GitHub Security Advisories (preferred):**
   Go to the [Security Advisories](https://github.com/ryanthemcpherson/minecraft-audio-viz/security/advisories/new) page and create a private report.

2. **Email:**
   Contact the maintainer directly with details of the vulnerability.

### What to Include

- A description of the vulnerability
- Steps to reproduce the issue
- Affected versions
- Any potential impact assessment
- Suggested fix (if you have one)

### What to Expect

- **Acknowledgment:** Within 48 hours of your report
- **Initial assessment:** Within 1 week
- **Fix timeline:** Depends on severity
  - Critical: Patch within 72 hours
  - High: Patch within 1 week
  - Medium/Low: Patch in next release

### Scope

The following components are in scope:

| Component | In Scope |
|-----------|----------|
| `audio_processor/` (Python) | Yes - WebSocket server, audio processing |
| `minecraft_plugin/` (Java) | Yes - Plugin running on game servers |
| `dj_client/` (Rust/Tauri) | Yes - Desktop application |
| `admin_panel/` (JS) | Yes - Web control interface |
| `preview_tool/` (JS) | Yes - Web preview interface |
| `python_client/` (Python) | Yes - Client library |

### Security Measures in CI

This project runs automated security scanning on every commit:

- **Python:** Bandit (SAST), pip-audit (dependency CVEs)
- **Java:** SpotBugs + Find-Sec-Bugs (SAST), OWASP Dependency-Check (CVEs)
- **Rust:** cargo-audit (dependency CVEs)
- **Node.js:** npm audit (dependency CVEs)
- **All:** Dependabot for automated dependency updates, CycloneDX SBOM generation, license compliance checks

## Verifying Release Artifacts

Every CI build generates SHA256 checksums for release artifacts.

### Verifying a Plugin JAR

1. Download the JAR and the `SHA256SUMS.txt` from the GitHub Actions artifacts
2. Verify the checksum:

```bash
# Linux/macOS
sha256sum -c SHA256SUMS.txt

# Windows (PowerShell)
$expected = (Get-Content SHA256SUMS.txt | Select-String "audioviz-plugin").ToString().Split(" ")[0]
$actual = (Get-FileHash audioviz-plugin-1.0.0-SNAPSHOT.jar -Algorithm SHA256).Hash.ToLower()
if ($expected -eq $actual) { "VERIFIED" } else { "MISMATCH" }
```

### GPG Signing (Commits & Releases)

All commits and tagged releases are signed with the maintainer's GPG key.

**Key details:**
- **Type:** Ed25519
- **Key ID:** `561027E0D366E271`
- **Fingerprint:** `7BAB B0A9 D457 939B 39D8 BCB2 5610 27E0 D366 E271`
- **Expires:** 2028-02-08

To verify a signed commit or release:

```bash
# Import the public key (first time only)
gpg --keyserver keyserver.ubuntu.com --recv-keys 561027E0D366E271

# Verify a signed commit
git log --show-signature -1

# Verify a release artifact signature
gpg --verify audioviz-plugin-1.0.0.jar.asc audioviz-plugin-1.0.0.jar
```

You can also verify commits on GitHub — signed commits display a "Verified" badge once the public key is added to the maintainer's GitHub account.

## Dependency Policy

- All dependencies are audited weekly via Dependabot and scheduled security scans
- No dependency with a known CVSS >= 7.0 vulnerability is shipped in releases
- SBOMs (Software Bill of Materials) in CycloneDX format are generated for every build
- License compliance is checked to ensure all dependencies are compatible with MIT
