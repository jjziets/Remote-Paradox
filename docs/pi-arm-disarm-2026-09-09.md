# Intermittent Arm/Disarm Investigation - 2026-09-09

## Evidence from the Pi

Read-only inspection found bridge 1.0.9 and PAI 3.7.0. The bridge had been up
since 7 September at approximately 20:36 SAST, with no systemd restarts since
that boot. Sleep targets were masked, WiFi power save was off, and the current
firmware throttle flags were `0x0`.

Two phone disarms for partition 1 failed on 7 September. The journal records
`control_partition timeout` at 20:36:20 and 20:36:21 SAST, and the audit database
records `success=False` for both. They completed while the bridge was shutting
down after an app-requested reboot at 20:36:07. Their exact request start times
and how long they had waited were not recorded. Do not infer from this sequence
that the reboot caused the original problem.

Other observed panel communication losses:

- 7 September at 20:19:58 and 22:09:38 SAST.
- 8 September at 05:53:21 SAST.
- 9 September at 04:38:59 SAST, followed by a reconnect attempt at 04:39:08.

The state recorder scan from 8 September 11:10 to 9 September 11:11 contained
86,462 samples. All recorded bridge/BLE/NetworkManager states were active,
WiFi was connected, and throttle flags were `0x0`. One local HTTP health probe
failed, near the reconnect at 04:39:15. Four gaps of 5-6 seconds also occurred.
These samples do not prove panel health: the recorder only checked the HTTP
status of `/health`, whose body can report `alarm_connected=false` with HTTP 200.

On 9 September, the 05:29:05 phone disarm was accepted and a disarmed partition
event appeared at 05:29:07. The 8 September 21:40:44 watch arm was accepted;
the panel entered arming and later armed-away at 21:41:34. The final armed state
includes the panel's exit delay, not just command round-trip time.

Additional journal errors included a SQLite `cannot commit - no transaction
is active` exception during event recording, an unawaited `_broadcast_status`
coroutine, and WebSocket-not-connected exceptions. These are real defects that
can affect history/status feedback; their contribution to the command timeouts
has not been established.

## Interpretation and Next Capture

There is direct evidence of panel-command timeouts and intermittent panel-link
loss. The retained evidence does not identify whether wiring, UART transport,
PAI request handling, or another panel condition caused those failures. It
does not support attributing these particular failures to Pi sleep.

PAI returns an acknowledgement boolean, which the bridge includes as `success`
in an HTTP 200 response. The phone and watch source in this checkout checks
HTTP success without checking that boolean. This can obscure a failed action;
installed client versions have not been independently inspected in this run.

Added opt-in command diagnostics with request IDs, panel acceptance, elapsed
time, waiting-command records, selected PAI errors, cached partition flags and
poll freshness. See [the logging runbook](pi-github-operations.md#armdisarm-command-diagnostics)
for installation, retention, privacy, and retrieval commands.

The local checkout identifies as bridge 1.0.6, whereas the Pi runs 1.0.9 with
additional push-notification code. The logging patch must preserve the live
1.0.9 `main.py`; copying this checkout's whole bridge to the Pi would regress it.
The live and local `alarm.py` matched byte-for-byte before instrumentation.

Validation uses simulated commands. No live arm/disarm, panic, or bypass action
is required to verify logging. Two existing tests directly await the permanent
reconnect loop and cannot finish; exclude `test_lifespan_calls_connect_in_real_mode`
and `test_connect_retries_on_failure` when running the focused regression suite.

## Deployment and Verification

Installed at approximately 11:21 SAST on 9 September. Original live source was
backed up under `/opt/paradox-bridge/command-diag-backup-20260909.1YaKix`.
Before replacement, hashes verified that the installed source still matched
the inspected files. Only the instrumentation, diagnostics module, installer
and dedicated logging environment drop-in were deployed. The Pi remains on
bridge 1.0.9; its newer push-notification features were preserved.

- Focused local regression run: 103 passed, 2 existing nonterminating tests
  deselected. The initial broader run was interrupted after 92 passing tests
  when it reached the permanent-loop test.
- Shell syntax checks passed. The staged Python files compiled with the Pi's
  installed Python 3.11 before activation.
- Live diagnostics recorded startup at 11:21:12, panel reconnection at
  11:21:18, and fresh cached panel state at 11:21:22 SAST.
- An unauthenticated POST to `/alarm/disarm` returned HTTP 401 and produced
  correlated request-start/request-finish records, with no panel command sent.
- A separate Python process on the Pi used a mock command function to verify
  that a `False` result is preserved, sent once, and logged as not accepted.
- Bridge, BLE, nginx and the original state recorder were active; `/health`
  returned `alarm_connected=true`. The new log directory was mode 0750 with
  the bridge service's ownership.

These checks verify the diagnostics, not a fix for intermittent control. No
authenticated alarm-changing request was made during verification. Source
changes remain local and uncommitted; no release was created. A future bridge
reinstall/update must include this patch or it can overwrite the live diagnostic
instrumentation. Retest with the newer main branch before publishing.

## Phone and Watch Follow-Up

The user reported that both clients are affected: the panel sometimes beeps
while the phone remains busy or stale, and the watch app can show status while
the swipe-left status tile says Offline. These observations distinguish
command execution from status delivery; a beep alone is not proof that the
requested final arm/disarm state was reached.

Source inspection identified the following reproducible defects:

- The phone's HTTP command exception handler automatically called the BLE
  command path. It could replay a command after a lost HTTP response, and that
  fallback omitted the original partition/code arguments.
- Phone, watch app, and direct watch-tile actions did not consistently inspect
  the response body's `success` value. PAI timeout results could be hidden by
  HTTP 200. Acceptance is still not a guarantee of the final panel state.
- The phone stopped HTTP polling whenever its WebSocket was marked connected,
  even if no status messages arrived. Old socket callbacks could also change
  the state of a replacement connection.
- PAI status callbacks run on a worker thread. Scheduling a broadcast there
  with `asyncio.ensure_future` explains the unawaited-coroutine journal error.
  Shared SQLite operations had no transaction-level synchronization; status
  snapshots also mutated event tracking concurrently.
- WebSocket broadcasts sent sequentially without a deadline. One stalled
  client could delay every other client's update.
- The watch tile used `runBlocking` for its own network fetch, ignored the
  app's latest status, and labelled all fetch/authentication failures Offline.

Local fixes now check panel acceptance, remove automatic HTTP-to-BLE command
replay, disable transparent OkHttp connection retries, guard concurrent control
requests, detect silent phone sockets, synchronize database and PAI snapshot
operations, dispatch status on the API event loop, and bound/fan out WebSocket
sends. A disconnected panel is explicitly broadcast rather than leaving the
previous connected snapshot on screen. PAI's internal retry policy is unchanged;
these changes do not establish exactly-once delivery at the panel protocol level.

Bluetooth remains available when selected before sending an action (including
no-network and BLE-only operation), with the selected partition and code intact.
Only the automatic replay after an uncertain HTTP result was removed.

The watch app and tile now share a private, server/account-scoped status cache.
Tile network work runs off the UI thread with a two-second overall deadline.
Only status GETs are retried after an authentication refresh. Fresh snapshots
expire after 15 seconds through timeline validity, with an explicit stale
fallback that opens the app; expired cached arm/disarm controls are not shown
as current. The app requests tile updates when status changes. Direct tile
actions check acceptance and update the same cache while confirming status.
This follows the bounded-fetch/cache and external-event update approach in
[Android's tile update guidance](https://developer.android.com/training/wearables/tiles/update).

These behavior fixes are **local only**, not installed on the Pi or clients.
The live Pi still has only the earlier diagnostic instrumentation. No release
or version bump has been made. Both phone and watch need new builds for the
client fixes. Integrate with current `main` first; do not ship this older bridge
checkout over live 1.0.9 or remove its push-notification handling. In particular,
the newer `_check_alarm_push()` path must also run on the API loop when adapting
the status dispatcher.

No real alarm-changing command was sent, and neither device was attached to
ADB. Hardware arm/disarm confirmation, tile rendering/expiry on the watch,
and intermittent UART/panel fault resolution remain unverified. The retained
diagnostics are still needed to establish why the panel link sometimes times out.

### Local Verification

Starting checkout: `f83d4ad4ccfc3182b4e0c78ad35daa75e590dd89`, with the pre-existing
working-tree changes preserved. No formal TraceWeaver requirement baseline is
present in this repository; this is incident-scoped verification, not release
or formal traceability approval.

- Focused backend suite: **141 passed**, with the two pre-existing
  nonterminating reconnect-loop tests excluded. Covers database concurrency,
  PAI-thread dispatch, slow/dead sockets, disconnected status, diagnostics,
  alarm behavior and API routes.
- Expanded backend suite adding auth, audit and BLE tests: **201 passed,
  1 failed, 2 deselected**. The failure is the existing
  `TestBleClientTracker.test_multiple_clients`: unchanged production code clears
  prior clients on each new connection, while the unchanged test expects two.
  It also exists in the starting commit; no BLE tracking behavior was changed.
- Android: **52 phone tests and 54 watch tests passed**, including compilation
  of both app modules, false/missing action results, retry configuration,
  silent-socket expiry, tile fetch deadlines/cache expiry/authentication errors,
  and overlapping watch command protection.
- Local test command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testDebugUnitTest :watch-app:testDebugUnitTest`
  from `android-app`. Existing Android deprecation warnings remain.
- `git diff --check` passed. A final read-only Pi health check returned
  `alarm_connected=true`; no bridge restart or behavior deployment was performed
  during this follow-up.

## Integrated Release Candidate

The earlier local-only notes above describe the first investigation phase.
The candidate is now integrated on `codex/arm-status-release`, based on main
`d5252cc`, preserving its newer push and recovery features. The phone dashboard
fix from published `v1.2.30` is retained despite that tag's divergent history.
Target versions are bridge **1.0.10**, phone **1.2.31 (81)** and watch
**1.2.31 (25)**; phone code 80 was already published and cannot be reused.

Final local checks: **331 backend tests passed, 1 strict known BLE xfail**;
**52 phone tests and 68 watch tests passed** under Java 17. The two old unbounded
reconnect tests now cancel their monitor tasks explicitly. Independent Android,
backend and signed-deployment reviews have no remaining blocking findings.

The new [signed CI/CD runbook](signed-pi-deployment.md) defines artifact
verification, installation, durable rollback and the live receipt required to
claim deployment. This candidate evidence alone does not prove production
deployment or physical arm/disarm behavior. Record live results below after
CI and Pi verification, without publishing credentials or alarm state details.

## Live Deployment Result

The candidate was merged and released as signed bridge `1.0.10`; the earlier
local-only limitation is superseded for the Pi. CI and the live signed pull
deployment succeeded on 2026-09-09. The receipt records commit `bc0efe3` and
`state=verified` at 12:45:23 UTC. Authenticated HTTP and nine WebSocket snapshots
over 37 seconds remained connected with panel poll age at most 6.56 seconds.
TLS, configuration and user identities were preserved. The service-user update
check successfully delegated to the root verifier without needing a GitHub token.

Full evidence and remaining limits are in the
[tested deployment record](signed-pi-deployment.md#tested-deployment). This proves
delivery of the Pi fixes and live status flow, not elimination of intermittent
UART failures or successful physical alarm changes. Continue collecting precise
incident times and command logs if the next supervised phone/watch test fails.
