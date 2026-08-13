# Phase 13N — English learning integration

Implemented the first real English learning integration using HeliBoard's existing native BinaryDictionary user-history mechanism.

Changes:
- Added `DictionaryFacilitator.getUserHistorySuggestions(...)`.
- Implemented it in `DictionaryFacilitatorImpl` against the preferred locale's `TYPE_USER_HISTORY` dictionary.
- English `Suggest` now merges those native learned candidates into the Desh English result set.
- The custom `DeshStyleLearningStore` is no longer written for English, preventing a second competing English learner.
- No HeliBoard main English candidate generation is invoked for the non-empty English prefix path.

Important limitation:
- This is based on the recovered Desh architecture that uses BinaryDictionary for learned words. It is not a claim that every proprietary Desh learning/ranking policy is byte-for-byte identical.
- Gradle/APK compilation could not be performed if the required Gradle distribution is unavailable in the environment.
