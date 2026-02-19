"""
DJ/VJ Authentication module with secure password hashing.

Supports:
- bcrypt (recommended, requires: pip install bcrypt)
- SHA256 (fallback, built-in)
- Plaintext (development only, NOT recommended)

Usage:
    # Generate a bcrypt hash
    python -m vj_server.auth hash "mypassword"

    # Verify a password
    python -m vj_server.auth verify "mypassword" "bcrypt:$2b$12$..."
"""

import argparse
import hashlib
import json
import logging
import secrets
import sys

# Try to import bcrypt for secure hashing
try:
    import bcrypt

    HAS_BCRYPT = True
except ImportError:
    HAS_BCRYPT = False
    bcrypt = None


def hash_password(password: str, method: str = "auto") -> str:
    """
    Hash a password using the specified method.

    Args:
        password: The plaintext password
        method: 'bcrypt', 'sha256', or 'auto' (uses bcrypt if available)

    Returns:
        Hashed password with method prefix (e.g., 'bcrypt:$2b$12$...')
    """
    if method == "auto":
        method = "bcrypt" if HAS_BCRYPT else "sha256"

    if method == "bcrypt":
        if not HAS_BCRYPT:
            raise ImportError("bcrypt not installed. Run: pip install bcrypt")
        salt = bcrypt.gensalt(rounds=12)
        hashed = bcrypt.hashpw(password.encode("utf-8"), salt)
        return f"bcrypt:{hashed.decode('utf-8')}"

    elif method == "sha256":
        # Use a random salt for SHA256
        salt = secrets.token_hex(16)
        hash_input = f"{salt}:{password}"
        hashed = hashlib.sha256(hash_input.encode("utf-8")).hexdigest()
        return f"sha256:{salt}:{hashed}"

    else:
        raise ValueError(f"Unknown hashing method: {method}")


def verify_password(password: str, hash_str: str) -> bool:
    """
    Verify a password against a stored hash.

    Args:
        password: The plaintext password to verify
        hash_str: The stored hash (with method prefix)

    Returns:
        True if password matches, False otherwise
    """
    if not hash_str:
        return False

    if hash_str.startswith("bcrypt:"):
        if not HAS_BCRYPT:
            raise ImportError("bcrypt not installed. Run: pip install bcrypt")
        stored_hash = hash_str[7:].encode("utf-8")
        try:
            return bcrypt.checkpw(password.encode("utf-8"), stored_hash)
        except (ValueError, TypeError):
            return False

    elif hash_str.startswith("sha256:"):
        parts = hash_str.split(":")
        if len(parts) == 3:
            # New salted format: sha256:salt:hash
            _, salt, stored_hash = parts
            hash_input = f"{salt}:{password}"
            computed = hashlib.sha256(hash_input.encode("utf-8")).hexdigest()
            return secrets.compare_digest(computed, stored_hash)
        elif len(parts) == 2:
            # Legacy format: sha256:hash (no salt)
            logging.warning(
                "Legacy unsalted SHA256 hash detected. "
                "Rehash with: python -m vj_server.auth hash <password>"
            )
            stored_hash = parts[1]
            computed = hashlib.sha256(password.encode("utf-8")).hexdigest()
            return secrets.compare_digest(computed, stored_hash)
        return False

    else:
        # Reject plaintext passwords — all hashes must use a recognized prefix.
        # Migrate legacy plaintext entries with: python -m vj_server.auth hash "<password>"
        return False


def generate_api_key() -> str:
    """Generate a cryptographically secure API key."""
    return secrets.token_urlsafe(32)


def create_auth_config(output_path: str, djs: list, vjs: list = None):
    """
    Create a new auth config file with hashed passwords.

    Args:
        output_path: Path to write the config file
        djs: List of (dj_id, name, password, priority) tuples
        vjs: List of (vj_id, name, password) tuples
    """
    config = {"djs": {}, "vj_operators": {}}

    for dj_id, name, password, priority in djs:
        config["djs"][dj_id] = {
            "name": name,
            "key_hash": hash_password(password),
            "priority": priority,
        }

    if vjs:
        for vj_id, name, password in vjs:
            config["vj_operators"][vj_id] = {
                "name": name,
                "key_hash": hash_password(password),
            }

    with open(output_path, "w") as f:
        json.dump(config, f, indent=2)

    print(f"Auth config written to: {output_path}")


def main():
    """CLI entry point for auth utilities."""
    parser = argparse.ArgumentParser(
        description="AudioViz DJ/VJ Authentication Utilities",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s hash "mypassword"              # Generate bcrypt hash
  %(prog)s hash "mypassword" --sha256     # Generate SHA256 hash
  %(prog)s verify "pass" "bcrypt:$2b..."  # Verify password
  %(prog)s keygen                         # Generate API key
  %(prog)s init configs/dj_auth.json      # Create new config file
        """,
    )

    subparsers = parser.add_subparsers(dest="command", required=True)

    # Hash command
    hash_parser = subparsers.add_parser("hash", help="Hash a password")
    hash_parser.add_argument("password", help="Password to hash")
    hash_parser.add_argument("--sha256", action="store_true", help="Use SHA256 instead of bcrypt")

    # Verify command
    verify_parser = subparsers.add_parser("verify", help="Verify a password")
    verify_parser.add_argument("password", help="Password to verify")
    verify_parser.add_argument("hash", help="Hash to verify against")

    # Key generation
    subparsers.add_parser("keygen", help="Generate a random API key")

    # Init config
    init_parser = subparsers.add_parser("init", help="Create new auth config")
    init_parser.add_argument("output", help="Output file path")
    init_parser.add_argument(
        "--dj",
        action="append",
        nargs=4,
        metavar=("ID", "NAME", "PASSWORD", "PRIORITY"),
        help="Add a DJ (can be repeated)",
    )

    args = parser.parse_args()

    if args.command == "hash":
        method = "sha256" if args.sha256 else "auto"
        hashed = hash_password(args.password, method)
        print(hashed)

    elif args.command == "verify":
        try:
            result = verify_password(args.password, args.hash)
            if result:
                print("Password matches!")
                sys.exit(0)
            else:
                print("Password does NOT match.")
                sys.exit(1)
        except ImportError as e:
            print(f"Error: {e}")
            sys.exit(1)

    elif args.command == "keygen":
        print(generate_api_key())

    elif args.command == "init":
        djs = []
        if args.dj:
            for dj_id, name, password, priority in args.dj:
                djs.append((dj_id, name, password, int(priority)))

        if not djs:
            # Interactive mode
            print("No DJs specified. Creating example config...")
            djs = [
                ("dj_1", "DJ One", generate_api_key()[:16], 10),
            ]
            print(f"Generated key for dj_1: {djs[0][2]}")

        create_auth_config(args.output, djs)


if __name__ == "__main__":
    main()
