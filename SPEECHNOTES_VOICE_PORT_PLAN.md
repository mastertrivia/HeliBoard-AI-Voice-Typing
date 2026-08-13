# Speechnotes → HeliBoard Voice Port Map

**Date:** 2026-08-13
**Status:** Phase 1 implemented in source; build verification pending local Gradle environment.

## Objective
Make HeliBoard normal `Voice input` behave as a continuous Speechnotes-style voice session while preserving HeliBoard's IME/editor integration. The AI Voice Typing feature is not part of this port.

## Reference APK
`Speechnotes_v5.0.8(508).apk`

The APK exposes/contains the following relevant identifiers: `ContSpeechRecognizer`, `RecognizerService`, `startVoiceRecognition`, `stopVoiceRecognition`, `onPartialResults`, `results_recognition`, `partialWindows`, `diffPartial`, `KEY_PREFS_BACKGROUND_LISTEN`, and `KEY_PREFS_TIME_TO_NO_SPEECH`.

## Port boundary

| Speechnotes behavior | HeliBoard implementation |
|---|---|
| Continuous recognition controller | `latin/voice/ContinuousSpeechRecognizer.kt` |
| Android speech recognition | `SpeechRecognizer` |
| Recognition callbacks | `RecognitionListener` |
| Partial recognition | internal controller buffer |
| Final recognition segment | `LatinIME.commitVoiceText()` |
| Text destination | current `InputConnection` |
| Start/stop button | existing `KeyCode.VOICE_INPUT` |
| Shortcut-IME dependency | removed from normal Voice path |
| AI Voice Typing | untouched |
| Live suggestion-strip buffer | Phase 2 |

## Why no floating buffer in Phase 1
The user requested the partial recognition buffer to remain internal initially. This avoids changing `SuggestionStripView` until the recognizer lifecycle is stable.

## Recognition lifecycle

```text
IDLE
  ↓ start
LISTENING
  ↓ partial
INTERNAL BUFFER
  ↓ final
COMMIT TO INPUT CONNECTION
  ↓
RESTART LISTENING
```

Manual stop is terminal for the current session. `onEndOfSpeech()` is not itself treated as a restart command; restart is scheduled from final-result/error callbacks so the recognizer is not started while its previous session is still closing.

## HeliBoard visibility fix
The old normal Voice path required `RichInputMethodManager.isShortcutImeReady()`. That made the toolbar Voice key disappear when no shortcut voice IME was available. Normal Voice now belongs to HeliBoard itself, so this dependency was removed while retaining password/email/no-microphone restrictions.

## Phase 2 (not implemented in this change)
1. Put the partial buffer into `SuggestionStripView`.
2. Hide ordinary suggestions while listening.
3. Render the live buffer in the suggestion area.
4. Scroll the live buffer right-to-left as new recognition text arrives.
5. Restore normal suggestions immediately after stop.

## Verification checklist
- English recognition
- Hindi recognition
- long speech
- natural pauses
- repeated pauses
- manual stop
- restart after a natural segment boundary
- recognition errors
- changing applications
- finishing input
- keyboard recreation
- AI Voice Typing remains independent
