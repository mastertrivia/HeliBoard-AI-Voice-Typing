# HeliBoard AI Voice Typing — Session Handover

## Project Objective

Add an "AI Voice Typing" feature to HeliBoard (a fork of `helium314.keyboard`, package `helium314.keyboard`, Kotlin 2.3.20, AGP 8.13.2, minSdk 21 / targetSdk 36, single `:app` module). The feature is delivered through phased milestones that progressively build a settings page, toolbar button behavior, and AI integration. The AI module must be modular and the HeliBoard core must stay as untouched as possible.

## Current Milestone Completed

**Milestone 1.1 — Insert the Two UI Entry Points.** Both entry points are implemented and self-reviewed:

1. **Settings entry**: A "AI Voice Typing" settings item inserted between "Advanced" and "About" in the main settings screen. Tapping it navigates to a new (currently empty, navigable) settings page.
2. **Toolbar entry**: A new "AI Voice Typing" toolbar action registered as the FIRST item in the toolbar key lists (main toolbar keys, pinned toolbar keys, clipboard toolbar keys). It reuses HeliBoard's existing toolbar registration mechanism end-to-end.

No AI logic, no settings page contents, and no toolbar button behavior were implemented in this milestone (per scope). Tapping the toolbar button is a safe no-op.

**IMPORTANT: This work has NOT been compile-verified.** No JDK, Kotlin compiler, Gradle, or Android SDK exists in the development environment. Build with `./gradlew assembleDebug` on a machine with the Android SDK as the first step of the next session.

## Exact Files Modified

- `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/floris/KeyCode.kt`
  - Added `const val AI_VOICE_INPUT = -10055` (after DPAD = -10054).
  - Added `AI_VOICE_INPUT` to the recognized-code list in `checkAndConvertCode`.
- `app/src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt`
  - Added `AI_VOICE` as the FIRST entry of `enum class ToolbarKey`.
  - `getCodeForToolbarKey`: `AI_VOICE -> KeyCode.AI_VOICE_INPUT`.
  - `defaultToolbarPref`: `AI_VOICE` now the first enabled key (above SETTINGS).
  - `defaultClipboardToolbarPref`: `AI_VOICE` now the first enabled key.
  - (Pinned list picks up AI_VOICE automatically via `entries` iteration; all pinned default to disabled.)
- `app/src/main/java/helium314/keyboard/keyboard/internal/KeyboardIconsSet.kt`
  - Added `ToolbarKey.AI_VOICE -> R.drawable.sym_keyboard_ai_voice` in all three style maps (holo ~line 127, material ~line 192, rounded ~line 257).
- `app/src/main/java/helium314/keyboard/settings/SettingsNavHost.kt`
  - Added `const val AiVoiceTyping = "ai_voice_typing"` to `SettingsDestination`.
  - Added `composable(SettingsDestination.AiVoiceTyping) { AiVoiceTypingScreen(onClickBack = ::goBack) }`.
  - Wired `onClickAiVoiceTyping = { navController.navigate(SettingsDestination.AiVoiceTyping) }` into the `MainSettingsScreen(...)` call.
  - Added import for `AiVoiceTypingScreen`.
- `app/src/main/java/helium314/keyboard/settings/screens/MainSettingsScreen.kt`
  - Added `onClickAiVoiceTyping: () -> Unit` parameter.
  - Added a `Preference` for AI Voice Typing between Advanced and About.
  - Updated preview lambda arity (now 13 `{}` args).
- `app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java`
  - Added `KeyCode.AI_VOICE_INPUT` to the no-op `break` group (lines 888-891) so tapping the toolbar button does not throw `RuntimeException("Unknown event")` when debug mode is enabled. This is required because the button is tappable in Milestone 1.1.
- `app/src/main/res/values/strings.xml`
  - Added `<string name="ai_voice">AI Voice Typing</string>` (with `tools:keep`) — toolbar key name resolved via `name.lowercase()`.
  - Added `<string name="settings_screen_ai_voice_typing">AI Voice Typing</string>` — settings page title.
  - Added `<string name="ai_voice_typing_summary">Speak and type with AI</string>` — settings entry summary.

## New Files Created

- `app/src/main/java/helium314/keyboard/settings/screens/AiVoiceTypingScreen.kt` — empty, navigable settings page using `SearchSettingsScreen(onClickBack, title, settings = emptyList())`, with a `@Preview` and `// Future:` context comments.
- `app/src/main/res/drawable/sym_keyboard_ai_voice.xml` — toolbar icon (mic + sparkle), white fill (`#FFF`), tinted via ColorStateList like other keyboard icons.
- `app/src/main/res/drawable/ic_settings_ai_voice.xml` — settings icon (mic + sparkle), `@color/foreground` fill like other `ic_settings_*` icons.

## Important Implementation Decisions

1. **Dedicated key code** `KeyCode.AI_VOICE_INPUT = -10055` instead of reusing the platform `VOICE_INPUT` (-233). This lets the new "AI Voice Typing" action coexist independently with the existing "Voice input" action, which must NOT be modified.
2. **Enum order + default lists control ordering**: `AI_VOICE` is placed first in the `ToolbarKey` enum, so `entries` iteration (used by the reorder screens and `defaultPinnedToolbarPref`) yields it first everywhere. It is also first in `defaultToolbarPref` and `defaultClipboardToolbarPref`.
3. **Reused HeliBoard's existing toolbar registration pattern** end-to-end: ToolbarKey enum + KeyCode + KeyboardIconsSet icon maps + default pref lists + `upgradeToolbarPrefs`. No custom implementation was added.
4. **`AI_VOICE` enum name chosen** so string lookup `ai_voice` resolves automatically via `getStringResourceOrName` (Ktx.kt:38) using `name.lowercase(Locale.US)`.
5. **No-op in InputLogic** (Milestone 1.1): `AI_VOICE_INPUT` was added to the existing no-op `break` group, not given a functional handler. The toolbar timer behavior is a later milestone.
6. **Settings page is empty** but uses the standard `SearchSettingsScreen` wrapper so it renders correctly and is searchable.

## Existing Assumptions

- Toolbar key display names resolve from `key.name.lowercase()` → string resource via `getStringResourceOrName` (ToolbarUtils.kt:32, Ktx.kt:38). Icons resolve via `KeyboardIconsSet.getNewDrawable(name)`, tinted via ColorStateList `TOOL_BAR_KEY -> toolbarKeyStateList` (Colors.kt:308/506).
- `upgradeToolbarPrefs()` (used by App.kt:44 and AppUpgrade.kt:711) auto-inserts new `ToolbarKey` entries into existing stored toolbar prefs; new keys are appended as disabled for existing users. Fresh installs use the new defaults (AI_VOICE first, enabled).
- Settings system is 100% Jetpack Compose: `SettingsContainer.createSettings()` aggregates screens; each screen file returns `List<Setting>`; search scans all registered settings.
- The Android manifest currently has no `INTERNET` or `RECORD_AUDIO` permission — future voice milestones will need additive manifest changes only.
- `setToolbarButtonActivatedState` (ToolbarUtils.kt:53) has `else -> true`, so AI_VOICE is always treated as active.
- `getCodeForToolbarKeyLongClick` has `else -> KeyCode.UNSPECIFIED`, so long-press on AI_VOICE does nothing (no custom code required).

## Pending Work

1. **Compile verification** on a machine with the Android SDK: `./gradlew assembleDebug`. No SDK exists in this environment.
2. **Milestone 1.2**: Build the settings page contents (cards, preferences, popup dialogs, bottom sheets, dropdowns, switches, radio groups, text fields, icons, buttons, animations, empty states, toast framework, timer UI).
3. **Later milestone**: Toolbar button visual behavior — idle (mic + "AI") → tap → recording (mic + 00:01, 00:02, ...) → tap → idle; timer must run visually but NOT connect to the microphone.
4. **Future**: AI integration, permissions (`INTERNET`, `RECORD_AUDIO`), microphone input.

## Known Limitations

- **Not compile-verified** (no Android SDK/JDK/Gradle in the environment).
- Tapping the AI Voice toolbar button is currently a silent no-op (no feedback, no timer).
- The settings page is empty (title + back only).
- `sym_keyboard_ai_voice.xml` and `ic_settings_ai_voice.xml` use a mic+sparkle icon derived from the Material mic icon; final design may want a custom icon.
- For existing users who already have toolbar prefs stored, AI_VOICE will be appended to the END of their toolbar lists (disabled) by `upgradeToolbarPrefs`; it is first only for fresh installs. This is standard HeliBoard behavior for new keys.

## TODO Comments Left in Code

- `AiVoiceTypingScreen.kt`: KDoc says page contents are built in a later milestone; an empty list is used.
- `InputLogic.java:890`: comment noting AI voice typing is a no-op and toolbar button behavior is a later milestone.
- `ToolbarUtils.kt:123-124`: comment noting AI_VOICE is first in the toolbar key lists and separate from VOICE.

## Things the Next Implementation Session Must Know

- **Verify compilation first.** Run `./gradlew assembleDebug` (or `./gradlew :app:assembleDebug`) with the Android SDK. If missing resources/imports are flagged, fix before continuing.
- The two entry points are the ONLY scope completed. Do not start AI logic.
- HeliBoard core files should stay untouched where possible; AI module must be modular.
- All changes are uncommitted in the working tree. Review `git status` / `git diff` before committing. Last upstream commit is `1330782`.
- Key integration points for later work:
  - `latin/LatinIME.java` — `onEvent`, `mRichImm.switchToShortcutIme` (~line 1414).
  - `latin/inputlogic/InputLogic.java` — `handleFunctionalEvent` (~line 719), VOICE_INPUT no-op (~line 883).
  - `latin/suggestions/SuggestionStripView.kt` — `onClickToolbarKey`, `createToolbarKey` (~line 158).
  - `latin/settings/Settings.java`, `latin/settings/Defaults.kt`, `settings/dialogs/ToolbarKeysCustomizer.kt`, `settings/dialogs/ReorderDialog.kt`, `settings/preferences/ReorderSwitchPreference.kt`.
- String resources: `ai_voice`, `settings_screen_ai_voice_typing`, `ai_voice_typing_summary`.
- Drawables: `sym_keyboard_ai_voice` (toolbar), `ic_settings_ai_voice` (settings).

---

## Milestone 1.2 — Continue Here

**Do not rediscover the project.** Milestone 1.1 (both UI entry points) is complete and the files above are ready. Begin Milestone 1.2 EXACTLY as follows:

1. **Verify compilation first**: On a machine with the Android SDK, run `./gradlew assembleDebug` and fix any reported issues before writing new code.
2. **Open `app/src/main/java/helium314/keyboard/settings/screens/AiVoiceTypingScreen.kt`** and replace the `settings = emptyList()` argument (inside `SearchSettingsScreen`) with the settings content described by the milestone spec: build the settings page with cards, preference items, popup dialogs, bottom sheets, dropdowns, switches, radio groups, text fields, icons, buttons, animations, empty states, a toast framework, and timer UI elements.
3. **Reuse the existing Compose settings building blocks** — follow the pattern of neighboring screens in `settings/screens/` (e.g. `ToolbarScreen.kt`, `AdvancedSettingsScreen.kt`) and the preference components in `settings/preferences/`.
4. **Keep HeliBoard core untouched**; implement the AI module modularly so future milestones can add AI logic without touching core files.
5. **Do NOT implement the toolbar timer button behavior** (that is a later milestone). For Milestone 1.2, focus solely on the settings page contents.
6. **Build and self-review** with `./gradlew assembleDebug` after completing the page.
