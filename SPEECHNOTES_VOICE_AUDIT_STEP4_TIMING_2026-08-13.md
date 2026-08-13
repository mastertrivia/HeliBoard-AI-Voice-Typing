# Speechnotes Voice Audit — Step 4: Timing

Date: 2026-08-13

## Scope
Only timing/session-boundary behavior was changed in this step.

## Finding
The previous HeliBoard implementation contained an unsupported 3.5-second local no-speech watchdog. That was not justified by the supplied Speechnotes APK and could incorrectly interrupt a normal pause during continuous dictation.

The supplied Speechnotes APK exposes a user preference for how long recognition may remain without speech (`KEY_PREFS_TIME_TO_NO_SPEECH`), with resource values including 2 Min, 5 Min, 10 Min and 30 Min. This is fundamentally different from a hard 3.5-second IME timeout.

## Change
The arbitrary 3.5-second watchdog was removed. HeliBoard now relies on Android SpeechRecognizer terminal callbacks (`onResults` / `onError`) for recognition-session boundaries and immediately/briefly schedules the next session only after the previous session has ended.

Restart delays are now treated only as recognizer teardown/restart scheduling delays, not as speech/silence timers.

## Verification
- No `noSpeechWatchdog` remains.
- No `NO_SPEECH_WATCHDOG_MS` remains.
- Natural pause does not trigger a locally invented 3.5-second cutoff.
- `onResults()` commits the completed recognition segment and keeps Voice active.
- `onError()` restarts while Voice is active except for permission failure.
- Explicit stop still prevents restart.
- Step 1 buffer-safety code remains present.
- Step 2 active-language-switch code remains present.
- Step 3 rolling partial-window code remains present.

## Important limitation
The closed-source APK does not expose every exact internal millisecond constant in a form that can be reliably recovered from the supplied compiled artifacts with the available local reverse-engineering tools. Therefore this step deliberately removes an unsupported timing guess rather than claiming invented values are “100% identical”.
