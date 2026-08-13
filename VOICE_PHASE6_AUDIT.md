# Voice Phase 6 — Buffer UI lifecycle audit (2026-08-13)

Scope: only the temporary live-transcription UI lifecycle.

Finding: Voice temporarily hides the normal toolbar/suggestion controls with `setToolbarVisibility(false)`. The previous implementation did not restore the toolbar state when the voice buffer disappeared, which could leave the normal toolbar hidden after Voice stopped.

Fix: store the pre-Voice toolbar visibility and restore that exact state in `hideVoiceTranscriptionBuffer()`. No speech-recognition/session logic was changed in this phase.

Verified statically:
- Voice buffer still uses the existing suggestion-strip container.
- Dynamic height remains bounded by the five-line buffer view.
- Buffer text color still comes from `ColorType.KEY_TEXT`.
- Voice start hides the normal strip/toolbar temporarily.
- Voice stop restores the exact pre-Voice toolbar visibility.
- Steps 1–5 source remains otherwise unchanged.
