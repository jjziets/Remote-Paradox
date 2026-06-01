"""Tests for bridge release updater helpers."""

import importlib.util
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "updater.py"
SPEC = importlib.util.spec_from_file_location("bridge_updater", SCRIPT)
updater = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(updater)


def test_version_tuple_handles_bridge_tags():
    assert updater.version_tuple("bridge-v1.0.12") == (1, 0, 12)
    assert updater.version_tuple("v1.2.3") == (1, 2, 3)


def test_select_latest_bridge_release_ignores_android_and_prerelease():
    releases = [
        {"tag_name": "v1.2.99"},
        {"tag_name": "bridge-v1.0.1", "draft": False, "prerelease": False},
        {"tag_name": "bridge-v1.0.3", "draft": False, "prerelease": True},
        {"tag_name": "bridge-v1.0.2", "draft": False, "prerelease": False},
    ]

    selected = updater.select_latest_bridge_release(releases)

    assert selected["tag_name"] == "bridge-v1.0.2"
