# Watch tile bypass dialog closes immediately

## Cause

The watch tile opens the partition's action picker when the area is not ready.
Choosing Arm Away or Arm Stay sets `pendingArm` for open, non-bypassed zones,
then dismisses the action picker. That dismissal sets `tileActionDone`.
Previously `MainActivity` finished 150 ms later regardless of the pending bypass
dialog. A normal launcher entry did not auto-finish, explaining the workaround.

## Fix

`WatchState.canReturnToTile` now requires the return request, a dashboard screen,
no pending bypass confirmation, no command in progress and no error.
`MainActivity` observes this eligibility and checks the latest state again after
the delay. A new tile intent resets an old completion flag; a launcher intent
clears tile-origin tracking. The activity stays open throughout bypass and the
following arm command. No bypass, authentication or panel command rules change.

This is watch-only: target release **1.2.32 (26)**. Phone **1.2.31 (81)** and
bridge **1.0.10** are unchanged; no Pi deployment is required.

## Verification

Java 17 unit tests: **76 watch tests and 52 phone tests passed**, including eight
new regressions covering both arming modes, one enabled mode, bypass-to-arm
handoff, cancellation, in-flight commands, failures and fresh launcher/tile state.
The tests exercise state policy, not on-device Compose rendering. No watch was
attached through ADB, and no real alarm/bypass command was sent.

After installing the signed watch release, verify with a supervised test:
open the status tile with an active zone, select the affected area and arm mode,
and confirm that bypass consent remains visible. Cancel once to verify no command
is sent. Only confirm bypass/arming when it is safe for the actual alarm system.
