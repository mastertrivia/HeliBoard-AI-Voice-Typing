# Phase 13O — Desh English learning isolation fix

## Result

Phase 13N had a concrete architectural weakness: English learned suggestions were being taken from HeliBoard's shared `UserHistoryDictionary`. Although that uses the same native `BinaryDictionary` mechanism, it is not a dedicated Desh-English learned dictionary.

Phase 13O fixes that boundary.

## New path

```text
English word accepted
        ↓
DeshEnglishLearningManager
        ↓
DeshEnglishLearnedDictionary
        ↓
ExpandableBinaryDictionary
        ↓
BinaryDictionary.updateEntriesForWordWithNgramContext()
        ↓
separate persistent Desh-English learned dictionary
        ↓
BinaryDictionary.getSuggestions()
        ↓
Desh English result set
```

## Changes

- Added `DeshEnglishLearnedDictionary.java`.
- Added `DeshEnglishLearningManager.kt`.
- English learning now writes to the dedicated dictionary instead of using the shared HeliBoard user-history dictionary as the Desh English candidate source.
- English suggestion lookup now reads from the dedicated learned dictionary.
- `DeshStyleLearningStore` remains excluded from English.
- Other languages retain the existing custom learner path.
- The normal HeliBoard user-history dictionary remains intact for its ordinary purposes.

## Why this is closer to Desh

The recovered Desh English learner also has a distinct learned dictionary object built around `BinaryDictionary`, with asynchronous/persistent native dictionary updates and native suggestion lookup. This change reproduces that architectural separation using HeliBoard's existing implementation of the same underlying native mechanism.

## Important limitation

This is still not claimed as literal byte-for-byte Desh learning code. The exact obfuscated Desh learner implementation is not available as readable source. The native dictionary update/query mechanism is being reused directly, while the dedicated English learned-dictionary boundary is now isolated.

## Verification

- Changed-file brace/syntax sanity checks: PASS.
- English custom `DeshStyleLearningStore` path: excluded.
- Dedicated learned dictionary source files present: PASS.
- Full Gradle/APK build: NOT AVAILABLE in this environment because the required Gradle distribution is not cached and network download is unavailable.

