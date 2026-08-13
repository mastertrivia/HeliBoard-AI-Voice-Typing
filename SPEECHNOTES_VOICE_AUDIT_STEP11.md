# Voice Audit Step 11 — Keyboard Collapse / Input-View Lifecycle

Date: 2026-08-13

## Scope
Only the requirement that collapsing/hiding the HeliBoard keyboard immediately stops continuous Voice.

## Finding
The previous implementation stopped the recognizer in `onFinishInput()`, but Android can hide/finish the IME input view through `onFinishInputView()` while the input connection remains alive. Therefore relying only on `onFinishInput()` could leave continuous Voice active after the keyboard was collapsed.

## Fix
`LatinIME.onFinishInputView()` now stops the continuous Voice controller before the normal input-view cleanup. This preserves the requested rule:

`keyboard collapsed/hidden -> Voice stops -> pending partial is finalized -> recognizer stops -> buffer UI disappears`.

## Verification
- The stop call uses the same `ContinuousSpeechRecognizer.stop()` path as an explicit Voice stop.
- Pending partial text therefore follows the already-audited Step 1 safety path.
- No changes were made to recognition, language, formatting, error recovery, or buffer rendering.
- `onFinishInput()` remains a second lifecycle safety net.
- Source archive integrity verified after packaging.

## Important lifecycle note
This intentionally treats hiding the IME input view as a Voice-stop event, matching the project's requested behavior.
