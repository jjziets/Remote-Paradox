"""Paradox Alarm Bridge — local FastAPI service for Paradox SP6000."""

import re as _re
from pathlib import Path as _Path


def _read_version() -> str:
    for candidate in [
        _Path(__file__).resolve().parent.parent.parent / "pyproject.toml",
        _Path("/opt/paradox-bridge/pyproject.toml"),
    ]:
        if candidate.exists():
            match = _re.search(r'^version\s*=\s*"([^"]+)"', candidate.read_text(), _re.MULTILINE)
            if match:
                return match.group(1)
    try:
        from importlib.metadata import version
        return version("paradox-bridge")
    except Exception:
        return "0.0.0"


__version__ = _read_version()
