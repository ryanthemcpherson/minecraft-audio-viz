#!/usr/bin/env python3
"""Executable contract tests for portable runtime wheel locking."""

from __future__ import annotations

import base64
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "runtime_lock.py"
ARCHITECTURES = {
    "linux-amd64": {
        "machine": 62,
        "platform": "manylinux_2_17_x86_64",
    },
    "linux-arm64": {
        "machine": 183,
        "platform": "manylinux_2_17_aarch64",
    },
}


def elf_payload(machine: int) -> bytes:
    header = bytearray(64)
    header[:6] = b"\x7fELF\x02\x01"
    header[18:20] = machine.to_bytes(2, "little")
    return bytes(header)


def record_hash(payload: bytes) -> str:
    digest = base64.urlsafe_b64encode(hashlib.sha256(payload).digest()).rstrip(b"=")
    return f"sha256={digest.decode()}"


def record_payload(files: dict[str, bytes], record_name: str) -> bytes:
    rows = [
        f"{name},{record_hash(payload)},{len(payload)}" for name, payload in sorted(files.items())
    ]
    rows.append(f"{record_name},,")
    return ("\n".join(rows) + "\n").encode()


def build_wheel(
    directory: Path,
    architecture: str,
    *,
    machine: int | None = None,
    python_tag: str = "cp312",
    abi_tag: str = "cp312",
    platform: str | None = None,
    include_native: bool = True,
) -> Path:
    platform = platform or ARCHITECTURES[architecture]["platform"]
    filename = f"tinydep-1.0.0-{python_tag}-{abi_tag}-{platform}.whl"
    wheel_path = directory / filename
    native_machine = machine if machine is not None else ARCHITECTURES[architecture]["machine"]
    record_name = "tinydep-1.0.0.dist-info/RECORD"
    files = {
        "tinydep/__init__.py": b'VERSION = "1.0.0"\n',
        "tinydep-1.0.0.dist-info/METADATA": (
            b"Metadata-Version: 2.1\nName: tinydep\nVersion: 1.0.0\n"
        ),
        "tinydep-1.0.0.dist-info/WHEEL": (
            b"Wheel-Version: 1.0\nGenerator: mcav-test\n"
            + (
                f"Root-Is-Purelib: {'false' if include_native else 'true'}\n"
                f"Tag: {python_tag}-{abi_tag}-{platform}\n"
            ).encode()
        ),
    }
    if include_native:
        files["tinydep/native.so"] = elf_payload(native_machine)
    files[record_name] = record_payload(files, record_name)
    with zipfile.ZipFile(wheel_path, "w", compression=zipfile.ZIP_STORED) as wheel:
        for name, payload in sorted(files.items()):
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            wheel.writestr(info, payload)
    return wheel_path


def wheel_record(path: Path) -> dict[str, str]:
    return {
        "filename": path.name,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }


def write_lock(path: Path, wheels: dict[str, Path], *, version: str = "1.0.0") -> None:
    lock = {
        "schema_version": 1,
        "python": "3.12.14",
        "release": "test",
        "runtimes": {
            architecture: {
                "url": f"https://example.invalid/{architecture}.tar.gz",
                "sha256": "1" * 64,
                "pip_platforms": [details["platform"]],
            }
            for architecture, details in ARCHITECTURES.items()
        },
        "dependencies": [
            {
                "name": "tinydep",
                "version": version,
                "wheels": {
                    architecture: wheel_record(wheel_path)
                    for architecture, wheel_path in wheels.items()
                },
            }
        ],
    }
    path.write_text(json.dumps(lock), encoding="utf-8")


def run_lock(*arguments: object) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), *(str(argument) for argument in arguments)],
        check=False,
        capture_output=True,
        text=True,
    )


def extract_install(wheel_path: Path, destination: Path) -> Path:
    site_packages = destination / "python/lib/python3.12/site-packages"
    site_packages.mkdir(parents=True)
    with zipfile.ZipFile(wheel_path) as wheel:
        wheel.extractall(site_packages)
    return site_packages


def replace_recorded_file(site_packages: Path, relative_path: str, payload: bytes) -> None:
    target = site_packages / relative_path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(payload)
    record = site_packages / "tinydep-1.0.0.dist-info/RECORD"
    rows = record.read_text(encoding="utf-8").splitlines()
    replacement = f"{relative_path},{record_hash(payload)},{len(payload)}"
    for index, row in enumerate(rows):
        if row.split(",", 1)[0] == relative_path:
            rows[index] = replacement
            break
    else:
        rows.insert(-1, replacement)
    record.write_text("\n".join(rows) + "\n", encoding="utf-8")


class RuntimeLockTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.wheelhouses: dict[str, Path] = {}
        self.wheels: dict[str, Path] = {}
        for architecture in ARCHITECTURES:
            wheelhouse = self.root / architecture
            wheelhouse.mkdir()
            self.wheelhouses[architecture] = wheelhouse
            self.wheels[architecture] = build_wheel(wheelhouse, architecture)
        self.lock = self.root / "runtime-lock.json"
        write_lock(self.lock, self.wheels)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def assert_rejected(
        self,
        architecture: str,
        expected_error: str,
        *,
        command: str = "verify-wheelhouse",
        target: Path | None = None,
    ) -> None:
        result = run_lock(
            command,
            self.lock,
            architecture,
            target or self.wheelhouses[architecture],
        )
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(expected_error, result.stderr)

    def replace_wheel(self, architecture: str, **wheel_options: object) -> Path:
        self.wheels[architecture].unlink()
        replacement = build_wheel(self.wheelhouses[architecture], architecture, **wheel_options)
        self.wheels[architecture] = replacement
        write_lock(self.lock, self.wheels)
        return replacement

    def test_valid_locked_wheelhouse_and_install_pass_for_each_architecture(self) -> None:
        for architecture in ARCHITECTURES:
            with self.subTest(architecture=architecture):
                wheelhouse_result = run_lock(
                    "verify-wheelhouse",
                    self.lock,
                    architecture,
                    self.wheelhouses[architecture],
                )
                self.assertEqual(wheelhouse_result.returncode, 0, wheelhouse_result.stderr)
                self.assertEqual(
                    wheelhouse_result.stdout.strip(),
                    str(self.wheels[architecture]),
                )

                site_packages = extract_install(
                    self.wheels[architecture], self.root / f"installed-{architecture}"
                )
                install_result = run_lock(
                    "verify-install",
                    self.lock,
                    architecture,
                    site_packages,
                )
                self.assertEqual(install_result.returncode, 0, install_result.stderr)
                self.assertIn("tinydep==1.0.0", install_result.stdout)

    def test_missing_locked_wheel_is_rejected(self) -> None:
        self.wheels["linux-amd64"].unlink()
        self.assert_rejected("linux-amd64", "missing locked wheels")

    def test_extra_wheel_is_rejected(self) -> None:
        (self.wheelhouses["linux-amd64"] / "unexpected-1.0.0-py3-none-any.whl").write_bytes(
            b"unexpected"
        )
        self.assert_rejected("linux-amd64", "extra wheels")

    def test_extra_installed_dependency_is_rejected(self) -> None:
        site_packages = extract_install(self.wheels["linux-amd64"], self.root / "extra-install")
        metadata = site_packages / "unexpected-9.0.dist-info/METADATA"
        metadata.parent.mkdir()
        metadata.write_text("Name: unexpected\nVersion: 9.0\n", encoding="utf-8")
        self.assert_rejected(
            "linux-amd64",
            "extra installed dependencies: unexpected",
            command="verify-install",
            target=site_packages,
        )

    def test_wrong_wheel_hash_is_rejected(self) -> None:
        lock = json.loads(self.lock.read_text(encoding="utf-8"))
        lock["dependencies"][0]["wheels"]["linux-amd64"]["sha256"] = "0" * 64
        self.lock.write_text(json.dumps(lock), encoding="utf-8")
        self.assert_rejected("linux-amd64", "SHA-256 mismatch")

    def test_wheel_metadata_version_must_match_lock(self) -> None:
        write_lock(self.lock, self.wheels, version="2.0.0")
        self.assert_rejected("linux-amd64", "metadata version 1.0.0 does not match 2.0.0")

    def test_foreign_platform_wheel_is_rejected(self) -> None:
        lock = json.loads(self.lock.read_text(encoding="utf-8"))
        lock["dependencies"][0]["wheels"]["linux-arm64"] = wheel_record(self.wheels["linux-amd64"])
        self.lock.write_text(json.dumps(lock), encoding="utf-8")
        foreign = self.wheelhouses["linux-arm64"] / self.wheels["linux-amd64"].name
        foreign.write_bytes(self.wheels["linux-amd64"].read_bytes())
        self.wheels["linux-arm64"].unlink()
        self.assert_rejected("linux-arm64", "is not compatible with linux-arm64")

    def test_wheel_tags_require_compatible_python_abi_and_exact_platform(self) -> None:
        invalid_tags = {
            "wrong-python": {
                "python_tag": "cp311",
                "abi_tag": "cp311",
            },
            "wrong-abi": {
                "python_tag": "cp312",
                "abi_tag": "cp311",
            },
            "near-miss-platform": {
                "platform": "manylinux_2_17_x86_64evil",
            },
            "unlocked-platform": {
                "platform": "manylinux_2_27_x86_64",
            },
        }
        for case, wheel_options in invalid_tags.items():
            with self.subTest(case=case):
                with tempfile.TemporaryDirectory() as temporary:
                    wheelhouse = Path(temporary)
                    wheel = build_wheel(
                        wheelhouse,
                        "linux-amd64",
                        **wheel_options,
                    )
                    wheels = dict(self.wheels)
                    wheels["linux-amd64"] = wheel
                    write_lock(self.lock, wheels)
                    self.assert_rejected(
                        "linux-amd64",
                        "is not compatible with linux-amd64",
                        target=wheelhouse,
                    )

    def test_py3_none_any_and_older_cpython_abi3_tags_are_accepted(self) -> None:
        valid_tags = {
            "pure-python": {
                "python_tag": "py3",
                "abi_tag": "none",
                "platform": "any",
                "include_native": False,
            },
            "stable-abi": {
                "python_tag": "cp39",
                "abi_tag": "abi3",
            },
        }
        for case, wheel_options in valid_tags.items():
            with self.subTest(case=case):
                with tempfile.TemporaryDirectory() as temporary:
                    wheelhouse = Path(temporary)
                    wheel = build_wheel(
                        wheelhouse,
                        "linux-amd64",
                        **wheel_options,
                    )
                    wheels = dict(self.wheels)
                    wheels["linux-amd64"] = wheel
                    write_lock(self.lock, wheels)
                    result = run_lock(
                        "verify-wheelhouse",
                        self.lock,
                        "linux-amd64",
                        wheelhouse,
                    )
                    self.assertEqual(result.returncode, 0, result.stderr)

    def test_final_install_replaces_base_packages_and_is_reverified(self) -> None:
        staged = extract_install(self.wheels["linux-amd64"], self.root / "staged")
        final = self.root / "runtime/python/lib/python3.12/site-packages"
        extra_metadata = final / "pip-99.0.dist-info/METADATA"
        extra_metadata.parent.mkdir(parents=True)
        extra_metadata.write_text("Name: pip\nVersion: 99.0\n", encoding="utf-8")

        result = run_lock(
            "install-staged",
            self.lock,
            "linux-amd64",
            staged,
            final,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(extra_metadata.exists())
        self.assertTrue((final / "tinydep-1.0.0.dist-info/METADATA").is_file())

    def test_final_install_missing_extra_and_wrong_version_are_rejected(self) -> None:
        cases = ("missing", "extra", "wrong-version")
        for case in cases:
            with self.subTest(case=case):
                site_packages = extract_install(
                    self.wheels["linux-amd64"], self.root / f"final-{case}"
                )
                metadata = site_packages / "tinydep-1.0.0.dist-info/METADATA"
                if case == "missing":
                    shutil.rmtree(metadata.parent)
                    expected = "missing installed dependencies"
                elif case == "extra":
                    extra = site_packages / "unexpected-9.0.dist-info/METADATA"
                    extra.parent.mkdir()
                    extra.write_text("Name: unexpected\nVersion: 9.0\n", encoding="utf-8")
                    expected = "extra installed dependencies"
                else:
                    metadata.write_text("Name: tinydep\nVersion: 2.0.0\n", encoding="utf-8")
                    expected = "version 2.0.0 does not match 1.0.0"
                self.assert_rejected(
                    "linux-amd64",
                    expected,
                    command="verify-install",
                    target=site_packages,
                )

    def test_installed_native_elf_must_match_architecture(self) -> None:
        wheel = build_wheel(
            self.wheelhouses["linux-arm64"],
            "linux-arm64",
            machine=ARCHITECTURES["linux-amd64"]["machine"],
        )
        write_lock(
            self.lock,
            {
                "linux-amd64": self.wheels["linux-amd64"],
                "linux-arm64": wheel,
            },
        )
        site_packages = extract_install(wheel, self.root / "wrong-elf-install")
        self.assert_rejected(
            "linux-arm64",
            "native ELF machine 62; expected 183",
            command="verify-install",
            target=site_packages,
        )

    def test_record_closure_rejects_missing_tampered_unowned_and_invalid_rows(self) -> None:
        for architecture in ARCHITECTURES:
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
            for case in cases:
                with self.subTest(architecture=architecture, case=case):
                    site_packages = extract_install(
                        self.wheels[architecture], self.root / f"record-{architecture}-{case}"
                    )
                    module = site_packages / "tinydep/__init__.py"
                    record = site_packages / "tinydep-1.0.0.dist-info/RECORD"
                    rows = record.read_text(encoding="utf-8").splitlines()
                    module_index = next(
                        index
                        for index, row in enumerate(rows)
                        if row.startswith("tinydep/__init__.py,")
                    )
                    expected = ""
                    if case == "removed":
                        module.unlink()
                        expected = "RECORD file is missing"
                    elif case == "tampered":
                        original = module.read_bytes()
                        module.write_bytes(bytes((original[0] ^ 1,)) + original[1:])
                        expected = "RECORD SHA-256 mismatch"
                    elif case == "unowned":
                        (site_packages / "unowned.py").write_text("UNOWNED = True\n")
                        expected = "unowned site-packages file"
                    elif case == "malformed":
                        rows[module_index] = "tinydep/__init__.py,sha256=bad"
                        record.write_text("\n".join(rows) + "\n", encoding="utf-8")
                        expected = "malformed RECORD row"
                    elif case == "traversal":
                        rows.insert(0, "../../../../escape.py,,")
                        record.write_text("\n".join(rows) + "\n", encoding="utf-8")
                        expected = "noncanonical RECORD path"
                    elif case == "wrong-hash":
                        fields = rows[module_index].split(",")
                        fields[1] = "sha256=" + "A" * 43
                        rows[module_index] = ",".join(fields)
                        record.write_text("\n".join(rows) + "\n", encoding="utf-8")
                        expected = "RECORD SHA-256 mismatch"
                    elif case == "wrong-size":
                        fields = rows[module_index].split(",")
                        fields[2] = str(int(fields[2]) + 1)
                        rows[module_index] = ",".join(fields)
                        record.write_text("\n".join(rows) + "\n", encoding="utf-8")
                        expected = "RECORD size mismatch"
                    else:
                        rows.insert(module_index, rows[module_index])
                        record.write_text("\n".join(rows) + "\n", encoding="utf-8")
                        expected = "ambiguous RECORD ownership"
                    self.assert_rejected(
                        architecture,
                        expected,
                        command="verify-install",
                        target=site_packages,
                    )

    def test_all_native_files_require_64_bit_elf_for_the_exact_architecture(self) -> None:
        for architecture, details in ARCHITECTURES.items():
            other_machine = 183 if details["machine"] == 62 else 62
            cases = {
                "non-elf-so": ("tinydep/native.so", b"not an ELF library\n", "not ELF"),
                "non-elf-pyd": ("tinydep/native.pyd", b"MZ-not-ELF\n", "not ELF"),
                "elf-without-extension": (
                    "tinydep/native_blob",
                    elf_payload(other_machine),
                    f"ELF machine {other_machine}; expected {details['machine']}",
                ),
                "elf32": (
                    "tinydep/native.so",
                    bytes(bytearray(elf_payload(details["machine"]))[:4])
                    + b"\x01"
                    + elf_payload(details["machine"])[5:],
                    "not 64-bit ELF",
                ),
            }
            for case, (relative_path, payload, expected) in cases.items():
                with self.subTest(architecture=architecture, case=case):
                    site_packages = extract_install(
                        self.wheels[architecture], self.root / f"native-{architecture}-{case}"
                    )
                    replace_recorded_file(site_packages, relative_path, payload)
                    self.assert_rejected(
                        architecture,
                        expected,
                        command="verify-install",
                        target=site_packages,
                    )

    def test_builder_canonicalizes_pip_target_records_and_prunes_only_removed_tests(self) -> None:
        site_packages = extract_install(
            self.wheels["linux-amd64"], self.root / "normalized-install"
        )
        record = site_packages / "tinydep-1.0.0.dist-info/RECORD"
        rows = record.read_text(encoding="utf-8").splitlines()
        module_index = next(
            index for index, row in enumerate(rows) if row.startswith("tinydep/__init__.py,")
        )
        rows[module_index] = "../../" + rows[module_index]
        record.write_text("\n".join(rows) + "\n", encoding="utf-8")

        normalize_result = run_lock(
            "normalize-target-records", self.lock, "linux-amd64", site_packages
        )
        self.assertEqual(normalize_result.returncode, 0, normalize_result.stderr)
        self.assertNotIn("..", record.read_text(encoding="utf-8"))

        removed_test = "tinydep/tests/test_removed.py"
        replace_recorded_file(site_packages, removed_test, b"def test_removed(): pass\n")
        shutil.rmtree(site_packages / "tinydep/tests")
        prune_result = run_lock("prune-records", self.lock, "linux-amd64", site_packages)
        self.assertEqual(prune_result.returncode, 0, prune_result.stderr)
        self.assertNotIn(removed_test, record.read_text(encoding="utf-8"))
        verify_result = run_lock("verify-install", self.lock, "linux-amd64", site_packages)
        self.assertEqual(verify_result.returncode, 0, verify_result.stderr)


if __name__ == "__main__":
    unittest.main()
