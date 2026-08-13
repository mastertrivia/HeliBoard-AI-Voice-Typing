# Desh English Engine — Phase 13A Asset Port
Date: 2026-08-13

## Scope
Only original Desh English prediction resources were added. No English suggestion logic was changed.

## Added
- `app/src/main/assets/desh_predictor/english/english_dictionary.bin`
- `app/src/main/assets/desh_predictor/english/english_lm.db`

## Source
Copied byte-for-byte from the original Desh APK extracted from the user-supplied `.apks`:
- `res/raw/english_dictionary.bin`
- `assets/english_lm.db`

## Integrity
The copied files were SHA-256 verified against their original Desh APK files.

## Deliberately NOT changed
- `Suggest.kt`
- `DictionaryFacilitatorImpl.kt`
- English ranking
- HeliBoard dictionary selection
- personal-learning logic
- Hindi predictor
- voice implementation

## Next
Phase 13B will implement only the Desh English resource-loading/dictionary wrapper boundary, after the loader/call path is fully mapped.
