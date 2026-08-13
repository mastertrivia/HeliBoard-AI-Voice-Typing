# Speechnotes Voice Buffer — Final Phase 2

Date: 2026-08-13

## User-visible behavior required

The HeliBoard Voice input must behave like the supplied Speechnotes dictation buffer. The only intentional difference is placement: Speechnotes displays its live buffer in its own application UI; HeliBoard displays the same live buffer inside the keyboard's existing suggestion-strip area.

### Required flow

1. User taps HeliBoard's normal Voice input button.
2. Continuous recognition starts using the existing Speechnotes-derived recognition controller already integrated in HeliBoard.
3. Recognition segments may finish internally. This must NOT paste anything into the application text field.
4. Partial recognition is shown only in the live buffer.
5. Final recognition segments are accumulated into the same live buffer.
6. Recognition automatically restarts after a segment ends while Voice remains active.
7. The live buffer remains visible and continues accumulating while the user speaks.
8. Only when the user explicitly stops Voice is the accumulated buffer committed once to the target text field.
9. The live buffer is immediately removed.
10. HeliBoard's normal suggestion strip returns immediately.

## Critical correction from the earlier Phase 2 implementation

The earlier Phase 2 implementation incorrectly committed every recognition segment as soon as Android delivered `onResults()`.

That behavior has been removed.

`onResults()` now only appends the recognized text to the in-memory voice buffer and updates the live display. It does not call `InputConnection.commitText()`.

The only commit path is the explicit-stop callback:

`onVoiceStoppedWithBuffer()` → `LatinIME.commitVoiceText()` → current application's `InputConnection`.

## Current buffer behavior

The buffer contains all finalized recognition segments received during the active dictation session. The current partial recognition is also included in the visible preview. If Voice is explicitly stopped while only partial recognition is available for the current segment, that visible partial is retained in the final buffer before the single commit.

## UI behavior

The buffer uses HeliBoard's current key-text theme color rather than a hard-coded foreground color. Therefore it remains readable across HeliBoard themes.

The buffer is hosted inside `SuggestionStripView` and does not create a floating window outside the IME.

The existing dynamic-height implementation expands the strip up to five visible lines and scrolls within that area when the text becomes longer. This is the HeliBoard placement adaptation; the recognition/commit semantics remain independent of that UI.

## Files changed for the final buffer correction

### `app/src/main/java/helium314/keyboard/latin/voice/ContinuousSpeechRecognizer.kt`

- Added the persistent voice-document buffer.
- Final recognition results are accumulated instead of committed.
- Partial results display the accumulated buffer plus current partial recognition.
- Explicit Voice stop flushes the complete buffer through a dedicated callback.
- Explicit stop clears the buffer after handing it to HeliBoard.
- Automatic recognition-session boundaries remain internal and do not terminate the user-visible dictation session.

### `app/src/main/java/helium314/keyboard/latin/LatinIME.java`

- `onVoiceFinalResult()` now updates the live buffer only.
- Added `onVoiceStoppedWithBuffer()` as the sole Voice-buffer commit path.
- Existing `commitVoiceText()` remains the target-editor commit implementation.
- Voice UI is hidden after the explicit stop and normal suggestions are restored.

### `app/src/main/java/helium314/keyboard/latin/voice/VoiceTranscriptionBufferView.kt`

- Remains the HeliBoard-hosted live buffer view.
- Uses `ColorType.KEY_TEXT` for theme-aware text rendering.
- Supports multi-line expansion and a five-line visible viewport.

### `app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt`

- Hosts and removes the temporary live buffer inside the existing suggestion area.
- Restores the normal suggestion strip after Voice stops.

## Verification performed

- Confirmed there is exactly one `ContinuousSpeechRecognizer.Callback` implementation in HeliBoard.
- Confirmed `commitVoiceText(text)` is called from the new explicit-stop buffer callback rather than from `onVoiceFinalResult()`.
- Confirmed the live buffer is cleared after the explicit-stop commit.
- Confirmed the normal suggestion strip is restored through `hideVoiceTranscriptionBuffer()`.
- Confirmed the buffer text uses HeliBoard theme key-text color.

## Build note

The source archive was prepared successfully. A final Gradle APK compilation could not be executed in this environment because the Gradle wrapper requires downloading Gradle 8.14 and outbound network access is unavailable. Therefore this archive is a source-level implementation and has not been falsely labelled as a successfully compiled APK.


## Correction — 2026-08-13 07:38 IST: natural-pause commit parity

A code-level reinspection of the supplied Speechnotes APK found an important behavior that the earlier Phase-2 implementation had reversed. In the APK, `ContSpeechRecognizer.onResults()` feeds the completed recognition segment through the Speechnotes result callback while the continuous recognizer remains active; `RecognizerService.q(...)` then forwards the result and restarts its timer/listening path. Therefore a natural pause commits the completed segment immediately, while the microphone remains active.

HeliBoard was corrected to match this behavior: each natural-pause final result is committed immediately, the live preview is reset to `Listening…`, and the next recognition session starts automatically. Explicit Stop only commits the currently unfinished partial segment, then restores normal HeliBoard suggestions.

The previous statement that the entire accumulated dictation was held until explicit Stop was incorrect and is superseded by this section.
