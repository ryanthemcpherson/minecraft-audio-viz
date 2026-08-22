from pathlib import Path
from xml.etree import ElementTree

PROJECT_ROOT = Path(__file__).resolve().parents[3]


def test_powershell_builder_selects_the_maven_final_jar() -> None:
    pom = ElementTree.parse(PROJECT_ROOT / "minecraft_plugin" / "pom.xml")
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    final_name = pom.findtext("m:build/m:finalName", namespaces=namespace)

    assert final_name is not None
    artifact_prefix = final_name.split("${", maxsplit=1)[0]
    build_script = (PROJECT_ROOT / "deploy" / "pterodactyl" / "build-release.ps1").read_text(
        encoding="utf-8"
    )

    assert f"-Filter '{artifact_prefix}*.jar'" in build_script
