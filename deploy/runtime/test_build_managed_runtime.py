from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

import build_managed_runtime as builder


class ManagedRuntimeBuildTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
