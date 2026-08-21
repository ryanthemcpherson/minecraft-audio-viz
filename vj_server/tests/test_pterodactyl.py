import hashlib
import json
import os
import zipfile
from pathlib import Path

import pytest

from vj_server.auth import verify_password
from vj_server.pterodactyl import BootstrapError, BootstrapPaths, bootstrap_pterodactyl


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


def test_first_run_creates_secure_identity_and_plugin(bootstrap_paths: BootstrapPaths) -> None:
    result = bootstrap_pterodactyl(bootstrap_paths, "26.1")

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
    assert "TLS_SHA256_FINGERPRINT=" in first_login
    if os.name != "nt":
        assert bootstrap_paths.tls_key.stat().st_mode & 0o777 == 0o600


def test_second_run_preserves_identity_byte_for_byte(bootstrap_paths: BootstrapPaths) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1")
    before = identity_snapshot(bootstrap_paths)

    result = bootstrap_pterodactyl(bootstrap_paths, "26.1")

    assert identity_snapshot(bootstrap_paths) == before
    assert result.credentials_created is False
    assert result.plugin_installed is False


def test_plugin_upgrade_is_backed_up(bootstrap_paths: BootstrapPaths) -> None:
    bootstrap_pterodactyl(bootstrap_paths, "26.1")
    old_digest = hashlib.sha256(
        (bootstrap_paths.plugins_dir / "AudioViz.jar").read_bytes()
    ).hexdigest()
    write_plugin_jar(bootstrap_paths.release_jar, b"upgraded")

    result = bootstrap_pterodactyl(bootstrap_paths, "26.2")

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

    bootstrap_pterodactyl(bootstrap_paths, "26.1")

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
        bootstrap_pterodactyl(bootstrap_paths, "26.1")

    assert (
        bootstrap_paths.runtime_env.read_text(encoding="utf-8") == "MINECRAFT_WS_SECRET=keep-this\n"
    )


def test_rejects_release_jar_with_wrong_plugin_name(bootstrap_paths: BootstrapPaths) -> None:
    with zipfile.ZipFile(bootstrap_paths.release_jar, "w") as archive:
        archive.writestr("plugin.yml", "name: SomethingElse\n")

    with pytest.raises(BootstrapError, match="AudioViz"):
        bootstrap_pterodactyl(bootstrap_paths, "26.1")

    assert not (bootstrap_paths.plugins_dir / "AudioViz.jar").exists()
