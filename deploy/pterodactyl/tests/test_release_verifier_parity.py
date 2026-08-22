#!/usr/bin/env python3
"""Run identical adversarial archives through both release verifiers."""

from __future__ import annotations

import base64
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from typing import Any, Callable

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
CASE_VARIANT_LAUNCHER = "mcav-vj/Start-Mcav.sh"
SITE_PACKAGES = "mcav-vj/bin/{architecture}/python/lib/python3.12/site-packages"
LOCKED_PACKAGE = "tinydep"
LOCKED_VERSION = "1.0.0"
LOCKED_METADATA = "tinydep-1.0.0.dist-info/METADATA"
LOCKED_RECORD = "tinydep-1.0.0.dist-info/RECORD"
LOCKED_WHEEL = "tinydep-1.0.0.dist-info/WHEEL"
LOCKED_MODULE = "tinydep/__init__.py"
LOCKED_NATIVE = "tinydep/native.so"
ARCHITECTURE_MACHINES = {"linux-amd64": 62, "linux-arm64": 183}


def elf_payload(machine: int) -> bytes:
    header = bytearray(64)
    header[:6] = b"\x7fELF\x02\x01"
    header[18:20] = machine.to_bytes(2, "little")
    return bytes(header)


def record_hash(payload: bytes) -> str:
    digest = base64.urlsafe_b64encode(hashlib.sha256(payload).digest()).rstrip(b"=")
    return f"sha256={digest.decode()}"


def installed_distribution_payloads(architecture: str) -> dict[str, bytes]:
    prefix = SITE_PACKAGES.format(architecture=architecture)
    relative_payloads = {
        LOCKED_METADATA: installed_metadata_payload(),
        LOCKED_WHEEL: b"Wheel-Version: 1.0\nGenerator: mcav-test\n",
        LOCKED_MODULE: b'VERSION = "1.0.0"\n',
        LOCKED_NATIVE: elf_payload(ARCHITECTURE_MACHINES[architecture]),
    }
    rows = [
        f"{name},{record_hash(payload)},{len(payload)}"
        for name, payload in sorted(relative_payloads.items())
    ]
    rows.append(f"{LOCKED_RECORD},,")
    relative_payloads[LOCKED_RECORD] = ("\n".join(rows) + "\n").encode()
    return {f"{prefix}/{name}": payload for name, payload in relative_payloads.items()}


def base_payloads() -> dict[str, bytes]:
    payloads = {name: f"fixture:{name}\n".encode() for name in REQUIRED - {MANIFEST}}
    payloads["mcav-vj/bin/linux-amd64/python/bin/python3.12"] = elf_payload(62)
    payloads["mcav-vj/bin/linux-arm64/python/bin/python3.12"] = elf_payload(183)
    payloads["mcav-vj/release/runtime-lock.json"] = runtime_lock_payload()
    for architecture in ("linux-amd64", "linux-arm64"):
        payloads.update(installed_distribution_payloads(architecture))
    payloads[OPTIONAL_PAYLOAD] = b"path validation fixture\n"
    payloads[MANIFEST] = manifest_payload(payloads)
    return payloads


def runtime_lock_payload() -> bytes:
    architectures = {
        "linux-amd64": "manylinux_2_17_x86_64",
        "linux-arm64": "manylinux_2_17_aarch64",
    }
    lock = {
        "schema_version": 1,
        "python": "3.12.14",
        "release": "test",
        "runtimes": {
            architecture: {
                "url": f"https://example.invalid/{architecture}.tar.gz",
                "sha256": "1" * 64,
                "pip_platforms": [platform],
            }
            for architecture, platform in architectures.items()
        },
        "dependencies": [
            {
                "name": LOCKED_PACKAGE,
                "version": LOCKED_VERSION,
                "wheels": {
                    architecture: {
                        "filename": (f"tinydep-1.0.0-cp312-cp312-{platform}.whl"),
                        "sha256": "2" * 64,
                    }
                    for architecture, platform in architectures.items()
                },
            }
        ],
    }
    return (json.dumps(lock, sort_keys=True) + "\n").encode()


def installed_metadata_payload(*, version: str = LOCKED_VERSION) -> bytes:
    return f"Metadata-Version: 2.1\nName: {LOCKED_PACKAGE}\nVersion: {version}\n".encode()


def replace_recorded_payload(
    payloads: dict[str, bytes], architecture: str, relative_path: str, payload: bytes
) -> None:
    prefix = SITE_PACKAGES.format(architecture=architecture)
    payloads[f"{prefix}/{relative_path}"] = payload
    record_name = f"{prefix}/{LOCKED_RECORD}"
    rows = payloads[record_name].decode().splitlines()
    replacement = f"{relative_path},{record_hash(payload)},{len(payload)}"
    for index, row in enumerate(rows):
        if row.split(",", 1)[0] == relative_path:
            rows[index] = replacement
            break
    else:
        rows.insert(-1, replacement)
    payloads[record_name] = ("\n".join(rows) + "\n").encode()


def mutate_runtime_lock(
    payloads: dict[str, bytes], mutation: Callable[[dict[str, Any]], None]
) -> None:
    lock = json.loads(payloads["mcav-vj/release/runtime-lock.json"])
    mutation(lock)
    payloads["mcav-vj/release/runtime-lock.json"] = (
        json.dumps(lock, sort_keys=True) + "\n"
    ).encode()


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
    mode = 0o100755 if name in EXECUTABLES | {CASE_VARIANT_LAUNCHER} else 0o100644
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

    def test_case_fold_path_collision_is_rejected(self) -> None:
        payloads = base_payloads()
        payloads[CASE_VARIANT_LAUNCHER] = b"#!/usr/bin/env bash\nexit 99\n"
        payloads[MANIFEST] = manifest_payload(payloads)
        self.assert_rejected(
            self.write_case("case-fold-launcher-collision", payloads),
            "case-fold path collision",
        )

    def test_manifest_lookup_is_case_sensitive(self) -> None:
        payloads = base_payloads()
        payloads["mcav-vj/manifest.sha256"] = payloads.pop(MANIFEST)
        self.assert_rejected(
            self.write_case("case-variant-manifest", payloads),
            "required release entr",
        )

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

    def test_runtime_lock_matches_final_site_packages_for_each_architecture(self) -> None:
        for architecture in ("linux-amd64", "linux-arm64"):
            metadata_name = f"{SITE_PACKAGES.format(architecture=architecture)}/{LOCKED_METADATA}"
            cases = ("missing", "extra", "wrong-version")
            for case in cases:
                with self.subTest(architecture=architecture, case=case):
                    payloads = base_payloads()
                    if case == "missing":
                        payloads.pop(metadata_name)
                        expected = "missing METADATA"
                    elif case == "extra":
                        extra_name = (
                            f"{SITE_PACKAGES.format(architecture=architecture)}/"
                            "unexpected-9.0.dist-info/METADATA"
                        )
                        payloads[extra_name] = b"Name: unexpected\nVersion: 9.0\n"
                        expected = "extra installed dependencies"
                    else:
                        payloads[metadata_name] = installed_metadata_payload(version="2.0.0")
                        expected = "version 2.0.0 does not match 1.0.0"
                    payloads[MANIFEST] = manifest_payload(payloads)
                    self.assert_rejected(
                        self.write_case(f"runtime-closure-{architecture}-{case}", payloads),
                        expected,
                    )

    def test_record_closure_rejections_are_identical_for_both_architectures(self) -> None:
        cases = (
            "removed",
            "tampered",
            "unowned",
            "malformed",
            "traversal",
            "wrong-hash",
            "wrong-size",
            "duplicate",
        )
        for architecture in ARCHITECTURE_MACHINES:
            prefix = SITE_PACKAGES.format(architecture=architecture)
            module_name = f"{prefix}/{LOCKED_MODULE}"
            record_name = f"{prefix}/{LOCKED_RECORD}"
            for case in cases:
                with self.subTest(architecture=architecture, case=case):
                    payloads = base_payloads()
                    rows = payloads[record_name].decode().splitlines()
                    module_index = next(
                        index
                        for index, row in enumerate(rows)
                        if row.startswith(f"{LOCKED_MODULE},")
                    )
                    expected = ""
                    if case == "removed":
                        payloads.pop(module_name)
                        expected = "RECORD file is missing"
                    elif case == "tampered":
                        original = payloads[module_name]
                        payloads[module_name] = bytes((original[0] ^ 1,)) + original[1:]
                        expected = "RECORD SHA-256 mismatch"
                    elif case == "unowned":
                        payloads[f"{prefix}/unowned.py"] = b"UNOWNED = True\n"
                        expected = "unowned site-packages file"
                    elif case == "malformed":
                        rows[module_index] = f"{LOCKED_MODULE},sha256=bad"
                        payloads[record_name] = ("\n".join(rows) + "\n").encode()
                        expected = "malformed RECORD row"
                    elif case == "traversal":
                        rows.insert(0, "../../../../escape.py,,")
                        payloads[record_name] = ("\n".join(rows) + "\n").encode()
                        expected = "noncanonical RECORD path"
                    elif case == "wrong-hash":
                        fields = rows[module_index].split(",")
                        fields[1] = "sha256=" + "A" * 43
                        rows[module_index] = ",".join(fields)
                        payloads[record_name] = ("\n".join(rows) + "\n").encode()
                        expected = "RECORD SHA-256 mismatch"
                    elif case == "wrong-size":
                        fields = rows[module_index].split(",")
                        fields[2] = str(int(fields[2]) + 1)
                        rows[module_index] = ",".join(fields)
                        payloads[record_name] = ("\n".join(rows) + "\n").encode()
                        expected = "RECORD size mismatch"
                    else:
                        rows.insert(module_index, rows[module_index])
                        payloads[record_name] = ("\n".join(rows) + "\n").encode()
                        expected = "ambiguous RECORD ownership"
                    payloads[MANIFEST] = manifest_payload(payloads)
                    self.assert_rejected(
                        self.write_case(f"record-{architecture}-{case}", payloads), expected
                    )

    def test_every_native_site_package_file_is_elf64_for_the_exact_architecture(self) -> None:
        for architecture, machine in ARCHITECTURE_MACHINES.items():
            other_machine = 183 if machine == 62 else 62
            elf32 = bytearray(elf_payload(machine))
            elf32[4] = 1
            cases = {
                "non-elf-so": (LOCKED_NATIVE, b"not an ELF library\n", "not ELF"),
                "non-elf-pyd": ("tinydep/native.pyd", b"MZ-not-ELF\n", "not ELF"),
                "elf-without-extension": (
                    "tinydep/native_blob",
                    elf_payload(other_machine),
                    f"ELF machine {other_machine}; expected {machine}",
                ),
                "elf32": (LOCKED_NATIVE, bytes(elf32), "not 64-bit ELF"),
            }
            for case, (relative_path, native_payload, expected) in cases.items():
                with self.subTest(architecture=architecture, case=case):
                    payloads = base_payloads()
                    replace_recorded_payload(payloads, architecture, relative_path, native_payload)
                    payloads[MANIFEST] = manifest_payload(payloads)
                    self.assert_rejected(
                        self.write_case(f"native-{architecture}-{case}", payloads), expected
                    )

    def test_malformed_runtime_lock_and_wheel_tags_are_rejected_by_both_verifiers(self) -> None:
        def set_wrong_python(lock: dict[str, Any]) -> None:
            lock["python"] = "3.11.9"

        def set_wrong_platform_list(lock: dict[str, Any]) -> None:
            lock["runtimes"]["linux-amd64"]["pip_platforms"] = ["manylinux_2_17_aarch64"]

        def set_non_list_platforms(lock: dict[str, Any]) -> None:
            lock["runtimes"]["linux-amd64"]["pip_platforms"] = "manylinux_2_17_x86_64"

        def set_filename_tag(tag: str) -> Callable[[dict[str, Any]], None]:
            def mutate(lock: dict[str, Any]) -> None:
                lock["dependencies"][0]["wheels"]["linux-amd64"]["filename"] = (
                    f"tinydep-1.0.0-{tag}.whl"
                )

            return mutate

        cases = {
            "wrong-python": set_wrong_python,
            "wrong-platform-list": set_wrong_platform_list,
            "non-list-platforms": set_non_list_platforms,
            "wrong-python-tag": set_filename_tag("cp311-cp311-manylinux_2_17_x86_64"),
            "wrong-abi-tag": set_filename_tag("cp312-cp311-manylinux_2_17_x86_64"),
            "near-miss-platform-tag": set_filename_tag("cp312-cp312-manylinux_2_17_x86_64evil"),
        }
        for case, mutation in cases.items():
            with self.subTest(case=case):
                payloads = base_payloads()
                mutate_runtime_lock(payloads, mutation)
                payloads[MANIFEST] = manifest_payload(payloads)
                self.assert_rejected(self.write_case(f"lock-{case}", payloads), "runtime lock")

    def test_forbidden_names_are_case_insensitive_and_cover_test_spec_files(self) -> None:
        invalid_names = (
            "mcav-vj/TeStS/fixture.txt",
            "mcav-vj/misc/fixture.TEST.js",
            "mcav-vj/misc/fixture.Spec.JSON",
        )
        for invalid_name in invalid_names:
            with self.subTest(invalid_name=invalid_name):
                payloads = base_payloads()
                payloads[invalid_name] = b"forbidden fixture\n"
                payloads[MANIFEST] = manifest_payload(payloads)
                self.assert_rejected(
                    self.write_case("forbidden-" + invalid_name.replace("/", "-"), payloads),
                    "forbidden",
                )


if __name__ == "__main__":
    unittest.main()
