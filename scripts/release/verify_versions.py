#!/usr/bin/env python3
"""Verify MCAV product versions and the immutable release artifact contract."""

from __future__ import annotations

import argparse
import json
import re
import sys
import tomllib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Sequence

PRODUCT_FIELDS = {
    "schema_version",
    "product",
    "version",
    "runtime_api",
    "protocol_version",
    "compatibility",
}
COMPATIBILITY_FIELDS = {"minecraft", "paper_api", "java"}
ARTIFACT_CONTRACT_FIELDS = {"schema_version", "artifacts"}
ARTIFACT_FIELDS = {"id", "filename"}
REQUIRED_ARTIFACT_IDS = {
    "paper-plugin",
    "runtime-linux-x86-64",
    "runtime-linux-aarch64",
    "runtime-windows-x86-64",
    "runtime-manifest",
    "runtime-manifest-signature",
    "dj-client-nsis",
    "dj-client-msi",
    "fabric-mod",
    "checksums",
    "checksums-signature",
}
STABLE_VERSION = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
RELEASE_VERSION = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-rc\.([1-9][0-9]*))?$"
)
ARTIFACT_ID = re.compile(r"^[a-z][a-z0-9-]{0,63}$")


class ContractError(ValueError):
    """Raised when release metadata violates the frozen product contract."""


@dataclass(frozen=True)
class ProductContract:
    version: str
    runtime_api: int
    protocol_version: str
    minecraft_version: str
    paper_api_version: str
    java_version: int


@dataclass(frozen=True)
class Artifact:
    identifier: str
    filename: str


@dataclass(frozen=True)
class ArtifactContract:
    artifacts: tuple[Artifact, ...]


@dataclass(frozen=True)
class VerificationResult:
    product_version: str
    requested_version: str
    runtime_api: int
    protocol_version: str
    minecraft_version: str
    paper_api_version: str
    java_version: int
    artifacts: tuple[str, ...]


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ContractError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def _require_file(path: Path, root: Path | None = None) -> Path:
    if not path.is_file():
        display = path.relative_to(root).as_posix() if root is not None else str(path)
        raise ContractError(f"missing ecosystem metadata: {display}")
    return path


def _read_json(path: Path, root: Path | None = None) -> dict[str, Any]:
    _require_file(path, root)
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ContractError(f"invalid JSON metadata: {path}") from error
    if not isinstance(value, dict):
        raise ContractError(f"JSON metadata must be an object: {path}")
    return value


def _read_toml(path: Path, root: Path) -> dict[str, Any]:
    _require_file(path, root)
    try:
        value = tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, tomllib.TOMLDecodeError) as error:
        raise ContractError(
            f"invalid TOML metadata: {path.relative_to(root).as_posix()}"
        ) from error
    return value


def _exact_fields(value: dict[str, Any], expected: set[str], label: str) -> None:
    unknown = sorted(value.keys() - expected)
    missing = sorted(expected - value.keys())
    if unknown:
        raise ContractError(f"{label} has unknown fields: {', '.join(unknown)}")
    if missing:
        raise ContractError(f"{label} is missing fields: {', '.join(missing)}")


def _required_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 160:
        raise ContractError(f"{label} must be a non-empty bounded string")
    return value


def _required_integer(value: Any, label: str, minimum: int = 1) -> int:
    if type(value) is not int or value < minimum or value > 1_000_000:
        raise ContractError(f"{label} must be an integer from {minimum} to 1000000")
    return value


def load_product_contract(path: Path) -> ProductContract:
    value = _read_json(path)
    _exact_fields(value, PRODUCT_FIELDS, "product contract")
    if value["schema_version"] != 1:
        raise ContractError("product contract schema_version must be 1")
    if value["product"] != "MCAV":
        raise ContractError("product contract product must be MCAV")
    version = _required_string(value["version"], "product contract version")
    if STABLE_VERSION.fullmatch(version) is None:
        raise ContractError("product contract version must be stable semantic versioning")
    runtime_api = _required_integer(value["runtime_api"], "runtime_api")
    protocol_version = _required_string(value["protocol_version"], "protocol_version")
    if STABLE_VERSION.fullmatch(protocol_version) is None:
        raise ContractError("protocol_version must be stable semantic versioning")
    compatibility = value["compatibility"]
    if not isinstance(compatibility, dict):
        raise ContractError("product compatibility must be an object")
    _exact_fields(compatibility, COMPATIBILITY_FIELDS, "product compatibility")
    minecraft = _required_string(compatibility["minecraft"], "minecraft compatibility")
    paper_api = _required_string(compatibility["paper_api"], "Paper API compatibility")
    java = _required_integer(compatibility["java"], "Java compatibility")
    return ProductContract(version, runtime_api, protocol_version, minecraft, paper_api, java)


def load_artifact_contract(path: Path) -> ArtifactContract:
    value = _read_json(path)
    _exact_fields(value, ARTIFACT_CONTRACT_FIELDS, "artifact contract")
    if value["schema_version"] != 1:
        raise ContractError("artifact contract schema_version must be 1")
    raw_artifacts = value["artifacts"]
    if not isinstance(raw_artifacts, list):
        raise ContractError("artifact contract artifacts must be an array")
    artifacts: list[Artifact] = []
    identifiers: set[str] = set()
    for index, raw in enumerate(raw_artifacts):
        if not isinstance(raw, dict):
            raise ContractError(f"artifact {index} must be an object")
        _exact_fields(raw, ARTIFACT_FIELDS, f"artifact {index}")
        identifier = _required_string(raw["id"], f"artifact {index} id")
        filename = _required_string(raw["filename"], f"artifact {index} filename")
        if ARTIFACT_ID.fullmatch(identifier) is None:
            raise ContractError(f"artifact {index} id is invalid")
        if identifier in identifiers:
            raise ContractError(f"duplicate artifact id: {identifier}")
        identifiers.add(identifier)
        if filename.count("{version}") > 1:
            raise ContractError(f"artifact {identifier} has multiple version tokens")
        artifacts.append(Artifact(identifier, filename))
    if identifiers != REQUIRED_ARTIFACT_IDS:
        missing = sorted(REQUIRED_ARTIFACT_IDS - identifiers)
        unknown = sorted(identifiers - REQUIRED_ARTIFACT_IDS)
        details = []
        if missing:
            details.append("missing=" + ",".join(missing))
        if unknown:
            details.append("unknown=" + ",".join(unknown))
        raise ContractError("artifact ids do not match the release contract: " + "; ".join(details))
    return ArtifactContract(tuple(artifacts))


def _validate_requested_version(requested: str, product_version: str) -> None:
    match = RELEASE_VERSION.fullmatch(requested)
    if match is None or requested.split("-", 1)[0] != product_version:
        raise ContractError(
            f"release version must be {product_version} or {product_version}-rc.N with N >= 1"
        )


def expected_artifact_names(contract: ArtifactContract, version: str) -> tuple[str, ...]:
    names: list[str] = []
    for artifact in contract.artifacts:
        name = artifact.filename.replace("{version}", version)
        if (
            not name
            or len(name) > 200
            or name in {".", ".."}
            or "/" in name
            or "\\" in name
            or "{" in name
            or "}" in name
        ):
            raise ContractError(f"artifact {artifact.identifier} renders an invalid filename")
        names.append(name)
    folded = [name.casefold() for name in names]
    if len(folded) != len(set(folded)):
        raise ContractError("duplicate artifact filename after version rendering")
    return tuple(names)


def verify_artifact_inventory(
    contract: ArtifactContract,
    version: str,
    observed: Sequence[str],
) -> None:
    expected = set(expected_artifact_names(contract, version))
    actual = set(observed)
    if len(actual) != len(observed):
        raise ContractError("artifact inventory contains duplicate names")
    undeclared = sorted(actual - expected)
    missing = sorted(expected - actual)
    if undeclared:
        raise ContractError("undeclared artifacts: " + ", ".join(undeclared))
    if missing:
        raise ContractError("missing artifacts: " + ", ".join(missing))


def _assert_version(actual: Any, expected: str, relative_path: str) -> None:
    if actual != expected:
        raise ContractError(f"{relative_path} version must be {expected}, got {actual!r}")


def _package_json_version(root: Path, relative: str, expected: str) -> None:
    value = _read_json(root / relative, root)
    _assert_version(value.get("version"), expected, relative)


def _package_lock_version(root: Path, relative: str, expected: str) -> None:
    value = _read_json(root / relative, root)
    _assert_version(value.get("version"), expected, relative)
    packages = value.get("packages")
    if not isinstance(packages, dict) or not isinstance(packages.get(""), dict):
        raise ContractError(f"{relative} must declare the root package")
    _assert_version(packages[""].get("version"), expected, relative)


def _gradle_version(root: Path, expected: str) -> None:
    relative = "minecraft_mod/build.gradle"
    path = _require_file(root / relative, root)
    matches = re.findall(
        r"^\s*version\s*=\s*['\"]([^'\"]+)['\"]\s*$",
        path.read_text(encoding="utf-8"),
        flags=re.MULTILINE,
    )
    if len(matches) != 1:
        raise ContractError(f"{relative} must declare exactly one project version")
    _assert_version(matches[0], expected, relative)


def _properties(path: Path, root: Path) -> dict[str, str]:
    _require_file(path, root)
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "=" not in line:
            raise ContractError(f"invalid properties metadata: {path.relative_to(root).as_posix()}")
        key, value = (part.strip() for part in line.split("=", 1))
        if not key or key in values:
            raise ContractError(f"duplicate or empty properties key: {key}")
        values[key] = value
    return values


def _maven_metadata(root: Path, contract: ProductContract) -> None:
    relative = "minecraft_plugin/pom.xml"
    path = _require_file(root / relative, root)
    if path.stat().st_size > 1024 * 1024:
        raise ContractError(f"{relative} exceeds the metadata size limit")
    source = path.read_text(encoding="utf-8")
    if "<!DOCTYPE" in source.upper() or "<!ENTITY" in source.upper():
        raise ContractError(f"{relative} must not contain DTD or entity declarations")

    def field(pattern: str, label: str) -> str:
        matches = re.findall(pattern, source, flags=re.DOTALL)
        if len(matches) != 1:
            raise ContractError(f"{relative} must declare exactly one {label}")
        return matches[0].strip()

    project_version = field(
        r"<artifactId>\s*audioviz-plugin\s*</artifactId>\s*<version>\s*([^<]+)\s*</version>",
        "project version",
    )
    _assert_version(project_version, contract.version, relative)
    for name, expected in (
        ("paper.version", contract.paper_api_version),
        ("java.version", str(contract.java_version)),
        ("runtime.api", str(contract.runtime_api)),
    ):
        escaped = re.escape(name)
        actual = field(rf"<{escaped}>\s*([^<]+)\s*</{escaped}>", name)
        _assert_version(actual, expected, relative)


def _cargo_metadata(root: Path, expected: str) -> None:
    manifest_relative = "dj_client/src-tauri/Cargo.toml"
    manifest = _read_toml(root / manifest_relative, root)
    package = manifest.get("package")
    if not isinstance(package, dict):
        raise ContractError(f"{manifest_relative} is missing package metadata")
    _assert_version(package.get("version"), expected, manifest_relative)

    lock_relative = "dj_client/src-tauri/Cargo.lock"
    lock = _read_toml(root / lock_relative, root)
    packages = lock.get("package")
    matches = (
        [item for item in packages if isinstance(item, dict) and item.get("name") == "dj-client"]
        if isinstance(packages, list)
        else []
    )
    if len(matches) != 1:
        raise ContractError(f"{lock_relative} must contain exactly one dj-client package")
    _assert_version(matches[0].get("version"), expected, lock_relative)


def _uv_lock_metadata(root: Path, expected: str) -> None:
    relative = "uv.lock"
    lock = _read_toml(root / relative, root)
    packages = lock.get("package")
    matches = (
        [
            item
            for item in packages
            if isinstance(item, dict) and item.get("name") == "mcav-vj-server"
        ]
        if isinstance(packages, list)
        else []
    )
    if len(matches) != 1:
        raise ContractError(f"{relative} must contain exactly one mcav-vj-server package")
    source = matches[0].get("source")
    if not isinstance(source, dict) or source.get("editable") != "vj_server":
        raise ContractError(f"{relative} mcav-vj-server must be editable from vj_server")
    _assert_version(matches[0].get("version"), expected, relative)


def _tauri_metadata(root: Path, expected: str) -> None:
    relative = "dj_client/src-tauri/tauri.conf.json"
    value = _read_json(root / relative, root)
    _assert_version(value.get("version"), expected, relative)
    bundle = value.get("bundle")
    if not isinstance(bundle, dict) or bundle.get("createUpdaterArtifacts") is not False:
        raise ContractError(f"{relative} bundle.createUpdaterArtifacts must be false")


def _verify_ecosystems(root: Path, contract: ProductContract) -> None:
    _package_json_version(root, "package.json", contract.version)
    _package_lock_version(root, "package-lock.json", contract.version)
    _package_json_version(root, "dj_client/package.json", contract.version)
    _package_lock_version(root, "dj_client/package-lock.json", contract.version)

    vj_relative = "vj_server/pyproject.toml"
    vj_project = _read_toml(root / vj_relative, root).get("project")
    if not isinstance(vj_project, dict):
        raise ContractError(f"{vj_relative} is missing project metadata")
    _assert_version(vj_project.get("version"), contract.version, vj_relative)
    _uv_lock_metadata(root, contract.version)

    _maven_metadata(root, contract)
    _gradle_version(root, contract.version)
    mod_properties = _properties(root / "minecraft_mod/gradle.properties", root)
    _assert_version(
        mod_properties.get("minecraft_version"),
        contract.minecraft_version,
        "minecraft_mod/gradle.properties minecraft_version",
    )
    _cargo_metadata(root, contract.version)
    _tauri_metadata(root, contract.version)

    protocol_relative = "protocol/schemas/index.json"
    protocol = _read_json(root / protocol_relative, root)
    _assert_version(
        protocol.get("protocol_version"),
        contract.protocol_version,
        f"{protocol_relative} protocol_version",
    )


def verify_repository(
    root: Path,
    version: str,
    observed_artifacts: Sequence[str] | None = None,
) -> VerificationResult:
    root = root.resolve()
    product = load_product_contract(root / "release/product-version.json")
    artifacts = load_artifact_contract(root / "release/artifacts-v1.json")
    _validate_requested_version(version, product.version)
    _verify_ecosystems(root, product)
    names = expected_artifact_names(artifacts, version)
    if observed_artifacts is not None:
        verify_artifact_inventory(artifacts, version, observed_artifacts)
    return VerificationResult(
        product.version,
        version,
        product.runtime_api,
        product.protocol_version,
        product.minecraft_version,
        product.paper_api_version,
        product.java_version,
        names,
    )


def _result_json(result: VerificationResult) -> str:
    return json.dumps(
        {
            "artifacts": list(result.artifacts),
            "java": result.java_version,
            "minecraft": result.minecraft_version,
            "paper_api": result.paper_api_version,
            "product_version": result.product_version,
            "protocol_version": result.protocol_version,
            "requested_version": result.requested_version,
            "runtime_api": result.runtime_api,
            "status": "verified",
        },
        separators=(",", ":"),
        sort_keys=True,
    )


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--artifact", action="append", default=None)
    arguments = parser.parse_args(argv)
    try:
        result = verify_repository(arguments.root, arguments.version, arguments.artifact)
    except ContractError as error:
        print(f"release contract verification failed: {error}", file=sys.stderr)
        return 1
    print(_result_json(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
