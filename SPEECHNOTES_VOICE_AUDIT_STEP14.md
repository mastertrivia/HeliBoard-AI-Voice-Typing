# Speechnotes Voice Audit — Step 14 — Result Boundary
Date: 2026-08-13

## Scope
Only the empty/no-match recognition-result boundary was changed.

## Finding
The Step 13 controller restarted correctly after `ERROR_NO_MATCH` and `ERROR_SPEECH_TIMEOUT`, but it did not clear the last partial preview before restarting. That could leave stale text visible during the restart window.

## Fix
For `ERROR_NO_MATCH` and `ERROR_SPEECH_TIMEOUT`:

1. clear the current partial-result window;
2. clear the transient partial state;
3. send an empty partial update so the HeliBoard live buffer clears;
4. continue the existing controlled continuous restart.

A no-match/error boundary is not treated as a final transcription and is never committed to the target editor.

## Verification
- Valid `onResults()` still commits at natural pause.
- Explicit stop still commits only the unfinished partial.
- Stale callback generation protection from Step 13 remains intact.
- No-match/timeout cannot leave an old partial phrase visible during restart.
- No-match/timeout does not create text in the target application.
- No other voice behavior was changed in this step.

## Limitation
This is source-level verification. A device build/run is still required for runtime confirmation.
