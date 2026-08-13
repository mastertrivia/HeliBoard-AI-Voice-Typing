# Speechnotes Voice Port — 2026-08-13

## Scope

The normal HeliBoard `VOICE_INPUT` path is implemented as an internal continuous speech recognizer. The separate Gemini/API-based `AI Voice Typing` path is not modified.

## Findings from the supplied Speechnotes APK

The APK exposes `ContSpeechRecognizer`, `RecognizerService`, Android `SpeechRecognizer`/`RecognitionListener`, partial-result state (`partialWindows` / `diffPartial`), recognition intent extras including `android.speech.extra.DICTATION_MODE`, `android.speech.extra.UNSTABLE_TEXT`, language/model/partial/max-result settings, no-speech/background preferences, and voice-formatting commands including new line/paragraph, punctuation, parentheses/quotation and hyphen/dash commands.

## HeliBoard implementation

- `ContinuousSpeechRecognizer.kt`: continuous `SpeechRecognizer` lifecycle, rolling partial window, no-speech watchdog, bounded restart/error recovery, explicit-stop protection, active-subtype locale propagation, dictation/unstable-text extras.
- `SpeechnotesVoiceResultProcessor.kt`: independent reimplementation of the observable formatting/voice-command behavior identified in the APK. It is deliberately scoped to English command phrases to avoid mutating Hindi/other language speech.
- `LatinIME.java`: final voice results are passed through the processor before `InputConnection.commitText()`.

## Important boundary

The original Speechnotes proprietary source code is not copied verbatim. The HeliBoard implementation reproduces the observed behavior using HeliBoard/Android APIs. The actual speech-recognition backend remains the device's Android recognition service.

## Verification

Static source checks completed for the modified voice files. A full Gradle/Android build still must be run in a normal Android build environment.
