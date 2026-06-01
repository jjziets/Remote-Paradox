"""Tests for Raspberry Pi boot fsck setup script."""

import subprocess
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "deploy" / "setup-boot-fsck.sh"


def test_setup_boot_fsck_adds_missing_flags(tmp_path):
    cmdline = tmp_path / "cmdline.txt"
    cmdline.write_text("console=tty1 root=PARTUUID=abcd-02 rootwait\n")

    result = subprocess.run(
        ["bash", str(SCRIPT)],
        env={"CMDLINE_FILE": str(cmdline)},
        text=True,
        capture_output=True,
        check=True,
    )

    updated = cmdline.read_text()
    assert "fsck.mode=force" in updated
    assert "fsck.repair=yes" in updated
    assert "\n" in updated
    assert list(tmp_path.glob("cmdline.txt.bak.*"))
    assert "updated" in result.stdout


def test_setup_boot_fsck_is_idempotent(tmp_path):
    cmdline = tmp_path / "cmdline.txt"
    original = "console=tty1 rootwait fsck.mode=force fsck.repair=yes\n"
    cmdline.write_text(original)

    result = subprocess.run(
        ["bash", str(SCRIPT)],
        env={"CMDLINE_FILE": str(cmdline)},
        text=True,
        capture_output=True,
        check=True,
    )

    assert cmdline.read_text() == original
    assert not list(tmp_path.glob("cmdline.txt.bak.*"))
    assert "already enabled" in result.stdout
