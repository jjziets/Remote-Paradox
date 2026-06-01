"""Tests for bridge package maintenance API routes."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import paradox_bridge.main as app_module
from paradox_bridge.main import app, init_services, shutdown_services


def auth_header(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture(autouse=True)
def setup_app(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    config_path = str(tmp_path / "config.json")
    maintenance_dir = tmp_path / "maintenance"
    script = tmp_path / "maintenance_job.sh"
    script.write_text("#!/usr/bin/env bash\nexit 0\n")
    script.chmod(0o755)

    monkeypatch.setattr(app_module, "MAINTENANCE_DIR", maintenance_dir)
    monkeypatch.setattr(app_module, "MAINTENANCE_SCRIPT", script)
    monkeypatch.setattr(app_module, "MAINTENANCE_LOG_TAIL_BYTES", 128)
    monkeypatch.setattr(app_module, "MAINTENANCE_REBOOT_REQUIRED_FILE", tmp_path / "reboot-required")
    init_services(config_path=config_path)
    app_module._auth.setup_admin("admin", "secret123")
    yield
    shutdown_services()


@pytest.fixture()
def client() -> TestClient:
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture()
def admin_token(client: TestClient) -> str:
    resp = client.post("/auth/login", json={"username": "admin", "password": "secret123"})
    assert resp.status_code == 200
    return resp.json()["token"]


def test_check_updates_starts_systemd_job_and_blocks_concurrent_jobs(
    client: TestClient,
    admin_token: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    commands: list[list[str]] = []

    def fake_run(command, **_kwargs):
        commands.append(command)
        return subprocess.CompletedProcess(command, 0, stdout="started", stderr="")

    monkeypatch.setattr(app_module.subprocess, "run", fake_run)

    resp = client.post("/system/maintenance/check-updates", headers=auth_header(admin_token))

    assert resp.status_code == 200
    job = resp.json()
    assert job["action"] == "check-updates"
    assert job["status"] == "queued"
    assert commands
    command = commands[0]
    assert command[:6] == ["sudo", "-n", "systemd-run", "--no-block", "--collect", "--property=Type=oneshot"]
    assert any(part.startswith("--unit=paradox-maintenance-") for part in command)
    assert any(part.startswith("--setenv=PARADOX_MAINTENANCE_DIR=") for part in command)
    assert command[-2:] == ["check-updates", job["job_id"]]

    status_resp = client.get("/system/maintenance/status", headers=auth_header(admin_token))
    assert status_resp.status_code == 200
    assert status_resp.json()["active"] is True
    assert status_resp.json()["current_job"]["job_id"] == job["job_id"]

    second = client.post("/system/maintenance/repair-packages", headers=auth_header(admin_token))
    assert second.status_code == 409


def test_full_upgrade_requires_exact_confirmation(
    client: TestClient,
    admin_token: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        app_module.subprocess,
        "run",
        lambda command, **_kwargs: subprocess.CompletedProcess(command, 0, stdout="", stderr=""),
    )

    rejected = client.post(
        "/system/maintenance/full-upgrade",
        headers=auth_header(admin_token),
        json={"confirmation": "upgrade"},
    )
    assert rejected.status_code == 400

    accepted = client.post(
        "/system/maintenance/full-upgrade",
        headers=auth_header(admin_token),
        json={"confirmation": "UPGRADE PACKAGES"},
    )
    assert accepted.status_code == 200
    assert accepted.json()["action"] == "full-upgrade"


def test_job_status_and_bounded_log_tail(client: TestClient, admin_token: str, tmp_path: Path) -> None:
    maintenance_dir = app_module.MAINTENANCE_DIR
    jobs_dir = maintenance_dir / "jobs"
    logs_dir = maintenance_dir / "logs"
    jobs_dir.mkdir(parents=True)
    logs_dir.mkdir(parents=True)
    job_id = "job-123"
    (jobs_dir / f"{job_id}.json").write_text(
        json.dumps(
            {
                "job_id": job_id,
                "action": "repair-packages",
                "status": "succeeded",
                "created_at": "2026-06-01T10:00:00Z",
                "updated_at": "2026-06-01T10:01:00Z",
                "finished_at": "2026-06-01T10:01:00Z",
                "message": "Completed",
                "exit_code": 0,
                "updates_available": 9,
                "reboot_required": True,
            }
        )
    )
    (logs_dir / f"{job_id}.log").write_text("\n".join(f"line-{i}" for i in range(10)) + "\n")

    job_resp = client.get(f"/system/maintenance/jobs/{job_id}", headers=auth_header(admin_token))
    assert job_resp.status_code == 200
    assert job_resp.json()["status"] == "succeeded"
    assert job_resp.json()["updates_available"] == 9
    assert job_resp.json()["reboot_required"] is True

    log_resp = client.get(
        f"/system/maintenance/jobs/{job_id}/log?lines=3",
        headers=auth_header(admin_token),
    )
    assert log_resp.status_code == 200
    assert log_resp.json()["lines"] == ["line-7", "line-8", "line-9"]
    assert log_resp.json()["truncated"] is True

    status_resp = client.get("/system/maintenance/status", headers=auth_header(admin_token))
    assert status_resp.status_code == 200
    assert status_resp.json()["updates_available"] == 9
    assert status_resp.json()["reboot_required"] is True


def test_maintenance_routes_require_admin(client: TestClient) -> None:
    resp = client.get("/system/maintenance/status")
    assert resp.status_code in (401, 403)
