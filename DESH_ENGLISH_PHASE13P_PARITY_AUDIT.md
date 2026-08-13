# DESH ENGLISH ENGINE — PHASE 13P
## Learning Parity / Integration Audit
Date: 2026-08-13
Status: IMPLEMENTED FIXES; BUILD NOT AVAILABLE IN THIS ENVIRONMENT

### Findings

1. The Phase-13O learned dictionary used one cached dictionary instance for every locale. That could cause an English locale switch (for example en-US -> en-GB) to reuse the wrong learned dictionary.

**Fixed:** the manager now caches one `DeshEnglishLearnedDictionary` per locale language tag.

2. The English suggestion merge used `SuggestionResults.addAll()`. `SuggestionResults.addAll()` delegates to `TreeSet.addAll()` and therefore bypasses the class's bounded `add()` method. This could allow the merged learned candidates to exceed the intended suggestion capacity.

**Fixed:** learned candidates are now inserted individually through `SuggestionResults.add()`.

3. A learned candidate could coexist with an existing candidate for the same word, producing duplicate visible words with different scores.

**Fixed:** before inserting a learned candidate, the existing same-word candidate is removed. The learned/native-scored candidate is then inserted through the normal score comparator.

4. The custom `DeshStyleLearningStore` remains excluded from English.

5. English learning continues to use the dedicated native `ExpandableBinaryDictionary` mechanism from Phase 13O.

### Resulting English learning path

```text
accepted English word
        ↓
DeshEnglishLearningManager
        ↓
locale-specific DeshEnglishLearnedDictionary
        ↓
ExpandableBinaryDictionary
        ↓
BinaryDictionary.updateEntriesForWordWithNgramContext
        ↓
persistent native learned dictionary
        ↓
BinaryDictionary.getSuggestions
        ↓
learned candidates
        ↓
merge by score / duplicate suppression / capacity
        ↓
HeliBoard suggestion strip
```

### Important limitation

This phase verifies and fixes the HeliBoard-side integration. It does NOT prove that the proprietary Desh APK's private Java/Kotlin learning orchestration is byte-for-byte identical; that remains an evidence limitation due to APK obfuscation.

### Build status

The environment still lacks a cached Gradle 8.14 distribution and cannot download it, so no successful APK compilation is claimed.
