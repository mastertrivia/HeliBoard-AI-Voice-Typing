# Step 8 — Recognition Backend / Configuration Audit
Date: 2026-08-13

## Scope
Only the speech-recognition backend and recognizer configuration were audited. No UI, buffer, language-switch, formatting, or legacy-path changes were made in this step.

## Supplied Speechnotes APK evidence
The supplied `Speechnotes_v5.0.8(508).apk` contains references to:
- `android.speech.SpeechRecognizer`
- `android.speech.RecognitionListener`
- `createSpeechRecognizer`
- `setRecognitionListener`
- `android.speech.action.RECOGNIZE_SPEECH`
- `android.speech.extra.LANGUAGE`
- `android.speech.extra.LANGUAGE_MODEL`
- `android.speech.extra.LANGUAGE_PREFERENCE`
- `android.speech.extra.MAX_RESULTS`
- `android.speech.extra.PARTIAL_RESULTS`
- `android.speech.extra.DICTATION_MODE`
- `android.speech.extra.UNSTABLE_TEXT`

No embedded Whisper/TFLite/ONNX speech model was found in the supplied APK package during this audit. This supports the conclusion that the transcription backend is delegated to Android's speech-recognition service rather than an embedded independent acoustic model.

## HeliBoard implementation
The current HeliBoard controller uses the same public Android recognition API family:
- `SpeechRecognizer.createSpeechRecognizer(appContext)`
- `RecognitionListener`
- `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`
- active keyboard locale through `EXTRA_LANGUAGE`
- `EXTRA_LANGUAGE_PREFERENCE`
- `LANGUAGE_MODEL_FREE_FORM`
- `EXTRA_PARTIAL_RESULTS`
- `DICTATION_MODE`
- `UNSTABLE_TEXT`
- `EXTRA_MAX_RESULTS`

The normal HeliBoard Voice path therefore reaches the same Android recognition-service layer rather than introducing a separate speech model.

## Result
No additional speech backend component was identified in the supplied Speechnotes APK that is missing from the HeliBoard Phase-7 implementation and can safely be transplanted as an independent model/service.

No source-code change was made in Step 8 because the audit did not identify a justified backend replacement to add. The existing recognizer configuration is retained.

## Important boundary
This is source/package evidence, not a claim that two proprietary applications contain byte-identical code. The objective is matching the externally observable recognition architecture using HeliBoard's public Android API integration.
