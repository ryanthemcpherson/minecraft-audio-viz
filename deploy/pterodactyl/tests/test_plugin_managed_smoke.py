#!/usr/bin/env python3
"""Tests for the plugin-managed real-Paper release rehearsal."""

from __future__ import annotations

import importlib.util
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock

MODULE_PATH = Path(__file__).with_name("plugin_managed_smoke.py")
SPEC = importlib.util.spec_from_file_location("plugin_managed_smoke", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {MODULE_PATH}")
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)


class PluginManagedSmokeTest(unittest.TestCase):
    def test_extract_release_preserves_executables_and_two_roots(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            archive = root / "release.zip"
            with zipfile.ZipFile(archive, "w") as bundle:
                launcher = zipfile.ZipInfo("mcav-vj/bin/linux-amd64/audioviz-vj")
                launcher.external_attr = 0o100755 << 16
                bundle.writestr(launcher, b"#!/bin/sh\n")
                bundle.writestr("mcav-vj/VERSION", "test\n")
                bundle.writestr("plugins/AudioViz.jar", b"jar")

            destination = root / "server"
            smoke.extract_release(archive, destination)

            self.assertEqual((destination / "plugins" / "AudioViz.jar").read_bytes(), b"jar")
            self.assertTrue(
                (destination / "mcav-vj" / "bin" / "linux-amd64" / "audioviz-vj").stat().st_mode
                & 0o100
            )

    def test_paper_command_is_the_unchanged_java_invocation(self) -> None:
        self.assertEqual(
            smoke.paper_command("/opt/java/bin/java"),
            [
                "/opt/java/bin/java",
                "-Xms1G",
                "-Xmx2G",
                "-jar",
                "paper.jar",
                "--nogui",
            ],
        )

    def test_load_required_environment_requires_public_host_and_ports(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            env_path = Path(temporary_directory) / "mcav.env"
            env_path.write_text(
                "MCAV_PUBLIC_HOST=203.0.113.10\nHTTP_PORT=18080\nVJ_SERVER_PORT=25818\n",
                encoding="utf-8",
            )

            environment = smoke.load_required_environment(env_path)

            self.assertEqual(environment.public_host, "203.0.113.10")
            self.assertEqual(environment.http_port, 18080)
            self.assertEqual(environment.dj_port, 25818)

    def test_wait_for_readiness_requires_every_listener(self) -> None:
        listener_states = {
            18080: iter([True, True]),
            25818: iter([False, True]),
        }

        result = smoke.wait_for_listeners(
            "127.0.0.1",
            (18080, 25818),
            timeout=0.1,
            interval=0.0,
            probe=lambda _host, port: next(listener_states[port]),
        )

        self.assertTrue(result)

    def test_stop_process_is_bounded_and_forces_a_hung_process(self) -> None:
        process = Mock()
        process.poll.return_value = None
        process.stdin = Mock()
        process.wait.side_effect = [subprocess.TimeoutExpired("paper", 0.1), 0]

        smoke.stop_process(process, timeout=0.1)

        process.stdin.write.assert_called_once_with("stop\n")
        process.kill.assert_called_once_with()
        self.assertEqual(process.wait.call_count, 2)

    def test_wait_for_cleanup_requires_process_and_listeners_gone(self) -> None:
        process_states = iter([True, False])
        listener_states = iter([True, True, False, False])

        cleaned = smoke.wait_for_cleanup(
            sidecar_pid=1234,
            host="127.0.0.1",
            ports=(18080, 25818),
            timeout=0.1,
            interval=0.0,
            process_exists=lambda _pid: next(process_states),
            listener_open=lambda _host, _port: next(listener_states),
        )

        self.assertTrue(cleaned)


if __name__ == "__main__":
    unittest.main()
