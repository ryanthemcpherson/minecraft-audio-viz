"""Create the deterministic MCAV Paper 1.1.0 candidate manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess  # nosec B404
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

RELEASE_VERSION = "1.1.0"
JAVA_RELEASE = 25
PAPER_API_COORDINATE = "io.papermc.paper:paper-api:26.2.build.112-stable"
DEFAULT_PAPER_MANIFEST = Path("scripts/release/paper_26_2_manifest.json")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
GIT_SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")

TOP_LEVEL_KEYS = {
    "artifact",
    "build_timestamp",
    "commit_sha",
    "java_release",
    "paper",
    "sbom",
    "schema_version",
    "soak_evidence",
    "version",
}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _git(repository: Path, *arguments: str) -> str:
    git_executable = shutil.which("git")
    if git_executable is None:
        raise ValueError("git executable was not found")
    result = subprocess.run(  # nosec B603
        [git_executable, "-C", str(repository), *arguments],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise ValueError(f"git {' '.join(arguments)} failed")
    return result.stdout.strip()


def _require_exact_keys(value: Mapping[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        raise ValueError(f"{label} required keys do not match the release schema")


def _require_sha256(value: Any, label: str) -> None:
    if not isinstance(value, str) or SHA256_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{label} must be a lowercase hexadecimal SHA-256")


def _require_mapping(value: Any, label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{label} must be an object")
    return value


def validate_candidate_manifest(manifest: Mapping[str, Any]) -> None:
    """Validate the closed candidate-manifest schema and release identity."""

    _require_exact_keys(manifest, TOP_LEVEL_KEYS, "manifest")
    if manifest["schema_version"] != 1:
        raise ValueError("schema_version must be 1")
    if manifest["version"] != RELEASE_VERSION:
        raise ValueError(f"version must be {RELEASE_VERSION}")
    if manifest["java_release"] != JAVA_RELEASE:
        raise ValueError(f"java_release must be {JAVA_RELEASE}")

    commit_sha = manifest["commit_sha"]
    if not isinstance(commit_sha, str) or GIT_SHA_PATTERN.fullmatch(commit_sha) is None:
        raise ValueError("commit_sha must be a lowercase 40-character Git SHA")

    artifact = _require_mapping(manifest["artifact"], "artifact")
    _require_exact_keys(artifact, {"file", "sha256"}, "artifact")
    if artifact["file"] != f"mcav-paper-{RELEASE_VERSION}.jar":
        raise ValueError("artifact filename does not match the release version")
    _require_sha256(artifact["sha256"], "artifact sha256")

    sbom = _require_mapping(manifest["sbom"], "sbom")
    _require_exact_keys(sbom, {"file", "sha256"}, "sbom")
    if sbom["file"] != f"mcav-paper-{RELEASE_VERSION}.cdx.json":
        raise ValueError("SBOM filename does not match the release version")
    _require_sha256(sbom["sha256"], "SBOM sha256")

    paper = _require_mapping(manifest["paper"], "paper")
    _require_exact_keys(
        paper,
        {"api_coordinate", "build", "file", "minecraft_version", "sha256"},
        "paper",
    )
    expected_paper = {
        "api_coordinate": PAPER_API_COORDINATE,
        "build": 112,
        "file": "paper-26.2-112.jar",
        "minecraft_version": "26.2",
    }
    for key, expected_value in expected_paper.items():
        if paper[key] != expected_value:
            raise ValueError(f"paper {key} does not match the release coordinate")
    _require_sha256(paper["sha256"], "Paper sha256")

    build_timestamp = _require_mapping(manifest["build_timestamp"], "build_timestamp")
    _require_exact_keys(
        build_timestamp,
        {"source", "source_date_epoch"},
        "build_timestamp",
    )
    if build_timestamp["source"] != "git_commit_timestamp":
        raise ValueError("build timestamp source must be git_commit_timestamp")
    source_date_epoch = build_timestamp["source_date_epoch"]
    if (
        not isinstance(source_date_epoch, int)
        or isinstance(source_date_epoch, bool)
        or source_date_epoch <= 0
    ):
        raise ValueError("source_date_epoch must be a positive integer")

    soak_evidence = _require_mapping(manifest["soak_evidence"], "soak_evidence")
    _require_exact_keys(soak_evidence, {"file", "sha256"}, "soak_evidence")
    soak_file = soak_evidence["file"]
    if not isinstance(soak_file, str) or not soak_file:
        raise ValueError("soak evidence filename must be present")
    _require_sha256(soak_evidence["sha256"], "soak evidence sha256")


def build_candidate_manifest(
    *,
    version: str,
    artifact: Path,
    sbom: Path,
    soak_evidence: Path,
    paper_manifest: Path,
    repository: Path,
) -> dict[str, Any]:
    """Build a manifest from explicit files and immutable Git metadata."""

    if version != RELEASE_VERSION:
        raise ValueError(f"version must be {RELEASE_VERSION}")
    expected_artifact = f"mcav-paper-{RELEASE_VERSION}.jar"
    if artifact.name != expected_artifact:
        raise ValueError(f"artifact filename must be {expected_artifact}")
    expected_sbom = f"mcav-paper-{RELEASE_VERSION}.cdx.json"
    if sbom.name != expected_sbom:
        raise ValueError(f"SBOM filename must be {expected_sbom}")

    required_files = {
        "artifact": artifact,
        "SBOM": sbom,
        "soak evidence": soak_evidence,
        "Paper manifest": paper_manifest,
    }
    for label, path in required_files.items():
        if not path.is_file():
            raise ValueError(f"{label} file does not exist: {path}")

    repository = repository.resolve()
    git_root = Path(_git(repository, "rev-parse", "--show-toplevel")).resolve()
    if git_root != repository:
        raise ValueError("repository must be the Git worktree root")
    if _git(repository, "status", "--porcelain=v1", "--untracked-files=all"):
        raise ValueError("release worktree must be clean")

    paper_payload = json.loads(paper_manifest.read_text(encoding="utf-8"))
    if not isinstance(paper_payload, dict):
        raise ValueError("Paper manifest must contain an object")
    if (
        paper_payload.get("project") != "paper"
        or paper_payload.get("minecraftVersion") != "26.2"
        or paper_payload.get("build") != 112
        or paper_payload.get("file") != "paper-26.2-112.jar"
    ):
        raise ValueError("Paper manifest does not match Paper 26.2 build 112")
    _require_sha256(paper_payload.get("sha256"), "Paper manifest sha256")

    manifest: dict[str, Any] = {
        "artifact": {"file": artifact.name, "sha256": _sha256(artifact)},
        "build_timestamp": {
            "source": "git_commit_timestamp",
            "source_date_epoch": int(_git(repository, "show", "-s", "--format=%ct", "HEAD")),
        },
        "commit_sha": _git(repository, "rev-parse", "HEAD"),
        "java_release": JAVA_RELEASE,
        "paper": {
            "api_coordinate": PAPER_API_COORDINATE,
            "build": paper_payload["build"],
            "file": paper_payload["file"],
            "minecraft_version": paper_payload["minecraftVersion"],
            "sha256": paper_payload["sha256"],
        },
        "sbom": {"file": sbom.name, "sha256": _sha256(sbom)},
        "schema_version": 1,
        "soak_evidence": {
            "file": soak_evidence.name,
            "sha256": _sha256(soak_evidence),
        },
        "version": version,
    }
    validate_candidate_manifest(manifest)
    return manifest


def write_candidate_manifest(path: Path, manifest: Mapping[str, Any]) -> None:
    """Atomically write canonical candidate JSON."""

    validate_candidate_manifest(manifest)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_suffix(f"{path.suffix}.part")
    try:
        temporary_path.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def _parse_arguments(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--artifact", required=True, type=Path)
    parser.add_argument("--sbom", required=True, type=Path)
    parser.add_argument("--soak-evidence", required=True, type=Path)
    parser.add_argument("--paper-manifest", type=Path, default=DEFAULT_PAPER_MANIFEST)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args(argv)


def execute_cli(argv: Sequence[str] | None = None) -> int:
    arguments = _parse_arguments(argv)
    manifest = build_candidate_manifest(
        version=arguments.version,
        artifact=arguments.artifact.resolve(),
        sbom=arguments.sbom.resolve(),
        soak_evidence=arguments.soak_evidence.resolve(),
        paper_manifest=arguments.paper_manifest.resolve(),
        repository=arguments.repository.resolve(),
    )
    write_candidate_manifest(arguments.output.resolve(), manifest)
    print(f"Wrote candidate manifest: {arguments.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(execute_cli())
