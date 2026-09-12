# Diagnostic Release Verification - 2026-09-12

[PR #9](https://github.com/jjziets/Remote-Paradox/pull/9) merged as
`16d22d8d10ed510d6e396103de0642b77ca88b7b`. Both immutable release tags point
to that exact, locally reviewed and tested tree.

| Component | Published Version | Build Code | Result |
| --- | --- | --- | --- |
| Phone | 1.2.33 | 82 | Signed APK downloaded and verified |
| Watch | 1.2.33 | 27 | Signed APK downloaded and verified |
| Pi bridge | 1.0.11 | n/a | Signed pull deployment verified on the Pi |

## Android Publication

[GitHub Android CI](https://github.com/jjziets/Remote-Paradox/actions/runs/34678983474)
passed and published [v1.2.33](https://github.com/jjziets/Remote-Paradox/releases/tag/v1.2.33)
at `2026-09-12T06:51:42Z`. The unauthenticated public latest-release endpoint
returns v1.2.33 with both versioned APK assets, matching the installed apps'
update-discovery path. The bridge release does not hide Android updates.

Downloaded APK metadata confirms both package IDs are `com.remoteparadox.app`
and versions/build codes match the table. Both pass `apksigner verify` and use
the existing release certificate SHA-256:
`7fe1770a027a44972278222a3d251508ace9e49bfb3758415750accd5b26d0ea`.

Downloaded SHA-256 values match GitHub's published digests:

- Phone: `f6f2a5daf1b059543d2f83a21e016ba21cb7200c84d50d4eeefa6729dde22f77`
- Watch: `9fd059967c38a442dc22cd68c258412523d1e141560c3beabbab24c7c7a5232e`

## Pi Deployment

[GitHub Pi CI](https://github.com/jjziets/Remote-Paradox/actions/runs/34678982996)
passed and published [bridge-v1.0.11](https://github.com/jjziets/Remote-Paradox/releases/tag/bridge-v1.0.11).
The workstation independently verified the pinned Ed25519 signature and all
65 archive file hashes. Archive SHA-256:
`fa337538858e55ee41dfe4c32bb528322cf4367361a83be8c90773d39558c652`.

The existing `paradox-signed-updater.service` was started over trusted SSH.
The Pi pulled the signed artifact directly from GitHub; no source files were
copied into the live application. Its root-owned deployment receipt records
`state=verified`, the exact release commit, version 1.0.11 and verification time
`2026-09-12T06:48:43.812473+00:00` (08:48 SAST).

Post-install checks confirmed all 41 managed installed file hashes match the
signed manifest, `/system/version` returns 1.0.11, health reports connected,
non-demo panel data with an observed age of 2.65 seconds, and bridge/BLE/nginx/
state-recorder/updater-timer services are active. Configuration, TLS certificate,
TLS private key and all five user records have unchanged pre/post hashes.
GitHub production deployment **6406938644** records success after these checks.

A synthetic, empty phone/watch report tested the real nginx HTTPS endpoint
with the exact existing certificate pinned and a short-lived authenticated
token kept entirely inside the Pi process. Missing authentication returned 401;
authenticated upload returned 200 and a matching receipt; retry returned the
same receipt. Stored directory/file modes were 0700/0600, expiry was 24 hours,
and the cached panel snapshot was connected. The synthetic report was removed
after verification, leaving no fake incident report or consumed storage slot.

## User Update Path and Limits

On the phone, use Settings > Check for updates and approve installation of
1.2.33. Reopen the app, check for watch updates and approve installation on the
watch. The Pi already runs 1.0.11. Then use Settings > Diagnostics > Send
diagnostic report; see the [capture and retrieval guide](client-diagnostics.md).

Local verification passed 197 Android tests and 411 Pi tests, with one known
strict BLE expected failure. Both local debug and optimized release builds
passed; GitHub repeated the release tests and signed builds.

Physical APK installation, watch Data Layer delivery, device Settings rendering
and real BLE transfer were not tested. No arm/disarm/bypass/panic commands, Pi
reboot, OS package upgrade or blank-card reflash were performed. This release
collects evidence; it does not fix or establish the cause of panel-session stalls.
