"""Signed deployment tests: no network, system services or real alarm commands."""
import hashlib
import importlib.util
import io
import json
import os
import stat
import tarfile
from pathlib import Path

import pytest
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

spec = importlib.util.spec_from_file_location("signed_update", Path(__file__).parents[1] / "scripts/signed_update.py")
updater = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)


@pytest.fixture
def bundle(tmp_path):
    files = {
        "release/paradox-bridge/pyproject.toml": b'[project]\nversion = "1.0.10"\n',
        "release/paradox-bridge/src/paradox_bridge/__init__.py": b"# new release\n",
        "release/paradox-bridge/scripts/signed_update.py": b"# tested verifier\n",
    }
    archive = tmp_path / "bundle.tar.gz"
    with tarfile.open(archive, "w:gz") as tar:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    manifest = {
        "schema": 1, "repository": updater.REPOSITORY, "tag": "bridge-v1.0.10",
        "version": "1.0.10", "commit": "a" * 40, "archive": "paradox-bridge.tar.gz",
        "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
        "files": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()},
    }
    key = Ed25519PrivateKey.generate()
    public = key.public_key().public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo)
    return archive, manifest, key, public


def test_signed_manifest_and_archive(bundle, tmp_path):
    archive, manifest, key, public = bundle
    raw = json.dumps(manifest).encode()
    verified = updater.verify_manifest(raw, key.sign(raw), public, manifest["tag"])
    source = updater.extract_verified(archive, tmp_path / "out", verified)
    assert "1.0.10" in (source / "pyproject.toml").read_text()


def test_manifest_tampering_is_rejected(bundle):
    _, manifest, key, public = bundle
    raw = json.dumps(manifest).encode()
    with pytest.raises(InvalidSignature):
        updater.verify_manifest(raw + b" ", key.sign(raw), public, manifest["tag"])
    with pytest.raises(InvalidSignature):
        updater.verify_manifest(raw, Ed25519PrivateKey.generate().sign(raw), public, manifest["tag"])


@pytest.mark.parametrize("field,value", [("repository", "attacker/bridge"), ("version", "0.0.1"),
                                         ("archive", "../bad.tar.gz"), ("commit", "invalid")])
def test_signed_but_wrong_identity_is_rejected(bundle, field, value):
    _, manifest, key, public = bundle
    manifest[field] = value
    raw = json.dumps(manifest).encode()
    with pytest.raises(ValueError):
        updater.verify_manifest(raw, key.sign(raw), public, "bridge-v1.0.10")


def test_archive_tampering_is_rejected_before_extraction(bundle, tmp_path):
    archive, manifest, _, _ = bundle
    archive.write_bytes(archive.read_bytes() + b"tampered")
    output = tmp_path / "out"
    with pytest.raises(ValueError, match="digest"):
        updater.extract_verified(archive, output, manifest)
    assert not output.exists()


@pytest.mark.parametrize("name", ["/tmp/escape", "release/paradox-bridge/../../escape", "release//paradox-bridge/file"])
def test_unsafe_manifest_paths_are_rejected(bundle, name):
    _, manifest, key, public = bundle
    manifest["files"][name] = "a" * 64
    raw = json.dumps(manifest).encode()
    with pytest.raises(ValueError, match="path"):
        updater.verify_manifest(raw, key.sign(raw), public, manifest["tag"])


def test_symlink_archive_member_is_rejected(bundle, tmp_path):
    archive, manifest, _, _ = bundle
    with tarfile.open(archive, "w:gz") as tar:
        link = tarfile.TarInfo("release/paradox-bridge/pyproject.toml")
        link.type = tarfile.SYMTYPE
        link.linkname = "/etc/passwd"
        tar.addfile(link)
    manifest["sha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
    with pytest.raises(ValueError, match="member"):
        updater.extract_verified(archive, tmp_path / "out", manifest)


@pytest.fixture
def deployment(bundle, tmp_path, monkeypatch):
    archive, manifest, _, _ = bundle
    source = updater.extract_verified(archive, tmp_path / "source", manifest)
    install, state, trusted = tmp_path / "install", tmp_path / "state", tmp_path / "trusted.py"
    (install / "src/paradox_bridge").mkdir(parents=True)
    (install / "src/paradox_bridge/__init__.py").write_text("# previous release\n")
    (install / "pyproject.toml").write_text('[project]\nversion = "1.0.9"\n')
    (install / "CURRENT_VERSION").write_text("1.0.9\n")
    (install / "tls.pem").write_text("unchanged TLS identity")
    (install / "database.db").write_text("unchanged user data")
    state.mkdir()
    trusted.write_text("# bootstrap verifier\n")
    monkeypatch.setattr(updater, "INSTALL", install)
    monkeypatch.setattr(updater, "STATE", state)
    monkeypatch.setattr(updater, "TRUSTED_SCRIPT", trusted)
    calls = []
    monkeypatch.setattr(updater, "run", lambda *args, **kwargs: calls.append(args))
    monkeypatch.setattr(updater, "install_metadata", lambda: None)
    monkeypatch.setattr(updater.os, "sync", lambda: None)
    return source, manifest, install, state, calls


def test_apply_requires_health_and_exact_files_before_success(deployment, monkeypatch):
    source, manifest, install, state, calls = deployment
    monkeypatch.setattr(updater, "wait_healthy", lambda *args, **kwargs: True)
    updater.apply_release(source, manifest, "1.0.9")
    receipt = json.loads((state / "deployment.json").read_text())
    assert receipt["state"] == "verified"
    assert receipt["commit"] == "a" * 40
    assert updater.installed_matches(manifest)
    assert (install / "CURRENT_VERSION").read_text().strip() == "1.0.10"
    assert (install / "tls.pem").read_text() == "unchanged TLS identity"
    assert (install / "database.db").read_text() == "unchanged user data"
    assert calls[0] == ("systemctl", "stop", "paradox-ble", "paradox-bridge")


def test_failed_panel_check_restores_previous_release(deployment, monkeypatch):
    source, manifest, install, state, _ = deployment
    results = iter([False, True])
    monkeypatch.setattr(updater, "wait_healthy", lambda *args, **kwargs: next(results))
    with pytest.raises(RuntimeError, match="polling"):
        updater.apply_release(source, manifest, "1.0.9")
    assert json.loads((state / "deployment.json").read_text())["state"] == "rolled_back"
    status = json.loads((install / "update_status.json").read_text())
    assert status["pending"] is False
    assert status["deployment_failed"] is True
    assert status["rollback_verified"] is True
    assert (install / "CURRENT_VERSION").read_text().strip() == "1.0.9"
    assert "previous release" in (install / "src/paradox_bridge/__init__.py").read_text()
    assert (install / "tls.pem").read_text() == "unchanged TLS identity"


@pytest.mark.parametrize("health", [
    {"alarm_connected": False, "panel_status_age_s": 1},
    {"alarm_connected": True, "panel_status_age_s": 50},
    {"alarm_connected": True},
    {"alarm_connected": True, "panel_status_age_s": 1, "demo_mode": True},
])
def test_http_ok_alone_cannot_verify_a_deployment(monkeypatch, health):
    monkeypatch.setattr(updater, "local_json", lambda url: {"version": "1.0.10"} if url == updater.VERSION_URL else health)
    assert not updater.healthy("1.0.10")


def test_api_failure_can_still_identify_installed_version(deployment, monkeypatch):
    monkeypatch.setattr(updater, "local_json", lambda url: (_ for _ in ()).throw(OSError()))
    assert updater.current_version() == "1.0.9"


def test_private_updater_umask_does_not_make_installed_package_private(deployment, monkeypatch):
    source, manifest, install, _, _ = deployment
    for path in [source, *source.rglob("*")]:
        if path.is_dir():
            path.chmod(0o700)
    monkeypatch.setattr(updater, "wait_healthy", lambda *args, **kwargs: True)
    previous = os.umask(0o077)
    try:
        updater.apply_release(source, manifest, "1.0.9")
    finally:
        os.umask(previous)
    assert stat.S_IMODE((install / "src/paradox_bridge").stat().st_mode) == 0o755
    assert stat.S_IMODE((install / "scripts").stat().st_mode) == 0o755


@pytest.mark.parametrize("rollback", [False, True])
def test_durable_checkpoints_follow_disk_flush(deployment, monkeypatch, rollback):
    source, manifest, _, _, _ = deployment
    events = []
    real_json = updater.atomic_json
    monkeypatch.setattr(updater.os, "sync", lambda: events.append("sync"))

    def record(path, value):
        if path.name == "deployment.json":
            events.append(value["state"])
        real_json(path, value)

    monkeypatch.setattr(updater, "atomic_json", record)
    results = iter([False, True] if rollback else [True])
    monkeypatch.setattr(updater, "wait_healthy", lambda *args, **kwargs: next(results))
    if rollback:
        with pytest.raises(RuntimeError):
            updater.apply_release(source, manifest, "1.0.9")
    else:
        updater.apply_release(source, manifest, "1.0.9")
    assert events[events.index("installing") - 1] == "sync"
    terminal = "rolled_back" if rollback else "verified"
    assert events[events.index(terminal) - 1] == "sync"


def test_generated_recovery_scripts_survive_successful_update(deployment, monkeypatch):
    source, manifest, install, _, _ = deployment
    (install / "scripts").mkdir()
    helper = install / "scripts/wifi-watchdog.sh"
    helper.write_text("# generated by setup helper\n")
    monkeypatch.setattr(updater, "wait_healthy", lambda *args, **kwargs: True)
    updater.apply_release(source, manifest, "1.0.9")
    assert helper.read_text() == "# generated by setup helper\n"


@pytest.mark.parametrize("phase", ["installing", "rolling_back"])
def test_interrupted_deployment_recovers_without_network_or_working_new_pyproject(deployment, monkeypatch, phase):
    _, manifest, install, state, _ = deployment
    backup = state / "backups/interrupted"
    backup.mkdir(parents=True)
    updater.copy_managed(install, backup)
    (install / "pyproject.toml").write_text("truncated package metadata")
    updater.atomic_json(state / "deployment.json", dict(manifest, state=phase, backup=str(backup), old_version="1.0.9"))
    monkeypatch.setattr(updater, "wait_healthy", lambda *args, **kwargs: True)
    monkeypatch.setattr(updater, "fetch", lambda *args, **kwargs: pytest.fail("Recovery must not need network"))
    updater.recover_interrupted()
    assert "1.0.9" in (install / "pyproject.toml").read_text()
    assert json.loads((state / "deployment.json").read_text())["state"] == "rolled_back"


def test_failed_recovery_does_not_restart_services_on_every_timer_tick(deployment, monkeypatch):
    _, manifest, install, state, calls = deployment
    backup = state / "backups/interrupted"
    backup.mkdir(parents=True)
    updater.copy_managed(install, backup)
    updater.atomic_json(state / "deployment.json", dict(manifest, state="installing", backup=str(backup), old_version="1.0.9"))
    monkeypatch.setattr(updater, "wait_healthy", lambda *args, **kwargs: False)
    with pytest.raises(RuntimeError):
        updater.recover_interrupted()
    count = len(calls)
    with pytest.raises(RuntimeError, match="manual inspection"):
        updater.recover_interrupted()
    assert len(calls) == count
