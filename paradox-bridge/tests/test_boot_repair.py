"""Tests for deploy/boot-repair.sh."""

from __future__ import annotations

import os
import stat
import subprocess
import textwrap
from pathlib import Path


REPO = Path(__file__).resolve().parents[1]
SCRIPT = REPO / "deploy" / "boot-repair.sh"


def _write_fake(tmp: Path, name: str, body: str) -> Path:
    path = tmp / name
    path.write_text(textwrap.dedent(body).lstrip("\n"))
    path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return path


def _run(tmp_path: Path, install_dir: Path, bin_dir: Path, extra_env: dict[str, str] | None = None):
    env = os.environ.copy()
    env.update(
        {
            "PATH": f"{bin_dir}:{env.get('PATH', '/usr/bin:/bin')}",
            "INSTALL_DIR": str(install_dir),
            "STATUS_FILE": str(install_dir / "update_status.json"),
            "LOCK_FILE": str(tmp_path / "boot-repair.lock"),
            "BOOT_REPAIR_SKIP_APT": "1",
            "BOOT_REPAIR_SLEEP_AFTER_RESTART": "0",
            "PYTHON_BIN": "python3",
        }
    )
    if extra_env:
        env.update(extra_env)
    return subprocess.run(
        ["bash", str(SCRIPT)],
        text=True,
        capture_output=True,
        check=False,
        env=env,
    )


def test_boot_repair_exits_when_bridge_is_healthy(tmp_path: Path) -> None:
    install_dir = tmp_path / "opt"
    bin_dir = tmp_path / "bin"
    install_dir.mkdir()
    bin_dir.mkdir()
    calls = tmp_path / "calls"

    _write_fake(bin_dir, "curl", "exit 0\n")
    _write_fake(bin_dir, "systemctl", f"echo systemctl \"$@\" >> {calls}\nexit 0\n")
    _write_fake(bin_dir, "logger", "exit 0\n")

    result = _run(tmp_path, install_dir, bin_dir)

    assert result.returncode == 0
    assert "bridge is healthy" in result.stdout
    assert not calls.exists()


def test_boot_repair_restarts_then_stages_and_applies_when_unhealthy(tmp_path: Path) -> None:
    install_dir = tmp_path / "opt"
    scripts_dir = install_dir / "scripts"
    bin_dir = tmp_path / "bin"
    scripts_dir.mkdir(parents=True)
    bin_dir.mkdir()
    calls = tmp_path / "calls"

    _write_fake(bin_dir, "curl", "exit 1\n")
    _write_fake(bin_dir, "logger", "exit 0\n")
    _write_fake(bin_dir, "systemctl", f"echo systemctl \"$@\" >> {calls}\nexit 0\n")
    updater = scripts_dir / "updater.py"
    updater.write_text(
        "import json, pathlib\n"
        f"path = pathlib.Path({str(install_dir / 'update_status.json')!r})\n"
        "path.write_text(json.dumps({'pending': True}))\n"
    )
    apply_update = scripts_dir / "apply_update.sh"
    apply_update.write_text(f"#!/usr/bin/env bash\necho apply_update >> {calls}\n")
    apply_update.chmod(apply_update.stat().st_mode | stat.S_IEXEC)

    result = _run(tmp_path, install_dir, bin_dir)

    assert result.returncode == 1
    call_text = calls.read_text()
    assert "systemctl restart paradox-bridge" in call_text
    assert "apply_update" in call_text
    assert "bridge still unhealthy" in result.stdout
