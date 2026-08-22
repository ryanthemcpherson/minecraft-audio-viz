"""Executable contracts for the privileged browser preview supply chain."""

from __future__ import annotations

import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PREVIEW_ROOT = PROJECT_ROOT / "preview_tool" / "frontend"


class PreviewDocumentParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.script_sources: list[str] = []
        self.inline_script_fragments: list[str] = []
        self.inline_style_attributes: list[str] = []
        self.csp: str | None = None
        self._inside_inline_script = False

    def handle_starttag(
        self,
        tag: str,
        attrs: list[tuple[str, str | None]],
    ) -> None:
        attributes = dict(attrs)
        if tag == "script":
            source = attributes.get("src")
            if source is None:
                self._inside_inline_script = True
            else:
                self.script_sources.append(source)
        if "style" in attributes:
            self.inline_style_attributes.append(attributes["style"] or "")
        if (
            tag == "meta"
            and (attributes.get("http-equiv") or "").lower() == "content-security-policy"
        ):
            self.csp = attributes.get("content")

    def handle_endtag(self, tag: str) -> None:
        if tag == "script":
            self._inside_inline_script = False

    def handle_data(self, data: str) -> None:
        if self._inside_inline_script and data.strip():
            self.inline_script_fragments.append(data)


def _preview_document() -> tuple[str, PreviewDocumentParser]:
    document = (PREVIEW_ROOT / "index.html").read_text(encoding="utf-8")
    parser = PreviewDocumentParser()
    parser.feed(document)
    return document, parser


def _csp_directives(policy: str) -> dict[str, list[str]]:
    directives: dict[str, list[str]] = {}
    for raw_directive in policy.split(";"):
        tokens = raw_directive.split()
        if tokens:
            directives[tokens[0]] = tokens[1:]
    return directives


def test_preview_loads_only_shipped_scripts_without_runtime_injection() -> None:
    document, parser = _preview_document()

    assert "js/vendor/three-r128.min.js" in parser.script_sources
    assert "document.write" not in document
    assert parser.inline_script_fragments == []

    for source in parser.script_sources:
        parsed = urlparse(source)
        assert not parsed.scheme and not parsed.netloc, source
        dependency = PREVIEW_ROOT / parsed.path.lstrip("/")
        assert dependency.is_file(), source
        assert dependency.resolve().is_relative_to(PREVIEW_ROOT.resolve()), source


def test_preview_csp_blocks_remote_and_inline_executable_content() -> None:
    _document, parser = _preview_document()

    assert parser.csp is not None
    directives = _csp_directives(parser.csp)
    assert directives["default-src"] == ["'self'"]
    assert directives["script-src"] == ["'self'"]
    assert directives["object-src"] == ["'none'"]
    assert directives["base-uri"] == ["'none'"]
    assert directives["form-action"] == ["'self'"]
    assert set(directives["style-src"]) == {
        "'self'",
        "https://fonts.googleapis.com",
    }
    assert directives["font-src"] == ["https://fonts.gstatic.com"]
    assert set(directives["img-src"]) == {"'self'", "data:", "https:"}
    assert set(directives["connect-src"]) == {"'self'", "ws:", "wss:"}
    assert parser.inline_style_attributes == []


def test_release_verifier_requires_preview_runtime_dependencies() -> None:
    deploy_root = PROJECT_ROOT / "deploy" / "pterodactyl"
    sys.path.insert(0, str(deploy_root))
    try:
        from release_archive import REQUIRED_ENTRIES
    finally:
        sys.path.remove(str(deploy_root))

    assert {
        "mcav-vj/preview_tool/frontend/js/latency-indicator.js",
        "mcav-vj/preview_tool/frontend/js/vendor/three-r128.min.js",
    } <= REQUIRED_ENTRIES
