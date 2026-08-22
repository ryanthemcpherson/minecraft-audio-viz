from __future__ import annotations

import asyncio
import json
import shlex
import struct
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from packaged_smoke import (
    PlaintextConnectionRecorder,
    WebSocketApplicationRecorder,
    generate_certificate,
    parse_rust_smoke_output,
    redact_bytes,
    redact_command,
    redact_text,
    service_command,
    validate_recorded_tls_connections,
    verify_and_extract,
)


def masked_text_frame(message: dict[str, object]) -> bytes:
    payload = json.dumps(message, separators=(",", ":")).encode()
    mask = b"\x11\x22\x33\x44"
    if len(payload) < 126:
        header = bytes((0x81, 0x80 | len(payload)))
    else:
        header = bytes((0x81, 0x80 | 126)) + struct.pack("!H", len(payload))
    masked = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
    return header + mask + masked


class WebSocketApplicationRecorderTests(unittest.TestCase):
    def test_counts_fragmented_upgrade_auth_and_audio_application_bytes(self) -> None:
        recorder = WebSocketApplicationRecorder()
        traffic = b"".join(
            [
                b"GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n",
                b"Connection: Upgrade\r\nUpgrade: websocket\r\n\r\n",
                masked_text_frame(
                    {
                        "type": "dj_auth",
                        "dj_id": "smoke-dj",
                        "dj_key": "never-retained",
                    }
                ),
                masked_text_frame(
                    {
                        "type": "dj_audio_frame",
                        "bands": [0.91, 0.72, 0.53, 0.34, 0.15],
                        "padding": "x" * 160,
                    }
                ),
            ]
        )
        offsets = (1, 9, 41, 73, 127, len(traffic))
        previous = 0
        for offset in offsets:
            recorder.feed(traffic[previous:offset])
            previous = offset

        evidence = recorder.evidence()
        self.assertEqual(evidence["post_tls_application_bytes"], len(traffic))
        self.assertEqual(evidence["websocket_upgrade_requests"], 1)
        self.assertEqual(evidence["auth_messages"], 1)
        self.assertEqual(evidence["audio_messages"], 1)
        self.assertEqual(evidence["message_types"], ["dj_auth", "dj_audio_frame"])
        self.assertNotIn("never-retained", json.dumps(evidence))

    def test_does_not_treat_unmasked_server_style_frame_as_client_message(self) -> None:
        recorder = WebSocketApplicationRecorder()
        recorder.feed(
            b"GET / HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
            b'\x81\x12{"type":"dj_auth"}'
        )

        evidence = recorder.evidence()
        self.assertEqual(evidence["websocket_upgrade_requests"], 1)
        self.assertEqual(evidence["auth_messages"], 0)
        self.assertEqual(evidence["parse_errors"], 1)


class RustSmokeOutputTests(unittest.TestCase):
    def test_accepts_one_structured_production_path_result(self) -> None:
        output = {
            "schema_version": 1,
            "mode": "match",
            "status": "passed",
            "process_id": 42,
            "executable": "C:\\release\\packaged_pin_smoke.exe",
            "production_path": "DjClient::connect + DjClient::try_send",
            "connected": True,
            "authenticated": True,
            "audio_frame_queued": True,
        }

        self.assertEqual(
            parse_rust_smoke_output(json.dumps(output) + "\n", expected_mode="match"),
            output,
        )

    def test_rejects_ambiguous_or_wrong_mode_output(self) -> None:
        line = json.dumps(
            {
                "schema_version": 1,
                "mode": "mismatch",
                "status": "passed",
                "process_id": 42,
                "executable": "smoke.exe",
                "production_path": "DjClient::connect",
                "connected": False,
                "authenticated": False,
                "audio_frame_queued": False,
            }
        )
        with self.assertRaisesRegex(AssertionError, "exactly one JSON line"):
            parse_rust_smoke_output(f"{line}\n{line}\n", expected_mode="mismatch")
        with self.assertRaisesRegex(AssertionError, "mode"):
            parse_rust_smoke_output(f"{line}\n", expected_mode="match")


class ProductionPinRecorderTests(unittest.TestCase):
    def test_matching_pin_requires_probe_then_application_connection(self) -> None:
        probe = {
            "upstream_connected": False,
            "post_tls_application_bytes": 0,
            "websocket_upgrade_requests": 0,
            "auth_messages": 0,
            "audio_messages": 0,
            "parse_errors": 0,
        }
        application = {
            "upstream_connected": True,
            "post_tls_application_bytes": 512,
            "websocket_upgrade_requests": 1,
            "auth_messages": 1,
            "audio_messages": 1,
            "parse_errors": 0,
        }

        selected = validate_recorded_tls_connections("match", [probe, application])

        self.assertIs(selected, application)
        with self.assertRaisesRegex(AssertionError, "two TLS connections"):
            validate_recorded_tls_connections("match", [application])


class PlaintextConnectionRecorderTests(unittest.IsolatedAsyncioTestCase):
    async def test_counts_unexpected_upgrade_and_auth_instead_of_assuming_zero(self) -> None:
        recorder = PlaintextConnectionRecorder(listen_port=0)
        await recorder.start()
        traffic = (
            b"GET / HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
            + masked_text_frame({"type": "dj_auth", "dj_key": "never-retained"})
        )
        try:
            _, writer = await asyncio.open_connection("127.0.0.1", recorder.port)
            writer.write(traffic)
            await writer.drain()
            writer.close()
            await writer.wait_closed()
            for _ in range(20):
                if recorder.evidence()["auth_messages"] == 1:
                    break
                await asyncio.sleep(0.01)

            evidence = recorder.evidence()
            self.assertEqual(evidence["connections"], 1)
            self.assertEqual(evidence["post_policy_application_bytes"], len(traffic))
            self.assertEqual(evidence["websocket_upgrade_requests"], 1)
            self.assertEqual(evidence["auth_messages"], 1)
            self.assertNotIn("never-retained", json.dumps(evidence))
        finally:
            await recorder.stop()


class EvidenceRedactionTests(unittest.TestCase):
    def test_renderer_secret_never_reaches_json_log_or_report_evidence(self) -> None:
        sentinel = "MCAV_RENDERER_SECRET_SENTINEL_7f19c9"
        with tempfile.TemporaryDirectory() as temporary_text:
            temporary = Path(temporary_text)
            command = service_command(
                temporary / "bundle",
                temporary / "certificate.pem",
                temporary / "private-key.pem",
                temporary / "auth.json",
                no_auth=False,
                renderer_secret=sentinel,
            )

        self.assertIn(sentinel, command, "the actual subprocess command must receive the secret")
        collected_command = redact_command(command)
        collected_cmdline = redact_text(" ".join(command), (sentinel,))
        collected_log = redact_bytes(
            f"command: {shlex.join(command)}\nserver echoed {sentinel}\n".encode(),
            (sentinel,),
        ).decode()
        evidence = {
            "command": collected_command,
            "process_identity": {"cmdline": collected_cmdline},
        }
        report = (
            f"command={shlex.join(collected_command)}\n"
            f"cmdline={collected_cmdline}\nlog={collected_log}"
        )
        outputs = (json.dumps(evidence, sort_keys=True), collected_log, report)

        for output in outputs:
            self.assertNotIn(sentinel, output)
            self.assertIn("<redacted>", output)


class ReleaseExtractionTests(unittest.TestCase):
    @patch("packaged_smoke.subprocess.run")
    def test_accepts_exact_plugin_install_path_beside_mcav_root(self, verifier_run) -> None:
        with tempfile.TemporaryDirectory() as temporary_text:
            temporary = Path(temporary_text)
            archive = temporary / "release.zip"
            with zipfile.ZipFile(archive, "w") as release:
                release.writestr("mcav-vj/VERSION", "rc2")
                release.writestr("plugins/AudioViz.jar", b"plugin")

            bundle = verify_and_extract(archive, temporary / "extracted")

            self.assertEqual((bundle / "VERSION").read_text(), "rc2")
            self.assertEqual(
                (temporary / "extracted/plugins/AudioViz.jar").read_bytes(),
                b"plugin",
            )
            verifier_run.assert_called_once()

    @patch("packaged_smoke.subprocess.run")
    def test_rejects_any_other_plugin_payload(self, _verifier_run) -> None:
        with tempfile.TemporaryDirectory() as temporary_text:
            temporary = Path(temporary_text)
            archive = temporary / "release.zip"
            with zipfile.ZipFile(archive, "w") as release:
                release.writestr("mcav-vj/VERSION", "rc2")
                release.writestr("plugins/Other.jar", b"plugin")

            with self.assertRaisesRegex(AssertionError, "unsafe archive entry"):
                verify_and_extract(archive, temporary / "extracted")


class CertificateFixtureTests(unittest.TestCase):
    def test_generated_leaf_is_not_a_ca_and_allows_server_auth(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_text:
            certificate, _private_key, _fingerprint = generate_certificate(Path(temporary_text))
            details = subprocess.run(
                ["openssl", "x509", "-in", str(certificate), "-noout", "-text"],
                check=True,
                capture_output=True,
                text=True,
            ).stdout

        self.assertIn("CA:FALSE", details)
        self.assertIn("TLS Web Server Authentication", details)


if __name__ == "__main__":
    unittest.main()
