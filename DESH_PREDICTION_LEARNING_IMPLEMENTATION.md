# Desh-style prediction and learning integration

This fork contains two isolated enhancements:

1. `DeshHindiPredictor` uses the bundled Desh Hindi native predictor on `desh_hindi` only. It is lazy-loaded and falls back to the normal HeliBoard predictor if native loading or prediction fails.
2. `DeshStyleLearningStore` is a local, per-locale personal learner. Accepted words are stored immediately on first use, together with a short previous-word context. Learned entries are injected into both prefix and next-word suggestions before normal display ranking, so a newly used word can become a strong candidate immediately.

The learner is independent of HeliBoard's existing `UserHistoryDictionary`; clearing HeliBoard's user-history dictionary also clears the new learner for the affected locale.

The integration does not replace HeliBoard's core input connection, composing, keyboard UI, or dictionary infrastructure. It is a suggestion-layer enhancement with failure-safe fallback.

## Build note

The source was not fully Gradle-built in this environment because the Gradle 8.14 distribution is not cached and outbound network access is unavailable. Build with the repository's normal wrapper/toolchain and report any compiler error for correction.
