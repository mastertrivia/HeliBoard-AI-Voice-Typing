# Voice Audit Step 12 — Late final-result / duplicate-commit protection

Date: 2026-08-13

## Scope
Only the boundary between an explicit Voice stop/cancel and Android's asynchronous `onResults()` callback.

## Finding
The controller's `stop()` and `destroy()` paths commit the currently visible unfinished partial text before cancelling `SpeechRecognizer`. Android can still deliver a late `onResults()` callback from that cancelled recognition session.

The previous implementation treated that late result as another stopped buffer and could therefore commit the same speech twice.

## Fix
When `explicitlyStopped` is already true, a late non-empty `onResults()` is now deliberately ignored. The explicit stop/destroy path has already committed the visible unfinished buffer.

Natural pause behavior is unchanged:

`onResults()` while Voice is active -> commit final segment -> restart recognition.

Explicit stop behavior is:

`stop()` -> commit current partial once -> cancel recognizer -> ignore late `onResults()` -> Voice OFF.

## Verification
- Natural final results still use `onVoiceFinalResult()`.
- Explicit stop still uses `onVoiceStoppedWithBuffer()` exactly once.
- `destroy()` still preserves the current partial before cleanup.
- No changes were made to language switching, formatting, buffer rendering, keyboard-collapse behavior, or error timing.
- Source was re-read after the edit and the new branch is reachable only after explicit stop/destroy has set `explicitlyStopped`.
