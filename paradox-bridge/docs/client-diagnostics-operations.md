# Client Diagnostic Storage

`POST /system/diagnostics` implements wire contract v1 in
`docs/client-diagnostics-contract.md` at the repository root. It requires a
valid bearer token whose subject still exists in the bridge users database.
Authentication finishes before any body read. Diagnostics never initiate
panel reads, alarm controls, retries, or reconnects.

## Storage and Limits

- Default directory: `client-diagnostics/` beside the active configuration
  file, normally `/etc/paradox-bridge/client-diagnostics/`.
- Override with `PARADOX_CLIENT_DIAGNOSTICS_DIR` in the bridge service
  environment. Use a dedicated absolute path on persistent local storage,
  writable by the bridge service user. This is independent of the trusted
  command logger's `PARADOX_DIAGNOSTICS_DIR`.
- Directory mode is `0700`; report files and the fixed `.lock` file are
  `0600`. Files are atomically installed as `<reportId>.json` and flushed
  before a receipt is returned. Client-supplied paths are never accepted.
- Body limit: 128 KiB, enforced both for Content-Length and while streaming;
  compressed bodies are rejected. Upload deadline: 15 seconds. At most four
  uploads are admitted concurrently, including work finishing after a lost
  HTTP response.
- Maximum 512 events per device, strict field/type/enum validation, maximum
  64-character semantic app version, and signed 64-bit nonnegative clocks.
  Optional event nulls and an absent/null unavailable watch log are accepted.
- Global limits: 64 data files and 8 MiB, plus one empty lock file. Each stored
  envelope is capped at 160 KiB. A temporary upload counts against the same
  data budget until renamed. Unexpired reports are never evicted to admit new
  ones: storage exhaustion returns HTTP 507.
- Five new reports per authenticated uploader per hour. The counter is based
  on retained server receipt times, survives restarts, and is enforced under
  the same process/file lock as quota and receipt creation. HTTP 429 includes
  Retry-After. The device clock is never used for rate limits or expiry.
- Receipt expiry is server receive time plus 24 hours. Cleanup runs at startup,
  before a write, and every 60 seconds while the bridge runs. Expired files
  are normally removed within one cleanup interval. Cleanup also removes
  abandoned temporary writes. While the bridge is stopped cleanup cannot run;
  startup reaps expired reports before subsequent uploads.

Same report ID, uploader and normalized validated JSON returns the original
receipt without another file, quota/rate charge, panel snapshot, or expiry
extension. Whitespace, key order and absent versus null optional fields do not
change report identity. A different uploader or changed content under a retained
ID returns HTTP 409. Once expired and removed, an ID can represent a new upload.

Each private JSON envelope separates `clientReport` (untrusted device evidence)
from `receipt`, `uploader`, `panelSnapshot`, `serverRequestId`, `clientRequestId`
and `bodySha256`. Uploader username/role come from the users database. The panel
snapshot reads cached attributes only. The exact response contains only
`reportId`, `receivedAtMs`, `expiresAtMs` and actual stored `sources`.

There is no HTTP download/list endpoint. An administrator retrieves a specific
receipt's file through the existing trusted SSH procedure documented by the
integration owner. Do not expose this directory through nginx or merge client
events into trusted server command logs. Report contents can still reveal alarm
activity; keep retrieved copies private.

Validation, serialization, directory scans and disk I/O run in worker threads.
Validation and storage error responses are generic and never echo supplied
fields, bodies, exception text, tokens or paths. A disconnected uploader may
still have a report stored; retry the saved report with the same ID/body.

## Command Correlation

The server generates its own request ID. One canonical UUID in
`X-Diagnostic-Request-Id` is retained separately as client correlation metadata;
invalid or duplicate values are ignored without failing an alarm command.
HTTP contexts are isolated across concurrent requests and reset on exit.

Existing command tracing now includes bypass, unbypass and panic, preserving
the exact control arguments, return values and exceptions. HTTP request timing
is recorded for the five alarm action routes only, avoiding status-poll log
storms. The PAI signal handler attaches to `PAI`, including the actual child
logger `PAI.paradox.paradox`, and accepts only known message templates. No
alarm-control retry, timeout, recovery or response semantics are changed.
