"""Contract tests for Pterodactyl assets in the immutable Paper release."""

from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "release-plugin.yml"


def test_plugin_release_packages_the_approved_candidate_for_pterodactyl() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")

    assert "  build-pterodactyl:" in workflow
    assert "    needs: [verify-candidate]" in workflow
    assert '--plugin-jar "candidate/mcav-paper-1.1.0.jar"' in workflow
    assert "name: pterodactyl-bundle" in workflow


def test_plugin_release_publishes_the_bundle_and_checksum() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")

    assert "    needs: [verify-candidate, build-pterodactyl]" in workflow
    assert "pterodactyl/*.zip" in workflow
    assert "pterodactyl/*.zip.sha256" in workflow


def test_existing_tag_recovery_does_not_require_the_commit_to_remain_main() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")

    for step_name, next_step_name in (
        (
            "- name: Require exact current main and recoverable release tag",
            "- name: Require successful candidate workflow provenance",
        ),
        (
            "- name: Ensure immutable tag targets approved commit",
            "- name: Publish byte-identical candidate files",
        ),
    ):
        step = workflow.split(step_name, 1)[1].split(next_step_name, 1)[0]
        existing_tag_branch = step.index('if [[ "$tag_status" -eq 0 ]]; then')
        missing_tag_branch = step.index('elif [[ "$tag_status" -eq 2 ]]; then')
        current_main_requirement = step.index('test "$COMMIT_SHA" = "$(git rev-parse origin/main)"')

        assert existing_tag_branch < missing_tag_branch < current_main_requirement
