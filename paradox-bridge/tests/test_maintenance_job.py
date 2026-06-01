"""Tests for scripts/maintenance_job.sh with fake package tools."""

from __future__ import annotations

import json
import os
import stat
import subprocess
import sys
import textwrap
from pathlib import Path


REPO = Path(__file__).resolve().parents[1]
SCRIPT = REPO / "scripts" / "maintenance_job.sh"


def _write_fake(bin_dir: Path, name: str, body: str) -> Path:
    path = bin_dir / name
    path.write_text(textwrap.dedent(body).lstrip("\n"))
    path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return path


def _run(tmp_path: Path, action: str, job_id: str, extra_env: dict[str, str] | None = None):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    _write_fake(bin_dir, "flock", "exit 0\n")
    python_link = bin_dir / "python3"
    if not python_link.exists():
        python_link.symlink_to(sys.executable)

    env = os.environ.copy()
    env.update(
        {
            "PATH": f"{bin_dir}:{env.get('PATH', '/usr/bin:/bin')}",
            "PARADOX_MAINTENANCE_DIR": str(tmp_path / "maintenance"),
            "REBOOT_REQUIRED_FILE": str(tmp_path / "reboot-required"),
        }
    )
    if extra_env:
        env.update(extra_env)
    return subprocess.run(
        ["bash", str(SCRIPT), action, job_id],
        text=True,
        capture_output=True,
        check=False,
        env=env,
    )


def _job_state(tmp_path: Path, job_id: str) -> dict:
    path = tmp_path / "maintenance" / "jobs" / f"{job_id}.json"
    return json.loads(path.read_text())


def test_check_updates_runs_update_and_lists_upgradable_packages(tmp_path: Path) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    calls = tmp_path / "calls"
    _write_fake(bin_dir, "apt-get", f"echo apt-get \"$@\" >> {calls}\nexit 0\n")
    _write_fake(bin_dir, "apt", f"echo apt \"$@\" >> {calls}\necho package/stable 1.2 armhf [upgradable]\n")

    result = _run(tmp_path, "check-updates", "job-check")

    assert result.returncode == 0
    state = _job_state(tmp_path, "job-check")
    assert state["status"] == "succeeded"
    assert state["updates_available"] == 1
    assert state["reboot_required"] is False
    call_text = calls.read_text()
    assert "apt-get update" in call_text
    assert "apt list --upgradable" in call_text
    assert "package/stable" in result.stdout
    assert not (tmp_path / "maintenance" / "current.json").exists()


def test_security_upgrade_reports_unsupported_without_unattended_upgrade(tmp_path: Path) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _write_fake(bin_dir, "apt-get", "exit 1\n")

    result = _run(
        tmp_path,
        "security-upgrade",
        "job-security",
        {"UNATTENDED_UPGRADE_BIN": "missing-unattended-upgrade"},
    )

    assert result.returncode == 0
    state = _job_state(tmp_path, "job-security")
    assert state["status"] == "unsupported"
    assert "unattended-upgrade is not installed" in state["message"]
    assert state["security_upgrade_supported"] is False
    assert "unsupported" in result.stdout


def test_reboot_required_flag_is_recorded(tmp_path: Path) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    reboot_required = tmp_path / "reboot-required"
    reboot_required.write_text("restart required\n")
    _write_fake(bin_dir, "dpkg", "exit 0\n")
    _write_fake(bin_dir, "apt-get", "exit 0\n")

    result = _run(tmp_path, "repair-packages", "job-reboot")

    assert result.returncode == 0
    state = _job_state(tmp_path, "job-reboot")
    assert state["status"] == "succeeded"
    assert state["reboot_required"] is True


def test_full_upgrade_allows_new_packages_without_dist_upgrade(tmp_path: Path) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    calls = tmp_path / "calls"
    _write_fake(bin_dir, "apt-get", f"echo apt-get \"$@\" >> {calls}\nexit 0\n")

    result = _run(tmp_path, "full-upgrade", "job-full")

    assert result.returncode == 0
    state = _job_state(tmp_path, "job-full")
    assert state["status"] == "succeeded"
    call_text = calls.read_text()
    assert "apt-get update" in call_text
    assert "apt-get -y --with-new-pkgs upgrade" in call_text
    assert "dist-upgrade" not in call_text
