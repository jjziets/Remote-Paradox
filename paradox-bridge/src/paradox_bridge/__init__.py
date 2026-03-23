"""Paradox Alarm Bridge — local FastAPI service for Paradox SP6000."""

from importlib.metadata import version as _pkg_version

try:
    __version__ = _pkg_version("paradox-bridge")
except Exception:
    __version__ = "0.0.0"
