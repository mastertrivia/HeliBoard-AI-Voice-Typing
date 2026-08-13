# Desh English Engine — Phase 13B
Date: 2026-08-13

## Scope

Phase 13B establishes the resource-loading boundary for the original Desh English binary dictionary.

## Implemented

- Added `DeshEnglishDictionaryLoader.kt`.
- Copies `assets/desh_predictor/english/english_dictionary.bin` byte-for-byte to `filesDir/desh_english/english_dictionary.bin`.
- Reads the dictionary header through HeliBoard's existing `BinaryDictionaryUtils`.
- Uses the dictionary's own locale/type metadata.
- Opens it through the existing `ReadOnlyBinaryDictionary`/`BinaryDictionary` implementation.
- Validates the loaded binary dictionary before retaining it.
- Keeps the original `english_lm.db` asset path exposed for the next phase.

## Deliberately NOT implemented

- No English suggestion routing change.
- No English ranking change.
- No English learning change.
- No `Suggest.kt` modification.
- No HeliBoard dictionary facilitator modification.
- No Hindi changes.

Therefore this phase cannot change current English suggestions by itself.

## Verification

The source was statically checked for:

1. correct asset path;
2. real-file extraction before native dictionary opening;
3. header validation;
4. locale/type extraction;
5. `ReadOnlyBinaryDictionary` validation;
6. safe cleanup on failure;
7. no call site added to the English suggestion path.

The Android Gradle build could not be executed in this environment because the wrapper requires Gradle 8.14 from `services.gradle.org`, which is unavailable offline. Runtime dictionary loading therefore still requires the normal Android build/device test.

## Next

Phase 13C will connect English prefix/context queries to the Desh English dictionary/model path. It must not be started until the Phase 13B resource loader is verified on-device.
