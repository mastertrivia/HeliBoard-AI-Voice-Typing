# Phase 13C — Desh English Prefix Bridge

## What changed

- Added `DeshEnglishPredictor.kt`.
- Initialized `DeshEnglishDictionaryLoader` from `App`.
- English non-empty composing input now tries the original Desh `english_dictionary.bin` through the Phase-13B dictionary boundary.
- The returned candidates are passed directly into HeliBoard's existing `SuggestionResults`/suggestion UI.
- The temporary `DeshStyleLearningStore` is explicitly prevented from injecting candidates into English during this stage.

## What did NOT change

- `english_lm.db` is not loaded yet.
- Desh English learned-prefix/user-learning is not implemented yet.
- HeliBoard's normal English engine remains the fallback if the Desh dictionary cannot be loaded or returns no result.
- Hindi and other languages are unchanged.
- Suggestion UI is unchanged.

## Important accuracy/integrity note

This is a **prefix dictionary bridge**, not yet the complete Desh English engine. It deliberately does not claim that the Desh LM, learned-word system, or final Desh ranking has been transplanted. Those are later phases.

## Verification

- Source changes inspected: PASS
- Original Desh English assets retained: PASS
- Existing Hindi branch preserved: PASS
- English custom learner excluded from this branch: PASS
- Gradle compile attempted: BLOCKED because Gradle 8.14 distribution is not cached locally and network access is unavailable (`UnknownHostException: services.gradle.org`).

No runtime APK build can be honestly claimed from this environment until Gradle dependencies are available.
