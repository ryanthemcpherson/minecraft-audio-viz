from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Any, Callable

import pytest
from verify_versions import (
    ContractError,
    expected_artifact_names,
    load_artifact_contract,
    load_product_contract,
    verify_artifact_inventory,
    verify_repository,
)

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_FILES = (
    "release/product-version.json",
    "release/artifacts-v1.json",
    "package.json",
    "package-lock.json",
    "vj_server/pyproject.toml",
    "uv.lock",
    "minecraft_plugin/pom.xml",
    "minecraft_mod/build.gradle",
    "minecraft_mod/gradle.properties",
    "dj_client/package.json",
    "dj_client/package-lock.json",
    "dj_client/src-tauri/Cargo.toml",
    "dj_client/src-tauri/Cargo.lock",
    "dj_client/src-tauri/tauri.conf.json",
    "protocol/schemas/index.json",
)
EXPECTED_STABLE_ARTIFACTS = (
    "AudioViz-1.2.0.jar",
    "mcav-vj-runtime-1.2.0-linux-x86_64.zip",
    "mcav-vj-runtime-1.2.0-linux-aarch64.zip",
    "mcav-vj-runtime-1.2.0-windows-x86_64.zip",
    "mcav-runtime-manifest-v1.json",
    "mcav-runtime-manifest-v1.json.sig",
    "MCAV-DJ-Client-1.2.0-setup.exe",
    "MCAV-DJ-Client-1.2.0.msi",
    "audioviz-fabric-1.2.0.jar",
    "SHA256SUMS.txt",
    "SHA256SUMS.txt.asc",
)


def copy_contract_tree(tmp_path: Path) -> Path:
    for relative in CONTRACT_FILES:
        source = REPOSITORY_ROOT / relative
        destination = tmp_path / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
    return tmp_path


def update_json(path: Path, update: Callable[[dict[str, Any]], None]) -> None:
    value = json.loads(path.read_text(encoding="utf-8"))
    update(value)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def test_repository_contract_converges_on_stable_version() -> None:
    result = verify_repository(REPOSITORY_ROOT, "1.2.0")

    assert result.product_version == "1.2.0"
    assert result.runtime_api == 1
    assert result.protocol_version == "1.0.0"
    assert result.minecraft_version == "1.21.11"
    assert result.paper_api_version == "1.21.11-R0.1-SNAPSHOT"
    assert result.java_version == 21
    assert result.artifacts == EXPECTED_STABLE_ARTIFACTS


def test_release_candidate_uses_stable_metadata_and_rc_artifact_names() -> None:
    result = verify_repository(REPOSITORY_ROOT, "1.2.0-rc.7")

    assert result.product_version == "1.2.0"
    assert "AudioViz-1.2.0-rc.7.jar" in result.artifacts
    assert "MCAV-DJ-Client-1.2.0-rc.7-setup.exe" in result.artifacts
    assert "mcav-runtime-manifest-v1.json" in result.artifacts


@pytest.mark.parametrize(
    "version",
    (
        "1.2",
        "1.2.0-beta.1",
        "1.2.0-rc.0",
        "1.2.0-rc.01",
        "1.2.1",
        "v1.2.0",
        "1.2.0+local",
    ),
)
def test_invalid_or_foreign_release_versions_are_rejected(version: str) -> None:
    with pytest.raises(ContractError, match="release version"):
        verify_repository(REPOSITORY_ROOT, version)


def test_product_contract_rejects_unknown_fields(tmp_path: Path) -> None:
    contract = json.loads(
        (REPOSITORY_ROOT / "release/product-version.json").read_text(encoding="utf-8")
    )
    contract["surprise"] = True
    path = tmp_path / "product-version.json"
    path.write_text(json.dumps(contract), encoding="utf-8")

    with pytest.raises(ContractError, match="unknown fields"):
        load_product_contract(path)


def test_ecosystem_version_drift_is_rejected(tmp_path: Path) -> None:
    root = copy_contract_tree(tmp_path)
    update_json(root / "dj_client/package.json", lambda value: value.update(version="1.1.0"))

    with pytest.raises(ContractError, match="dj_client/package.json"):
        verify_repository(root, "1.2.0")


def test_uv_lock_editable_vj_version_drift_is_rejected(tmp_path: Path) -> None:
    root = copy_contract_tree(tmp_path)
    lock = root / "uv.lock"
    source = lock.read_text(encoding="utf-8")
    lock.write_text(
        source.replace(
            'name = "mcav-vj-server"\nversion = "1.2.0"',
            'name = "mcav-vj-server"\nversion = "1.1.0"',
            1,
        ),
        encoding="utf-8",
    )

    with pytest.raises(ContractError, match="uv.lock"):
        verify_repository(root, "1.2.0")


def test_missing_ecosystem_metadata_is_rejected(tmp_path: Path) -> None:
    root = copy_contract_tree(tmp_path)
    (root / "vj_server/pyproject.toml").unlink()

    with pytest.raises(ContractError, match="vj_server/pyproject.toml"):
        verify_repository(root, "1.2.0")


def test_protocol_and_runtime_api_do_not_follow_product_version(tmp_path: Path) -> None:
    root = copy_contract_tree(tmp_path)
    update_json(
        root / "protocol/schemas/index.json", lambda value: value.update(protocol_version="1.2.0")
    )

    with pytest.raises(ContractError, match="protocol_version"):
        verify_repository(root, "1.2.0")


def test_auto_update_artifacts_must_remain_disabled(tmp_path: Path) -> None:
    root = copy_contract_tree(tmp_path)
    update_json(
        root / "dj_client/src-tauri/tauri.conf.json",
        lambda value: value["bundle"].update(createUpdaterArtifacts=True),
    )

    with pytest.raises(ContractError, match="createUpdaterArtifacts"):
        verify_repository(root, "1.2.0")


def test_maven_metadata_rejects_dtd_and_entity_declarations(tmp_path: Path) -> None:
    root = copy_contract_tree(tmp_path)
    pom = root / "minecraft_plugin/pom.xml"
    source = pom.read_text(encoding="utf-8")
    pom.write_text(
        source.replace(
            "<project ",
            '<!DOCTYPE project [<!ENTITY version "1.2.0">]>\n<project ',
            1,
        ),
        encoding="utf-8",
    )

    with pytest.raises(ContractError, match="DTD or entity"):
        verify_repository(root, "1.2.0")


def test_artifact_contract_rejects_duplicate_rendered_names(tmp_path: Path) -> None:
    source = REPOSITORY_ROOT / "release/artifacts-v1.json"
    value = json.loads(source.read_text(encoding="utf-8"))
    value["artifacts"][1]["filename"] = value["artifacts"][0]["filename"]
    path = tmp_path / "artifacts-v1.json"
    path.write_text(json.dumps(value), encoding="utf-8")

    contract = load_artifact_contract(path)
    with pytest.raises(ContractError, match="duplicate artifact filename"):
        expected_artifact_names(contract, "1.2.0")


def test_artifact_inventory_rejects_missing_and_undeclared_files() -> None:
    contract = load_artifact_contract(REPOSITORY_ROOT / "release/artifacts-v1.json")

    with pytest.raises(ContractError, match="undeclared artifacts"):
        verify_artifact_inventory(
            contract,
            "1.2.0",
            (*EXPECTED_STABLE_ARTIFACTS, "unreviewed.zip"),
        )
    with pytest.raises(ContractError, match="missing artifacts"):
        verify_artifact_inventory(contract, "1.2.0", EXPECTED_STABLE_ARTIFACTS[:-1])
