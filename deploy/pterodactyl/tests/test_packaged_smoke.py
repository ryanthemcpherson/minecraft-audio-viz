from __future__ import annotations

import asyncio
import json
import struct
import unittest

from packaged_smoke import (
    PlaintextConnectionRecorder,
    WebSocketApplicationRecorder,
    parse_rust_smoke_output,
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


if __name__ == "__main__":
    unittest.main()
