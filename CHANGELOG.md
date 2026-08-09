# CHANGELOG — Session: Milestone 1.1 (AI Voice Typing Entry Points)

All changes below were made in this session against HeliBoard (`helium314.keyboard`).
Scope: Milestone 1.1 — insert the two UI entry points only. Not compile-verified (no Android SDK in environment).

## 1. `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/floris/KeyCode.kt`
- **Changed**: Added `const val AI_VOICE_INPUT = -10055` (next free value after DPAD = -10054) and added `AI_VOICE_INPUT` to the recognized-code list in `checkAndConvertCode`.
- **Why**: The new AI voice action needs a dedicated key code distinct from the platform `VOICE_INPUT` (-233) so both features can coexist; registering it in `checkAndConvertCode` makes it a valid toolbar code.

## 2. `app/src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt`
- **Changed**: Added `AI_VOICE` as the first entry of `enum class ToolbarKey`; mapped `AI_VOICE -> KeyCode.AI_VOICE_INPUT` in `getCodeForToolbarKey`; made `AI_VOICE` the first enabled key in `defaultToolbarPref` and `defaultClipboardToolbarPref`.
- **Why**: Registers the new toolbar action as the first item in all toolbar key lists using HeliBoard's existing registration mechanism (enum + defaults + `upgradeToolbarPrefs`).

## 3. `app/src/main/java/helium314/keyboard/keyboard/internal/KeyboardIconsSet.kt`
- **Changed**: Added `ToolbarKey.AI_VOICE -> R.drawable.sym_keyboard_ai_voice` to the holo, material, and rounded icon maps.
- **Why**: Gives the toolbar button an icon in all three icon styles, keeping the exhaustive `when` blocks compile-safe.

## 4. `app/src/main/java/helium314/keyboard/settings/SettingsNavHost.kt`
- **Changed**: Added `SettingsDestination.AiVoiceTyping = "ai_voice_typing"`, a `composable` for it rendering `AiVoiceTypingScreen`, the `onClickAiVoiceTyping` navigation wiring into the `MainSettingsScreen` call, and the import.
- **Why**: Makes the settings entry point navigable to the new (empty) page.

## 5. `app/src/main/java/helium314/keyboard/settings/screens/MainSettingsScreen.kt`
- **Changed**: Added `onClickAiVoiceTyping: () -> Unit` parameter and a `Preference` for AI Voice Typing between the Advanced and About items; updated the preview lambda arity to 13.
- **Why**: Inserts the "AI Voice Typing" settings entry point in the requested position (between Advanced and About).

## 6. `app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java`
- **Changed**: Added `KeyCode.AI_VOICE_INPUT` to the existing no-op `break` group in `handleFunctionalEvent` (with VOICE_INPUT, EMOJI, etc.).
- **Why**: Without a handler, tapping the toolbar button would hit the `default:` branch and throw `RuntimeException("Unknown event")` when debug mode is enabled. This makes the button a safe no-op for Milestone 1.1.

## 7. `app/src/main/res/values/strings.xml`
- **Changed**: Added `ai_voice` (toolbar key name, with `tools:keep`), `settings_screen_ai_voice_typing` (page title), and `ai_voice_typing_summary` (settings entry summary).
- **Why**: Strings required by the toolbar name resolution (`name.lowercase()` → resource lookup) and the settings page.

## 8. `app/src/main/java/helium314/keyboard/settings/screens/AiVoiceTypingScreen.kt` (NEW)
- **Changed**: New empty navigable settings screen using `SearchSettingsScreen` with `settings = emptyList()`, including a `@Preview`.
- **Why**: Provides the destination for the settings entry point; contents are built in Milestone 1.2.

## 9. `app/src/main/res/drawable/sym_keyboard_ai_voice.xml` (NEW)
- **Changed**: New toolbar icon (microphone + sparkle), white fill.
- **Why**: Icon for the AI Voice toolbar button, tinted via ColorStateList like other keyboard icons.

## 10. `app/src/main/res/drawable/ic_settings_ai_voice.xml` (NEW)
- **Changed**: New settings icon (microphone + sparkle), `@color/foreground` fill.
- **Why**: Icon for the AI Voice Typing settings entry, matching other `ic_settings_*` icons.
