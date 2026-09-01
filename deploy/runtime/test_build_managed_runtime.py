from __future__ import annotations

import hashlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import build_managed_runtime as builder


class ManagedRuntimeBuildTest(unittest.TestCase):
    def test_download_stops_when_payload_exceeds_locked_size(self) -> None:
        response = io.BytesIO(b"oversized")
        response.headers = {}  # type: ignore[attr-defined]
        with tempfile.TemporaryDirectory() as temporary_value:
            destination = Path(temporary_value) / "python.tar.gz"
            with (
                patch.object(builder.urllib.request, "urlopen", return_value=response),
                self.assertRaisesRegex(ValueError, "size bound"),
            ):
                builder.download_verified(
                    builder.load_lock()["runtimes"]["linux-x86_64"]["url"],
                    "0" * 64,
                    destination,
                    expected_size=4,
                )
            self.assertFalse(destination.exists())

    def test_wheel_cache_removes_oversized_expected_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_value:
            wheel_cache = Path(temporary_value)
            cached = wheel_cache / "locked.whl"
            cached.write_bytes(b"oversized")

            builder.prune_wheel_cache(
                wheel_cache,
                {"locked.whl": (4, hashlib.sha256(b"safe").hexdigest())},
            )

            self.assertFalse(cached.exists())

    def test_native_windows_build_uses_windows_runtime(self) -> None:
        self.assertEqual(
            builder.detect_native_build_target("Windows", "AMD64"),
            "windows-x86_64",
        )

    def test_native_arm_linux_build_uses_arm_runtime(self) -> None:
        self.assertEqual(
            builder.detect_native_build_target("Linux", "aarch64"),
            "linux-aarch64",
        )

    def test_locked_runtime_urls_are_accepted(self) -> None:
        lock = builder.load_lock()
        payload = b"cached portable runtime"
        digest = hashlib.sha256(payload).hexdigest()
        with tempfile.TemporaryDirectory() as temporary_value:
            for platform, runtime in lock["runtimes"].items():
                destination = Path(temporary_value) / f"{platform}.tar.gz"
                destination.write_bytes(payload)

                builder.download_verified(runtime["url"], digest, destination)

    def test_download_rejects_non_release_url_before_io(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_value:
            destination = Path(temporary_value) / "python.tar.gz"

            with self.assertRaisesRegex(ValueError, "official pinned HTTPS release"):
                builder.download_verified(
                    "file:///tmp/untrusted-python.tar.gz",
                    "0" * 64,
                    destination,
                )

            self.assertFalse(destination.exists())

    def test_trim_removes_pip_console_scripts_with_host_shebangs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_value:
            runtime_root = Path(temporary_value) / "runtime"
            entrypoint = runtime_root / "python/python.exe"
            generated_script = runtime_root / "python/Lib/site-packages/bin/websockets"
            entrypoint.parent.mkdir(parents=True)
            entrypoint.write_bytes(b"python")
            generated_script.parent.mkdir(parents=True)
            generated_script.write_text(
                f"#!{builder.REPOSITORY_ROOT}/vj_server/.venv/bin/python\n",
                encoding="utf-8",
            )

            builder.trim_runtime(runtime_root, "windows-x86_64", "python/python.exe")

            self.assertFalse(generated_script.parent.exists())
            self.assertTrue(entrypoint.is_file())

    def test_payload_validation_rejects_local_build_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_value:
            build_root = Path(temporary_value) / "build"
            runtime_root = build_root / "runtime"
            payload = runtime_root / "python/Lib/site-packages/leak.py"
            payload.parent.mkdir(parents=True)
            payload.write_text(
                f"BUILD_INTERPRETER = {builder.REPOSITORY_ROOT!s}/.venv/bin/python\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "leaks a local build path"):
                builder.validate_no_local_build_paths(runtime_root, build_root)

    def test_payload_validation_accepts_relocatable_content(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_value:
            build_root = Path(temporary_value) / "build"
            runtime_root = build_root / "runtime"
            payload = runtime_root / "vj_server/cli.py"
            payload.parent.mkdir(parents=True)
            payload.write_text("def main():\n    return 0\n", encoding="utf-8")

            builder.validate_no_local_build_paths(runtime_root, build_root)

    def test_runtime_files_use_platform_independent_posix_order(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_value:
            runtime_root = Path(temporary_value)
            for relative in ("z/file.txt", "admin/file.txt", "LICENSE"):
                path = runtime_root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(relative, encoding="utf-8")

            observed = [
                path.relative_to(runtime_root).as_posix()
                for path in builder.runtime_files(runtime_root)
            ]

            self.assertEqual(observed, ["LICENSE", "admin/file.txt", "z/file.txt"])

    def test_python_license_is_normalized_across_portable_runtimes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_value:
            runtime_root = Path(temporary_value)
            source = runtime_root / "python/lib/python3.12/LICENSE.txt"
            source.parent.mkdir(parents=True)
            source.write_text("Python license\n", encoding="utf-8")

            builder.normalize_python_license(runtime_root, "3.12.14")

            self.assertEqual(
                (runtime_root / "python/LICENSE.txt").read_text(encoding="utf-8"),
                "Python license\n",
            )

    def test_tracked_symlink_is_rejected_instead_of_dereferenced(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_value:
            root = Path(temporary_value)
            external = root / "external.txt"
            external.write_text("outside repository\n", encoding="utf-8")
            source = root / "tracked-link.txt"
            source.symlink_to(external)

            with self.assertRaisesRegex(ValueError, "symlink"):
                builder.copy_tracked_regular_file(source, root / "runtime/copied.txt")


if __name__ == "__main__":
    unittest.main()
