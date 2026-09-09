"""Tests for deploy/state-recorder.sh."""

from __future__ import annotations

import os
import subprocess
import time
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "deploy" / "state-recorder.sh"


def _run_once(log_dir: Path, retention_minutes: int = 1440) -> subprocess.CompletedProcess:
    env = os.environ.copy()
    env.update(
        {
            "STATE_RECORDER_LOG_DIR": str(log_dir),
            "STATE_RECORDER_ONCE": "1",
            "STATE_RECORDER_RETENTION_MINUTES": str(retention_minutes),
            "STATE_RECORDER_PRUNE_INTERVAL_SEC": "0",
            "STATE_RECORDER_BRIDGE_URL": "http://127.0.0.1:1/health",
        }
    )
    return subprocess.run(
        ["bash", str(SCRIPT)],
        text=True,
        capture_output=True,
        check=False,
        env=env,
    )


def test_state_recorder_writes_compact_state_line(tmp_path: Path) -> None:
    log_dir = tmp_path / "state"

    result = _run_once(log_dir)

    assert result.returncode == 0, result.stderr
    logs = list(log_dir.glob("state-*.log"))
    assert len(logs) == 1
    line = logs[0].read_text().strip()
    assert "epoch=" in line
    assert "uptime=" in line
    assert "bridge=" in line
    assert "health=" in line
    assert "root_avail_kb=" in line


def test_state_recorder_prunes_logs_older_than_retention(tmp_path: Path) -> None:
    log_dir = tmp_path / "state"
    log_dir.mkdir()
    stale = log_dir / "state-20000101-0000.log"
    stale.write_text("old\n")
    old_time = time.time() - 120
    os.utime(stale, (old_time, old_time))

    result = _run_once(log_dir, retention_minutes=1)

    assert result.returncode == 0, result.stderr
    assert not stale.exists()
    assert list(log_dir.glob("state-*.log"))
