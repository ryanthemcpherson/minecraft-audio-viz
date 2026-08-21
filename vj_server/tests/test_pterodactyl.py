import hashlib
import ipaddress
import json
import os
import re
import shlex
import shutil
import ssl
import subprocess
import sys
import threading
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Sequence

import pytest

import vj_server.pterodactyl as pterodactyl
from vj_server.auth import verify_password
from vj_server.cli import vj_server
from vj_server.pterodactyl import BootstrapError, BootstrapPaths, bootstrap_pterodactyl

PUBLIC_IPV4 = "8.8.8.8"
SECOND_PUBLIC_IPV4 = "1.1.1.1"
THIRD_PUBLIC_IPV4 = "9.9.9.9"
PUBLIC_IPV6 = "2606:4700:4700::1111"
EXPANDED_PUBLIC_IPV6 = "2606:4700:4700:0:0:0:0:1111"


def write_plugin_jar(path: Path, payload: bytes = b"release") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(
            "plugin.yml",
            "name: AudioViz\nversion: 26.1\nmain: com.audioviz.AudioVizPlugin\n",
        )
        archive.writestr("payload.bin", payload)


@pytest.fixture
def bootstrap_paths(tmp_path: Path) -> BootstrapPaths:
    project_root = tmp_path / "mcav-vj"
    release_dir = project_root / "release"
    release_dir.mkdir(parents=True)
    write_plugin_jar(release_dir / "AudioViz.jar")
    (release_dir / "plugin-config.default.yml").write_text(
        '# keep me\nwebsocket:\n  address: "0.0.0.0"\n  port: 9999\n'
        'ws-secret: ""\ndefaults:\n  entity_count: 160\n',
        encoding="utf-8",
    )
    return BootstrapPaths(project_root=project_root, plugins_dir=tmp_path / "plugins")


def identity_snapshot(paths: BootstrapPaths) -> dict[str, bytes]:
    return {
        name: (paths.project_root / name).read_bytes()
        for name in (
            "state/runtime.env",
            "state/dj_auth.json",
            "state/tls.crt",
            "state/tls.key",
            "FIRST_LOGIN.txt",
        )
    }


def run_openssl(arguments: Sequence[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        list(arguments),
        check=True,
        capture_output=True,
        text=True,
    )


def replace_tls_with_localhost_identity(paths: BootstrapPaths) -> None:
    run_openssl(
        [
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "ec",
            "-pkeyopt",
            "ec_paramgen_curve:P-256",
            "-sha256",
            "-days",
            "397",
            "-nodes",
            "-subj",
            "/CN=MCAV Control Center",
            "-addext",
            "subjectAltName=DNS:localhost,IP:127.0.0.1",
            "-keyout",
            str(paths.tls_key),
            "-out",
            str(paths.tls_cert),
        ]
    )
    generation = current_identity_generation(paths)
    fingerprint_output = run_openssl(
        [
            "openssl",
            "x509",
            "-fingerprint",
            "-sha256",
            "-noout",
            "-in",
            str(generation / "tls.crt"),
        ]
    ).stdout
    fingerprint = re.sub(r"[:\s]", "", fingerprint_output.split("=", 1)[1]).lower()
    metadata = json.loads((generation / "identity.json").read_text(encoding="utf-8"))
    metadata["sha256_fingerprint"] = fingerprint
    (generation / "identity.json").write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    public_ip = ipaddress.ip_address(metadata["public_host"])
    (generation / "FIRST_LOGIN.txt").write_bytes(
        pterodactyl._update_first_login(
            (generation / "FIRST_LOGIN.txt").read_text(encoding="utf-8"),
            public_ip,
            fingerprint,
        )
    )


def first_login_values(paths: BootstrapPaths) -> dict[str, str]:
    return dict(
        line.split("=", 1)
        for line in paths.first_login.read_text(encoding="utf-8").splitlines()
        if "=" in line
    )


def current_identity_generation(paths: BootstrapPaths) -> Path:
    pointer = paths.state_dir / "current-identity"
    assert pointer.is_symlink()
    generation = pointer.resolve(strict=True)
    assert generation.parent == (paths.state_dir / "identity-generations").resolve()
    return generation


def convert_current_identity_to_fd0b918_layout(paths: BootstrapPaths) -> dict[str, bytes]:
    """Replace the transactional fixture with the exact prior flat identity layout."""
    legacy_content = identity_snapshot(paths)
    for entrypoint in (
        paths.runtime_env,
        paths.auth_file,
        paths.tls_cert,
        paths.tls_key,
        paths.identity_metadata,
        paths.first_login,
    ):
        entrypoint.unlink()
    paths.identity_pointer.unlink()
    shutil.rmtree(paths.identity_generations)
    paths.identity_lock.unlink()

    legacy_modes = {
        "state/runtime.env": 0o600,
        "state/dj_auth.json": 0o600,
        "state/tls.crt": 0o644,
        "state/tls.key": 0o600,
        "FIRST_LOGIN.txt": 0o600,
    }
    for relative_path, content in legacy_content.items():
        path = paths.project_root / relative_path
        path.write_bytes(content)
        os.chmod(path, legacy_modes[relative_path])
    return legacy_content


def assert_current_identity_is_coherent(paths: BootstrapPaths) -> dict[str, str]:
    generation = current_identity_generation(paths)
    metadata = json.loads((generation / "identity.json").read_text(encoding="utf-8"))
    public_ip = ipaddress.ip_address(metadata["public_host"])
    endpoint_host = (
        f"[{public_ip}]" if isinstance(public_ip, ipaddress.IPv6Address) else str(public_ip)
    )
    login = first_login_values(paths)
    fingerprint_output = run_openssl(
        [
            "openssl",
            "x509",
            "-fingerprint",
            "-sha256",
            "-noout",
            "-in",
            str(generation / "tls.crt"),
        ]
    ).stdout
    actual_fingerprint = re.sub(
        r"[:\s]",
        "",
        fingerprint_output.split("=", 1)[1],
    ).lower()

    assert login["TLS_SHA256_FINGERPRINT"] == metadata["sha256_fingerprint"]
    assert actual_fingerprint == metadata["sha256_fingerprint"]
    assert login["ADMIN_URL"] == f"https://{endpoint_host}:8080/"
    assert login["PREVIEW_URL"] == f"https://{endpoint_host}:8080/preview/"
    assert login["DJ_ENDPOINT"] == f"wss://{endpoint_host}:25808"
    assert pterodactyl.certificate_covers_ip(generation / "tls.crt", public_ip)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(generation / "tls.crt", generation / "tls.key")

    for entrypoint in (
        paths.runtime_env,
        paths.auth_file,
        paths.tls_cert,
        paths.tls_key,
        paths.identity_metadata,
        paths.first_login,
    ):
        assert entrypoint.is_symlink()
        assert entrypoint.resolve(strict=True).parent == generation
    return metadata


def test_parse_public_ip_normalizes_ipv4_and_ipv6() -> None:
    assert pterodactyl.parse_public_ip(" 8.8.8.8 ") == ipaddress.IPv4Address("8.8.8.8")
    assert pterodactyl.parse_public_ip(PUBLIC_IPV6) == ipaddress.IPv6Address(PUBLIC_IPV6)


@pytest.mark.parametrize(
    "public_host",
    [
        "",
        "localhost",
        "127.0.0.1",
        "0.0.0.0",
        "10.20.30.40",
        "169.254.10.20",
        "224.0.0.1",
        "2001:db8::1",
        "2606:4700:4700::1111%eth0",
    ],
)
def test_invalid_or_non_public_host_is_rejected_before_identity_creation(
    bootstrap_paths: BootstrapPaths,
    public_host: str,
) -> None:
    with pytest.raises(
        BootstrapError,
        match="MCAV_PUBLIC_HOST must be a public IPv4 or IPv6 address",
    ):
        bootstrap_pterodactyl(
            bootstrap_paths,
            "26.1-test",
            public_host=public_host,
        )

    assert not bootstrap_paths.tls_cert.exists()
    assert not bootstrap_paths.tls_key.exists()


def test_missing_public_host_is_rejected_before_identity_creation(
    bootstrap_paths: BootstrapPaths,
) -> None:
    with pytest.raises(
        BootstrapError,
        match="MCAV_PUBLIC_HOST must be a public IPv4 or IPv6 address",
    ):
        bootstrap_pterodactyl(bootstrap_paths, "26.1-test")

    assert not bootstrap_paths.tls_cert.exists()
    assert not bootstrap_paths.tls_key.exists()


def test_first_run_creates_secure_identity_and_plugin(bootstrap_paths: BootstrapPaths) -> None:
    result = bootstrap_pterodactyl(bootstrap_paths, "26.1", public_host=PUBLIC_IPV4)

    assert result.credentials_created is True
    assert (bootstrap_paths.plugins_dir / "AudioViz.jar").is_file()
    auth = json.loads(bootstrap_paths.auth_file.read_text(encoding="utf-8"))
    first_login = bootstrap_paths.first_login.read_text(encoding="utf-8")
    admin_id = next(iter(auth["vj_operators"]))
    admin_password = next(
        line.split("=", 1)[1]
        for line in first_login.splitlines()
        if line.startswith("ADMIN_PASSWORD=")
    )
    assert verify_password(admin_password, auth["vj_operators"][admin_id]["key_hash"])
    assert "MINECRAFT_WS_SECRET" not in first_login
    assert re.search(r"TLS_SHA256_FINGERPRINT=[0-9a-f]{64}$", first_login, re.MULTILINE)
    assert "ADMIN_URL=https://8.8.8.8:8080/" in first_login
    assert "PREVIEW_URL=https://8.8.8.8:8080/preview/" in first_login
    assert "DJ_ENDPOINT=wss://8.8.8.8:25808" in first_login
    assert pterodactyl.certificate_covers_ip(
        bootstrap_paths.tls_cert,
        ipaddress.ip_address(PUBLIC_IPV4),
    )
    san_output = run_openssl(
        [
            "openssl",
            "x509",
            "-noout",
            "-ext",
            "subjectAltName",
            "-in",
            str(bootstrap_paths.tls_cert),
        ]
    ).stdout
    assert "IP Address:8.8.8.8" in san_output
    assert "DNS:localhost" in san_output
    assert "IP Address:127.0.0.1" in san_output
    if os.name != "nt":
        assert bootstrap_paths.tls_key.stat().st_mode & 0o777 == 0o600
        assert bootstrap_paths.tls_cert.stat().st_mode & 0o777 == 0o644


def test_first_run_commits_one_immutable_identity_generation(
    bootstrap_paths: BootstrapPaths,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1", public_host=PUBLIC_IPV4)

    metadata = assert_current_identity_is_coherent(bootstrap_paths)
    assert metadata["public_host"] == PUBLIC_IPV4
    generations_dir = bootstrap_paths.state_dir / "identity-generations"
    assert len(list(generations_dir.iterdir())) == 1
    if os.name != "nt":
        generation = current_identity_generation(bootstrap_paths)
        assert bootstrap_paths.state_dir.stat().st_mode & 0o777 == 0o700
        assert generations_dir.stat().st_mode & 0o777 == 0o700
        assert generation.stat().st_mode & 0o777 == 0o700
        for filename in (
            "runtime.env",
            "dj_auth.json",
            "tls.key",
            "FIRST_LOGIN.txt",
            "identity.json",
        ):
            assert (generation / filename).stat().st_mode & 0o777 == 0o600
        assert (generation / "tls.crt").stat().st_mode & 0o777 == 0o644
        assert (bootstrap_paths.state_dir / ".bootstrap.lock").stat().st_mode & 0o777 == 0o600


def test_first_run_formats_ipv6_san_and_endpoints(bootstrap_paths: BootstrapPaths) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1", public_host=PUBLIC_IPV6)

    first_login = bootstrap_paths.first_login.read_text(encoding="utf-8")
    assert "ADMIN_URL=https://[2606:4700:4700::1111]:8080/" in first_login
    assert "PREVIEW_URL=https://[2606:4700:4700::1111]:8080/preview/" in first_login
    assert "DJ_ENDPOINT=wss://[2606:4700:4700::1111]:25808" in first_login
    assert pterodactyl.certificate_covers_ip(
        bootstrap_paths.tls_cert,
        ipaddress.ip_address(PUBLIC_IPV6),
    )


def test_second_run_preserves_identity_byte_for_byte(bootstrap_paths: BootstrapPaths) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1", public_host=PUBLIC_IPV4)
    before = identity_snapshot(bootstrap_paths)

    result = bootstrap_pterodactyl(bootstrap_paths, "26.1", public_host=PUBLIC_IPV4)

    assert identity_snapshot(bootstrap_paths) == before
    assert result.credentials_created is False
    assert result.plugin_installed is False


def test_matching_identity_repairs_world_readable_private_key(
    bootstrap_paths: BootstrapPaths,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1", public_host=PUBLIC_IPV4)
    os.chmod(current_identity_generation(bootstrap_paths) / "tls.key", 0o644)

    bootstrap_pterodactyl(bootstrap_paths, "26.1", public_host=PUBLIC_IPV4)

    assert bootstrap_paths.tls_key.stat().st_mode & 0o777 == 0o600
    assert_current_identity_is_coherent(bootstrap_paths)


def test_existing_wrong_san_requires_exact_explicit_rotation_command(
    bootstrap_paths: BootstrapPaths,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)
    before = identity_snapshot(bootstrap_paths)

    with pytest.raises(BootstrapError, match="rotate-tls-identity") as error:
        bootstrap_pterodactyl(
            bootstrap_paths,
            "26.1-test",
            public_host=SECOND_PUBLIC_IPV4,
        )

    expected_command = shlex.join(
        [
            "audioviz-vj",
            "--bootstrap-pterodactyl",
            "--project-root",
            str(bootstrap_paths.project_root.resolve()),
            "--plugins-dir",
            str(bootstrap_paths.plugins_dir.resolve()),
            "--public-host",
            SECOND_PUBLIC_IPV4,
            "--http-port",
            "8080",
            "--port",
            "25808",
            "--unified-web",
            "--rotate-tls-identity",
        ]
    )
    assert expected_command in str(error.value)
    assert identity_snapshot(bootstrap_paths) == before


def test_existing_localhost_only_certificate_requires_explicit_rotation(
    bootstrap_paths: BootstrapPaths,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)
    replace_tls_with_localhost_identity(bootstrap_paths)
    localhost_identity = identity_snapshot(bootstrap_paths)

    with pytest.raises(BootstrapError, match="rotate-tls-identity"):
        bootstrap_pterodactyl(
            bootstrap_paths,
            "26.1-test",
            public_host=PUBLIC_IPV4,
        )

    assert identity_snapshot(bootstrap_paths) == localhost_identity


def test_explicit_rotation_replaces_only_tls_and_endpoint_metadata(
    bootstrap_paths: BootstrapPaths,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)
    bootstrap_paths.plugin_config.write_bytes(
        b'# operator-owned\nwebsocket:\n  address: "10.0.0.4"\n  port: 4321\n'
        b'ws-secret: "operator-value"\n'
    )
    preserved_paths = (
        bootstrap_paths.runtime_env,
        bootstrap_paths.auth_file,
        bootstrap_paths.plugin_config,
    )
    preserved_bytes = {path: path.read_bytes() for path in preserved_paths}
    old_credentials = {
        key: value
        for key, value in first_login_values(bootstrap_paths).items()
        if key in {"ADMIN_USERNAME", "ADMIN_PASSWORD", "DJ_USERNAME", "DJ_PASSWORD"}
    }
    old_certificate = bootstrap_paths.tls_cert.read_bytes()
    old_key = bootstrap_paths.tls_key.read_bytes()

    result = bootstrap_pterodactyl(
        bootstrap_paths,
        "26.1-test",
        public_host=SECOND_PUBLIC_IPV4,
        rotate_tls_identity=True,
    )

    assert result.credentials_created is False
    assert {path: path.read_bytes() for path in preserved_paths} == preserved_bytes
    assert bootstrap_paths.tls_cert.read_bytes() != old_certificate
    assert bootstrap_paths.tls_key.read_bytes() != old_key
    assert {
        key: value
        for key, value in first_login_values(bootstrap_paths).items()
        if key in {"ADMIN_USERNAME", "ADMIN_PASSWORD", "DJ_USERNAME", "DJ_PASSWORD"}
    } == old_credentials
    login = bootstrap_paths.first_login.read_text(encoding="utf-8")
    assert "ADMIN_URL=https://1.1.1.1:8080/" in login
    assert "DJ_ENDPOINT=wss://1.1.1.1:25808" in login
    assert pterodactyl.certificate_covers_ip(
        bootstrap_paths.tls_cert,
        ipaddress.ip_address(SECOND_PUBLIC_IPV4),
    )
    assert not pterodactyl.certificate_covers_ip(
        bootstrap_paths.tls_cert,
        ipaddress.ip_address(PUBLIC_IPV4),
    )
    assert_current_identity_is_coherent(bootstrap_paths)


@pytest.mark.parametrize(
    "failure_point",
    [
        "runtime.env",
        "dj_auth.json",
        "tls.crt",
        "tls.key",
        "FIRST_LOGIN.txt",
        "identity.json",
        "publish-generation",
        "switch-pointer",
    ],
)
def test_rotation_failure_before_atomic_pointer_commit_keeps_old_generation_current(
    bootstrap_paths: BootstrapPaths,
    monkeypatch: pytest.MonkeyPatch,
    failure_point: str,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)
    pointer = bootstrap_paths.state_dir / "current-identity"
    old_target = os.readlink(pointer)
    old_identity = identity_snapshot(bootstrap_paths)
    original_replace = os.replace
    failure_injected = False

    def fail_at_boundary(
        source: str | os.PathLike[str], destination: str | os.PathLike[str]
    ) -> None:
        nonlocal failure_injected
        source_path = Path(source)
        destination_path = Path(destination)
        is_generation_file = (
            failure_point == destination_path.name
            and destination_path.parent.name.startswith(".generation-")
        )
        is_generation_publish = (
            failure_point == "publish-generation"
            and source_path.name.startswith(".generation-")
            and destination_path.parent.name == "identity-generations"
        )
        is_pointer_switch = failure_point == "switch-pointer" and destination_path == pointer
        if not failure_injected and (
            is_generation_file or is_generation_publish or is_pointer_switch
        ):
            failure_injected = True
            raise OSError(f"injected failure at {failure_point}")
        original_replace(source, destination)

    monkeypatch.setattr(pterodactyl.os, "replace", fail_at_boundary)

    with pytest.raises(BootstrapError, match="persist|commit"):
        bootstrap_pterodactyl(
            bootstrap_paths,
            "26.1-test",
            public_host=SECOND_PUBLIC_IPV4,
            rotate_tls_identity=True,
        )

    assert failure_injected
    assert os.readlink(pointer) == old_target
    assert identity_snapshot(bootstrap_paths) == old_identity
    assert_current_identity_is_coherent(bootstrap_paths)


def test_post_switch_fsync_failure_rolls_back_to_old_current_identity(
    bootstrap_paths: BootstrapPaths,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)
    old_target = os.readlink(bootstrap_paths.identity_pointer)
    old_identity = identity_snapshot(bootstrap_paths)
    original_fsync_directory = pterodactyl._fsync_directory
    failure_injected = False

    def fail_first_state_fsync_after_switch(path: Path) -> None:
        nonlocal failure_injected
        pointer_was_switched = (
            bootstrap_paths.identity_pointer.is_symlink()
            and os.readlink(bootstrap_paths.identity_pointer) != old_target
        )
        if path == bootstrap_paths.state_dir and pointer_was_switched and not failure_injected:
            failure_injected = True
            raise OSError("injected post-switch state fsync failure")
        original_fsync_directory(path)

    monkeypatch.setattr(
        pterodactyl,
        "_fsync_directory",
        fail_first_state_fsync_after_switch,
    )

    with pytest.raises(BootstrapError, match="commit|durability|rolled back"):
        bootstrap_pterodactyl(
            bootstrap_paths,
            "26.1-test",
            public_host=SECOND_PUBLIC_IPV4,
            rotate_tls_identity=True,
        )

    assert failure_injected
    assert os.readlink(bootstrap_paths.identity_pointer) == old_target
    assert identity_snapshot(bootstrap_paths) == old_identity
    assert_current_identity_is_coherent(bootstrap_paths)


def test_concurrent_rotations_are_serialized_and_commit_complete_generations(
    bootstrap_paths: BootstrapPaths,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)
    runner_guard = threading.Lock()
    first_request_entered = threading.Event()
    second_request_entered = threading.Event()
    active_requests = 0
    maximum_active_requests = 0
    request_count = 0

    def tracking_runner(arguments: Sequence[str]) -> subprocess.CompletedProcess[str]:
        nonlocal active_requests, maximum_active_requests, request_count
        is_generation_request = len(arguments) > 1 and arguments[1] == "req"
        if not is_generation_request:
            return run_openssl(arguments)

        with runner_guard:
            request_count += 1
            current_request = request_count
            active_requests += 1
            maximum_active_requests = max(maximum_active_requests, active_requests)
        try:
            if current_request == 1:
                first_request_entered.set()
                second_request_entered.wait(timeout=0.4)
            else:
                second_request_entered.set()
            time.sleep(0.02)
            return run_openssl(arguments)
        finally:
            with runner_guard:
                active_requests -= 1

    def rotate(public_host: str) -> None:
        bootstrap_pterodactyl(
            bootstrap_paths,
            "26.1-test",
            public_host=public_host,
            rotate_tls_identity=True,
            command_runner=tracking_runner,
        )

    with ThreadPoolExecutor(max_workers=2) as executor:
        first = executor.submit(rotate, SECOND_PUBLIC_IPV4)
        assert first_request_entered.wait(timeout=2)
        second = executor.submit(rotate, THIRD_PUBLIC_IPV4)
        first.result(timeout=10)
        second.result(timeout=10)

    assert maximum_active_requests == 1
    metadata = assert_current_identity_is_coherent(bootstrap_paths)
    assert metadata["public_host"] in {SECOND_PUBLIC_IPV4, THIRD_PUBLIC_IPV4}


def test_failed_rotation_preserves_complete_existing_identity(
    bootstrap_paths: BootstrapPaths,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)
    before = identity_snapshot(bootstrap_paths)
    fingerprint_requests = 0

    def fail_fingerprint(arguments: Sequence[str]) -> subprocess.CompletedProcess[str]:
        nonlocal fingerprint_requests
        if "-fingerprint" in arguments:
            fingerprint_requests += 1
            if fingerprint_requests == 2:
                raise subprocess.CalledProcessError(
                    1,
                    list(arguments),
                    stderr="fingerprint failed",
                )
        return run_openssl(arguments)

    with pytest.raises(BootstrapError, match="TLS identity generation failed"):
        bootstrap_pterodactyl(
            bootstrap_paths,
            "26.1-test",
            public_host=SECOND_PUBLIC_IPV4,
            rotate_tls_identity=True,
            command_runner=fail_fingerprint,
        )

    assert identity_snapshot(bootstrap_paths) == before
    assert not list(bootstrap_paths.state_dir.glob(".bootstrap-*"))


def test_explicit_rotation_refuses_a_missing_identity(
    bootstrap_paths: BootstrapPaths,
) -> None:
    with pytest.raises(BootstrapError, match="existing complete deployment identity"):
        bootstrap_pterodactyl(
            bootstrap_paths,
            "26.1-test",
            public_host=PUBLIC_IPV4,
            rotate_tls_identity=True,
        )

    assert not bootstrap_paths.runtime_env.exists()
    assert not bootstrap_paths.auth_file.exists()
    assert not bootstrap_paths.tls_cert.exists()
    assert not bootstrap_paths.tls_key.exists()
    assert not bootstrap_paths.first_login.exists()


def test_rotation_does_not_require_or_mutate_release_plugin_or_backups(
    bootstrap_paths: BootstrapPaths,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)
    bootstrap_paths.plugin_config.write_bytes(b"operator-owned plugin configuration\n")
    plugin_before = bootstrap_paths.plugin_config.read_bytes()
    installed_plugin = bootstrap_paths.plugins_dir / "AudioViz.jar"
    installed_before = installed_plugin.read_bytes()
    backups_before = sorted(
        path.relative_to(bootstrap_paths.backups_dir)
        for path in bootstrap_paths.backups_dir.rglob("*")
    )
    bootstrap_paths.release_jar.unlink()
    bootstrap_paths.default_config.unlink()

    result = bootstrap_pterodactyl(
        bootstrap_paths,
        "26.1-test",
        public_host=SECOND_PUBLIC_IPV4,
        rotate_tls_identity=True,
    )

    assert result.credentials_created is False
    assert result.plugin_installed is False
    assert result.config_updated is False
    assert bootstrap_paths.plugin_config.read_bytes() == plugin_before
    assert installed_plugin.read_bytes() == installed_before
    assert (
        sorted(
            path.relative_to(bootstrap_paths.backups_dir)
            for path in bootstrap_paths.backups_dir.rglob("*")
        )
        == backups_before
    )
    assert_current_identity_is_coherent(bootstrap_paths)


def test_malformed_fingerprint_refuses_atomic_identity_creation(
    bootstrap_paths: BootstrapPaths,
) -> None:
    def malformed_fingerprint(arguments: Sequence[str]) -> subprocess.CompletedProcess[str]:
        result = run_openssl(arguments)
        if "-fingerprint" in arguments:
            return subprocess.CompletedProcess(
                list(arguments),
                0,
                "sha256 Fingerprint=not-a-fingerprint\n",
                "",
            )
        return result

    with pytest.raises(BootstrapError, match="fingerprint"):
        bootstrap_pterodactyl(
            bootstrap_paths,
            "26.1-test",
            public_host=PUBLIC_IPV4,
            command_runner=malformed_fingerprint,
        )

    assert not bootstrap_paths.runtime_env.exists()
    assert not bootstrap_paths.auth_file.exists()
    assert not bootstrap_paths.tls_cert.exists()
    assert not bootstrap_paths.tls_key.exists()
    assert not bootstrap_paths.first_login.exists()


def test_plugin_upgrade_is_backed_up(bootstrap_paths: BootstrapPaths) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1", public_host=PUBLIC_IPV4)
    old_digest = hashlib.sha256(
        (bootstrap_paths.plugins_dir / "AudioViz.jar").read_bytes()
    ).hexdigest()
    write_plugin_jar(bootstrap_paths.release_jar, b"upgraded")

    result = bootstrap_pterodactyl(bootstrap_paths, "26.2", public_host=PUBLIC_IPV4)

    assert result.plugin_installed is True
    backups = list(bootstrap_paths.backups_dir.rglob("AudioViz.jar"))
    assert len(backups) == 1
    assert hashlib.sha256(backups[0].read_bytes()).hexdigest() == old_digest


def test_config_patch_preserves_unrelated_settings(bootstrap_paths: BootstrapPaths) -> None:
    bootstrap_paths.plugins_dir.mkdir(parents=True)
    config_dir = bootstrap_paths.plugins_dir / "AudioViz"
    config_dir.mkdir()
    config_path = config_dir / "config.yml"
    config_path.write_text(
        '# operator comment\nwebsocket:\n  address: "10.0.0.4"\n  port: 4321\n'
        "ws-secret: old\ndefaults:\n  material: DIAMOND_BLOCK\n",
        encoding="utf-8",
    )

    bootstrap_pterodactyl(bootstrap_paths, "26.1", public_host=PUBLIC_IPV4)

    patched = config_path.read_text(encoding="utf-8")
    secret = bootstrap_paths.runtime_env.read_text(encoding="utf-8").split("=", 1)[1].strip()
    assert "# operator comment" in patched
    assert "material: DIAMOND_BLOCK" in patched
    assert 'address: "127.0.0.1"' in patched
    assert "port: 8765" in patched
    assert f'ws-secret: "{secret}"' in patched


def test_partial_identity_state_is_refused_without_rotation(
    bootstrap_paths: BootstrapPaths,
) -> None:
    bootstrap_paths.state_dir.mkdir(parents=True)
    bootstrap_paths.runtime_env.write_text("MINECRAFT_WS_SECRET=keep-this\n", encoding="utf-8")

    with pytest.raises(BootstrapError, match="partial deployment identity"):
        bootstrap_pterodactyl(
            bootstrap_paths,
            "26.1",
            public_host=PUBLIC_IPV4,
            rotate_tls_identity=True,
        )

    assert (
        bootstrap_paths.runtime_env.read_text(encoding="utf-8") == "MINECRAFT_WS_SECRET=keep-this\n"
    )


def test_matching_fd0b918_flat_identity_is_adopted_with_canonical_ipv6_origin(
    bootstrap_paths: BootstrapPaths,
) -> None:
    bootstrap_pterodactyl(
        bootstrap_paths,
        "26.1-test",
        public_host=EXPANDED_PUBLIC_IPV6,
    )
    legacy_identity = convert_current_identity_to_fd0b918_layout(bootstrap_paths)
    os.chmod(bootstrap_paths.tls_key, 0o644)

    result = bootstrap_pterodactyl(
        bootstrap_paths,
        "26.1-test",
        public_host=f"  {EXPANDED_PUBLIC_IPV6}  ",
    )

    assert result.credentials_created is False
    assert identity_snapshot(bootstrap_paths) == legacy_identity
    metadata = assert_current_identity_is_coherent(bootstrap_paths)
    assert metadata["public_host"] == PUBLIC_IPV6
    assert first_login_values(bootstrap_paths)["ADMIN_URL"] == (
        "https://[2606:4700:4700::1111]:8080/"
    )
    assert bootstrap_paths.tls_key.stat().st_mode & 0o777 == 0o600


def test_fd0b918_flat_identity_mismatch_is_refused_without_migration(
    bootstrap_paths: BootstrapPaths,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)
    legacy_identity = convert_current_identity_to_fd0b918_layout(bootstrap_paths)

    with pytest.raises(BootstrapError, match="rotate-tls-identity"):
        bootstrap_pterodactyl(
            bootstrap_paths,
            "26.1-test",
            public_host=SECOND_PUBLIC_IPV4,
        )

    assert not os.path.lexists(bootstrap_paths.identity_pointer)
    assert identity_snapshot(bootstrap_paths) == legacy_identity
    for path in (
        bootstrap_paths.runtime_env,
        bootstrap_paths.auth_file,
        bootstrap_paths.tls_cert,
        bootstrap_paths.tls_key,
        bootstrap_paths.first_login,
    ):
        assert path.is_file()
        assert not path.is_symlink()


def test_fd0b918_flat_identity_can_rotate_without_release_or_plugin_mutation(
    bootstrap_paths: BootstrapPaths,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)
    legacy_identity = convert_current_identity_to_fd0b918_layout(bootstrap_paths)
    bootstrap_paths.plugin_config.write_bytes(b"operator-owned plugin configuration\n")
    installed_plugin = bootstrap_paths.plugins_dir / "AudioViz.jar"
    plugin_before = bootstrap_paths.plugin_config.read_bytes()
    installed_before = installed_plugin.read_bytes()
    backups_before = sorted(
        path.relative_to(bootstrap_paths.backups_dir)
        for path in bootstrap_paths.backups_dir.rglob("*")
    )
    bootstrap_paths.release_jar.unlink()
    bootstrap_paths.default_config.unlink()

    result = bootstrap_pterodactyl(
        bootstrap_paths,
        "26.1-test",
        public_host=SECOND_PUBLIC_IPV4,
        rotate_tls_identity=True,
    )

    assert result.credentials_created is False
    assert bootstrap_paths.runtime_env.read_bytes() == legacy_identity["state/runtime.env"]
    assert bootstrap_paths.auth_file.read_bytes() == legacy_identity["state/dj_auth.json"]
    assert bootstrap_paths.tls_cert.read_bytes() != legacy_identity["state/tls.crt"]
    assert bootstrap_paths.tls_key.read_bytes() != legacy_identity["state/tls.key"]
    assert bootstrap_paths.plugin_config.read_bytes() == plugin_before
    assert installed_plugin.read_bytes() == installed_before
    assert (
        sorted(
            path.relative_to(bootstrap_paths.backups_dir)
            for path in bootstrap_paths.backups_dir.rglob("*")
        )
        == backups_before
    )
    metadata = assert_current_identity_is_coherent(bootstrap_paths)
    assert metadata["public_host"] == SECOND_PUBLIC_IPV4


def test_fd0b918_adoption_pointer_failure_preserves_flat_identity(
    bootstrap_paths: BootstrapPaths,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)
    legacy_identity = convert_current_identity_to_fd0b918_layout(bootstrap_paths)
    original_replace = os.replace
    failure_injected = False

    def fail_pointer_switch(
        source: str | os.PathLike[str],
        destination: str | os.PathLike[str],
    ) -> None:
        nonlocal failure_injected
        if not failure_injected and Path(destination) == bootstrap_paths.identity_pointer:
            failure_injected = True
            raise OSError("injected legacy adoption pointer failure")
        original_replace(source, destination)

    monkeypatch.setattr(pterodactyl.os, "replace", fail_pointer_switch)

    with pytest.raises(BootstrapError, match="commit"):
        bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)

    assert failure_injected
    assert not os.path.lexists(bootstrap_paths.identity_pointer)
    assert not os.path.lexists(bootstrap_paths.identity_metadata)
    assert identity_snapshot(bootstrap_paths) == legacy_identity
    for path in (
        bootstrap_paths.runtime_env,
        bootstrap_paths.auth_file,
        bootstrap_paths.tls_cert,
        bootstrap_paths.tls_key,
        bootstrap_paths.first_login,
    ):
        assert path.is_file()
        assert not path.is_symlink()


def test_fd0b918_adoption_entrypoint_failure_restores_flat_identity(
    bootstrap_paths: BootstrapPaths,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)
    legacy_identity = convert_current_identity_to_fd0b918_layout(bootstrap_paths)
    original_replace = os.replace
    failure_injected = False

    def fail_first_entrypoint_switch(
        source: str | os.PathLike[str],
        destination: str | os.PathLike[str],
    ) -> None:
        nonlocal failure_injected
        if not failure_injected and Path(destination) == bootstrap_paths.runtime_env:
            failure_injected = True
            raise OSError("injected legacy entrypoint failure")
        original_replace(source, destination)

    monkeypatch.setattr(pterodactyl.os, "replace", fail_first_entrypoint_switch)

    with pytest.raises(BootstrapError, match="adoption|restored"):
        bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)

    assert failure_injected
    assert not os.path.lexists(bootstrap_paths.identity_pointer)
    assert not os.path.lexists(bootstrap_paths.identity_metadata)
    assert identity_snapshot(bootstrap_paths) == legacy_identity
    for path in (
        bootstrap_paths.runtime_env,
        bootstrap_paths.auth_file,
        bootstrap_paths.tls_cert,
        bootstrap_paths.tls_key,
        bootstrap_paths.first_login,
    ):
        assert path.is_file()
        assert not path.is_symlink()


def test_rejects_release_jar_with_wrong_plugin_name(bootstrap_paths: BootstrapPaths) -> None:
    with zipfile.ZipFile(bootstrap_paths.release_jar, "w") as archive:
        archive.writestr("plugin.yml", "name: SomethingElse\n")

    with pytest.raises(BootstrapError, match="AudioViz"):
        bootstrap_pterodactyl(bootstrap_paths, "26.1", public_host=PUBLIC_IPV4)

    assert not (bootstrap_paths.plugins_dir / "AudioViz.jar").exists()


@pytest.mark.parametrize(
    "topology_override",
    [
        {"http_port": 8081},
        {"dj_port": 9000},
        {"unified_web": False},
    ],
)
def test_bootstrap_rejects_non_exact_pterodactyl_topology(
    bootstrap_paths: BootstrapPaths,
    topology_override: dict[str, int | bool],
) -> None:
    with pytest.raises(BootstrapError, match="HTTP 8080, DJ 25808, and unified web"):
        bootstrap_pterodactyl(
            bootstrap_paths,
            "26.1-test",
            public_host=PUBLIC_IPV4,
            **topology_override,
        )

    assert not bootstrap_paths.tls_cert.exists()


def test_cli_rotation_requires_bootstrap_and_public_host(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.setattr(sys, "argv", ["audioviz-vj", "--rotate-tls-identity"])

    assert vj_server() == 2
    assert (
        "--rotate-tls-identity requires --bootstrap-pterodactyl and --public-host"
        in capsys.readouterr().out
    )


def test_cli_explicitly_rotates_existing_tls_identity(
    bootstrap_paths: BootstrapPaths,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host=PUBLIC_IPV4)
    preserved_auth = bootstrap_paths.auth_file.read_bytes()
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "audioviz-vj",
            "--bootstrap-pterodactyl",
            "--project-root",
            str(bootstrap_paths.project_root),
            "--plugins-dir",
            str(bootstrap_paths.plugins_dir),
            "--release-version",
            "26.1-test",
            "--public-host",
            SECOND_PUBLIC_IPV4,
            "--http-port",
            "8080",
            "--port",
            "25808",
            "--unified-web",
            "--rotate-tls-identity",
        ],
    )

    assert vj_server() == 0
    assert bootstrap_paths.auth_file.read_bytes() == preserved_auth
    assert pterodactyl.certificate_covers_ip(
        bootstrap_paths.tls_cert,
        ipaddress.ip_address(SECOND_PUBLIC_IPV4),
    )


def test_bootstrap_cli_rejects_public_listener_port_collision(
    bootstrap_paths: BootstrapPaths,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "audioviz-vj",
            "--bootstrap-pterodactyl",
            "--project-root",
            str(bootstrap_paths.project_root),
            "--plugins-dir",
            str(bootstrap_paths.plugins_dir),
            "--public-host",
            PUBLIC_IPV4,
            "--unified-web",
            "--http-port",
            "8080",
            "--port",
            "8080",
        ],
    )

    assert vj_server() == 2
    assert "HTTP and DJ listener ports must differ" in capsys.readouterr().out
    assert not bootstrap_paths.tls_cert.exists()
