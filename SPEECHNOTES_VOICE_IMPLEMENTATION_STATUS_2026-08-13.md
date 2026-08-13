# Speechnotes Voice Port — 2026-08-13

## Scope

This change targets **HeliBoard's normal Voice input** (`KeyCode.VOICE_INPUT`). The separate Gemini/API-based **AI Voice Typing** feature is intentionally untouched.

## Source reference

The reference application is the user-supplied `Speechnotes_v5.0.8(508).apk`.
Observed APK structures include:

- `android.speech.SpeechRecognizer`
- `android.speech.RecognitionListener`
- `co.speechnotes.speechnotes.RecognizerService`
- `com.speechlogger.continuousspeechrecognitizer.ContSpeechRecognizer` references
- `partialWindows`
- `diffPartial`
- `KEY_PREFS_TIME_TO_NO_SPEECH`
- `KEY_PREFS_BACKGROUND_LISTEN`
- `KEY_TEMP_PARTIAL_RESULTS`
- recognition callbacks: `onReadyForSpeech`, `onBeginningOfSpeech`, `onEndOfSpeech`, `onPartialResults`, `onResults`, `onError`

The application documentation inside the APK states that Android's built-in `SpeechRecognizer` is used for voice transcription and that the device's configured recognition service supplies the recognition backend.

## HeliBoard replacement point

Original HeliBoard behavior:

`VOICE_INPUT` → `RichInputMethodManager.switchToShortcutIme()`

New behavior:

`VOICE_INPUT` → `ContinuousSpeechRecognizer` → Android `SpeechRecognizer` → configured recognition provider → `InputConnection.commitText()`

The Voice button is no longer dependent on an external shortcut IME being available.

## Current implementation

`app/src/main/java/helium314/keyboard/latin/voice/ContinuousSpeechRecognizer.kt`

Responsibilities now include:

- `SpeechRecognizer` lifecycle
- `RecognitionListener` lifecycle
- partial-result reception
- a rolling partial-result window (`partialWindows` analogue)
- internal partial-difference computation (`diffPartial` analogue)
- final-result buffering
- automatic recognition restart after normal result boundaries
- bounded recovery for recognizer errors
- no-speech watchdog
- explicit/manual stop that prevents automatic restart
- recognizer cleanup during IME destruction
- locale forwarding from the current HeliBoard subtype
- partial-result data kept internal; no suggestion-strip UI work yet

## Deliberate architectural choice

The port reproduces the observable recognition/session-management behavior rather than copying closed-source Speechnotes code verbatim. The speech backend remains Android's `SpeechRecognizer`, so the device/default recognition provider is still responsible for acoustic/language recognition.

## Deferred Phase 2

The live voice buffer is **not yet drawn in `SuggestionStripView`**. While voice mode is active, partial results remain internal. The planned Phase 2 will temporarily replace normal suggestions with the live voice buffer and restore normal suggestions when voice mode stops.

## Verification

A full Gradle compile could not be executed in this environment because the Gradle 8.14 distribution is not cached and network access to `services.gradle.org` is unavailable. The modified source remains the artifact to build on the user's machine.

## Phase 2 — live transcription buffer in the suggestion-strip area (2026-08-13)

### Reference inspection: supplied Desh Hindi Keyboard APK

The supplied `Desh Hindi Keyboard_v17.4.9(11749) (1).apks` was inspected directly from its `base.apk` resources. The relevant voice UI is not merely a label change in the ordinary suggestion strip.

The APK contains these dedicated voice resources/classes:

- `com.deshkeyboard.voice.view.VoiceInputInlineView`
- `com.deshkeyboard.voice.view.MarqueeTextView`
- `res/layout/voice_input_bar.xml`
- `res/layout/layout_voice_input_bar.xml`
- `res/layout/voice_top_section.xml`
- `res/layout/layout_voice_top_error.xml`
- resource IDs including `voiceInputView`, `voiceInputViewTopSection`, and `voiceInputViewTopError`

`voice_input_bar.xml` is a wrapper around `VoiceInputInlineView`.
`layout_voice_input_bar.xml` contains a dedicated `ConstraintLayout` with a marquee/live-text view, voice controls, and a Lottie listening indicator. Its ordinary voice bar also contains the English/Hindi/Hinglish language buttons visible in the supplied screenshot.
`voice_top_section.xml` is a second, separate voice section containing a `MarqueeTextView` and a button. This explains why Desh can visually add a section above its ordinary keyboard area while voice mode is active.

### HeliBoard comparison

HeliBoard already has a safer equivalent layout mechanism that should be reused rather than introducing a floating window or overlay:

```text
input_view.xml
  -> main_keyboard_frame.xml
       -> strip_container.xml
            -> SuggestionStripView
       -> KeyboardWrapperView / MainKeyboardView
```

`strip_container` is the actual vertical space above the keyboard and HeliBoard's `LatinIME.onComputeInsets()` already measures its runtime height:

```text
stripHeight = mKeyboardSwitcher.getStripContainer().getHeight()
visibleTopY = inputHeight - keyboardHeight - stripHeight
```

Therefore changing the runtime height of `strip_container` is a native HeliBoard layout operation, and its existing inset/touch-region calculation automatically follows the expanded height.

### Phase-2 implementation choice

We intentionally did **not** copy Desh's English/Hindi/Hinglish language selector UI because HeliBoard already has its own language-switch key and the requirement is a live transcription buffer only.

Instead, the existing HeliBoard suggestion-strip content is temporarily replaced by:

```text
Voice active
    ↓
normal suggestions hidden
    ↓
VoiceTranscriptionBufferView inserted into SuggestionStripView
    ↓
strip_container height expands with the live text
    ↓
keyboard remains the same keyboard; only the area above it grows
```

The buffer uses the existing suggestion-strip height as one line. It expands dynamically as the partial transcript wraps, up to **5 visible lines**. Once five lines are occupied, the `ScrollView` keeps the newest text visible and older text leaves the visible five-line window.

On a natural recognition boundary, the final chunk is committed to the target editor and the transient preview is reset to `Listening…` while continuous recognition continues.

On an explicit Voice stop or normal keyboard interaction, the buffer view is removed, `strip_container` returns to the original suggestion-strip height, and HeliBoard resumes its normal suggestions.

### Files changed in Phase 2

- `app/src/main/java/helium314/keyboard/latin/voice/VoiceTranscriptionBufferView.kt`
  - New in-IME live transcription buffer.
  - No overlay window.
  - Maximum visible window: five lines.
  - Auto-scrolls to newest partial text.
  - Uses HeliBoard's suggestion-strip height as the per-line unit.

- `app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt`
  - Added voice-buffer lifecycle methods.
  - Temporarily replaces ordinary suggestion contents.
  - Dynamically resizes its parent `strip_container`.
  - Prevents ordinary suggestion UI updates from destroying the active voice buffer.
  - Restores normal suggestions through the existing `removeExternalSuggestions()` pathway.

- `app/src/main/java/helium314/keyboard/latin/LatinIME.java`
  - Partial recognition results now feed the live buffer.
  - Final recognition chunks still go through the existing Speechnotes-style result processor and are committed once.
  - Voice start immediately creates the buffer.
  - Voice stop removes it and restores suggestions.

### Important separation

The Desh APK was inspected here only for the **layout/voice-buffer presentation mechanism**. The voice recognition engine remains the previously integrated Speechnotes-style/Android `SpeechRecognizer` path. Desh's own `DeshSpeechRecognizer` and other Desh-specific voice recognizer components are not mixed into HeliBoard.

## 2026-08-13 FINAL CORRECTION

The previous Phase-2 implementation committed each recognition segment immediately. That was not the required Speechnotes-style live-buffer behavior. The final Phase-2 correction changed this so recognition results accumulate internally and are committed only once when the user explicitly stops Voice. See `SPEECHNOTES_VOICE_PHASE2_FINAL_2026-08-13.md` for the authoritative behavior and file-level record.

## Stepwise final-audit fix — Step 1: preserve pending buffer on IME destruction

Date: 2026-08-13

During the final reverse-engineering audit, the voice controller could clear `latestPartial` during `destroy()` without first forwarding the unfinished segment to the target editor. This was fixed in `ContinuousSpeechRecognizer.destroy()`.

Behavior after this fix:
- If the IME is destroyed/collapsed while an unfinished partial voice segment exists, that segment is forwarded through the existing `onVoiceStoppedWithBuffer()` callback before recognizer destruction.
- The recognizer is then cancelled/destroyed and restart callbacks are removed.
- The voice state is explicitly returned to non-listening.
- Natural pause behavior and the existing explicit-stop path are unchanged in this step.

Only this single audit item was changed in this step; the next audit item is intentionally not modified yet.

## Audit Step 2 — 2026-08-13 — Active-language synchronization

### Change
When HeliBoard's active input subtype changes while continuous Voice is active, `LatinIME.onCurrentInputMethodSubtypeChanged()` now forwards `mRichImm.getCurrentSubtypeLocale()` to `ContinuousSpeechRecognizer.updateLocale()`.

### Runtime behavior
1. Current partial voice text is committed through the existing stop-with-buffer callback so it is not lost under the old locale.
2. The old recognition session is cancelled.
3. The recognizer locale is changed to the new HeliBoard subtype locale.
4. Voice remains logically ON; the Voice UI is not turned off.
5. A fresh recognition session starts using the new locale.

If Voice is not active, changing the subtype only updates the stored locale for the next Voice start.

### Scope
This is an audit Step 2 change only. No suggestion-buffer UI, generic keyboard-touch stop behavior, or recognition error policy was changed in this step.

### Verification
- Confirmed exactly one `updateLocale(Locale)` method in `ContinuousSpeechRecognizer`.
- Confirmed exactly one call site from `LatinIME.onCurrentInputMethodSubtypeChanged()`.
- Confirmed the new recognition `Intent` already uses the controller's `locale` through `RecognizerIntent.EXTRA_LANGUAGE` and `EXTRA_LANGUAGE_PREFERENCE`.
- Confirmed the old `switchToShortcutIme()` remains legacy infrastructure but is not the normal Voice toolbar execution path.
- Source archive integrity verified after packaging.
- Full Android compilation was not possible in this environment because the required Gradle distribution is not locally cached and external download is unavailable.

## Step 7 — legacy shortcut-IME voice path audit (2026-08-13)

- Audited `VOICE_INPUT` routing after Steps 1–6.
- Confirmed the active toolbar path is `LatinIME.onEvent()` → `toggleContinuousVoiceInput()` → `ContinuousSpeechRecognizer`; it does not call `switchToShortcutIme()`.
- Removed the now-unused `RichInputMethodManager.switchToShortcutIme()` method so the old explicit voice-to-shortcut handoff cannot accidentally be reintroduced through that method.
- Updated the stale `InputLogic` comment so it documents the internal continuous controller rather than the former shortcut-IME behavior.
- Kept the broader shortcut-IME discovery/cache machinery untouched because it is shared legacy infrastructure and removing it would be an unrelated behavioral change.
- Re-verified there are no remaining call sites for `switchToShortcutIme()`.

## Audit Step 12 — 2026-08-13 — Late-result duplicate protection

During final-stop/cancellation, Android may deliver `onResults()` asynchronously after `cancel()`. The explicit stop/destroy path already commits the visible unfinished partial segment, so a late final callback must not commit it again. The controller now ignores non-empty `onResults()` callbacks when `explicitlyStopped` is already true. Natural pause commits remain unchanged.

## Audit Step 13 — 2026-08-13 — Recognition-session ownership

The controller now assigns a generation to every `SpeechRecognizer` instance. Recognition callbacks from cancelled/destroyed sessions are ignored so an old asynchronous callback cannot mutate a newer Voice session. Explicit stop, IME destruction, and language switching invalidate and destroy the old recognizer; natural pause/restart remains within the same recognizer generation.
