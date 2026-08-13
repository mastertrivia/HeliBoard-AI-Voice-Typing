# Desh English Engine — Phase 13E

## Actual LM runtime bridge recovered

This phase found the critical native boundary that earlier phases had not yet established.

The supplied Desh APK contains:

`lib/arm64-v8a/libjni_latinime.so`

It exports the exact JNI symbol:

`Java_com_android_inputmethod_latin_BinaryDictionary_loadTrigramLanguageModelNative`

and the Desh DEX contains:

`BinaryDictionary.loadTrigramLanguageModel(String, AssetManager)`

which calls that native entry point.

Therefore `english_lm.db` is not merely an inert copied asset: Desh's own BinaryDictionary has an explicit native LM-loading path.

## Changes in this phase

1. Added the Desh `BinaryDictionary.loadTrigramLanguageModel(...)` bridge to HeliBoard's Java BinaryDictionary.
2. Added the matching native declaration.
3. Added the exact Desh ARM64 `libjni_latinime.so` binary, byte-for-byte.
4. Changed `DeshEnglishDictionaryLoader` to invoke the native LM loader using the original asset path:

`desh_predictor/english/english_lm.db`

and the Android `AssetManager`.

## Important

This phase does NOT add a new Kotlin scoring algorithm. The intent is specifically to let the original Desh/LatinIME native machinery consume the original LM resource.

## Verification performed

- Desh `libjni_latinime.so` SHA-256: `3d228ab491685827cc87fda450e658ef14662e52392b6584f452e12441c2604c`
- HeliBoard packaged copy has the same SHA-256: identical.
- The Desh binary exports `loadTrigramLanguageModelNative`.
- Java bridge signature was added.
- The English loader invokes the bridge.
- Original `english_lm.db` asset remains unchanged.

## Build limitation

A full Android/Gradle build was not available in this environment because the required Gradle distribution is not cached and network download is unavailable. Therefore no claim of a successfully compiled APK is made here.

## Scope boundary

English learned/user-word integration is NOT implemented in this phase. This phase only establishes the actual Desh English dictionary + native LM loading path.
