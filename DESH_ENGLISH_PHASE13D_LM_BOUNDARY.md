# Phase 13D — Desh English LM Resource Boundary

Status: completed as a resource-boundary phase; **not yet claiming LM scoring integration**.

Changes:
- preserves `english_lm.db` byte-for-byte;
- adds extraction through `DeshEnglishLanguageModelLoader`;
- keeps the model outside HeliBoard's `BinaryDictionary` scoring path;
- does not invent a parser or scoring algorithm for the Desh binary model.

Why: the Desh `english_lm.db` format and its exact runtime reader have not been recovered sufficiently to safely pass it into HeliBoard's existing n-gram APIs. Doing so would be an approximation, not a direct Desh transplant.

No English ranking or learning behavior is changed by this phase.
