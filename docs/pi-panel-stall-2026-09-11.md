# Panel Communication Stall - 2026-09-11

## Outcome

The user reported repeating keypad beeps and an unresponsive/stale phone app
at approximately 22:31 SAST. Bridge 1.0.10 was running. The panel connection
recovered automatically at 22:32 without a Pi reboot or service restart.
This investigation did not send arm, disarm, bypass, panic or other control
commands, and did not change installed software or alarm configuration.

A subsequent authenticated, read-only status request showed both partitions
disarmed, no active alarm or tamper flags, and zone 16 bypassed. The user was
warned to check that bypass before arming again. Physical keypad/beep recovery
and the condition of the sensor were not independently verified.

## Timeline

Times below are SAST (UTC+02). Command diagnostic timestamps are UTC.

| Time | Evidence |
| --- | --- |
| 22:18:22 | Watch arm-away request for partition 2 accepted in 105 ms. |
| 22:19:13 | Event history records partition 2 armed-away. |
| 22:29:33 | Zone 16 opened and entered alarm; partition 2 became triggered. |
| 22:29:40 | Zone 16 closed. |
| 22:29:41-42 | Watch disarm accepted in 690 ms. |
| 22:29:43 | Partition 2 arm flag false and zone alarm flag cleared, but partition alarm flags remained true in cached state. |
| 22:29:53 | Last completed status update already 19.77 seconds old; serial request lock held. |
| 22:30:35 | Phone disarm started against 61.88-second-old panel status and a held request lock. |
| 22:31:09 | PAI logged `control_zone timeout`; API audit recorded a bypass for zone 16. Its start time is not in command diagnostics. |
| 22:31:18 | Phone disarm returned `success=False` after 43.064 seconds; status age 104.94 seconds. |
| 22:32:01 | PAI reported lost communication and stopped polling. |
| 22:32:04-09 | Existing reconnect loop reconnected to the panel. |
| 22:32:10 | Event history recorded zone 16 bypassed. This is observation time, not proof of when the panel changed it. |
| 22:32:13 | Fresh status: both partitions disarmed, partition alarm flags false. |

The preserved 24-hour diagnostic window also contains a stall at approximately
03:07:48-03:10:08 SAST. The first loss of panel replies is still unexplained;
do not attribute it to a particular user command, wiring fault, or sensor fault
without more evidence.

## Pi and Deployment Evidence

- Pi uptime exceeded four days. Bridge/BLE process start times remained
  9 September 14:45; both systemd restart counters were zero.
- All 240 one-second samples from 22:29:00 through 22:32:59 showed bridge,
  BLE, nginx and networking active, Wi-Fi connected, and throttle flags `0x0`.
  There were no sample gaps; 239 HTTP health probes passed and one failed.
- Minimum available RAM was 154,112 KiB; maximum sampled temperature was
  53.154 C. These observations do not indicate sleep, an OS crash, overheating
  or reported undervoltage during this incident.
- Sleep, suspend, hibernate and hybrid-sleep targets remain masked.
- Signed updater rejected the stale release as unhealthy at 22:31:38 without
  reinstalling it. At 22:36:48 it reported `Already verified 1.0.10`.
  The receipt still identifies the 9 September deployment of commit
  `bc0efe36d9e732311a9d0a6476171e3dd84165ab`.
- Kernel logs contain recurring `export_store: invalid GPIO 17` messages
  before, during and after the incident. No causal connection is established.

## Confirmed Software Defects

1. **Stale panel data is presented as connected.** `AlarmService.is_connected`
   checks the serial transport flag, not completed-poll freshness. The API and
   WebSocket can therefore supply old panel state while the internal diagnostic
   monitor already marks it stale. The reconnect monitor waits for PAI to
   declare failure, which took roughly 148 seconds after the last full poll.

2. **Internal command retries remain enabled.** Installed PAI 3.7.0
   `Paradox.send_wait` defaults to five attempts. Partition and zone
   `PerformAction` calls use that default; `Paradox(retries=1)` in our connect
   code does not override it. An isolated mock-connection reproduction on the
   Pi produced five writes after lost acknowledgements, with no serial access.
   The exact number of actual writes during the incident was not captured.
   These retries are a plausible contributor to repeated beeps, not proof of
   their physical cause. Both bypass and clear-bypass map to action `0x10` in
   the installed Spectra/Magellan implementation, making blind replay unsafe.

3. **Bypass falsely reports success.** `/alarm/bypass` discards the boolean from
   `bypass_zone`/`unbypass_zone` and always returns `success=True`. An isolated
   local mock returning `False` reproduced the incorrect API result. A timeout
   means the outcome is unconfirmed, not necessarily that no action occurred.

4. **PAI diagnostic handler targets the wrong logger.** The handler attaches to
   `paradox`, while installed PAI logs under `PAI.paradox.paradox`. Consequently
   important timeout messages exist in the journal but not as `pai_signal`
   records. Bypass requests are also outside the current middleware path list.

## Required Follow-Up

Treat this as a panel-session reliability fix, not an OS upgrade or reboot fix:

- Bound command waiting and prevent automatic replay of alarm-changing
  operations after missing acknowledgements. Keep safe read-only polling
  separate from control commands and preserve existing authentication.
- Detect stale completed polls, stop presenting cached values as fresh, and
  recover the panel session sooner. Cancel/drain old polling and command tasks
  before replacing the connection; cancellation handling in PAI must be tested.
- Return honest bypass acceptance/unknown outcomes, trace bypass requests, and
  connect the allowlisted diagnostics to the actual PAI logger namespace.
- Test lost acknowledgements, queued commands, cancellation, stale callbacks,
  reconnect races, and bypass toggles with simulated serial I/O before release.
- Verify any eventual signed deployment on the Pi without sending real alarm
  commands; supervised phone/watch/keypad checks remain a separate step.

Independent read-only reliability review reproduced stale command eligibility
and three lifecycle hazards with controlled mocks: an old polling finalizer
can clear a replacement session's connected flag; an old callback can refresh
replacement freshness; and overlapping disconnect/connect can close the wrong
transport. It also demonstrated that cancellation-swallowing command code can
return success after a nominal `wait_for` deadline. A simple earlier reconnect
or timeout wrapper is therefore not a sufficient safe fix. The reviewer ran
117 existing alarm/diagnostic/status/API tests successfully; those tests do
not establish coverage of these newly reproduced failure cases.

No behavior patch, new release, or claim of a permanent fix accompanies this
diagnostic record. The underlying reason that panel replies stopped remains
open. Faster recovery alone would mitigate the symptom, not establish its cause.

## Evidence Location and Privacy

Private evidence is preserved on the Pi at
`/var/log/paradox-bridge/incidents/20260911-2231/` (root-only). It contains
command diagnostics, one-second recorder files, service/kernel/updater
journals, the deployment receipt and selected audit/event database rows.
This incident snapshot is separate from the rolling 24-hour logs and requires
manual retention review after the incident is resolved.

Do not publish the raw evidence: service journals can contain WebSocket bearer
tokens in query strings. This document intentionally omits credentials, client
addresses and sensor labels. See also [the previous investigation](pi-arm-disarm-2026-09-09.md)
and [the operational logging guide](pi-github-operations.md).
