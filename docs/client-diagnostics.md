# Phone and Watch Diagnostic Reports

This feature is released in phone/watch 1.2.33 and Pi 1.0.11. Install all three updated
components before collecting a combined report. Adding it to source does not
update installed devices. It adds evidence collection, not a fix for panel
communication stalls. See the [verified release and Pi deployment](client-diagnostics-release-2026-09-12.md)
for signed artifact checks and remaining physical-device tests.

## Capture

Open phone **Settings > Diagnostics > Send diagnostic report**. The phone
captures its log and requests the paired watch's recorded log, including watch
app and quick-panel activity. The watch app does not have to be foregrounded;
the updated watch listener responds to the request. Collection waits at most
10 seconds and reports when the watch is unavailable or using another account
or server. A phone-only report is still useful.

The phone saves the capture privately before attempting upload. If upload is
not confirmed, **Retry saved report** sends that same capture and report ID again.
Only a matching Pi receipt establishes that the report arrived. A timeout may
mean the report arrived but the receipt was lost; retries are idempotent.
The pending capture expires after 24 hours. Cleanup runs on startup, foreground,
and every minute while the process runs, including interrupted temporary writes;
it does not keep the device awake. Changing account/server or logging out clears
the prior account's diagnostics and pending capture. **Capture ID** means local
evidence; **Report ID** is shown only after a validated Pi receipt.

Upload requires login and HTTPS with the Pi certificate fingerprint from the
trusted server setup/invite. It does not use the app's legacy trust-all TLS
fallback, follow redirects or upload over plain HTTP. The paired-device
exchange uses the existing [Wear Data Layer](https://developer.android.com/training/wearables/data/overview),
which requires matching app package names and signing certificates. Google
documents encrypted Bluetooth transport and end-to-end encrypted cloud relay
when the devices are not directly connected.

## What Is Recorded

Both devices keep structured application diagnostics in private, no-backup
storage. Records include timestamps, process/sequence identifiers, command
and connection transitions, HTTP outcomes, received partition modes and
zone counts. HTTP request IDs link the client timeline to Pi command logs.
Client clock values are evidence supplied by the device, not trusted Pi time.

No raw logcat, request/response bodies, arbitrary exception messages, tokens,
PINs, passwords, server addresses, usernames or sensor labels are collected.
Existing raw BLE command and watch credential-sync payload logging is removed.
These reports do not contain all Android/OS crash logs or raw serial traffic.

Retention is at most 24 hours and 512 KiB per device, with cleanup on startup,
write and export. Android need not wake a stopped app simply to delete files;
expired entries are excluded on its next use. Each report exports the newest
48 KiB per device and marks truncation. An active capture therefore may cover
less than a full day. Stable status summaries are coalesced with a heartbeat
to avoid filling the log with identical polling results.

`http_finished.success` describes HTTP transport success, not panel acceptance.
Compare it with command outcome records and later state summaries. In
particular, HTTP 200 alone does not establish that an arm/disarm succeeded.
The known panel-session and bypass-result defects remain separate work.

## Find a Report on the Pi

Default directory: `/etc/paradox-bridge/client-diagnostics/`, alongside the
configured bridge database. `PARADOX_CLIENT_DIAGNOSTICS_DIR` can override it.
The directory is mode 0700 and reports are mode 0600. Only the bridge service
account and root should read them. There is no public report-download route.

```bash
ssh home@192.168.50.32
sudo ls -lt /etc/paradox-bridge/client-diagnostics/
sudo less /etc/paradox-bridge/client-diagnostics/REPORT-UUID.json
```

Use the report UUID shown by the app. Each file separates `clientReport`
(client-provided evidence) from the trusted Pi `receipt`, uploader identity,
and cached `panelSnapshot`. It deliberately does not copy raw journals that
may contain WebSocket bearer tokens. Keep reports out of Git and public issues.

To correlate a command, take its `requestId` from a client event and search
for the corresponding `client_request_id` in:

```bash
sudo grep -F 'REQUEST-UUID' /var/log/paradox-bridge/command-diagnostics/commands.jsonl*
```

The Pi retains its own separate `request_id`; client correlation IDs are not
authentication. Bypass and panic requests are now traced as well as arm/disarm.
PAI signal logging attaches to the actual `PAI` logger namespace, including
zone-command timeouts. Enable the existing [command diagnostic installer](pi-github-operations.md#armdisarm-command-diagnostics)
if that logging is not already active.

The Pi expires reports using its receive time, with cleanup every 60 seconds
and on upload. It caps storage at 64 reports / 8 MiB, accepts at most five new
reports per uploader per hour, and limits each upload to 128 KiB. Full storage
or rate limits return an error; they do not overwrite unexpired evidence.
Retrying an identical report does not consume another report slot. A manually
copied incident archive is outside this automatic retention policy.

## Verification

Local verification on 12 September 2026 passed: 197 Android unit tests (41
shared recorder, 75 phone, 81 watch), both debug and optimized release APK
builds, and 411 Pi tests with one pre-existing expected BLE failure. The real
authenticated Pi upload test consumes the exact Kotlin serialization fixture.
These are local checks, not a claim of device installation or deployment.

Run all three Android test suites before building release APKs:

```bash
cd android-app
./gradlew :diagnostics:testDebugUnitTest :app:testDebugUnitTest :watch-app:testDebugUnitTest
./gradlew :app:assembleDebug :watch-app:assembleDebug
```

From the repository root:

```bash
PYTHONPATH=paradox-bridge/src pytest -q paradox-bridge/tests
```

The GitHub Android workflow includes the shared recorder tests. Signed Pi
deployment continues through the [existing release path](signed-pi-deployment.md);
there are no diagnostic-specific OS package upgrades or alarm-changing tests.
Physical phone/watch collection and upload must be supervised after installing
all three updated components. Preserve the failing incident before restarting
anything; see [the 11 September evidence](pi-panel-stall-2026-09-11.md).
