# Shared Client Diagnostics

Package: `com.remoteparadox.diagnostics`. Wire fields follow
`docs/client-diagnostics-contract.md`. Events use allowlisted string values;
unknown fields, invalid enums, quoted numbers/booleans and out-of-range values
are rejected during wire decoding. `record` drops invalid events without throwing.

Initialize once per process, supplying a directory inside `Context.noBackupFilesDir`,
the device (`phone` or `watch`), build version and build code. The module owns only
the `structured-diagnostics-v1` child directory, with owner-only permissions.
Repeated initialization is harmless; configuration is fixed for the process.
Call `bindScope(Diagnostics.scope(baseUrl, username))` before recording. Logout
must bind null; account/server changes must bind the new scope. Clear pending
upload files in the integration as well. No username, URL, token or label is stored.

The writer uses one daemon thread and a 128-slot queue, reserving a slot for
snapshot barriers. Recording performs no disk I/O and never waits for the writer.
Overflow drops events and declares truncation. Retention cleanup runs at startup,
write and snapshot, keeping at most 24 hours and 512 KiB including store metadata.
Files use 16 KiB segments; an oldest segment may be evicted as a unit. Backward
wall-clock changes discard records dated in the future. Status summaries are
deduplicated on change with a 60-second heartbeat and a bounded 64-key cache.

`snapshot()` drains accepted preceding work, with a two-second maximum wait.
Storage failures or saturation return an empty truncated log. The complete
serialized `DeviceLog`, including its envelope, is capped at 48 KiB. `maxBytes`
can reduce this cap, with a minimum of 256 bytes for the envelope. Smaller values
are invalid arguments. Lost/evicted data and export trimming set `truncated`.
Cleanup and scope deletion run on the writer, while scope invalidation is immediate.
Storage is best-effort diagnostics, not a power-loss-durable audit trail.

Create a fresh `Diagnostics.interceptor()` after binding each session, and attach
it once as an OkHttp application interceptor. It captures the current scope and
generation; callbacks from an old client, including A-to-B-to-A transitions and
`clear()`, cannot append to the new session. Pass the original scope explicitly
when recording other asynchronous callbacks. Only allowlisted route paths are
recorded. Every request receives a fresh UUID correlation header. Successful
status GET pairs are suppressed. The interceptor does not read bodies or headers
for logging, retry requests, alter responses, or configure TLS/upload policy.

The integration owns pinned HTTPS upload, Wear request timeouts/node validation,
private pending reports and clearing those pending reports on a scope change.
The singleton is intended for the application's normal single-process setup.

## Verification

Run from `android-app` with JDK 17:

```sh
./gradlew :diagnostics:testDebugUnitTest --offline --console=plain
```

`src/test/resources/client-report-v1.json` is compared byte-for-byte against the
actual Kotlin serializer output in the module tests. It contains synthetic phone
and watch logs for the Python contract integration. This module's tests do not
build the phone or watch applications and do not contact a server or alarm.
