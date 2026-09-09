#!/usr/bin/env python3
"""Root-owned, outbound-only deployment of CI-signed Paradox bridge releases."""
import argparse
import fcntl
import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import time
import tomllib
import urllib.request
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import load_pem_public_key

REPOSITORY = "jjziets/Remote-Paradox"
INSTALL = Path("/opt/paradox-bridge")
STATE = Path("/var/lib/paradox-updater")
PUBLIC_KEY = Path("/etc/paradox-updater/release-public.pem")
TRUSTED_SCRIPT = Path("/usr/local/lib/paradox-updater/signed_update.py")
HEALTH_URL = "http://127.0.0.1:8080/health"
VERSION_URL = "http://127.0.0.1:8080/system/version"
MAX_ARCHIVE = 32 * 1024 * 1024
MANAGED = ("src/paradox_bridge", "scripts", "deploy", "pyproject.toml", "start.py", "CURRENT_VERSION")


def atomic_write(path: Path, data: bytes) -> None:
    tmp = path.with_suffix(".tmp")
    with tmp.open("wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    tmp.chmod(0o644)
    tmp.replace(path)
    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def atomic_json(path: Path, value: dict) -> None:
    atomic_write(path, (json.dumps(value, sort_keys=True) + "\n").encode())


def sync_tree(root: Path) -> None:
    for path in [*root.rglob("*"), root]:
        descriptor = os.open(path, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def version_tuple(value: str) -> tuple:
    if not re.fullmatch(r"\d+\.\d+\.\d+", value):
        raise ValueError("Invalid bridge version")
    return tuple(map(int, value.split(".")))


def fetch(url: str, limit: int = MAX_ARCHIVE) -> bytes:
    if not url.startswith("https://"):
        raise ValueError("Release downloads require HTTPS")
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "Paradox-Signed-Updater"})
    with urllib.request.urlopen(req, timeout=45) as response:
        if not response.url.startswith("https://"):
            raise ValueError("Insecure release redirect")
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("Release download exceeds size limit")
    return data


def verify_manifest(raw: bytes, signature: bytes, public_pem: bytes, tag: str) -> dict:
    key = load_pem_public_key(public_pem)
    if not isinstance(key, Ed25519PublicKey):
        raise ValueError("Expected pinned Ed25519 key")
    key.verify(signature, raw)
    manifest = json.loads(raw)
    version_tuple(manifest["version"])
    if (manifest["schema"] != 1 or manifest["repository"] != REPOSITORY
            or manifest["tag"] != tag or tag != "bridge-v" + manifest["version"]
            or manifest["archive"] != "paradox-bridge.tar.gz"
            or not re.fullmatch(r"[0-9a-f]{40}", manifest["commit"])
            or not re.fullmatch(r"[0-9a-f]{64}", manifest["sha256"])):
        raise ValueError("Release identity mismatch")
    for name, digest in manifest["files"].items():
        path = PurePosixPath(name)
        if (path.is_absolute() or ".." in path.parts or str(path) != name
                or not name.startswith("release/paradox-bridge/")
                or not re.fullmatch(r"[0-9a-f]{64}", digest)):
            raise ValueError("Invalid signed file path or digest")
    return manifest


def extract_verified(archive: Path, destination: Path, manifest: dict) -> Path:
    if hashlib.sha256(archive.read_bytes()).hexdigest() != manifest["sha256"]:
        raise ValueError("Release archive digest mismatch")
    seen = set()
    total = 0
    with tarfile.open(archive) as tar:
        for member in tar:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or str(path) != member.name.rstrip("/"):
                raise ValueError("Unsafe archive path")
            if member.isdir():
                continue
            if not member.isfile() or member.name not in manifest["files"] or member.name in seen:
                raise ValueError("Unexpected archive member")
            total += member.size
            if total > 64 * 1024 * 1024:
                raise ValueError("Expanded release exceeds size limit")
            data = tar.extractfile(member).read()
            if hashlib.sha256(data).hexdigest() != manifest["files"][member.name]:
                raise ValueError("Release file digest mismatch")
            target = destination / member.name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            target.chmod(0o755 if member.mode & 0o111 else 0o644)
            seen.add(member.name)
    if seen != set(manifest["files"]):
        raise ValueError("Incomplete release archive")
    source = destination / "release/paradox-bridge"
    if tomllib.loads((source / "pyproject.toml").read_text())["project"]["version"] != manifest["version"]:
        raise ValueError("Package version does not match signed manifest")
    return source


def run(*command: str, timeout: int = 180, umask: int = -1) -> None:
    subprocess.run(command, check=True, timeout=timeout, umask=umask)


def local_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=5) as response:
        return json.load(response)


def current_version() -> str:
    try:
        return local_json(VERSION_URL)["version"]
    except Exception:
        return tomllib.loads((INSTALL / "pyproject.toml").read_text())["project"]["version"]


def healthy(version: str, require_fresh: bool = True) -> bool:
    try:
        health = local_json(HEALTH_URL)
        age = health.get("panel_status_age_s")
        return (local_json(VERSION_URL).get("version") == version
                and health.get("alarm_connected") is True and not health.get("demo_mode")
                and (not require_fresh or isinstance(age, (int, float)) and 0 <= age < 15))
    except Exception:
        return False


def wait_healthy(version: str, require_fresh: bool = True, seconds: int = 120) -> bool:
    deadline = time.monotonic() + seconds
    good = 0
    while time.monotonic() < deadline:
        good = good + 1 if healthy(version, require_fresh) else 0
        if good >= 3:
            return True
        time.sleep(3)
    return False


def copy_managed(source: Path, target: Path, merge_helpers: bool = False) -> None:
    for relative in MANAGED:
        src, dst = source / relative, target / relative
        merge = merge_helpers and relative in ("scripts", "deploy") and src.is_dir()
        if dst.is_dir() and not merge:
            shutil.rmtree(dst)
        elif (dst.exists() or dst.is_symlink()) and not merge:
            dst.unlink()
        if src.is_dir():
            shutil.copytree(src, dst, dirs_exist_ok=merge, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
            # Updater staging is private, but the service user must traverse installed code.
            dst.chmod(0o755)
            for directory in dst.rglob("*"):
                if directory.is_dir():
                    directory.chmod(0o755)
        elif src.is_file():
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)


def installed_matches(manifest: dict) -> bool:
    for name, digest in manifest["files"].items():
        relative = name.removeprefix("release/paradox-bridge/")
        if not any(relative == root or relative.startswith(root + "/") for root in MANAGED):
            continue
        path = INSTALL / relative
        if not path.is_file() or path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            return False
    return True


def install_metadata() -> None:
    # No OS/package upgrades as a side effect of a bridge deployment.
    run(str(INSTALL / "venv/bin/pip"), "install", "--no-deps", "--no-build-isolation", "--no-index", "-e", str(INSTALL), umask=0o022)


def apply_release(source: Path, manifest: dict, old_version: str) -> None:
    backup = STATE / "backups" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%f")
    backup.mkdir(parents=True)
    copy_managed(INSTALL, backup)
    sync_tree(backup)
    # Also persist new parent directory entries before publishing the checkpoint.
    os.sync()
    receipt = {"version": manifest["version"], "commit": manifest["commit"], "sha256": manifest["sha256"], "tag": manifest["tag"], "backup": str(backup), "old_version": old_version}
    atomic_json(STATE / "deployment.json", dict(receipt, state="installing"))
    try:
        run("systemctl", "stop", "paradox-ble", "paradox-bridge")
        copy_managed(source, INSTALL, merge_helpers=True)
        for root in (INSTALL / "src/paradox_bridge", INSTALL / "scripts", INSTALL / "deploy"):
            if root.exists():
                sync_tree(root)
        install_metadata()
        for name in ("setup-boot-fsck.sh", "setup-wifi-watchdog.sh", "setup-boot-repair.sh", "setup-watchdog.sh",
                     "setup-zram-swap.sh", "setup-panic-recovery.sh", "setup-power-hardening.sh", "setup-state-recorder.sh",
                     "setup-command-diagnostics.sh"):
            script, previous = INSTALL / "deploy" / name, backup / "deploy" / name
            payload = {"setup-boot-repair.sh": "boot-repair.sh", "setup-wifi-watchdog.sh": "wifi-watchdog.sh",
                       "setup-state-recorder.sh": "state-recorder.sh"}.get(name)
            payload_changed = payload and (not (backup / "deploy" / payload).exists()
                or (backup / "deploy" / payload).read_bytes() != (INSTALL / "deploy" / payload).read_bytes())
            if script.is_file() and (not previous.exists() or previous.read_bytes() != script.read_bytes() or payload_changed):
                run("bash", str(script))
        run("systemctl", "daemon-reload")
        run("systemctl", "start", "paradox-bridge", "paradox-ble")
        if not wait_healthy(manifest["version"]):
            raise RuntimeError("Installed version/panel polling did not become healthy")
        run("systemctl", "is-active", "--quiet", "paradox-bridge", "paradox-ble", "nginx")
        if not installed_matches(manifest):
            raise RuntimeError("Installed files do not match the signed release")
        atomic_write(INSTALL / "CURRENT_VERSION", (manifest["version"] + "\n").encode())
        new_agent = source / "scripts/signed_update.py"
        atomic_write(TRUSTED_SCRIPT, new_agent.read_bytes())
        # Includes package metadata and helper-generated files outside the source tree.
        os.sync()
    except Exception as exc:
        atomic_json(STATE / "deployment.json", dict(receipt, state="rolling_back"))
        try:
            run("systemctl", "stop", "paradox-ble", "paradox-bridge")
            copy_managed(backup, INSTALL)
            install_metadata()
            run("systemctl", "start", "paradox-bridge", "paradox-ble")
            recovered = wait_healthy(old_version, require_fresh=False)
            os.sync()
        except Exception:
            recovered = False
        atomic_json(STATE / "deployment.json", dict(receipt, state="rolled_back" if recovered else "rollback_failed", error=type(exc).__name__))
        atomic_json(INSTALL / "update_status.json", {"pending": False, "applied": False,
                    "current_version": old_version, "deployment_failed": True,
                    "failed_tag": manifest["tag"], "rollback_verified": recovered})
        raise
    atomic_json(STATE / "deployment.json", dict(receipt, state="verified", verified_at=datetime.now(timezone.utc).isoformat()))
    atomic_json(INSTALL / "update_status.json", {"pending": False, "applied": True, "verified": True,
                "current_version": manifest["version"], "tag": manifest["tag"], "commit": manifest["commit"]})
    print(f"Verified deployment: {manifest['tag']} {manifest['commit']}")
    # Bound disk use, but always keep the most recent rollback source.
    for old in sorted((STATE / "backups").iterdir())[:-2]:
        shutil.rmtree(old)


def recover_interrupted() -> None:
    path = STATE / "deployment.json"
    if not path.exists():
        return
    receipt = json.loads(path.read_text())
    if receipt.get("state") not in ("installing", "rolling_back", "rollback_failed"):
        return
    if receipt.get("recovery_attempts", 0) >= 1:
        raise RuntimeError("Interrupted-deployment recovery failed; manual inspection required")
    backup = Path(receipt["backup"])
    if backup.resolve().parent != (STATE / "backups").resolve() or backup.is_symlink():
        raise ValueError("Invalid rollback checkpoint")
    old_version = receipt["old_version"]
    version_tuple(old_version)
    if tomllib.loads((backup / "pyproject.toml").read_text())["project"]["version"] != old_version:
        raise ValueError("Rollback checkpoint version mismatch")
    receipt.update(state="rolling_back", recovery_attempts=1, reason="interrupted_deployment")
    atomic_json(path, receipt)
    try:
        run("systemctl", "stop", "paradox-ble", "paradox-bridge")
        copy_managed(backup, INSTALL)
        install_metadata()
        run("systemctl", "start", "paradox-bridge", "paradox-ble")
        if not wait_healthy(old_version, require_fresh=False):
            raise RuntimeError("Interrupted deployment rollback remains unhealthy")
        os.sync()
    except Exception:
        atomic_json(path, dict(receipt, state="rollback_failed"))
        raise
    atomic_json(path, dict(receipt, state="rolled_back"))
    atomic_json(INSTALL / "update_status.json", {"pending": False, "applied": False, "current_version": old_version,
                "deployment_failed": True, "failed_tag": receipt.get("tag")})
    print(f"Recovered interrupted deployment to {old_version}")


def update(stage_only: bool = False, force: bool = False) -> None:
    releases = json.loads(fetch(f"https://api.github.com/repos/{REPOSITORY}/releases?per_page=100", 2 * 1024 * 1024))
    candidates = [r for r in releases if not r.get("draft") and not r.get("prerelease")
                  and re.fullmatch(r"bridge-v\d+\.\d+\.\d+", r.get("tag_name", ""))]
    if not candidates:
        raise ValueError("No bridge release found")
    release = max(candidates, key=lambda r: version_tuple(r["tag_name"][8:]))
    assets = {a["name"]: a["browser_download_url"] for a in release["assets"]}
    base = f"https://github.com/{REPOSITORY}/releases/download/{release['tag_name']}/"
    for name in ("bridge-manifest.json", "bridge-manifest.sig", "paradox-bridge.tar.gz"):
        if assets.get(name) != base + name:
            raise ValueError("Latest bridge release is missing signed assets")
    raw = fetch(assets["bridge-manifest.json"], 1024 * 1024)
    signature = fetch(assets["bridge-manifest.sig"], 128)
    manifest = verify_manifest(raw, signature, PUBLIC_KEY.read_bytes(), release["tag_name"])
    old_version = current_version()
    if version_tuple(manifest["version"]) < version_tuple(old_version):
        raise ValueError("Automatic downgrade refused")
    if manifest["version"] == old_version and not force:
        if installed_matches(manifest) and not healthy(old_version):
            raise RuntimeError("Installed release is unhealthy; explicit repair required")
        if installed_matches(manifest):
            atomic_json(INSTALL / "update_status.json", {"pending": False, "current_version": old_version,
                        "tag": manifest["tag"], "commit": manifest["commit"], "verified": True})
            print(f"Already verified {old_version}")
            return
    previous = json.loads((STATE / "deployment.json").read_text()) if (STATE / "deployment.json").exists() else {}
    if previous.get("commit") == manifest["commit"] and previous.get("state") in ("rolled_back", "rollback_failed") and not force:
        raise RuntimeError("Failed release is quarantined; explicit retry required")
    with tempfile.TemporaryDirectory(prefix="release-", dir=STATE) as temporary:
        work = Path(temporary)
        archive = work / "paradox-bridge.tar.gz"
        archive.write_bytes(fetch(assets["paradox-bridge.tar.gz"]))
        source = extract_verified(archive, work / "extracted", manifest)
        atomic_json(INSTALL / "update_status.json", {"pending": True, "current_version": old_version,
                    "new_version": manifest["version"], "tag": manifest["tag"], "signature_verified": True})
        if stage_only:
            print(f"Verified release available: {manifest['tag']}")
            return
        apply_release(source, manifest, old_version)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage-only", action="store_true")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit("Run the signed updater as root")
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    maintenance = Path("/var/lib/paradox-bridge/maintenance/maintenance.lock")
    maintenance.parent.mkdir(parents=True, exist_ok=True)
    with (STATE / "update.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        with maintenance.open("a") as maintenance_lock:
            fcntl.flock(maintenance_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            recover_interrupted()
            update(args.stage_only, args.force)


if __name__ == "__main__":
    main()
