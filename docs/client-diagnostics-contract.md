# Client Diagnostics Work Item

Status: released on 12 September 2026; Pi deployment verified, physical app installation pending.
Authority: owner request on 12 September 2026 to collect phone and watch
active/debugging logs and send both to the Pi. No formal TraceWeaver baseline
exists in this repository; this is scoped engineering evidence, not a formal
requirements approval or a fix for the panel-session incident.

## Progress

- Scope/wire contract fixed; work split into disjoint recorder, Android and Pi ownership.
- Pi implementation independently checked: 411 tests passed, with one pre-existing strict BLE xfail.
- The exact Kotlin serialization fixture passes the real Python validator and authenticated upload endpoint.
- Security/reliability review repairs verified: corrupt scope metadata, interrupted captures, idle expiry and orphaned temporary files.
- Android tests independently rerun with `--rerun-tasks`: recorder 41, phone 75, watch 81 passed (197 total).
- Both debug and optimized release APK builds passed with JDK 17, including release lint and R8. The first offline release attempt needed an uncached lint dependency; the online rerun passed.
- Workflow YAML parses and `git diff --check` passes. Shared-recorder changes now trigger watch release inclusion and its tests run in Android CI.
- No physical phone/watch is connected to ADB; no alarm controls were sent. The live Pi HTTPS endpoint passed an authenticated synthetic report and idempotent retry check.
- The owner's follow-up on 12 September requested the missing release. PR #9 merged through main; GitHub signed and published phone/watch 1.2.33 (codes 82/27) and Pi 1.0.11. APK signatures/metadata/digests, public latest-release discovery and the Pi's signed deployment receipt were verified. See [release evidence](client-diagnostics-release-2026-09-12.md).

## Scope and Acceptance

- Capture structured application events continuously while the app or watch
  tile is active, including transport outcomes, UI/command transitions and
  received alarm-state summaries. Do not scrape raw system logcat.
- Keep at most 24 hours and 512 KiB per device in private no-backup storage.
  Cleanup occurs on app startup, write and export. Export the newest 48 KiB
  per device, declaring truncation. No permanent background wake lock.
- Phone Settings has Send diagnostic report, busy/result states, explicit
  watch availability and retry of a privately saved report after upload fails.
  Capture is useful even when the Pi cannot be reached. The watch app need not
  be open to respond; its WearableListenerService serves the recorded window.
- Ask all currently connected watch nodes (maximum four) and accept only a
  matching report ID, originating node and server/account scope. Select the
  first matching response; otherwise report unavailable or scope_mismatch.
  Bound the whole watch request to 10 seconds and always remove the listener.
- Upload only to the selected Pi over HTTPS with a nonempty verified
  certificate pin, using the existing bearer token. No trust-all diagnostic
  upload, redirects, background control commands or alarm-command retries.
- Clear captured/pending data on logout or server/account change. Bind
  in-flight callbacks/uploads to the original scope; never combine accounts.
- Pi accepts authenticated, strictly typed reports <=128 KiB, privately saves
  them with trusted receive time/uploader identity and a cached panel snapshot,
  returns a receipt, expires them after 24 hours and caps total storage.
  No raw upload endpoint or public report-download route.

## Wire Contract v1

All JSON names below are camelCase, with unknown fields rejected. Optional
event fields may be absent or null. Dates are UTC epoch milliseconds; device
clocks are not assumed synchronized. The server adds its own receive time.

`POST /system/diagnostics`:

```
{schemaVersion:1, reportId:UUID, watchStatus:"included"|"unavailable"|"scope_mismatch",
 phone:DeviceLog, watch:DeviceLog|null}
```

`DeviceLog`:
`{device:"phone"|"watch",appVersion:semver-string,buildCode:positive-int,
capturedAtMs:epoch-ms,truncated:boolean,events:[DiagnosticEvent]}`

`DiagnosticEvent` required fields:
`{timeMs:epoch-ms,monotonicMs:nonnegative-long,processId:UUID,sequence:positive-long,
kind:enum,source:enum}`

Optional fields: `requestId:UUID`, `route:enum`, `partitionId:int(1..32)`,
`zoneId:int(1..512)`, `httpStatus:int(100..599)`, `elapsedMs:nonnegative-long`,
`success:boolean`, `connected:boolean`, `mode:enum`, `openZones:int(0..512)`,
`bypassedZones:int(0..512)`, `error:enum`.

Allowed kinds: `app_start`, `foreground`, `background`, `command_requested`,
`command_finished`, `http_started`, `http_finished`, `http_failed`,
`status_received`, `ws_open`, `ws_closed`, `ws_failed`, `tile_render`,
`tile_action`, `report_requested`.
Allowed sources: `phone_app`, `watch_app`, `watch_tile`, `http`, `ws`, `ble`, `system`.
Allowed routes: `/alarm/arm-away`, `/alarm/arm-stay`, `/alarm/disarm`,
`/alarm/bypass`, `/alarm/panic`, `/alarm/status`, `/ws`.
Allowed modes: `disarmed`, `arming`, `armed_away`, `armed_home`, `triggered`, `unknown`.
Allowed errors: `timeout`, `connection`, `http`, `parse`, `cancelled`, `unknown`.
No arbitrary message, stack trace, URL, address, username, sensor label,
request/response body, PIN, credential or token fields.

Receipt: `{reportId:UUID,receivedAtMs:epoch-ms,expiresAtMs:epoch-ms,sources:["phone","watch"]}`.
`sources` contains only actually stored device sources. Same report ID + same
authenticated uploader + same body is idempotent; conflicting body is rejected.
An `included` report must contain a watch log and other statuses must not.

Wear message paths: `/paradox/diagnostics/request` and `/paradox/diagnostics/response`.
Request: `{reportId:UUID,scope:64-lowercase-hex}`.
Reply: `{reportId:UUID,scope:64-lowercase-hex,status:"included"|"scope_mismatch",log:DeviceLog|null}`.
Request max 256 bytes, reply max 64 KiB. Scope is SHA256 of canonical selected
base URL (lowercase scheme/host, explicit effective port, no trailing slash)
+ newline + exact username. It is compared but never treated as authentication.
Wear transport additionally requires the app package and signing certificate
to match, per [Android Data Layer security](https://developer.android.com/training/wearables/data/overview).

Every instrumented HTTP request gets a random `X-Diagnostic-Request-Id` UUID.
The Pi keeps its own trusted request ID and records the validated client UUID
as correlation metadata. Never accept client IDs as credentials or file paths.

## Shared Android API

New Android library `:diagnostics`, package `com.remoteparadox.diagnostics`.
Serializable data classes: `DiagnosticEvent` (optional fields default null;
time/monotonic/sequence default 0, processId default empty), `DeviceLog`,
`DiagnosticReport`, `DiagnosticReceipt`, `WatchLogRequest`, `WatchLogReply`.
Property names match the wire contract. `DiagnosticCodec.json` is strict,
encodes defaults and omits nulls.

Singleton `Diagnostics`:
- `initialize(directory:File,device:String,appVersion:String,buildCode:Long)`
- `scope(baseUrl:String?,username:String?):String?`
- `bindScope(scope:String?)` (clears previous account data when changed)
- `currentScope:String?`
- `record(event:DiagnosticEvent,scope:String?=currentScope)` (stamps clock,
  process UUID and sequence; never throws or records mismatched scope)
- `snapshot(maxBytes:Int=48*1024):DeviceLog`
- `clear()`
- `interceptor():okhttp3.Interceptor` (captures scope when constructed)

All recorder I/O must be bounded and failure-isolated. Use a single bounded
writer queue off the UI/network callback thread; snapshot drains prior queued
events without exposing another session. Unit tests exercise the pure file
store, strict fields/privacy, retention, byte cap, restart, scope switch and
network correlation. Avoid logging successful repetitive status GET pairs;
record summaries on change plus a bounded heartbeat instead.

## Ownership and Verification

- Shared recorder: `android-app/diagnostics/**`, root Android Gradle settings
  and plugin declaration only. No app/watch module files.
- Android integration: `android-app/app/**`, `android-app/watch-app/**` only.
- Pi endpoint: `paradox-bridge/**` only; no alarm control semantics changes.
- Integration owner: this contract, runbook/README, cross-component validation,
  review and publication decisions. Preserve the untracked prior incident note.

Required verification: Android module unit tests and both APK builds; API
auth/size/schema/privacy/retention/quota/idempotency tests; serialized client
fixture accepted by the real Python validator; scope-mismatch/offline/cancel
tests. No live alarm-changing tests. Physical device collection/upload remains
held unless real devices and the updated Pi endpoint are available.
