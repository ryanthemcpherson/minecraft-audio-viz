"""
AudioViz CLI - Command-line interface for audio visualization.

Entry points:
    audioviz      - Local DJ mode (capture audio, send to Minecraft)
    audioviz-vj   - VJ server mode (accept remote DJ connections)
"""

import argparse
import asyncio
import os
import signal
import sys

# Fix Windows console encoding for unicode characters
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def validate_port(value: str) -> int:
    """Validate port number is in valid range."""
    try:
        port = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError(f"Invalid port number: {value}")

    if not 1 <= port <= 65535:
        raise argparse.ArgumentTypeError(f"Port must be between 1 and 65535, got: {port}")
    return port


def validate_positive_int(value: str) -> int:
    """Validate positive integer."""
    try:
        num = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError(f"Invalid integer: {value}")

    if num <= 0:
        raise argparse.ArgumentTypeError(f"Value must be positive, got: {num}")
    return num


def validate_hostname(value: str) -> str:
    """Validate hostname or IP address."""
    if not value or len(value) > 253:
        raise argparse.ArgumentTypeError(f"Invalid hostname: {value}")
    # Basic validation - allow alphanumeric, dots, hyphens
    # More sophisticated validation would use socket.getaddrinfo
    valid_chars = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-:[]")
    if not all(c in valid_chars for c in value):
        raise argparse.ArgumentTypeError(f"Invalid characters in hostname: {value}")
    return value


def main():
    """
    Main entry point for local DJ mode.

    Captures audio from a Windows application and sends visualization
    data to Minecraft and/or browser preview.
    """
    parser = argparse.ArgumentParser(
        prog="audioviz",
        description="AudioViz - Real-time audio visualization for Minecraft",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  audioviz                          # Capture Spotify, send to localhost
  audioviz --app chrome             # Capture Chrome audio
  audioviz --host 192.168.1.100     # Send to remote Minecraft server
  audioviz --preview                # Start browser preview server
  audioviz --list-apps              # Show active audio applications
  audioviz --test                   # Test audio capture without Minecraft
  audioviz --dj-relay ws://192.168.1.204:9000 --dj-name "Ryan"  # Remote DJ mode
        """,
    )

    # Audio source
    audio_group = parser.add_argument_group("Audio Source")
    audio_group.add_argument(
        "--app",
        "-a",
        type=str,
        default="spotify",
        help="Application name to capture audio from (default: spotify)",
    )
    audio_group.add_argument(
        "--list-apps", action="store_true", help="List active audio applications and exit"
    )
    audio_group.add_argument(
        "--list-devices", action="store_true", help="List available audio devices and exit"
    )

    # Minecraft connection
    mc_group = parser.add_argument_group("Minecraft Connection")
    mc_group.add_argument(
        "--host",
        "-H",
        type=validate_hostname,
        default=os.environ.get("MINECRAFT_HOST", "localhost"),
        help="Minecraft server host (default: localhost or $MINECRAFT_HOST)",
    )
    mc_group.add_argument(
        "--port",
        "-p",
        type=validate_port,
        default=int(os.environ.get("MINECRAFT_PORT", "8765")),
        help="Minecraft WebSocket port (default: 8765 or $MINECRAFT_PORT)",
    )
    mc_group.add_argument(
        "--zone", "-z", type=str, default="main", help="Visualization zone name (default: main)"
    )
    mc_group.add_argument(
        "--entities",
        "-e",
        type=validate_positive_int,
        default=16,
        help="Number of visualization entities (default: 16)",
    )
    mc_group.add_argument(
        "--no-minecraft",
        action="store_true",
        help="Run without Minecraft connection (preview/spectrograph only)",
    )

    # Preview server
    preview_group = parser.add_argument_group("Preview Server")
    preview_group.add_argument(
        "--preview", action="store_true", help="Enable browser preview server"
    )
    preview_group.add_argument(
        "--preview-port",
        type=validate_port,
        default=8766,
        help="WebSocket port for browser preview (default: 8766)",
    )
    preview_group.add_argument(
        "--http-port",
        type=validate_port,
        default=8080,
        help="HTTP port for web interface (default: 8080)",
    )
    preview_group.add_argument(
        "--no-http", action="store_true", help="Disable built-in HTTP server (use Vite dev server)"
    )

    # Display options
    display_group = parser.add_argument_group("Display Options")
    display_group.add_argument(
        "--no-spectrograph", action="store_true", help="Disable terminal spectrograph display"
    )
    display_group.add_argument(
        "--compact", action="store_true", help="Use compact single-line spectrograph"
    )
    display_group.add_argument(
        "--quiet", "-q", action="store_true", help="Minimal output (errors only)"
    )

    # Performance tuning
    perf_group = parser.add_argument_group("Performance")
    perf_group.add_argument(
        "--low-latency",
        action="store_true",
        help="Use low-latency FFT mode (~25ms total, smaller buffers)",
    )
    perf_group.add_argument(
        "--ultra-low-latency",
        action="store_true",
        help="Use ultra-low-latency mode (~15ms, WASAPI exclusive, reduced bass)",
    )
    perf_group.add_argument(
        "--beat-prediction",
        action="store_true",
        help="Enable predictive beat tracking (fires beats BEFORE they occur)",
    )
    perf_group.add_argument(
        "--prediction-lookahead",
        type=int,
        default=80,
        help="Beat prediction lookahead in ms (default: 80, match your total latency)",
    )
    perf_group.add_argument(
        "--tick-aligned",
        action="store_true",
        help="Align updates to Minecraft 20 TPS with beat prediction",
    )
    perf_group.add_argument(
        "--no-fft",
        action="store_true",
        help="Disable FFT analysis (use simple peak detection only)",
    )
    perf_group.add_argument(
        "--no-mmcss", action="store_true", help="Disable MMCSS thread priority elevation"
    )
    perf_group.add_argument(
        "--no-native-format",
        action="store_true",
        help="Use AUTOCONVERTPCM mode (disable native format optimization)",
    )
    perf_group.add_argument(
        "--no-bass-lane",
        action="store_true",
        help="Disable parallel bass lane for instant kick detection",
    )
    perf_group.add_argument(
        "--buffer-stats", action="store_true", help="Show ring buffer statistics in spectrograph"
    )
    perf_group.add_argument(
        "--list-audio-backends",
        action="store_true",
        help="List available audio backends (WASAPI, ASIO) and exit",
    )

    # Testing
    test_group = parser.add_argument_group("Testing")
    test_group.add_argument(
        "--test",
        action="store_true",
        help="Test audio capture and display spectrograph (no network)",
    )

    # DJ Relay mode (connect to remote VJ server)
    relay_group = parser.add_argument_group("DJ Relay Mode")
    relay_group.add_argument(
        "--dj-relay",
        type=str,
        metavar="URL",
        help="Connect to VJ server as remote DJ (e.g., ws://192.168.1.204:9000)",
    )
    relay_group.add_argument(
        "--dj-name", type=str, default="DJ", help="DJ display name (default: DJ)"
    )
    relay_group.add_argument(
        "--dj-id", type=str, default="dj_1", help="DJ identifier (default: dj_1)"
    )
    relay_group.add_argument(
        "--dj-key", type=str, default="", help="DJ authentication key (if VJ server requires auth)"
    )
    relay_group.add_argument(
        "--direct",
        action="store_true",
        help="Direct mode: send visualization directly to Minecraft (lower latency)",
    )
    relay_group.add_argument(
        "--relay-minecraft-host",
        type=validate_hostname,
        default=None,
        help="Minecraft host for direct mode (default: get from VJ server)",
    )
    relay_group.add_argument(
        "--relay-minecraft-port",
        type=validate_port,
        default=8765,
        help="Minecraft WebSocket port for direct mode (default: 8765)",
    )

    args = parser.parse_args()

    # Handle list commands first
    if args.list_apps:
        _list_apps()
        return 0

    if args.list_devices:
        _list_devices()
        return 0

    if getattr(args, "list_audio_backends", False):
        _list_audio_backends()
        return 0

    # Test mode is equivalent to --no-minecraft --preview
    if args.test:
        args.no_minecraft = True

    # DJ Relay mode - connect to remote VJ server instead of Minecraft
    if args.dj_relay:
        return _run_dj_relay(args)

    # Import here to avoid slow startup for --help
    from audio_processor.app_capture import AppCaptureAgent

    # Create agent with mapped arguments
    agent = AppCaptureAgent(
        app_name=args.app,
        minecraft_host=args.host,
        minecraft_port=args.port,
        zone=args.zone,
        entity_count=args.entities,
        show_spectrograph=not args.no_spectrograph,
        compact_spectrograph=args.compact,
        broadcast_port=args.preview_port if args.preview or args.test else 0,
        http_port=0 if args.no_http else args.http_port,
        vscode_mode=False,
        use_fft=not args.no_fft,
        low_latency=args.low_latency,
        ultra_low_latency=getattr(args, "ultra_low_latency", False),
        use_beat_prediction=getattr(args, "beat_prediction", False),
        prediction_lookahead_ms=float(getattr(args, "prediction_lookahead", 80)),
        tick_aligned=args.tick_aligned,
    )

    # Signal handlers
    def signal_handler(sig, frame):
        agent.stop()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    # Run
    try:
        asyncio.run(_run_agent(agent, args))
    except KeyboardInterrupt:
        pass

    return 0


async def _run_agent(agent, args):
    """Run the capture agent with appropriate connections."""
    # Connect to Minecraft if enabled
    if not args.no_minecraft:
        connected = await agent.connect_minecraft()
        if not connected:
            print(f"Warning: Could not connect to Minecraft at {args.host}:{args.port}")
            print("Running in preview-only mode...")

    # Start capture
    await agent.run()


def _run_dj_relay(args):
    """Run in DJ relay mode - send audio to a VJ server."""
    from audio_processor.app_capture import AppAudioCapture
    from audio_processor.dj_relay import DJRelay, DJRelayAgent, DJRelayConfig
    from audio_processor.fft_analyzer import HybridAnalyzer
    from audio_processor.spectrograph import CompactSpectrograph, TerminalSpectrograph

    mode_str = "DIRECT" if args.direct else "RELAY"
    print(f"Starting DJ Relay mode ({mode_str})...")
    print(f"  VJ Server: {args.dj_relay}")
    print(f"  DJ Name: {args.dj_name}")
    print(f"  Capturing: {args.app}")
    if args.direct:
        mc_host = args.relay_minecraft_host or "(from VJ server)"
        print(f"  Direct Mode: Minecraft @ {mc_host}:{args.relay_minecraft_port}")

    # Create relay config
    relay_config = DJRelayConfig(
        vj_server_url=args.dj_relay,
        dj_id=args.dj_id,
        dj_name=args.dj_name,
        dj_key=args.dj_key,
        direct_mode=args.direct,
        minecraft_host=args.relay_minecraft_host,
        minecraft_port=args.relay_minecraft_port,
        zone=args.zone,
        entity_count=args.entities,
    )

    # Create components
    relay = DJRelay(relay_config)
    capture = AppAudioCapture(args.app)

    # FFT analyzer (HybridAnalyzer falls back to synthetic if no audio device)
    fft_analyzer = None
    if not args.no_fft:
        try:
            fft_analyzer = HybridAnalyzer(
                low_latency=args.low_latency,
                ultra_low_latency=getattr(args, "ultra_low_latency", False),
                use_beat_prediction=getattr(args, "beat_prediction", False),
                prediction_lookahead_ms=float(getattr(args, "prediction_lookahead", 80)),
                enable_bass_lane=not getattr(args, "no_bass_lane", False),
            )
        except Exception as e:
            print(f"Warning: FFT not available: {e}")

    # Spectrograph
    spectrograph = None
    if not args.no_spectrograph:
        if args.compact:
            spectrograph = CompactSpectrograph()
        else:
            spectrograph = TerminalSpectrograph()

    # Create and run agent
    agent = DJRelayAgent(relay, capture, fft_analyzer, spectrograph)

    def signal_handler(sig, frame):
        agent.stop()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    try:
        asyncio.run(agent.run())
    except KeyboardInterrupt:
        pass

    return 0


def vj_server():
    """
    VJ Server mode - Central server for multi-DJ setups.

    Accepts connections from remote DJs running audioviz and
    broadcasts combined visualization to Minecraft/browsers.
    """
    parser = argparse.ArgumentParser(
        prog="audioviz-vj",
        description="AudioViz VJ Server - Central server for multi-DJ visualization",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  audioviz-vj                           # Start VJ server on default ports
  audioviz-vj --port 9000               # Custom DJ connection port
  audioviz-vj --minecraft-host mc.local # Connect to remote Minecraft
        """,
    )

    # VJ Server settings
    parser.add_argument(
        "--port",
        "-p",
        type=validate_port,
        default=int(os.environ.get("VJ_SERVER_PORT", "9000")),
        help="Port for DJ connections (default: 9000 or $VJ_SERVER_PORT)",
    )
    parser.add_argument(
        "--minecraft-host",
        type=validate_hostname,
        default=os.environ.get("MINECRAFT_HOST", "localhost"),
        help="Minecraft server host (default: localhost or $MINECRAFT_HOST)",
    )
    parser.add_argument(
        "--minecraft-port",
        type=validate_port,
        default=int(os.environ.get("MINECRAFT_PORT", "8765")),
        help="Minecraft WebSocket port (default: 8765 or $MINECRAFT_PORT)",
    )
    parser.add_argument(
        "--broadcast-port",
        type=validate_port,
        default=8766,
        help="WebSocket port for browser clients (default: 8766)",
    )
    parser.add_argument(
        "--auth-file",
        type=str,
        default=os.environ.get("DJ_AUTH_FILE", "configs/dj_auth.json"),
        help="DJ authentication config file",
    )
    parser.add_argument(
        "--no-auth",
        action="store_true",
        help="Disable DJ authentication (INSECURE - development only)",
    )
    parser.add_argument(
        "--hash-passwords",
        action="store_true",
        help="Hash any plaintext passwords in the auth config file and exit",
    )

    args = parser.parse_args()

    # Import and run VJ server
    from audio_processor.vj_server import DJAuthConfig, VJServer

    # Handle --hash-passwords: hash plaintext entries in-place and exit
    if args.hash_passwords:
        import json
        from pathlib import Path

        from audio_processor.auth import hash_password

        auth_path = Path(args.auth_file)
        if not auth_path.exists():
            print(f"Error: Auth config not found: {args.auth_file}")
            return 1
        with open(auth_path) as f:
            auth_data = json.load(f)
        changed = 0
        for section in ["djs", "vj_operators"]:
            for entry_id, entry in auth_data.get(section, {}).items():
                key_hash = entry.get("key_hash", "")
                if key_hash and not key_hash.startswith(("bcrypt:", "sha256:")):
                    entry["key_hash"] = hash_password(key_hash)
                    changed += 1
                    print(f"  Hashed: {section}/{entry_id}")
        if changed:
            with open(auth_path, "w") as f:
                json.dump(auth_data, f, indent=2)
            print(f"\nHashed {changed} plaintext password(s) in {args.auth_file}")
        else:
            print("No plaintext passwords found — all entries already hashed.")
        return 0

    # Load auth config if authentication is enabled
    auth_config = None
    if not args.no_auth and args.auth_file:
        import json
        from pathlib import Path

        auth_path = Path(args.auth_file)
        if auth_path.exists():
            try:
                with open(auth_path) as f:
                    auth_data = json.load(f)
                auth_config = DJAuthConfig.from_dict(auth_data)
                print(f"Loaded DJ auth config from {args.auth_file}")

                # Refuse to start with plaintext passwords when auth is required
                if auth_config.has_plaintext_passwords():
                    print(
                        "\nERROR: Auth config contains plaintext passwords.\n"
                        "Fix with: audioviz-vj --hash-passwords\n"
                        "Or to skip auth (dev only): audioviz-vj --no-auth"
                    )
                    return 1
            except Exception as e:
                print(f"Warning: Failed to load auth config: {e}")
        else:
            # Auth file not found — require explicit --no-auth
            print(
                f"\nERROR: Auth config not found: {args.auth_file}\n"
                f"Create it with: python -m audio_processor.auth init {args.auth_file}\n"
                f"Or to skip auth (dev only): audioviz-vj --no-auth"
            )
            return 1

    server = VJServer(
        dj_port=args.port,
        minecraft_host=args.minecraft_host,
        minecraft_port=args.minecraft_port,
        broadcast_port=args.broadcast_port,
        auth_config=auth_config,
        require_auth=not args.no_auth,
    )

    def signal_handler(sig, frame):
        server.stop()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    async def _run_vj_server():
        """Run VJ server with Minecraft connection."""
        # Connect to Minecraft first
        if await server.connect_minecraft():
            print(f"Connected to Minecraft at {args.minecraft_host}:{args.minecraft_port}")
        else:
            print(
                f"Warning: Could not connect to Minecraft at {args.minecraft_host}:{args.minecraft_port}"
            )

        try:
            await server.run()
        finally:
            await server.cleanup()

    try:
        asyncio.run(_run_vj_server())
    except KeyboardInterrupt:
        pass

    return 0


def _list_apps():
    """List active audio applications."""
    try:
        from audio_processor.app_capture import AppAudioCapture
    except ImportError:
        print("Error: pycaw not installed. Run: pip install pycaw comtypes")
        sys.exit(1)

    print("\nActive audio applications:")
    print("-" * 50)

    capture = AppAudioCapture("")
    sessions = capture.list_sessions()

    if sessions:
        for s in sessions:
            print(f"  {s['name']:35} (PID: {s['pid']})")
    else:
        print("  No active audio sessions found.")
        print("  Make sure an application is playing audio.")

    print("-" * 50)


def _list_devices():
    """List available audio devices."""
    try:
        from audio_processor.fft_analyzer import list_audio_devices

        list_audio_devices()
    except ImportError:
        print("Error: FFT module not available.")
        print("Install with: pip install sounddevice numpy scipy")
        sys.exit(1)


def _list_audio_backends():
    """List available audio backends and their capabilities."""
    print("\n" + "=" * 60)
    print("AUDIO BACKEND AVAILABILITY")
    print("=" * 60)

    # Check WASAPI (pyaudiowpatch)
    print("\n[WASAPI Loopback - pyaudiowpatch]")
    try:
        import pyaudiowpatch as pyaudio

        p = pyaudio.PyAudio()
        try:
            loopback = p.get_default_wasapi_loopback()
            print("  Status: AVAILABLE")
            print(f"  Default loopback: {loopback['name']}")
            print(f"  Sample rate: {int(loopback['defaultSampleRate'])} Hz")
            print("  Latency: ~10-20ms (shared mode)")
        except Exception:
            print("  Status: AVAILABLE (no loopback device found)")
        p.terminate()
    except ImportError:
        print("  Status: NOT INSTALLED")
        print("  Install: pip install pyaudiowpatch")

    # Check ASIO
    print("\n[ASIO - Ultra Low Latency]")
    try:
        from audio_processor.asio_capture import check_asio_available, list_asio_devices

        available, msg = check_asio_available()
        print(f"  Status: {'AVAILABLE' if available else 'NOT AVAILABLE'}")
        print(f"  {msg}")
        if available:
            devices = list_asio_devices()
            print(f"  Devices: {len(devices)}")
            for dev in devices[:3]:  # Show first 3
                print(f"    - {dev.name} ({dev.default_low_latency * 1000:.1f}ms)")
            print("  Latency: 1-5ms (driver dependent)")
        else:
            print("  Install ASIO4ALL: https://www.asio4all.org/")
    except ImportError as e:
        print(f"  Status: MODULE ERROR ({e})")

    # Check IAudioClient3 (low-latency WASAPI)
    print("\n[WASAPI IAudioClient3 - Low Latency Shared Mode]")
    try:
        from audio_processor.wasapi_loopback import check_wasapi_lowlatency_available

        available, msg = check_wasapi_lowlatency_available()
        print(f"  Status: {'AVAILABLE' if available else 'NOT AVAILABLE'}")
        print(f"  {msg}")
        if available:
            print("  Latency: 2-10ms (Windows 10+ required)")
    except ImportError as e:
        print(f"  Status: MODULE ERROR ({e})")
        print("  Install: pip install comtypes")

    # Check sounddevice
    print("\n[sounddevice - General Purpose]")
    try:
        import sounddevice as sd

        print("  Status: AVAILABLE")
        hostapis = sd.query_hostapis()
        print(f"  Host APIs: {', '.join(api['name'] for api in hostapis)}")
    except ImportError:
        print("  Status: NOT INSTALLED")
        print("  Install: pip install sounddevice")

    print("\n" + "=" * 60)
    print("\nRecommended setup for lowest latency:")
    print("  1. Beat prediction (--beat-prediction) - compensates for latency")
    print("  2. ASIO if available (requires ASIO driver)")
    print("  3. Ultra-low-latency mode (--ultra-low-latency)")
    print("=" * 60 + "\n")


if __name__ == "__main__":
    sys.exit(main())
