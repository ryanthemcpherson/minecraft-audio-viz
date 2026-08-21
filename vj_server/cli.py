"""
MCAV VJ Server CLI - Command-line interface for the VJ server.

Entry point:
    audioviz-vj   - VJ server mode (accept remote DJ connections)
"""

import argparse
import asyncio
import os
import signal
import sys
from pathlib import Path

from vj_server.config import validate_http_bind_host

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


def validate_hostname(value: str) -> str:
    """Validate hostname or IP address."""
    try:
        value = validate_http_bind_host(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(str(exc)) from exc
    if len(value) > 253:
        raise argparse.ArgumentTypeError(f"Invalid hostname: {value}")
    valid_chars = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-:[]")
    if not all(c in valid_chars for c in value):
        raise argparse.ArgumentTypeError(f"Invalid characters in hostname: {value}")
    return value


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
  audioviz-vj --minecraft-host 127.0.0.1 --minecraft-port 18765
                                        # Use a local encrypted-tunnel endpoint
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
        help=(
            "Minecraft loopback host or local encrypted-tunnel endpoint "
            "(default: localhost or $MINECRAFT_HOST)"
        ),
    )
    parser.add_argument(
        "--minecraft-port",
        type=validate_port,
        default=int(os.environ.get("MINECRAFT_PORT", "8765")),
        help="Minecraft WebSocket port (default: 8765 or $MINECRAFT_PORT)",
    )
    parser.add_argument(
        "--minecraft-ws-secret",
        default=os.environ.get("MINECRAFT_WS_SECRET"),
        help="Shared secret for the Minecraft WebSocket",
    )
    parser.add_argument(
        "--broadcast-port",
        type=validate_port,
        default=8766,
        help="WebSocket port for browser clients (default: 8766)",
    )
    parser.add_argument(
        "--http-host",
        type=validate_hostname,
        default=os.environ.get("HTTP_HOST", "127.0.0.1"),
        help="HTTP bind host for admin panel (default: 127.0.0.1 or $HTTP_HOST)",
    )
    parser.add_argument(
        "--http-port",
        type=validate_port,
        default=int(os.environ.get("HTTP_PORT", "8080")),
        help="HTTPS/HTTP port for admin panel (default: 8080 or $HTTP_PORT)",
    )
    parser.add_argument(
        "--unified-web",
        action="store_true",
        help="Serve admin, preview, and browser WebSocket traffic on the HTTPS port",
    )
    parser.add_argument(
        "--public-origin",
        help="Exact public HTTPS origin for unified browser WebSocket validation",
    )
    parser.add_argument(
        "--project-root",
        type=Path,
        default=(Path(value) if (value := os.environ.get("MCAV_PROJECT_ROOT")) else None),
        help="Project root containing admin, preview, patterns, and configs",
    )
    parser.add_argument(
        "--bootstrap-pterodactyl",
        action="store_true",
        help="Install persistent Pterodactyl identity, plugin, and renderer configuration, then exit",
    )
    parser.add_argument(
        "--public-host",
        default=os.environ.get("MCAV_PUBLIC_HOST"),
        help="Public IPv4 or IPv6 address used by Pterodactyl TLS bootstrap",
    )
    parser.add_argument(
        "--rotate-tls-identity",
        action="store_true",
        help="Explicitly rotate only the Pterodactyl TLS certificate and private key",
    )
    parser.add_argument(
        "--plugins-dir",
        type=Path,
        default=None,
        help="Paper plugins directory used by --bootstrap-pterodactyl",
    )
    parser.add_argument(
        "--release-version",
        default=None,
        help="Release label recorded by --bootstrap-pterodactyl",
    )
    parser.add_argument(
        "--tls-cert",
        type=Path,
        default=(Path(value) if (value := os.environ.get("TLS_CERT")) else None),
        help="TLS certificate for admin HTTPS and browser WSS",
    )
    parser.add_argument(
        "--tls-key",
        type=Path,
        default=(Path(value) if (value := os.environ.get("TLS_KEY")) else None),
        help="TLS private key for admin HTTPS and browser WSS",
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
    parser.add_argument(
        "--metrics-port",
        type=validate_port,
        default=int(os.environ.get("METRICS_PORT", "9001")),
        help="Port for metrics HTTP endpoint (default: 9001 or $METRICS_PORT)",
    )
    parser.add_argument(
        "--no-metrics",
        action="store_true",
        help="Disable metrics HTTP endpoint",
    )
    parser.add_argument(
        "--visual-delay-ms",
        type=float,
        default=float(os.environ.get("VISUAL_DELAY_MS", "0")),
        help="Visual delay in ms for audio-visual sync (default: 0 or $VISUAL_DELAY_MS)",
    )
    parser.add_argument(
        "--visual-delay-mode",
        choices=["manual", "auto", "discord", "svc"],
        default=os.environ.get("VISUAL_DELAY_MODE", "manual"),
        help="Visual delay mode: manual, auto, discord, svc (default: manual or $VISUAL_DELAY_MODE)",
    )
    parser.add_argument(
        "--no-spectrograph",
        action="store_true",
        help="Disable terminal spectrograph display",
    )
    parser.add_argument(
        "--enable-link",
        action="store_true",
        default=os.environ.get("ENABLE_LINK", "").lower() in ("1", "true", "yes"),
        help="Enable Ableton Link tempo sync (requires aalink package)",
    )
    parser.add_argument(
        "--entities",
        type=int,
        default=int(os.environ.get("ENTITY_COUNT", "100")),
        help="Initial entity pool size (default: 100 or $ENTITY_COUNT)",
    )

    args = parser.parse_args()

    if args.rotate_tls_identity and not (args.bootstrap_pterodactyl and args.public_host):
        print("ERROR: --rotate-tls-identity requires --bootstrap-pterodactyl and --public-host")
        return 2
    if args.bootstrap_pterodactyl and not args.public_host:
        print("ERROR: --public-host is required with --bootstrap-pterodactyl")
        return 2
    if args.unified_web and args.http_port == args.port:
        print("ERROR: HTTP and DJ listener ports must differ in unified mode")
        return 2

    if args.bootstrap_pterodactyl:
        from vj_server.pterodactyl import BootstrapError, BootstrapPaths, bootstrap_pterodactyl

        if args.project_root is None:
            print("ERROR: --project-root is required with --bootstrap-pterodactyl")
            return 2
        plugins_dir = args.plugins_dir or args.project_root.parent / "plugins"
        version_file = args.project_root / "VERSION"
        release_version = args.release_version
        if release_version is None:
            release_version = (
                version_file.read_text(encoding="utf-8").strip()
                if version_file.is_file()
                else "unknown"
            )
        try:
            result = bootstrap_pterodactyl(
                BootstrapPaths(args.project_root, plugins_dir),
                release_version,
                public_host=args.public_host,
                rotate_tls_identity=args.rotate_tls_identity,
            )
        except BootstrapError as exc:
            print(f"ERROR: Pterodactyl bootstrap failed: {exc}")
            return 1
        print(f"MCAV bootstrap complete. First login: {result.first_login}")
        return 0

    # Import and run VJ server
    from vj_server.models import DJAuthConfig
    from vj_server.vj_server import VJServer

    # Handle --hash-passwords: hash plaintext entries in-place and exit
    if args.hash_passwords:
        import json

        from vj_server.auth import hash_password

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
            # Auth file not found -- require explicit --no-auth
            print(
                f"\nERROR: Auth config not found: {args.auth_file}\n"
                f"Create it with: python -m vj_server.auth init {args.auth_file}\n"
                f"Or to skip auth (dev only): audioviz-vj --no-auth"
            )
            return 1

    server = VJServer(
        dj_port=args.port,
        minecraft_host=args.minecraft_host,
        minecraft_port=args.minecraft_port,
        minecraft_ws_secret=args.minecraft_ws_secret,
        broadcast_port=args.broadcast_port,
        http_host=args.http_host,
        http_port=args.http_port,
        project_root=args.project_root,
        tls_cert=args.tls_cert,
        tls_key=args.tls_key,
        unified_web=args.unified_web,
        public_origin=args.public_origin,
        entity_count=args.entities,
        auth_config=auth_config,
        require_auth=not args.no_auth,
        show_spectrograph=sys.stdout.isatty() and not args.no_spectrograph,
        metrics_port=None if args.no_metrics else args.metrics_port,
        visual_delay_ms=args.visual_delay_ms,
        visual_delay_mode=args.visual_delay_mode,
        enable_link=args.enable_link,
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

    # Use uvloop for faster async I/O on Linux/macOS
    if sys.platform != "win32":
        try:
            import uvloop

            asyncio.set_event_loop_policy(uvloop.EventLoopPolicy())
            print("Using uvloop event loop")
        except ImportError:
            pass

    try:
        asyncio.run(_run_vj_server())
    except KeyboardInterrupt:
        pass

    return 0


if __name__ == "__main__":
    sys.exit(vj_server())
