# Panel Communication Stall - 2026-10-06

## Observed Incident

The owner reported that, after an alarm triggered, phone/watch disarm actions
were followed by repeated panel beeps and an armed display even after the
physical panel was disarmed. The Pi was running signed bridge 1.0.11. The USB
phone was running 1.2.33 (build 82), installed on 12 September.

The investigation did not send arm, disarm, bypass, panic, or other panel
control commands. It did not reboot the Pi or clear phone application data.

The relative timeline below comes from trusted bridge diagnostic timestamps.
Exact times and current alarm state are retained only in private evidence.
A request being accepted by HTTP does not establish panel
acceptance, and a timed-out command may already have acted.

| Elapsed | Evidence |
| --- | --- |
| 0 seconds | Disarm began with completed panel status 5.48 seconds old. |
| 19 seconds | The command returned unconfirmed after 19.014 seconds. HTTP returned 200 with `success=false`; completed status was now 24.5 seconds old. |
| 102 seconds | A second disarm request began with panel status 107.65 seconds old and the serial request lock held. |
| 143 seconds | The second command returned unconfirmed after 40.938 seconds; panel status was 148.59 seconds old. |
| 144 seconds | PAI declared polling communication lost and stopped its loop. |
| 151 seconds | The existing reconnect monitor started another connection. |
| 157 seconds | Panel connection was re-established. |
| Subsequent check | Read-only API status and health reported a fresh completed panel poll. |

Only two HTTP disarm requests occur in this incident window. This does not
prove the app sent commands continuously. Installed PAI 3.7.0 `send_wait`
defaults to five attempts per request, including `PerformAction` commands;
`Paradox(retries=1)` does not override those calls. Internal replay is a
plausible contributor to repeated beeps, but actual wire-write counts were
not recorded. The original reason panel replies stopped remains unknown.

At evidence collection the Pi had been running for over seven days, and the
bridge, nginx and state recorder were active. No Pi reboot was needed for
the observed recovery. This observation alone does not rule out every
hardware, power, wiring or serial fault.

## Diagnostic Transfer

The Pi diagnostic report folder and checked nginx access logs contained no
client diagnostic uploads at collection time. The owner's "saved" message
may refer to a private pending capture rather than confirmed server delivery.
Do not claim delivery until the app shows a validated Report ID and a matching
server receipt exists. Capture ID is only a local identifier.

Release APKs are not debuggable, so Android `run-as` cannot read their private
captures. Preserve security boundaries: do not root, uninstall, clear app
data, or replace the signed release with a debug build merely to obtain logs.
The strict diagnostic uploader requires a valid enrolled certificate pin;
the legacy API client can work without one. Missing pin, rejected upload,
timeout and invalid receipt must be distinguishable without logging secrets.

## Evidence

Private incident evidence is archived under the Pi's
`/var/log/paradox-bridge/incidents/` directory (root-only).
The archive includes bridge journal, command diagnostics and recorder files.
Raw journal data can contain WebSocket bearer tokens; do not publish it.
The archive is separate from rolling 24-hour retention and needs manual
retention review after resolution.

See [the September incident](pi-panel-stall-2026-09-11.md) for previously
reproduced retry, freshness and session-lifecycle defects, and
[client diagnostics](client-diagnostics.md) for the upload contract.

## Repair Qualification

Required checks before claiming repair:

- Lost acknowledgements do not replay an alarm-changing packet.
- Stale, expired, cancelled or retired operations cannot write later.
- Concurrent controls are rejected rather than queued behind a failed command.
- Safe status reads retain their retry policy.
- Swallowed cancellation cannot turn an expired operation into success.
- Poll siblings and old callbacks are drained/fenced before replacing the UART.
- Stale HTTP/WebSocket status is disconnected with no asserted partition state.
- Bypass returns actual confirmation instead of unconditional success.
- Phone/watch transport failure cannot leave old state represented as live.
- Diagnostic upload retains strict HTTPS pinning and a private retry capture.
- HTTP `503 Retry-After: 0` cannot replay control or diagnostic POSTs; safe
  status GET follow-ups remain unchanged.
- Python 3.11 CI and signed Pi deployment receipt are verified.

## Verification

The full Python 3.11 suite with the deployed PAI 3.7.0 library passed:
478 tests passed and one documented expected failure. New transport-double
regressions exercise one-write controls, late replies, cancellation, poll
sibling draining, callback ownership, slow connection establishment and the
one-shot close-session handshake before UART replacement.
No test used a real panel or UART.

Real MockWebServer JVM regressions reproduced OkHttp's hidden POST retry on
`503 Retry-After: 0` before the fix, then verified one request after the fix.
This additional retry path was found during review; it is not established as
the cause of this incident.

Actual Android 35 SDK compilation and unit tests passed across all modules:
46 shared diagnostics tests, 112 phone tests and 98 watch tests (256 total,
no failures or skips). The phone regressions cover cancellation before command
launch, ownership-safe gate release, abandoned BLE reply draining/quarantine
and panic-button availability independent of confirmed status. Dashboard
caller wiring is required by compilation, rather than a default argument.

Independent backend architecture and Android correctness reviews found no
remaining blocker after the close-handshake, command-gate, BLE-drain and panic
UI wiring repairs. The main session independently reran both complete suites.
Release and signed Pi deployment evidence are recorded below as they are verified.
Simulated tests cannot establish the physical cause of the first missing panel
reply.

## Signed Rollout Qualification

[PR #11](https://github.com/jjziets/Remote-Paradox/pull/11) merged as
`f05a27bab8882a966c1937a941cd1e0553fb9e65`. GitHub-hosted CI published
[phone/watch 1.2.34](https://github.com/jjziets/Remote-Paradox/releases/tag/v1.2.34).
Downloaded APK hashes matched GitHub's asset digests; both signatures matched
the installed phone and previous release certificate. The USB phone was updated
in place to 1.2.34 (build 83), without uninstalling or clearing data. The watch
APK is 1.2.34 (build 28); physical watch installation was not verified.

The first signed Pi deployment, bridge 1.0.12, failed fresh-panel qualification
and automatically restored 1.0.11. The root-owned receipt recorded
`state=rolled_back`. Configuration, TLS certificate/key and user-record
fingerprints were preserved, and the restored bridge resumed fresh polling.
The failed release remains immutable but is marked prerelease, so stable
discovery excludes it. Do not force this failed version.

The live PAI 3.7.0 constructor already used `definitions_loaded`; the stock
library used the typo `definitons_loaded`. Unconditional removal of the typo
raised `ValueError` on the live variant before connecting. Partially registered
callbacks and an empty retained owner then prevented the next cleanup. Read-only
source inspection found identical implementations of seven critical handshake,
polling, status and control methods; the constructor differed.

The bridge 1.0.13 follow-up reconciles both topic spellings per instance,
including duplicates, and cleans up failed construction. Any allocated UART
remains owned until drain and closure are proven. Regression tests exercise
both constructor variants through actual PAI parsing, simulated handshake,
EEPROM loading and all seven RAM status blocks. They also cover constructor
failure, callback isolation and failed UART closure. Live signed deployment
must still confirm fresh polling; passing simulated tests alone is insufficient.
The main session independently reran the complete Python 3.11 suite for this
follow-up: 487 passed and the same documented expected failure. Independent
architecture review accepted the exact adapter and service source hashes.

[PR #12](https://github.com/jjziets/Remote-Paradox/pull/12) merged as
`cf73de8f06a1be11e818af6030bc73aa01cb8654`. GitHub-hosted CI tested, signed and
published bridge 1.0.13. The workstation verified its pinned signature and all
68 archive files; the Pi pulled it using the existing signed updater.
The root-owned receipt recorded `state=verified` at
`2026-10-06T05:54:46.635403+00:00`, upgrading the restored 1.0.11 installation.
All 42 managed installed hashes plus verifier/public pin matched. Nine
pinned-TLS WebSocket snapshots over 26.52 seconds stayed connected, with panel
poll age at most 7.53 seconds. Authenticated HTTP and pinned HTTPS health agreed.
Configuration, TLS certificate/key and all five user records were unchanged;
database integrity and active bridge/BLE/nginx/recorder/timer services passed.
No physical alarm controls, reboot or OS package changes were performed.

## Remaining Limits

The existing PAI panic serialization failure was reproduced without real
alarm I/O and was not changed by this session-recovery repair. Emergency
actions are not physically qualified by these tests; do not treat a button's
availability as proof of delivery. The existing BLE client-tracker test remains
a documented expected failure. Neither limitation explains the first missing
panel reply in this incident.
