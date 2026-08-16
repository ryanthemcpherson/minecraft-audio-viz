"""Contract tests for the immutable Paper candidate workflow."""

from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "paper-candidate.yml"


def test_staged_candidate_is_installed_before_probe_build() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")

    staging_step = workflow.index("- name: Stage JAR, SBOM, checksums, and manifest")
    install_step = workflow.index(
        "org.apache.maven.plugins:maven-install-plugin:3.1.1:install-file"
    )
    probe_step = workflow.index("- name: Build integration probe")

    assert staging_step < install_step < probe_step
    assert '-Dfile="$RUNNER_TEMP/paper-plugin-candidate-1.1.0/mcav-paper-1.1.0.jar"' in workflow
    assert "-DpomFile=minecraft_plugin/pom.xml" in workflow


def test_reproducible_timestamp_tracks_repository_root_commit() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")

    assert "git rev-list --max-parents=0 HEAD" in workflow
    assert 'git show -s --format=%ct "$source_commit_sha"' in workflow
    assert "git log -1 --format=%ct -- minecraft_plugin" not in workflow
    assert "git show -s --format=%ct HEAD" not in workflow
