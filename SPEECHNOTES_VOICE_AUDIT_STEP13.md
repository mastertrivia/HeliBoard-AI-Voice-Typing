# Voice Audit Step 13 — Recognition-session ownership / stale callback isolation

Date: 2026-08-13

## Scope

Only the lifetime of individual Android `SpeechRecognizer` instances was changed in this step. Steps 1–12 were not intentionally altered.

## Finding

The continuous controller reused one `SpeechRecognizer` object across explicit stop/restart and language-switch transitions. Android recognition callbacks are asynchronous, so a callback from a cancelled/destroyed recognition instance can arrive after a newer recognition session has already started. Without session ownership, an old `onResults()`, `onError()`, or `onPartialResults()` could affect the new session.

## Fix

Each `SpeechRecognizer` instance now receives a monotonically increasing `recognizerGeneration`. Its `RecognitionListener` captures that generation and ignores callbacks that do not belong to the currently active recognizer generation.

Explicit stop, destroy, and language-switch transitions invalidate the old generation, cancel/destroy the old recognizer, and clear the reference before a new recognizer is created.

Natural pause/restart remains within the same recognizer generation, because that is one continuous Voice session.

## Resulting flow

```text
continuous session
  -> recognition instance generation N
  -> natural pause
  -> final result / restart
  -> same generation N

explicit stop / IME destroy / language switch
  -> invalidate generation N
  -> cancel + destroy recognizer N
  -> stale callbacks from N ignored
  -> create recognizer generation N+1 when appropriate
```

## Verification

- Confirmed `onReadyForSpeech`, `onBeginningOfSpeech`, `onEndOfSpeech`, `onError`, `onResults`, and `onPartialResults` all reject stale generations.
- Confirmed natural recognition restarts retain the same generation.
- Confirmed explicit stop destroys the old recognizer so the next Voice start receives a fresh generation.
- Confirmed language switching destroys the old recognizer before creating the new locale session.
- Confirmed IME destruction invalidates the active generation before recognizer destruction.
- Confirmed no old `switchToShortcutIme()` call was reintroduced.

## Build limitation

Source-level audit and archive integrity were performed here. A complete Android Gradle build could not be executed in this environment because the required Gradle distribution is not locally cached and outbound network access is unavailable.
