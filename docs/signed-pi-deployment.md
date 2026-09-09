# Signed Pi deployment

## Release contract

Merge reviewed changes to `main`, increase the bridge version in
`paradox-bridge/pyproject.toml`, then tag that commit `bridge-vX.Y.Z`.
`.github/workflows/release-bridge.yml` runs the Python tests on a GitHub-hosted
runner and signs a manifest containing the commit, version, archive hash and
individual file hashes. Its assets are `bridge-manifest.json`,
`bridge-manifest.sig`, and `paradox-bridge.tar.gz`. Never replace a published tag
or release asset; fix failures with a new version.

The private Ed25519 key is the GitHub Actions secret `BRIDGE_SIGNING_KEY`.
Its public half is `paradox-bridge/deploy/bridge-release.pub`. Keep an offline
backup of the private key outside Git. Key rotation requires an explicit trusted
administrator action; a downloaded release cannot change the Pi's pinned key.

Android `v*` releases are built/tested separately using the existing Android
keystore secrets. APK signing, bridge signing and HTTPS certificates are separate
identities. This deployment preserves the Pi's TLS key/certificate and existing
app certificate pins. It does not obtain a public CA certificate.

## One-time bootstrap

Prerequisites: a bootable networked Pi, Python 3.11+, working bridge/BLE/nginx
services, existing Python dependencies, and trusted SSH access. On a new card,
complete [README deployment](../README.md#deployment-from-scratch-pi) first.
The signing verifier needs Debian's `python3-cryptography` outside the app venv:

```bash
sudo apt-get install python3-cryptography
/usr/bin/python3 -I -c 'from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey'
```

On a trusted workstation, from a reviewed checkout containing the public key
and verifier, download the CI assets. The workstation needs Python 3.11+ with
`cryptography` and authenticated `gh` (public downloads do not require Pi login).
Replace `PI` with the SSH destination, never commit it:

```bash
PI='<user>@<pi-host>'
RELEASE=bridge-v1.0.10
BUNDLE=$(mktemp -d)
gh release download "$RELEASE" --repo jjziets/Remote-Paradox --dir "$BUNDLE" \
  --pattern bridge-manifest.json --pattern bridge-manifest.sig \
  --pattern paradox-bridge.tar.gz
python3 - "$RELEASE" "$BUNDLE" <<'PY'
import importlib.util
import sys
from pathlib import Path
spec = importlib.util.spec_from_file_location("verify", "paradox-bridge/scripts/signed_update.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)
tag, directory = sys.argv[1:]
bundle = Path(directory)
manifest = verify.verify_manifest(
    (bundle / "bridge-manifest.json").read_bytes(),
    (bundle / "bridge-manifest.sig").read_bytes(),
    Path("paradox-bridge/deploy/bridge-release.pub").read_bytes(), tag)
verify.extract_verified(bundle / "paradox-bridge.tar.gz", bundle / "verified", manifest)
print("Signature and all archive files verified:", tag, manifest["commit"])
PY
REMOTE=$(ssh "$PI" mktemp -d /tmp/paradox-bootstrap.XXXXXX)
scp -r "$BUNDLE/verified/release/paradox-bridge" "$PI:$REMOTE/"
ssh "$PI" "sudo bash '$REMOTE/paradox-bridge/deploy/setup-signed-updates.sh'"
ssh "$PI" 'sudo systemctl start paradox-signed-updater.service'
```

The bootstrap pins the public key and installs a root-owned isolated verifier,
sudo rules limited to release checks, and `paradox-signed-updater.timer`.
It disables the old unsigned updater timer and redirects the legacy app/repair
entrypoints. The actual deployment then pulls the signed release directly from
GitHub. No GitHub token, private signing key, inbound SSH from CI, or full runner
is installed on the Pi. Verify below before removing bootstrap temporary files.

## What counts as deployed

```bash
sudo cat /var/lib/paradox-updater/deployment.json
cat /opt/paradox-bridge/update_status.json
curl -fsS http://127.0.0.1:8080/system/version
curl -fsS http://127.0.0.1:8080/health
systemctl is-active paradox-bridge paradox-ble nginx paradox-state-recorder
systemctl list-timers paradox-signed-updater.timer --no-pager
sudo journalctl -u paradox-signed-updater -n 60 --no-pager
```

Require receipt `state=verified`, the expected tag/commit and running version,
`alarm_connected=true`, `demo_mode=false`, and `panel_status_age_s` below 15.
The installer requires three consecutive healthy samples, active services, and
installed file hashes matching the signed manifest before writing this receipt.
Health polling does not arm or disarm the panel; acceptance of physical alarm
commands still requires a supervised phone/watch test.

GitHub publication alone is **not** a successful deployment. The private LAN Pi
does not upload a receipt to GitHub; an operator checks it through SSH or the
app's version/status view. `/system/update-status` is informational; the
root-owned receipt and live health are the stronger deployment evidence.

## Operation and recovery

- The timer checks every five minutes with up to 20 seconds of jitter, starting
  three minutes after boot. A new release briefly interrupts app/BLE connections.
- Downloads require HTTPS and the pinned signature. Unsafe paths, links,
  oversized archives, wrong identities and changed files are rejected.
- The deploy lock also excludes package-maintenance and boot-repair jobs.
- Source, entrypoint, package metadata and recovery helpers are updated. Existing
  generated helper scripts are preserved. TLS, users, config, FCM credentials,
  the venv dependencies, static web assets and nginx config are not replaced.
- Code backup and installation checkpoints are flushed before reporting durable
  state. A failed health/hash check restores previous code. An interrupted
  installation is restored before attempting a network download.
- The two most recent successful-deployment backups are retained under
  `/var/lib/paradox-updater/backups`. A failed commit is quarantined until an
  administrator explicitly retries it. Failed interrupted recovery stops for
  inspection instead of repeatedly restarting services.

After investigating a failure, explicitly retry the latest signed release:

```bash
sudo /usr/bin/python3 -I /usr/local/lib/paradox-updater/signed_update.py --force
```

An interrupted recovery that itself failed requires inspection of the receipt
and backup before changing the checkpoint; `--force` does not bypass that guard.
To pause automatic updates:

```bash
sudo systemctl disable --now paradox-signed-updater.timer
```

This is application recovery, not an OS image replacement. Rollback does not
undo database schema migrations, package upgrades or helper changes in `/etc`.
Releases must keep those changes backward-compatible. The updater cannot fix
an unbootable kernel, unreadable SD card, absent power or a network that never
comes up. Disk flushes improve recovery but cannot guarantee SD controller
behavior after power loss.
