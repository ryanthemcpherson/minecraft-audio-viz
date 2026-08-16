from __future__ import annotations

import json
from pathlib import Path

import scripts.release.paper_e2e as paper_e2e
from scripts.release.paper_e2e import REQUIRED_CHECKS, execute_cli

EXPECTED_CHECKS = {
    "plugin_loaded",
    "secret_generated",
    "bad_secret_rejected",
    "authenticated",
    "zone_loaded",
    "pool_initialized",
    "display_entities_applied",
    "malformed_frame_rejected",
    "oversize_frame_rejected",
    "reconnected",
    "disconnect_cleanup",
    "world_unload_cleanup",
    "restart_has_no_orphans",
    "port_conflict_safe",
    "clean_machine_install",
    "uninstall_cleanup",
    "optional_integrations_absent_safe",
}


def _arguments(plugin: Path, report: Path) -> list[str]:
    return ["--plugin", str(plugin), "--report", str(report)]


def test_required_checks_match_release_contract() -> None:
    assert REQUIRED_CHECKS == EXPECTED_CHECKS


def test_cli_passes_only_when_every_required_check_is_true(
    tmp_path: Path,
    capsys,
) -> None:
    plugin = tmp_path / "mcav-paper-1.1.0.jar"
    report = tmp_path / "paper-e2e.json"
    plugin.write_bytes(b"plugin")

    exit_code = execute_cli(
        _arguments(plugin, report),
        scenario_runner=lambda _arguments: {key: True for key in REQUIRED_CHECKS},
    )

    assert exit_code == 0
    payload = json.loads(report.read_text(encoding="utf-8"))
    assert payload["status"] == "pass"
    assert payload["checks"] == {key: True for key in sorted(REQUIRED_CHECKS)}
    assert payload["failed_checks"] == []
    assert "PASS" in capsys.readouterr().out


def test_cli_fails_when_a_required_check_is_false(tmp_path: Path) -> None:
    plugin = tmp_path / "mcav-paper-1.1.0.jar"
    report = tmp_path / "paper-e2e.json"
    plugin.write_bytes(b"plugin")
    checks = {key: True for key in REQUIRED_CHECKS}
    checks["disconnect_cleanup"] = False

    exit_code = execute_cli(
        _arguments(plugin, report),
        scenario_runner=lambda _arguments: checks,
    )

    assert exit_code == 1
    payload = json.loads(report.read_text(encoding="utf-8"))
    assert payload["status"] == "fail"
    assert payload["failed_checks"] == ["disconnect_cleanup"]


def test_cli_fails_when_a_required_check_is_missing(tmp_path: Path) -> None:
    plugin = tmp_path / "mcav-paper-1.1.0.jar"
    report = tmp_path / "paper-e2e.json"
    plugin.write_bytes(b"plugin")
    checks = {key: True for key in REQUIRED_CHECKS - {"world_unload_cleanup"}}

    exit_code = execute_cli(
        _arguments(plugin, report),
        scenario_runner=lambda _arguments: checks,
    )

    assert exit_code == 1
    payload = json.loads(report.read_text(encoding="utf-8"))
    assert payload["checks"]["world_unload_cleanup"] is False
    assert payload["failed_checks"] == ["world_unload_cleanup"]


def test_report_contains_no_scenario_only_values(tmp_path: Path) -> None:
    plugin = tmp_path / "mcav-paper-1.1.0.jar"
    report = tmp_path / "paper-e2e.json"
    plugin.write_bytes(b"plugin")
    secret = "scenario-secret-must-not-escape"

    def scenario_runner(_arguments) -> dict[str, bool]:
        _ = secret
        return {key: True for key in REQUIRED_CHECKS}

    assert execute_cli(_arguments(plugin, report), scenario_runner=scenario_runner) == 0
    assert secret not in report.read_text(encoding="utf-8")


def test_java_resolver_materializes_a_local_runtime_for_loopback(
    tmp_path: Path, monkeypatch
) -> None:
    plugin = tmp_path / "mcav-paper-1.1.0.jar"
    materialized_java = tmp_path / "java-cache" / "bin" / "java"
    plugin.write_bytes(b"plugin")
    materialized_java.parent.mkdir(parents=True)
    materialized_java.write_bytes(b"java")
    arguments = paper_e2e.E2EArguments(
        plugin=plugin,
        report=tmp_path / "report.json",
        manifest=tmp_path / "manifest.json",
        paper_cache=tmp_path / "paper-cache",
        probe=tmp_path / "probe.jar",
        java="missing-java",
        java_container_image=paper_e2e.DEFAULT_JAVA_CONTAINER_IMAGE,
    )

    monkeypatch.setattr(
        paper_e2e,
        "_java_25_is_available",
        lambda executable: executable == str(materialized_java),
    )
    monkeypatch.setattr(
        paper_e2e,
        "_materialize_java_25",
        lambda _image, _cache: materialized_java,
    )

    assert paper_e2e._resolve_java_25(arguments) == str(materialized_java)
