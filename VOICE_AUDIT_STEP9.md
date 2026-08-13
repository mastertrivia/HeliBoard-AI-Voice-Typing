# Voice Audit — Step 9: Error and Recovery Lifecycle
Date: 2026-08-13

## Scope
Only recognition availability, provider/session errors, and recovery-state handling were audited and corrected in this step. Steps 1–8 were preserved.

## Findings
1. `ERROR_INSUFFICIENT_PERMISSIONS` was classified as non-restartable, but the old implementation did not transition the Voice UI/state to OFF after deciding not to restart. That could leave Voice visually active without a recognizer session.
2. `SpeechRecognizer.isRecognitionAvailable()` failure reported an error but did not explicitly terminate the active Voice state.
3. A `startListening()` runtime exception could leave stale `listening` state from the preceding session.

## Corrections
- Non-recoverable errors now cancel pending restarts, mark the controller explicitly stopped, and emit `onVoiceStateChanged(false)`.
- Recognition-provider unavailability now cleanly exits the Voice state instead of leaving the UI active.
- `startListening()` exceptions now clear stale listening state while preserving the active Voice intent for bounded recovery.
- Transient errors continue to schedule controlled restart while Voice remains logically ON.

## Resulting state model
Transient provider/session error:
`error -> Voice remains ON -> bounded restart -> new recognition session`

Non-recoverable error:
`error -> no restart -> Voice OFF`

Explicit user stop:
`stop -> no restart -> Voice OFF`

IME destruction/collapse:
`finalize pending partial -> destroy recognizer -> Voice OFF`

## Verification
- Source re-read after modification.
- `ERROR_INSUFFICIENT_PERMISSIONS` reaches `NO_RESTART` and OFF state.
- Provider unavailable reaches OFF state.
- Runtime `startListening()` exception clears stale listening state and schedules bounded recovery.
- No changes made to the normal result/partial/formatting/language/UI paths in this step.
