# HeliBoard × Desh Hindi — Detailed Maintenance & Porting Record

**Document status:** Living maintenance record  
**Last updated:** 2026-08-12  
**Purpose:** Preserve an exact, reproducible record of every Desh-derived integration and every HeliBoard architectural change so that a future HeliBoard update can be merged without losing the custom work.

---

## 0. Golden rule for future updates

**Do not overwrite this fork with a new HeliBoard release.**

When updating HeliBoard, first obtain the new upstream source, then re-apply/port the changes documented in this file. Treat the sections marked **CUSTOM** as the authoritative map of the fork.

The safest future workflow is:

```text
NEW HeliBoard upstream
        │
        ├── compare every file listed in §6
        ├── preserve upstream changes where possible
        ├── re-apply CUSTOM changes
        ├── re-check assets/resources
        ├── build
        └── regression-test English / Hindi / Symbols / देश हिंदी
```

Never blindly copy an old modified file over a new upstream file: HeliBoard may have changed the same lines.

---

# 1. Project objective

This fork adds a separate Hindi subtype named:

> **देश हिंदी keyboard**

The goal is to make HeliBoard feel like the Desh Hindi keyboard while preserving HeliBoard's normal architecture and stability.

The work is intentionally divided into independent layers:

```text
┌──────────────────────────────────────────────────────────┐
│                    HeliBoard UI / IME                    │
├──────────────────────────────────────────────────────────┤
│ English       │ Hindi       │ Symbols │ देश हिंदी        │
│ own layout    │ own layout  │ own     │ own layout       │
│ own row policy│ own rows    │ rows    │ own row policy   │
├──────────────────────────────────────────────────────────┤
│                 Suggestion / prediction                  │
├──────────────────────────────────────────────────────────┤
│                 Personal learning                        │
├──────────────────────────────────────────────────────────┤
│            HeliBoard input/composition core              │
└──────────────────────────────────────────────────────────┘
```

The custom work must not turn English, ordinary Hindi, Symbols, or other layouts into dependencies of the `देश हिंदी keyboard` implementation.

---

# 2. Source/provenance: Desh APK

## 2.1 Source used

The Desh implementation work was based on the **original Desh Hindi Keyboard APK bundle supplied in this project conversation**, not on a website or a third-party description.

The supplied bundle was:

```text
Desh Hindi Keyboard_v17.4.9(11749) (1).apks
```

The APK bundle was inspected/decompiled to locate the native predictor, its assets, language-model files, and learning-related classes/identifiers.

## 2.2 Important Desh assets discovered

From the supplied Desh APK, the following assets were identified:

```text
assets/native_words.db
assets/native_lm.db
assets/english_lm.db
assets/transliteration.db
assets/reverse_transliteration.db
```

For the current HeliBoard fork, only the two Hindi/native prediction assets were brought into the project:

```text
app/src/main/assets/desh_predictor/native_words.db
app/src/main/assets/desh_predictor/native_lm.db
```

The current files are the original Desh native vocabulary/model resources used by the native predictor integration. They are **not converted HeliBoard `.dict` files**.

## 2.3 Desh native predictor components

The Desh APK was found to contain native predictor components corresponding to:

```text
libcommon_utils.so
libc++_shared.so
liblanguage_model.so
libnativepredictor.so
```

The current HeliBoard fork contains the arm64-v8a versions under:

```text
app/src/main/jniLibs/arm64-v8a/
```

The Java-side predictor wrapper was also ported into:

```text
app/src/main/java/com/deshkeyboard/suggestions/nativesuggestions/nativepredictor/NativePredictor.java
```

### Important maintenance note

These native binaries and proprietary Desh assets must be treated as **third-party/proprietary dependencies**, not as HeliBoard-original source. Do not describe them as HeliBoard code. Before public redistribution, verify the applicable Desh license/permission.

---

# 3. Desh behavior reverse-engineered/targeted

The supplied APK exposed a native prediction interface containing operations corresponding to:

```text
nativeLayoutPrefixSearch()
transliterationPrefixSearch()
getTopExactMatch()
getNextWords()
getWordMlId()
getWordMlFromId()
load()
loadLm()
```

The APK also exposed learning-related identifiers/paths including concepts corresponding to:

```text
NATIVE_LEARNT
learnt_dict_prefix_search
learnt_dict_suggestions
user_added_native_words
user_reordered_native_words
user_native_word_added
user_native_word_used
ENGLISH_LEARNT_SUGGESTIONS
PREDICTION_EN_PICKED_LEARNT_WORD
NEXT_WORD_PREDICTION
```

These observations are why this project treats **dictionary/vocabulary**, **prediction/ranking**, and **personal learning** as separate systems.

Do not collapse them into one dictionary file in future maintenance.

---

# 4. देश हिंदी keyboard: user-visible behavior

## 4.1 New subtype

Added to HeliBoard's subtype list:

```text
Display name: देश हिंदी keyboard
Main layout:  desh_hindi
```

The subtype is defined in:

```text
app/src/main/res/values/strings.xml
app/src/main/res/xml/method.xml
```

Current subtype extra value:

```text
KeyboardLayoutSet=MAIN:desh_hindi,NoNumberRow,NoShiftKey,NoShiftProximityCorrection,EmojiCapable
```

## 4.2 Dedicated layout

File:

```text
app/src/main/assets/layouts/main/desh_hindi.json
```

The layout is a dedicated five-row Devanagari layout and is not a replacement for HeliBoard's normal Hindi layouts.

## 4.3 Contextual vowel/matra row

The layout uses the custom key-data serializer:

```text
$ = desh_hindi_vowel_selector
```

Implemented in:

```text
app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/floris/KeyData.kt
```

The selector supports the intended state change:

```text
Initial state:
अ  आ  इ  ई  उ  ऊ  ए  ऐ  ओ  औ  ं

After a Devanagari consonant:
ः  ा  ि  ी  ु  ू  े  ै  ो  ौ  ँ
```

The selector is a **visual/layout state mechanism**. Hindi text composition itself must remain independent from the row editor.

## 4.4 Context state

`LatinIME.java` determines whether the current text before the cursor indicates the contextual state. The state is recalculated after relevant input/editor events and causes the keyboard to reload when the state changes.

This is intentionally isolated to:

```text
desh_hindi
```

and must not affect other languages.

---

# 5. Number-row and layout isolation fix

## Requirement

The final architecture must be:

```text
English:
    global Number row preference applies

Hindi / देश हिंदी:
    global Number row preference does NOT inject a number row

Symbols:
    Symbols' own number-row preference remains independent

Custom layouts:
    any number of rows; renderer calculates height dynamically
```

The key point is **not** to hard-code Hindi to five rows. A future editor must be able to add a sixth or seventh Hindi row without breaking anything else.

## Modified implementation

Primary file:

```text
app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/KeyboardParser.kt
```

The parser now distinguishes the active layout/subtype when deciding whether the global number row is injected. Hindi/Devanagari is excluded from the global number-row injection.

Symbols use their separate `numberRowInSymbols` policy.

The bottom-row sizing logic was also changed to use the **actual active MAIN layout row count**, rather than relying on a global four/five-row assumption.

### Desired invariant

```text
Edit English rows      → English changes only
Edit Hindi rows        → Hindi changes only
Edit Symbols rows      → Symbols changes only
Toggle English number  → English changes only
Toggle Symbols number  → Symbols changes only
Hindi row count change → Hindi resizes itself only
```

This is an architectural requirement, not a visual patch.

---

# 6. Exact CUSTOM file map

The following files are the main places that have been modified or added by this fork.

## 6.1 Subtype/resources

```text
app/src/main/res/values/strings.xml
```

**CUSTOM:** Adds display string for `देश हिंदी keyboard`.

```text
app/src/main/res/xml/method.xml
```

**CUSTOM:** Registers the new subtype and points it at `MAIN:desh_hindi`; includes `NoNumberRow` and other subtype flags.

## 6.2 Layout

```text
app/src/main/assets/layouts/main/desh_hindi.json
```

**CUSTOM:** Dedicated Hindi layout.

## 6.3 Layout parser / dynamic keys

```text
app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/floris/KeyData.kt
```

**CUSTOM:** Defines the serializable `desh_hindi_vowel_selector` key-data type.

```text
app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/LayoutParser.kt
```

**CUSTOM:** Registers the `DeshHindiVowelSelector` serializer for the relevant layout parsing path.

```text
app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/KeyboardParser.kt
```

**CUSTOM:** Integrates contextual row selection and the layout-isolated number-row policy.

## 6.4 Keyboard state/reload

```text
app/src/main/java/helium314/keyboard/latin/LatinIME.java
```

**CUSTOM:** Tracks whether the `देश हिंदी keyboard` is in the consonant/matra contextual state and triggers layout refresh when the state changes.

```text
app/src/main/java/helium314/keyboard/keyboard/KeyboardLayoutSet.kt
app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java
app/src/main/java/helium314/keyboard/keyboard/KeyboardId.kt
```

**CUSTOM:** Carries the contextual keyboard state through keyboard construction/cache identity so two different visual states are not incorrectly served from the same keyboard cache entry.

## 6.5 Desh predictor bridge

```text
app/src/main/java/com/deshkeyboard/suggestions/nativesuggestions/nativepredictor/NativePredictor.java
```

**CUSTOM/PORT:** Java/JNI bridge corresponding to the predictor interface discovered in the supplied Desh APK.

```text
app/src/main/java/helium314/keyboard/latin/DeshHindiPredictor.kt
```

**CUSTOM:** HeliBoard-side adapter. Loads the predictor lazily, loads vocabulary + LM, calls prefix/next-word prediction, converts returned strings to HeliBoard `SuggestionResults`, and falls back to HeliBoard if the native backend fails.

## 6.6 Desh prediction resources

```text
app/src/main/assets/desh_predictor/native_words.db
app/src/main/assets/desh_predictor/native_lm.db
```

**CUSTOM/IMPORTED:** Desh native Hindi vocabulary/index and language model assets from the supplied APK.

## 6.7 Desh native libraries

```text
app/src/main/jniLibs/arm64-v8a/libcommon_utils.so
app/src/main/jniLibs/arm64-v8a/libc++_shared.so
app/src/main/jniLibs/arm64-v8a/liblanguage_model.so
app/src/main/jniLibs/arm64-v8a/libnativepredictor.so
```

**CUSTOM/IMPORTED:** Native dependencies used by the Desh predictor bridge.

Current implementation deliberately limits the native predictor to `arm64-v8a`; other ABIs fall back to HeliBoard's normal suggestion path.

## 6.8 Suggestion integration

```text
app/src/main/java/helium314/keyboard/latin/Suggest.kt
```

**CUSTOM:** For `desh_hindi`, invokes the Desh predictor and merges the resulting candidates with the personal learner path; normal keyboards continue through HeliBoard's existing pipeline.

## 6.9 Personal learning

```text
app/src/main/java/helium314/keyboard/latin/personalization/DeshStyleLearningStore.kt
```

**CUSTOM:** Immediate local learning store. Records accepted words on first use, keeps usage count, last-use timestamp, and up to eight contextual keys per learned word. Provides prefix and next-word candidates.

```text
app/src/main/java/helium314/keyboard/latin/DictionaryFacilitatorImpl.kt
```

**CUSTOM:** Records accepted words into the Desh-style learner and clears learner data with the relevant dictionary group.

## 6.10 Dictionary metadata

```text
app/src/main/java/helium314/keyboard/latin/DeshHindiDictionaryInfo.kt
```

**CUSTOM:** Metadata/helper for the bundled Desh native vocabulary and its recorded entry count.

## 6.11 Application initialization

```text
app/src/main/java/helium314/keyboard/latin/App.kt
```

**CUSTOM:** Initializes the lazy Desh predictor bridge and personal learner store without forcing native predictor model loading at application startup.

---

# 7. Dictionary architecture: critical distinction

## HeliBoard dictionary format

HeliBoard's normal built-in dictionaries are AOSP/HeliBoard binary dictionaries loaded through classes such as:

```text
app/src/main/java/com/android/inputmethod/latin/BinaryDictionary.java
app/src/main/java/helium314/keyboard/latin/dictionary/ReadOnlyBinaryDictionary.java
app/src/main/java/helium314/keyboard/latin/dictionary/DictionaryFactory.kt
```

The Desh `native_words.db` file is **not** an AOSP `.dict` file.

Therefore:

```text
DO NOT:
rename native_words.db → main_hi-desh.dict
```

That would not make it a valid HeliBoard dictionary.

The current fork instead keeps the original Desh native vocabulary/model as read-only assets and accesses them through the Desh predictor interface.

## Current reported vocabulary count

The supplied Desh native vocabulary file contains a header count recorded by this fork as:

```text
85,676 entries
```

This is metadata reported by the supplied native DB header. It is **not a statement that there are exactly 85,676 human-readable unique Hindi words**; the proprietary index format has not been converted into a plain word list.

## Future dictionary-conversion work

If a genuine HeliBoard `.dict` is desired later:

```text
native_words.db
      ↓
reverse-engineer/export entries
      ↓
obtain words + frequencies/metadata
      ↓
HeliBoard dictionary builder
      ↓
main_hi-desh.dict
```

Do not replace the native predictor with a plain dictionary unless the prediction model is intentionally being discarded.

---

# 8. Personal learning architecture

The custom learner is intentionally separate from HeliBoard's existing `UserHistoryDictionary`.

Current path:

```text
accepted word
      ↓
DictionaryFacilitatorImpl
      ↓
DeshStyleLearningStore.record()
      ↓
word + count + last-used + context
      ↓
JSON persistence
      ↓
prefix / next-word candidates
```

Storage directory:

```text
<app filesDir>/desh_style_learning/
```

File naming:

```text
<language-tag>.json
```

Current limits:

```text
maximum learned entries per locale: 5,000
maximum stored contexts per word:   8
```

The learner intentionally accepts a word after **one accepted use**; it does not wait for HeliBoard's ordinary personal-dictionary consolidation threshold.

### Important backup status

As of this document version, the custom `desh_style_learning` directory is **not yet guaranteed to be included in HeliBoard's existing Backup/Restore package**.

This must be fixed before treating device migration as complete.

Required future change:

```text
HeliBoard Backup
      +
filesDir/desh_style_learning/
      ↓
backup archive
      ↓
Restore
      ↓
DeshStyleLearningStore restored
```

Do not forget this when implementing final backup integration.

---

# 9. What was NOT changed

The following must remain independent unless a future task explicitly changes them:

- HeliBoard's ordinary Hindi subtype(s)
- HeliBoard's Hindi Compact/Phonetic subtype(s)
- HeliBoard's normal English dictionaries
- HeliBoard's normal Symbols layouts
- HeliBoard's standard UserHistoryDictionary behavior
- HeliBoard's normal keyboard editor architecture
- HeliBoard's normal InputConnection/editor integration

The custom Desh predictor should not become a global predictor merely because `Suggest.kt` contains the adapter.

---

# 10. Stability/fallback design

The Desh native predictor is deliberately isolated.

Expected behavior:

```text
Desh predictor available
        ↓
use Desh predictor

Desh predictor unavailable
        ↓
use normal HeliBoard suggestions

Native predictor call throws/fails
        ↓
disable predictor for the process
        ↓
continue with HeliBoard
```

Native model loading is lazy. This prevents the custom predictor from becoming an application-startup failure point.

### ABI limitation

Current native predictor assets are installed for:

```text
arm64-v8a
```

On unsupported ABIs, HeliBoard must continue using its normal prediction path.

---

# 11. How to update from a future HeliBoard release

## Step 1 — obtain fresh upstream source

Do not start from the old modified ZIP if the goal is to update to a newer HeliBoard release. Download/extract the new official HeliBoard source.

## Step 2 — identify upstream changes

Compare the new source against the current fork for every file in §6.

Highest-risk files:

```text
KeyboardParser.kt
LatinIME.java
KeyboardLayoutSet.kt
KeyboardSwitcher.java
KeyboardId.kt
Suggest.kt
DictionaryFacilitatorImpl.kt
LayoutParser.kt
KeyData.kt
method.xml
strings.xml
App.kt
```

## Step 3 — copy independent custom files

These can normally be restored directly if upstream has not introduced a conflicting path/API:

```text
DeshHindiPredictor.kt
DeshHindiDictionaryInfo.kt
DeshStyleLearningStore.kt
DeshHindiPredictor.java bridge
native_words.db
native_lm.db
native .so files
```

Still review licenses and ABI changes before redistribution.

## Step 4 — manually re-apply changes in shared files

Never blindly replace a newer upstream file with the old fork version.

Instead, re-apply only the documented CUSTOM blocks.

## Step 5 — verify subtype registration

Confirm:

```text
দেশ हिंदी keyboard
MAIN:desh_hindi
NoNumberRow
```

## Step 6 — verify layout isolation

Test all combinations:

```text
English + Number row ON
English + Number row OFF
Hindi + Number row ON
Hindi + Number row OFF
Symbols + Symbols-number-row ON
Symbols + Symbols-number-row OFF
देश हिंदी + Number row ON
देश हिंदी + Number row OFF
```

Expected result:

```text
English setting affects English only.
Symbols setting affects Symbols only.
Hindi remains independent.
देश हिंदी remains independent.
```

## Step 7 — verify row count

Edit `desh_hindi.json` to temporarily create:

```text
4 rows
5 rows
6 rows
7 rows
```

Build/run each case and verify that the keyboard resizes rather than affecting another layout.

## Step 8 — verify contextual Hindi behavior

Test:

```text
क
क + ा
क + ि
क + ी
क + ु
क + ू
क + े
क + ै
क + ो
क + ौ
क + ्
```

Also test:

```text
cursor movement
backspace
selection replacement
paste
autocorrection
switching languages
switching to Symbols
returning to देश हिंदी
```

## Step 9 — verify prediction

Test:

```text
prefix completion
next-word prediction
beginning-of-sentence prediction
multiple previous-word contexts
predictor unavailable / unsupported ABI
```

## Step 10 — verify learning

Test the exact one-use scenario:

```text
Type a previously unknown word.
Accept/send it.
Delete/retype the prefix.
Verify the word appears immediately.
Restart the keyboard.
Verify it remains.
```

Then test context:

```text
previous word + learned word
same prefix under another context
```

## Step 11 — verify Backup/Restore

This is currently a pending task for `desh_style_learning`.

After implementation:

```text
Phone A
  learn a unique test word
  backup

Phone B
  restore
  type prefix
  verify learned word appears
```

---

# 12. Error diagnosis guide

## Symptom: `देश हिंदी keyboard` does not appear

Check:

```text
strings.xml
method.xml
subtype ID
mainLayoutName = desh_hindi
```

## Symptom: Hindi gets a number row

Check:

```text
KeyboardParser.kt
numberRowEnabled
isHindiDevanagariSubtype()
method.xml NoNumberRow
```

Do **not** solve this by hard-coding the Hindi row count.

## Symptom: Symbols lose a row when English number row changes

Check that:

```text
numberRowEnabled
```

and:

```text
numberRowInSymbols
```

are not being treated as the same policy.

## Symptom: adding a Hindi row changes another layout

Check for shared mutable row lists or shared cached `KeyData` objects.

The active layout must own its row structure.

## Symptom: vowel row does not change after typing a consonant

Check:

```text
LatinIME.java
DeshHindiVowelDiacriticMode
KeyboardSwitcher.java
KeyboardLayoutSet.kt
KeyboardId.kt
KeyData.kt
LayoutParser.kt
```

Especially verify that the contextual state participates in keyboard cache identity.

## Symptom: Desh predictor crashes or fails to load

Check:

```text
arm64-v8a libraries
libnativepredictor.so
liblanguage_model.so
libcommon_utils.so
native_words.db
native_lm.db
```

Then verify that `DeshHindiPredictor.ensureLoaded()` falls back instead of propagating the exception.

## Symptom: suggestions appear but learning does not

Check:

```text
DictionaryFacilitatorImpl.kt
DeshStyleLearningStore.kt
Suggest.kt
```

Check the directory:

```text
files/desh_style_learning/
```

## Symptom: learned words disappear after device migration

This is expected until §8's pending Backup/Restore integration is implemented.

---

# 13. Current implementation status — 2026-08-12

| Component | Status | Notes |
|---|---|---|
| `देश हिंदी keyboard` subtype | **Implemented** | Separate subtype |
| Desh Hindi layout | **Implemented** | `desh_hindi.json` |
| Dynamic vowel/matra row | **Implemented** | Context-sensitive |
| Hindi number-row isolation | **Implemented** | Global English setting does not inject it |
| Symbols number-row isolation | **Implemented** | Separate policy |
| Dynamic row count | **Implemented** | Based on active layout |
| Desh native vocabulary asset | **Bundled** | `native_words.db` |
| Desh native LM asset | **Bundled** | `native_lm.db` |
| Desh native predictor bridge | **Implemented** | arm64-v8a |
| Predictor fallback | **Implemented** | Falls back to HeliBoard |
| Immediate personal learner | **Implemented** | Separate JSON learner |
| Desh learner backup | **PENDING** | Must integrate into Backup/Restore |
| Genuine AOSP `.dict` conversion of Desh DB | **PENDING** | Native DB is not `.dict` |
| Full Gradle build in modification environment | **NOT VERIFIED** | Gradle 8.14 distribution unavailable locally |
| Full device regression test | **PENDING** | Must be performed on Android |

---

# 14. Date-stamped change log

## 2026-08-12 — Desh/HeliBoard integration record consolidated

Changes recorded in this document:

- Dedicated `देश हिंदी keyboard` subtype.
- Dedicated `desh_hindi` layout.
- Context-sensitive Hindi vowel/matra selector.
- Context state propagated through keyboard cache identity.
- Hindi number-row isolation.
- Independent Symbols number-row policy.
- Dynamic row-count sizing.
- Desh native vocabulary/model assets bundled.
- Desh native predictor bridge added.
- Desh-style immediate personal learner added.
- Dictionary metadata helper added.
- Stability/fallback behavior documented.
- Future Backup/Restore requirement explicitly recorded.
- Desh APK provenance and proprietary-resource boundary documented.

Future modifications must add a new dated entry rather than silently rewriting this history.

---

# 15. Future change-entry template

Copy this block whenever modifying the fork:

```md
## YYYY-MM-DD — <short change title>

### Reason
<Why the change was required.>

### Upstream HeliBoard version/commit
<Exact upstream version or commit.>

### Desh source version
<Exact APK/version if applicable.>

### Files changed
- `path/to/file` — <what changed>
- `path/to/file` — <what changed>

### Source/provenance
- Taken/derived from: <Desh APK path, HeliBoard upstream path, or new code>
- Destination: <HeliBoard path>
- Adaptation required: <yes/no + details>

### Behavior change
<Exact user-visible behavior.>

### Compatibility risks
<Potential conflict with future HeliBoard changes.>

### Verification
- [ ] Build
- [ ] Install
- [ ] English
- [ ] Hindi
- [ ] Symbols
- [ ] देश हिंदी
- [ ] Prediction
- [ ] Learning
- [ ] Backup/Restore

### Rollback
<How to remove this change safely.>
```

---

# 16. Final maintenance principle

The fork should always be thought of as:

```text
                    UPSTREAM HELIBOARD
                           │
                    ┌──────┴──────┐
                    │             │
                upstream       CUSTOM LAYER
                behavior          │
                                  ├── देश हिंदी
                                  ├── Desh predictor
                                  ├── Desh learner
                                  ├── layout isolation
                                  └── Desh assets
```

The objective of future maintenance is **not** to freeze HeliBoard at today's source version.

The objective is to keep HeliBoard current while preserving this isolated custom layer.

If an upstream HeliBoard refactor changes one of the shared files listed in §6, re-apply the custom behavior at the new architectural insertion point rather than restoring the old file wholesale.

**This document is the map for doing that.**


## 2026-08-13 — Speechnotes-style normal Voice Input engine (Phase 1)

### Scope
This change targets **only HeliBoard's normal `Voice input` feature** (`KeyCode.VOICE_INPUT`). The separate `AI Voice Typing` implementation (`AI_VOICE_INPUT`, Gemini/API-backed) is intentionally untouched.

### Reference inspected
- `Speechnotes_v5.0.8(508).apk` supplied for this project.
- Identified APK components/strings include `ContSpeechRecognizer`, `RecognizerService`, `startVoiceRecognition`, `stopVoiceRecognition`, `onPartialResults`, `results_recognition`, `partialWindows`, `diffPartial`, `KEY_PREFS_BACKGROUND_LISTEN`, and `KEY_PREFS_TIME_TO_NO_SPEECH`.
- The implementation uses Android's `SpeechRecognizer`/`RecognitionListener` family rather than shipping a speech model inside the APK.

### HeliBoard locations changed
1. `app/src/main/java/helium314/keyboard/latin/voice/ContinuousSpeechRecognizer.kt`
   - New Phase-1 controller.
   - Uses Android `SpeechRecognizer`.
   - Requests partial results.
   - Maintains partial results only in memory.
   - Commits only final recognition segments.
   - Automatically starts a new recognition session after final results or recoverable recognition errors.
   - Explicit `stop()` cancels the session and disables automatic restart.
   - `destroy()` cancels/destroys the recognizer.
   - Uses the active HeliBoard subtype locale.
   - No suggestion-strip UI work is included yet; the partial buffer remains internal as requested.

2. `latin/LatinIME.java`
   - `KeyCode.VOICE_INPUT` no longer calls `RichInputMethodManager.switchToShortcutIme()`.
   - It now toggles the internal continuous recognizer.
   - Any ordinary keyboard interaction stops active continuous voice mode.
   - Final voice segments are committed through the current `InputConnection`.
   - Voice is stopped when input finishes and when the IME is destroyed.
   - AI Voice Typing callbacks and controller remain separate and unchanged.

3. `latin/InputAttributes.java`
   - Normal Voice button visibility no longer depends on a shortcut IME being installed/ready.
   - Existing password/email/no-microphone restrictions remain.

4. `keyboard/KeyboardSwitcher.java`
   - Normal Voice key is no longer disabled merely because no shortcut IME exists.

5. `keyboard/PopupKeysKeyboardView.java`
   - Same independence from shortcut-IME availability for the normal Voice key.

### Current Phase-1 behavior
```text
VOICE_INPUT
  -> internal ContinuousSpeechRecognizer
  -> Android SpeechRecognizer / configured device recognition provider
  -> partial result (internal only)
  -> final result
  -> InputConnection.commitText()
  -> restart recognition

VOICE_INPUT pressed again / normal keyboard interaction / input finished
  -> stop
  -> no automatic restart
```

### Deliberately deferred to Phase 2
- Rendering the live partial buffer inside `SuggestionStripView`.
- Right-to-left scrolling/animation of the live voice buffer.
- Replacing the suggestion strip with `Listening...` while voice mode is active.
- Restoring normal suggestions immediately after stopping.

These are intentionally deferred so the speech engine can be validated independently before UI changes.

### Build/verification status
Source changes were applied on 2026-08-13. A local Gradle build was attempted, but the Gradle wrapper could not download Gradle 8.14 because this environment has no external network/DNS access. Therefore **the modified source has not been compile-verified in this environment**. Do not treat the ZIP as a tested APK. Build it locally before installing.

### Future update rule
When updating from a newer HeliBoard version, preserve this integration boundary: replace/update the normal `VOICE_INPUT` path and the voice-key visibility logic, while leaving `AI_VOICE_INPUT` and the existing Desh Hindi/prediction/theme integrations independent.
## 2026-08-13 — Voice toolbar visibility fix

### Problem
The normal HeliBoard `Voice input` toolbar/pinned-toolbar item could be enabled in the toolbar settings but subsequently hidden by `SettingsValues.mShowsVoiceInputKey`. That value is derived from `InputAttributes.mShouldShowVoiceInputKey`, which applies editor-specific microphone eligibility rules. This made the toolbar item behave differently from ordinary toolbar items.

### Fix
`SuggestionStripView.updateVoiceKey()` no longer uses `mShowsVoiceInputKey` as a visibility gate for the toolbar/pinned Voice item. If the `VOICE` toolbar item exists because the user enabled/pinned it, it is made visible unconditionally.

The speech provider is therefore checked only when the user actually invokes Voice input; provider availability does not remove the toolbar button. The separate editor-level `mShouldShowVoiceInputKey` mechanism remains available for HeliBoard's keyboard-layout voice shortcut and was not globally removed.

### Files changed
- `app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt`

### Verification
- Confirmed toolbar and pinned-toolbar construction is preference-driven through `PREF_TOOLBAR_KEYS` / `PREF_PINNED_TOOLBAR_KEYS`.
- Confirmed `ToolbarKey.VOICE` maps to `KeyCode.VOICE_INPUT`.
- Confirmed `LatinIME.onEvent()` routes `KeyCode.VOICE_INPUT` directly to `toggleContinuousVoiceInput()`.
- Confirmed no other `SpeechRecognizer` implementation exists in the HeliBoard source tree outside `ContinuousSpeechRecognizer.kt`; the old normal voice path is not separately invoked by `VOICE_INPUT`.
- Source/archive packaging completed after the change. Full Gradle compilation could not be run in this environment because the Gradle wrapper attempted to download Gradle 8.14 and outbound network access was unavailable.

### Important scope note
This change guarantees toolbar visibility, not speech-provider success. If no recognizer is installed, the button still appears and the existing controller reports the recognition error after invocation.



## 2026-08-13 — Voice audit Step 3: partial-window reconciliation
- The rolling partial-result window is now functional rather than decorative.
- `diffPartial()` now feeds a `lastStablePartial` guard.
- If a recognition provider regresses a partial hypothesis, the visible buffer keeps the last stable prefix instead of jumping backwards.
- The stable partial is never committed independently; natural `onResults()` remains the commit boundary.
- This step does not change Voice start/stop, language switching, keyboard expansion, or final-result commit behavior.

## 2026-08-13 — Voice audit Step 10: continuous-mode state during restart gaps

- Corrected `ContinuousSpeechRecognizer.isListening()` so it represents the user-visible continuous Voice mode, not the Android `SpeechRecognizer` low-level session state.
- Previously, `onResults()`/`onError()` temporarily set `listening=false` while a restart was scheduled. During that short interval, a normal keyboard interaction could bypass `LatinIME`'s `stop()` path and the already-scheduled restart could turn Voice back on after the keyboard was touched.
- `isListening()` now returns `!explicitlyStopped`, so Voice remains logically active throughout natural-pause result delivery, error recovery, and scheduled restart windows.
- This preserves the required behavior: **any normal keyboard interaction stops continuous Voice and cancels the pending restart**, while a natural speech pause does not.
- Verified call sites: the Voice toggle and subtype/language switching use this state; no low-level recognizer availability decision is made from this method.
