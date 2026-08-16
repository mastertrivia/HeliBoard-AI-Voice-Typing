# AGENTS.md

## Known regressions in upstream ZIP builds (RESTORE EVERY TIME)

The user provides ZIP builds of this project. The following two things are
ALWAYS missing/incorrect in every provided ZIP. Restore them before pushing,
otherwise the CI build fails or the repo diff is dirty.

### 1. Missing Kotlin import (breaks `:app:compileDebugKotlin`)

File: `app/src/main/java/helium314/keyboard/latin/translation/DeshTranslationView.kt`

The ZIP always drops `import kotlinx.coroutines.isActive`, but line ~257 uses
`isActive` inside the translation coroutine. Without the import the build fails
with: `Unresolved reference 'isActive'`.

Fix: ensure the import is present (alphabetical order, after `delay`, before
`launch`):

```kotlin
import kotlinx.coroutines.isActive
```

### 2. gradlew.bat loses executable bit

The ZIP extracts `gradlew.bat` as mode `100644`; the repo tracks it as `100755`.

Fix before committing:

```bash
chmod 755 gradlew.bat
git checkout gradlew.bat
```

(It shows as a `0 0` mode-only diff otherwise. This one does not break the Linux
CI build since CI uses `./gradlew`, but keep the repo clean.)

### 3. Previously-fixed bugs are ALWAYS missing in newer ZIPs

The user edits a STALE copy (the ZIP before my fixes), so every new ZIP
REINTRODUCES the earlier bugs. Even though `main` already contains the fixes,
each new ZIP must be re-checked against these known regressions and the fixes
re-applied before pushing. These regressed CI in ZIP 3:

- `app/src/main/java/helium314/keyboard/settings/screens/DeshNativeWordsScreen.kt`
  uses Pair accessors `.first`/`.second` on entries — requires
  `DeshNativeWordStore.all()` to return `List<Pair<String, String>>`
  (NOT `Map<String, String>`). Fix: in
  `app/src/main/java/helium314/keyboard/latin/DeshNativeWordStore.kt`, change
  `fun all()` to return `List<Pair<String, String>>`, drop `.toMap()`, and use
  `emptyList()` for the null-prefs case. The screen also declares
  `words` as `mutableStateOf(DeshNativeWordStore.all())`, which works once
  `all()` returns the list.
- `app/src/main/java/helium314/keyboard/latin/translation/DeshTranslationEngine.kt`
  (since ZIP 6 the translation network layer lives here, not in DeshTranslationView)
  must NOT contain `import kotlinx.serialization.json.getOrNull` (does not
  exist in kotlinx-serialization-json 1.11.0; `getOrNull` on `JsonArray`
  resolves via `kotlin.collections` auto-import). If present, remove that line.
- ZIP 6 refactored the translation subsystem: `DeshTranslationView.kt` no longer
  uses coroutines/okhttp, so the old `isActive`-import regression (section 1)
  does NOT apply anymore — check `DeshTranslationEngine.kt` instead for the
  getOrNull import and any coroutine usage.

### 4. ZIPs may REMOVE features too (delete stale files, don't just overwrite)

The user occasionally deletes whole features/files from the source. The sync
step must therefore ALSO delete any file that exists in the current workspace
tree but is NOT present in the new ZIP (a plain overwrite-copy leaves the old
file in place, so the removed feature silently comes back in the build). Exclude
`.git/` and `AGENTS.md` from deletion. After a removal-style sync, confirm via
`git status` that the deleted files show up as `D` and are included in the
commit. If the removed feature had callers elsewhere, run the cross-reference
checks and delete those too (CI will flag any dangling references).

## Workflow

1. Download the ZIP, extract (paths use backslashes on Windows).
2. Sync the whole tree over `/workspace`, skipping root-level dev-doc bloat
   (files matching `CODEX_*`, `DESH_*`, `SPEECHNOTES_*`, `VOICE_*`, `STEP8_*`,
   `AI_*`, `HANDOVER.md`, `layouts.md`, `PHASE13C_REPORT.md`, and the
   `REFERENCE_APPS` directory). If files were REMOVED from the ZIP, delete the
   corresponding stale files from `/workspace` too (see section 4).
3. Apply the fixes in the sections above (isActive import, gradlew.bat
   mode, and the DeshNativeWordStore/DeshTranslationView regressions in
   section 3).
4. Commit and push to `main`. CI (`Build APK` workflow) runs `assembleDebug`.
5. Monitor the GitHub Actions run; fix any compile errors that surface.
