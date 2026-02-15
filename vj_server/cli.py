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
    if not value or len(value) > 253:
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

    args = parser.parse_args()

    # Import and run VJ server
    from vj_server.vj_server import DJAuthConfig, VJServer

    # Handle --hash-passwords: hash plaintext entries in-place and exit
    if args.hash_passwords:
        import json
        from pathlib import Path

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
        broadcast_port=args.broadcast_port,
        auth_config=auth_config,
        require_auth=not args.no_auth,
        metrics_port=None if args.no_metrics else args.metrics_port,
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


if __name__ == "__main__":
    sys.exit(vj_server())
