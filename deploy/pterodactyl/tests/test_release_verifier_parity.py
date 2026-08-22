#!/usr/bin/env python3
"""Run identical adversarial archives through both release verifiers."""

from __future__ import annotations

import hashlib
import shutil
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

DEPLOY_ROOT = Path(__file__).resolve().parents[1]
PYTHON_VERIFIER = DEPLOY_ROOT / "release_archive.py"
POWERSHELL_VERIFIER = DEPLOY_ROOT / "verify-release.ps1"
MANIFEST = "mcav-vj/MANIFEST.sha256"
REQUIRED = {
    "mcav-vj/start-mcav.sh",
    "mcav-vj/VERSION",
    "mcav-vj/mcav.env.example",
    "mcav-vj/bin/linux-amd64/audioviz-vj",
    "mcav-vj/bin/linux-amd64/python/bin/python3.12",
    "mcav-vj/bin/linux-arm64/audioviz-vj",
    "mcav-vj/bin/linux-arm64/python/bin/python3.12",
    "mcav-vj/release/AudioViz.jar",
    "mcav-vj/release/plugin-config.default.yml",
    "mcav-vj/release/runtime-lock.json",
    "mcav-vj/vj_server/__init__.py",
    "mcav-vj/vj_server/auth.py",
    "mcav-vj/vj_server/cli.py",
    "mcav-vj/vj_server/config.py",
    "mcav-vj/vj_server/patterns.py",
    "mcav-vj/vj_server/vj_server.py",
    "mcav-vj/vj_server/web_gateway.py",
    "mcav-vj/patterns/lib.lua",
    "mcav-vj/patterns/bars.lua",
    "mcav-vj/admin_panel/index.html",
    "mcav-vj/admin_panel/runtime-config.js",
    "mcav-vj/preview_tool/frontend/index.html",
    "mcav-vj/preview_tool/frontend/runtime-config.js",
    MANIFEST,
}
EXECUTABLES = {
    "mcav-vj/start-mcav.sh",
    "mcav-vj/bin/linux-amd64/audioviz-vj",
    "mcav-vj/bin/linux-amd64/python/bin/python3.12",
    "mcav-vj/bin/linux-arm64/audioviz-vj",
    "mcav-vj/bin/linux-arm64/python/bin/python3.12",
}
OPTIONAL_PAYLOAD = "mcav-vj/misc/payload.txt"


def elf_payload(machine: int) -> bytes:
    header = bytearray(64)
    header[:6] = b"\x7fELF\x02\x01"
    header[18:20] = machine.to_bytes(2, "little")
    return bytes(header)


def base_payloads() -> dict[str, bytes]:
    payloads = {name: f"fixture:{name}\n".encode() for name in REQUIRED - {MANIFEST}}
    payloads["mcav-vj/bin/linux-amd64/python/bin/python3.12"] = elf_payload(62)
    payloads["mcav-vj/bin/linux-arm64/python/bin/python3.12"] = elf_payload(183)
    payloads[OPTIONAL_PAYLOAD] = b"path validation fixture\n"
    payloads[MANIFEST] = manifest_payload(payloads)
    return payloads


def manifest_payload(payloads: dict[str, bytes]) -> bytes:
    rows = []
    for name, payload in sorted(payloads.items()):
        if name == MANIFEST:
            continue
        relative = name.removeprefix("mcav-vj/")
        rows.append(f"{hashlib.sha256(payload).hexdigest()}  {relative}")
    return ("\n".join(rows) + "\n").encode()


def archive_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
    mode = 0o100755 if name in EXECUTABLES else 0o100644
    info.create_system = 3
    info.external_attr = mode << 16
    return info


def write_archive(
    path: Path,
    payloads: dict[str, bytes],
    *,
    duplicate_name: str | None = None,
) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, payload in payloads.items():
            archive.writestr(archive_info(name), payload)
        if duplicate_name is not None:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                archive.writestr(archive_info(duplicate_name), payloads[duplicate_name])


def replace_manifest_row(manifest: bytes, old_path: str, new_path: str) -> bytes:
    lines = manifest.decode().splitlines()
    replaced = [
        f"{line.split('  ', 1)[0]}  {new_path}" if line.endswith(f"  {old_path}") else line
        for line in lines
    ]
    return ("\n".join(replaced) + "\n").encode()


def find_powershell() -> str:
    for candidate in (
        shutil.which("pwsh"),
        "/mnt/c/Program Files/PowerShell/7/pwsh.exe",
        shutil.which("powershell.exe"),
    ):
        if candidate and Path(candidate).exists():
            return candidate
    raise RuntimeError("PowerShell is required for verifier parity tests")


def powershell_path(path: Path, executable: str) -> str:
    if executable.lower().endswith(".exe") and sys.platform != "win32":
        return subprocess.check_output(["wslpath", "-w", str(path.resolve())], text=True).strip()
    return str(path.resolve())


class ReleaseVerifierParityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.powershell = find_powershell()

    def setUp(self) -> None:
        # Keep fixtures on the shared workspace volume so Windows PowerShell can
        # address them without a WSL UNC provider path.
        self.temporary = tempfile.TemporaryDirectory(dir=DEPLOY_ROOT)
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_verifiers(self, archive: Path) -> dict[str, subprocess.CompletedProcess[str]]:
        return {
            "python": subprocess.run(
                [sys.executable, str(PYTHON_VERIFIER), "verify", str(archive)],
                check=False,
                capture_output=True,
                text=True,
            ),
            "powershell": subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-File",
                    powershell_path(POWERSHELL_VERIFIER, self.powershell),
                    "-Archive",
                    powershell_path(archive, self.powershell),
                ],
                check=False,
                capture_output=True,
                text=True,
            ),
        }

    def assert_valid(self, archive: Path) -> None:
        for verifier, result in self.run_verifiers(archive).items():
            with self.subTest(verifier=verifier):
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def assert_rejected(self, archive: Path, expected_error: str) -> None:
        for verifier, result in self.run_verifiers(archive).items():
            with self.subTest(verifier=verifier):
                output = result.stdout + result.stderr
                self.assertNotEqual(result.returncode, 0, output)
                self.assertIn(expected_error.casefold(), output.casefold(), output)

    def write_case(
        self,
        case: str,
        payloads: dict[str, bytes],
        *,
        duplicate_name: str | None = None,
    ) -> Path:
        archive = self.root / f"{case}.zip"
        write_archive(archive, payloads, duplicate_name=duplicate_name)
        return archive

    def test_valid_archive_with_both_native_architectures(self) -> None:
        self.assert_valid(self.write_case("valid", base_payloads()))

    def test_every_required_asset_is_enforced(self) -> None:
        for missing_name in sorted(REQUIRED):
            with self.subTest(missing_name=missing_name):
                payloads = base_payloads()
                payloads.pop(missing_name)
                if missing_name != MANIFEST:
                    payloads[MANIFEST] = manifest_payload(payloads)
                archive = self.write_case("missing-" + missing_name.replace("/", "-"), payloads)
                self.assert_rejected(archive, "required release entr")

    def test_duplicate_zip_entry_is_rejected(self) -> None:
        payloads = base_payloads()
        archive = self.write_case("duplicate-zip", payloads, duplicate_name=OPTIONAL_PAYLOAD)
        self.assert_rejected(archive, "duplicate zip")

    def test_duplicate_missing_and_extra_manifest_rows_are_rejected(self) -> None:
        base = base_payloads()
        first_row = base[MANIFEST].decode().splitlines()[0]
        cases = {
            "duplicate-manifest": base[MANIFEST] + (first_row + "\n").encode(),
            "missing-manifest": (
                "\n".join(base[MANIFEST].decode().splitlines()[1:]) + "\n"
            ).encode(),
            "extra-manifest": base[MANIFEST] + ("0" * 64 + "  ghost.txt\n").encode(),
        }
        for case, manifest in cases.items():
            with self.subTest(case=case):
                payloads = dict(base)
                payloads[MANIFEST] = manifest
                expected = (
                    "duplicate manifest" if case == "duplicate-manifest" else "coverage mismatch"
                )
                self.assert_rejected(self.write_case(case, payloads), expected)

    def test_manifest_digest_mismatch_is_rejected(self) -> None:
        payloads = base_payloads()
        rows = payloads[MANIFEST].decode().splitlines()
        _, first_path = rows[0].split("  ", 1)
        rows[0] = f"{'0' * 64}  {first_path}"
        payloads[MANIFEST] = ("\n".join(rows) + "\n").encode()
        self.assert_rejected(
            self.write_case("digest-mismatch", payloads), "manifest digest mismatch"
        )

    def test_noncanonical_zip_paths_are_rejected(self) -> None:
        invalid_names = {
            "absolute": "/mcav-vj/absolute.txt",
            "drive-absolute": "C:/mcav-vj/drive.txt",
            "backslash": "mcav-vj\\backslash.txt",
            "dot": "mcav-vj/./dot.txt",
            "dot-dot": "mcav-vj/a/../dotdot.txt",
            "double-slash": "mcav-vj//double.txt",
        }
        for case, invalid_name in invalid_names.items():
            with self.subTest(case=case):
                payloads = base_payloads()
                payload = payloads.pop(OPTIONAL_PAYLOAD)
                payloads[invalid_name] = payload
                payloads[MANIFEST] = manifest_payload(payloads)
                self.assert_rejected(
                    self.write_case(f"zip-path-{case}", payloads),
                    "noncanonical zip entry",
                )

    def test_noncanonical_manifest_paths_are_rejected(self) -> None:
        invalid_paths = {
            "absolute": "/absolute.txt",
            "drive-absolute": "C:/drive.txt",
            "backslash": "back\\slash.txt",
            "dot": "./dot.txt",
            "dot-dot": "a/../dotdot.txt",
            "double-slash": "a//double.txt",
        }
        original_path = OPTIONAL_PAYLOAD.removeprefix("mcav-vj/")
        for case, invalid_path in invalid_paths.items():
            with self.subTest(case=case):
                payloads = base_payloads()
                payloads[MANIFEST] = replace_manifest_row(
                    payloads[MANIFEST], original_path, invalid_path
                )
                self.assert_rejected(
                    self.write_case(f"manifest-path-{case}", payloads),
                    "noncanonical manifest path",
                )

    def test_wrong_native_elf_architecture_is_rejected_for_each_runtime(self) -> None:
        cases = {
            "linux-amd64": ("mcav-vj/bin/linux-amd64/python/bin/python3.12", 183),
            "linux-arm64": ("mcav-vj/bin/linux-arm64/python/bin/python3.12", 62),
        }
        for architecture, (name, wrong_machine) in cases.items():
            with self.subTest(architecture=architecture):
                payloads = base_payloads()
                payloads[name] = elf_payload(wrong_machine)
                payloads[MANIFEST] = manifest_payload(payloads)
                self.assert_rejected(
                    self.write_case(f"wrong-elf-{architecture}", payloads), "elf machine"
                )


if __name__ == "__main__":
    unittest.main()
