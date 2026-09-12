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
RELEASE=bridge-v1.0.11
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

On a fresh installation that already exactly matches the latest signed version,
the timer may report `Already verified` without creating a deployment receipt.
Run the explicit `--force` command in the recovery section once to perform the
full backup/install/three-sample verification and create the initial receipt.
This extra same-version bootstrap step was not needed for the tested 1.0.9
upgrade below.

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

## Tested deployment

The latest tested update is [bridge 1.0.11 on 12 September 2026](client-diagnostics-release-2026-09-12.md),
including signed archive/installed-file verification, preserved TLS/config/users
and a live authenticated HTTPS diagnostic upload. The original bootstrap evidence
below remains the record of the one-time setup, not a new blank-card reflash.

On 2026-09-09, [PR #6](https://github.com/jjziets/Remote-Paradox/pull/6) merged
as `bc0efe36d9e732311a9d0a6476171e3dd84165ab`.
[GitHub CI](https://github.com/jjziets/Remote-Paradox/actions/runs/34352449826)
tested, signed and published
[bridge-v1.0.10](https://github.com/jjziets/Remote-Paradox/releases/tag/bridge-v1.0.10).
The first clean runner exposed an undeclared QR/Pillow dependency, which was
corrected before merge. The final backend result was 331 passed and one strict
pre-existing BLE tracker xfail; production BLE client tracking was not changed.

The runbook's asset download, pinned signature verification, archive extraction,
trusted SSH bootstrap and Pi `systemctl start` path were executed against the
existing Bookworm/Python 3.11 Pi. The verifier checked 62 signed file hashes.
The Pi downloaded the release itself and recorded `state=verified` at
**2026-09-09T12:45:23Z**, upgrading 1.0.9 to 1.0.10. Archive SHA-256:
`2251eb813c3521a79c85de21cfdfa7f6c4edf327b988c9fee07dd70953c1de7d`.

Post-install checks confirmed:

- Exact version/commit and installed hashes in the root-owned receipt.
- Authenticated HTTP status plus nine connected WebSocket status frames over
  37 seconds; maximum observed panel poll age 6.56 seconds.
- Unchanged TLS certificate, configuration and user-account fingerprints.
- Active bridge, BLE, nginx, state recorder and signed-updater timer.
- A successful check through the legacy updater entrypoint as the service user,
  now delegated to the signed root verifier: `Already verified 1.0.10`.
- The first automatic timer check also succeeded at 12:50:41 UTC, recognized
  the verified version and did not reinstall or restart it.
- Root-owned verifier/public key, private updater state, recent command logs and
  preserved generated recovery scripts. Sleep targets were masked and Wi-Fi
  power saving disabled in the pre-deployment check.

GitHub deployment **6350071647** records the operator's verified result in
`production-pi`; that status was posted after SSH checks, not by an unattended
callback from the LAN Pi. Future releases still require inspection of the Pi's
receipt for remote deployment confirmation.

No real arm/disarm/panic/bypass commands, device APK installation, OS package
upgrade, deliberate power cut or blank-card reflash were performed. Rollback
and interrupted recovery were exercised with isolated regression tests, not by
damaging the live installation. Intermittent physical panel/UART fault resolution
remains a field verification task using the retained diagnostic logs.

The [Android CI run](https://github.com/jjziets/Remote-Paradox/actions/runs/34352449232)
also succeeded and published [v1.2.31](https://github.com/jjziets/Remote-Paradox/releases/tag/v1.2.31).
Downloaded APK metadata confirmed phone version code **81** and watch code
**25**, both version name **1.2.31**. Both signatures match the previous release
certificate (SHA-256
`7fe1770a027a44972278222a3d251508ace9e49bfb3758415750accd5b26d0ea`),
and both downloads match GitHub's published SHA-256 digests. The latest-release
endpoint points to `v1.2.31`, so bridge publication has not hidden app updates.
On the phone, use Settings > Check for updates, then Update Watch and approve
the installer prompts on the respective devices. The APKs were verified but
not installed on physical devices during this deployment.
