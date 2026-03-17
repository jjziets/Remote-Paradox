#!/usr/bin/env python3
"""Check GitHub for newer releases and stage updates for admin approval."""

import json
import os
import shutil
import tarfile
import tempfile
import urllib.request
from pathlib import Path

GITHUB_REPO = "jjziets/Remote-Paradox"
INSTALL_DIR = Path("/opt/paradox-bridge")
VERSION_FILE = INSTALL_DIR / "CURRENT_VERSION"
STATUS_FILE = INSTALL_DIR / "update_status.json"
STAGING_DIR = INSTALL_DIR / "staging"

API_URL = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"


def current_version() -> str:
    if VERSION_FILE.exists():
        return VERSION_FILE.read_text().strip()
    return "0.0.0"


def fetch_latest_release() -> dict | None:
    req = urllib.request.Request(API_URL, headers={"Accept": "application/vnd.github+json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read())
    except Exception as e:
        print(f"[updater] Failed to fetch release: {e}")
        return None


def version_tuple(v: str) -> tuple[int, ...]:
    return tuple(int(x) for x in v.lstrip("v").split(".") if x.isdigit())


def download_and_stage(tarball_url: str, tag: str) -> bool:
    STAGING_DIR.mkdir(parents=True, exist_ok=True)
    try:
        with tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False) as tmp:
            req = urllib.request.Request(tarball_url)
            with urllib.request.urlopen(req, timeout=120) as resp:
                shutil.copyfileobj(resp, tmp)
            tmp_path = tmp.name

        if STAGING_DIR.exists():
            shutil.rmtree(STAGING_DIR)
        STAGING_DIR.mkdir(parents=True)

        with tarfile.open(tmp_path, "r:gz") as tar:
            tar.extractall(STAGING_DIR, filter="data")

        os.unlink(tmp_path)

        status = {
            "pending": True,
            "current_version": current_version(),
            "new_version": tag.lstrip("v"),
            "tag": tag,
        }
        STATUS_FILE.write_text(json.dumps(status))
        print(f"[updater] Staged {tag}, awaiting approval")
        return True
    except Exception as e:
        print(f"[updater] Failed to stage: {e}")
        return False


def check_and_stage():
    release = fetch_latest_release()
    if not release:
        return

    tag = release.get("tag_name", "")
    latest = version_tuple(tag)
    cur = version_tuple(current_version())

    if latest <= cur:
        print(f"[updater] Up to date ({current_version()})")
        clear_status_if_not_pending()
        return

    tarball_url = release.get("tarball_url")
    if not tarball_url:
        print("[updater] No tarball URL in release")
        return

    print(f"[updater] New release {tag} (current: {current_version()})")
    download_and_stage(tarball_url, tag)


def clear_status_if_not_pending():
    if STATUS_FILE.exists():
        try:
            data = json.loads(STATUS_FILE.read_text())
            if not data.get("pending"):
                STATUS_FILE.unlink(missing_ok=True)
        except Exception:
            pass


if __name__ == "__main__":
    check_and_stage()
