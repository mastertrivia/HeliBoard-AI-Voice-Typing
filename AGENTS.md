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

## Workflow

1. Download the ZIP, extract (paths use backslashes on Windows).
2. Sync the whole tree over `/workspace`, skipping root-level dev-doc bloat
   (files matching `CODEX_*`, `DESH_*`, `SPEECHNOTES_*`, `VOICE_*`, `STEP8_*`,
   `AI_*`, `HANDOVER.md`, `layouts.md`, `PHASE13C_REPORT.md`, and the
   `REFERENCE_APPS` directory).
3. Apply the two restorations above.
4. Commit and push to `main`. CI (`Build APK` workflow) runs `assembleDebug`.
5. Monitor the GitHub Actions run; fix any compile errors that surface.
