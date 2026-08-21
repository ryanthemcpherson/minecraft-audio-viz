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
