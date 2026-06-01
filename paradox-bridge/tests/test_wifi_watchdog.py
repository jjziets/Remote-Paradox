"""Tests for deploy/wifi-watchdog.sh (NetworkManager WiFi recovery)."""

from __future__ import annotations

import os
import stat
import subprocess
import textwrap
from pathlib import Path

import pytest

_REPO = Path(__file__).resolve().parents[1]
_SCRIPT = _REPO / "deploy" / "wifi-watchdog.sh"


def _write_fake(tmp: Path, name: str, body: str) -> Path:
    p = tmp / name
    p.write_text(textwrap.dedent(body).lstrip("\n"))
    p.chmod(p.stat().st_mode | stat.S_IEXEC)
    return p


def _run_watchdog(tmp_bin: Path, env: dict) -> subprocess.CompletedProcess:
    e = os.environ.copy()
    e.update(env)
    e["PATH"] = f"{tmp_bin}:{e.get('PATH', '/usr/bin:/bin')}"
    return subprocess.run(
        ["bash", str(_SCRIPT)],
        capture_output=True,
        text=True,
        env=e,
        check=False,
    )


@pytest.mark.skipif(not _SCRIPT.exists(), reason="wifi-watchdog.sh missing")
def test_watchdog_exits_quietly_when_interface_missing(tmp_path: Path) -> None:
    """No wlan0: script should exit 0 without reconnect actions."""
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _write_fake(
        bin_dir,
        "nmcli",
        """#!/bin/bash
        echo "stub nmcli should not be called for missing iface" >&2
        exit 99
        """,
    )
    _write_fake(
        bin_dir,
        "ip",
        """#!/bin/bash
        # simulate missing / down interface
        if [[ "$1" == "link" && "$2" == "show" && "$3" == "wlan0" ]]; then
          exit 1
        fi
        exit 1
        """,
    )
    env = {"WIFI_WATCHDOG_IFACE": "wlan0", "WIFI_WATCHDOG_DRY_RUN": "1"}
    r = _run_watchdog(bin_dir, env)
    assert r.returncode == 0
    assert "DRY_RUN" not in r.stdout


@pytest.mark.skipif(not _SCRIPT.exists(), reason="wifi-watchdog.sh missing")
def test_watchdog_no_action_when_connected_and_ping_ok(tmp_path: Path) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _write_fake(
        bin_dir,
        "nmcli",
        """#!/bin/bash
        if [[ "$1" == "-g" && "$2" == "GENERAL.STATE" && "$3" == "dev" && "$4" == "show" && "$5" == "wlan0" ]]; then
          echo "100 (connected)"
          exit 0
        fi
        exit 99
        """,
    )
    _write_fake(
        bin_dir,
        "ip",
        """#!/bin/bash
        if [[ "$1" == "link" && "$2" == "show" && "$3" == "wlan0" ]]; then
          exit 0
        fi
        if [[ "$1" == "-4" && "$2" == "route" && "$3" == "show" && "$4" == "default" && "$5" == "dev" && "$6" == "wlan0" ]]; then
          echo "default via 192.168.50.1 proto dhcp"
          exit 0
        fi
        exit 1
        """,
    )
    _write_fake(
        bin_dir,
        "ping",
        """#!/bin/bash
        exit 0
        """,
    )
    env = {"WIFI_WATCHDOG_IFACE": "wlan0", "WIFI_WATCHDOG_DRY_RUN": "1"}
    r = _run_watchdog(bin_dir, env)
    assert r.returncode == 0
    assert "DRY_RUN" not in r.stdout


@pytest.mark.skipif(not _SCRIPT.exists(), reason="wifi-watchdog.sh missing")
def test_watchdog_reconnect_when_not_connected(tmp_path: Path) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _write_fake(
        bin_dir,
        "nmcli",
        """#!/bin/bash
        if [[ "$1" == "-g" && "$2" == "GENERAL.STATE" && "$3" == "dev" && "$4" == "show" && "$5" == "wlan0" ]]; then
          echo "30 (disconnected)"
          exit 0
        fi
        if [[ "$1" == "radio" && "$2" == "wifi" && "$3" == "on" ]]; then exit 0; fi
        if [[ "$1" == "networking" && "$2" == "on" ]]; then exit 0; fi
        if [[ "$1" == "connection" && "$2" == "up" && "$3" == "preconfigured" ]]; then exit 0; fi
        echo "unexpected nmcli: $*" >&2
        exit 99
        """,
    )
    _write_fake(
        bin_dir,
        "ip",
        """#!/bin/bash
        if [[ "$1" == "link" && "$2" == "show" && "$3" == "wlan0" ]]; then exit 0; fi
        exit 0
        """,
    )
    _write_fake(
        bin_dir,
        "ping",
        """#!/bin/bash
        exit 0
        """,
    )
    env = {
        "WIFI_WATCHDOG_IFACE": "wlan0",
        "WIFI_WATCHDOG_NM_CONN": "preconfigured",
        "WIFI_WATCHDOG_DRY_RUN": "1",
    }
    r = _run_watchdog(bin_dir, env)
    assert r.returncode == 0
    assert "DRY_RUN:nmcli radio wifi on" in r.stdout
    assert "DRY_RUN:nmcli networking on" in r.stdout
    assert "DRY_RUN:nmcli connection up preconfigured" in r.stdout


@pytest.mark.skipif(not _SCRIPT.exists(), reason="wifi-watchdog.sh missing")
def test_watchdog_reconnect_when_ping_fails(tmp_path: Path) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _write_fake(
        bin_dir,
        "nmcli",
        """#!/bin/bash
        if [[ "$1" == "-g" && "$2" == "GENERAL.STATE" ]]; then
          echo "100 (connected)"
          exit 0
        fi
        if [[ "$1" == "radio" && "$2" == "wifi" && "$3" == "on" ]]; then exit 0; fi
        if [[ "$1" == "networking" && "$2" == "on" ]]; then exit 0; fi
        if [[ "$1" == "connection" && "$2" == "up" && "$3" == "preconfigured" ]]; then exit 0; fi
        exit 99
        """,
    )
    _write_fake(
        bin_dir,
        "ip",
        """#!/bin/bash
        if [[ "$1" == "link" && "$2" == "show" && "$3" == "wlan0" ]]; then exit 0; fi
        echo "default via 192.168.50.1 proto dhcp dev wlan0"
        exit 0
        """,
    )
    _write_fake(
        bin_dir,
        "ping",
        """#!/bin/bash
        exit 1
        """,
    )
    env = {"WIFI_WATCHDOG_IFACE": "wlan0", "WIFI_WATCHDOG_DRY_RUN": "1"}
    r = _run_watchdog(bin_dir, env)
    assert r.returncode == 0
    assert "DRY_RUN:nmcli connection up preconfigured" in r.stdout


def test_script_exists() -> None:
    assert _SCRIPT.is_file(), f"Expected {_SCRIPT}"
