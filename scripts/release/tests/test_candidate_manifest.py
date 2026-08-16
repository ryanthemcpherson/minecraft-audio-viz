from __future__ import annotations

import copy
import hashlib
import json
import subprocess
from pathlib import Path

import pytest

from scripts.release.create_candidate_manifest import (
    PAPER_API_COORDINATE,
    RELEASE_VERSION,
    build_candidate_manifest,
    validate_candidate_manifest,
    write_candidate_manifest,
)


def _git(repository: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.fixture
def candidate_inputs(tmp_path: Path) -> dict[str, Path]:
    repository = tmp_path / "repository"
    repository.mkdir()
    _git(repository, "init")
    _git(repository, "config", "user.name", "MCAV Test")
    _git(repository, "config", "user.email", "mcav@example.invalid")

    (repository / ".gitignore").write_text("out/\n", encoding="utf-8")
    (repository / "tracked.txt").write_text("release input\n", encoding="utf-8")
    plugin_directory = repository / "minecraft_plugin"
    plugin_directory.mkdir()
    (plugin_directory / "source.txt").write_text("plugin source\n", encoding="utf-8")
    paper_manifest = repository / "paper-manifest.json"
    paper_manifest.write_text(
        json.dumps(
            {
                "project": "paper",
                "minecraftVersion": "26.2",
                "build": 112,
                "channel": "STABLE",
                "file": "paper-26.2-112.jar",
                "sha256": "a" * 64,
                "url": "https://example.invalid/paper.jar",
            }
        ),
        encoding="utf-8",
    )
    soak_evidence = repository / "soak.md"
    soak_evidence.write_text("# Eight-hour soak\n\nPASS\n", encoding="utf-8")
    _git(
        repository,
        "add",
        ".gitignore",
        "tracked.txt",
        "minecraft_plugin/source.txt",
        "paper-manifest.json",
        "soak.md",
    )
    _git(repository, "commit", "-m", "test fixture")

    output_directory = repository / "out"
    output_directory.mkdir()
    artifact = output_directory / "mcav-paper-1.1.0.jar"
    artifact.write_bytes(b"plugin candidate bytes")
    sbom = output_directory / "mcav-paper-1.1.0.cdx.json"
    sbom.write_text('{"bomFormat":"CycloneDX"}\n', encoding="utf-8")

    return {
        "artifact": artifact,
        "paper_manifest": paper_manifest,
        "repository": repository,
        "sbom": sbom,
        "soak_evidence": soak_evidence,
    }


def test_manifest_captures_exact_release_identity(candidate_inputs: dict[str, Path]) -> None:
    manifest = build_candidate_manifest(version=RELEASE_VERSION, **candidate_inputs)
    repository = candidate_inputs["repository"]
    repository_root_commit = _git(repository, "rev-list", "--max-parents=0", "HEAD")

    assert manifest == {
        "artifact": {
            "file": "mcav-paper-1.1.0.jar",
            "sha256": _sha256(candidate_inputs["artifact"]),
        },
        "build_timestamp": {
            "source": "repository_root_commit_timestamp",
            "source_commit_sha": repository_root_commit,
            "source_date_epoch": int(
                _git(repository, "show", "-s", "--format=%ct", repository_root_commit)
            ),
        },
        "commit_sha": _git(repository, "rev-parse", "HEAD"),
        "java_release": 25,
        "paper": {
            "api_coordinate": PAPER_API_COORDINATE,
            "build": 112,
            "file": "paper-26.2-112.jar",
            "minecraft_version": "26.2",
            "sha256": "a" * 64,
        },
        "sbom": {
            "file": "mcav-paper-1.1.0.cdx.json",
            "sha256": _sha256(candidate_inputs["sbom"]),
        },
        "schema_version": 1,
        "soak_evidence": {
            "file": "soak.md",
            "sha256": _sha256(candidate_inputs["soak_evidence"]),
        },
        "version": "1.1.0",
    }


def test_docs_only_commit_preserves_repository_build_timestamp(
    candidate_inputs: dict[str, Path],
) -> None:
    repository = candidate_inputs["repository"]
    before = build_candidate_manifest(version=RELEASE_VERSION, **candidate_inputs)
    (repository / "readiness.md").write_text("release evidence\n", encoding="utf-8")
    _git(repository, "add", "readiness.md")
    _git(repository, "commit", "-m", "docs only")

    after = build_candidate_manifest(version=RELEASE_VERSION, **candidate_inputs)

    assert after["commit_sha"] != before["commit_sha"]
    assert after["build_timestamp"] == before["build_timestamp"]


def test_manifest_rejects_dirty_tracked_worktree(candidate_inputs: dict[str, Path]) -> None:
    (candidate_inputs["repository"] / "tracked.txt").write_text(
        "changed after commit\n", encoding="utf-8"
    )

    with pytest.raises(ValueError, match="worktree must be clean"):
        build_candidate_manifest(version=RELEASE_VERSION, **candidate_inputs)


@pytest.mark.parametrize("version", ["1.1", "v1.1.0", "1.1.1", "1.1.0+local"])
def test_manifest_rejects_non_release_version(
    candidate_inputs: dict[str, Path], version: str
) -> None:
    with pytest.raises(ValueError, match="version must be 1.1.0"):
        build_candidate_manifest(version=version, **candidate_inputs)


def test_manifest_rejects_wrong_artifact_names(candidate_inputs: dict[str, Path]) -> None:
    wrong_artifact = candidate_inputs["artifact"].with_name("renamed.jar")
    wrong_artifact.write_bytes(candidate_inputs["artifact"].read_bytes())

    with pytest.raises(ValueError, match="artifact filename"):
        build_candidate_manifest(
            version=RELEASE_VERSION,
            **{**candidate_inputs, "artifact": wrong_artifact},
        )


def test_validator_rejects_missing_required_key(candidate_inputs: dict[str, Path]) -> None:
    manifest = build_candidate_manifest(version=RELEASE_VERSION, **candidate_inputs)
    manifest.pop("paper")

    with pytest.raises(ValueError, match="required keys"):
        validate_candidate_manifest(manifest)


@pytest.mark.parametrize(
    ("section", "field"),
    [("artifact", "sha256"), ("paper", "sha256"), ("sbom", "sha256"), ("soak_evidence", "sha256")],
)
def test_validator_rejects_non_hex_hashes(
    candidate_inputs: dict[str, Path], section: str, field: str
) -> None:
    manifest = copy.deepcopy(build_candidate_manifest(version=RELEASE_VERSION, **candidate_inputs))
    manifest[section][field] = "not-a-sha256"

    with pytest.raises(ValueError, match="SHA-256"):
        validate_candidate_manifest(manifest)


def test_manifest_serialization_is_sorted_and_stable(
    candidate_inputs: dict[str, Path], tmp_path: Path
) -> None:
    manifest = build_candidate_manifest(version=RELEASE_VERSION, **candidate_inputs)
    output = tmp_path / "candidate-manifest.json"

    write_candidate_manifest(output, manifest)

    assert (
        output.read_text(encoding="utf-8") == json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    )
