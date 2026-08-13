# Voice Audit Step 5 — Result Formatting / Voice Commands

Date: 2026-08-13

Scope: only the result-formatting layer. Steps 1–4 are preserved.

## Audited path

SpeechRecognizer final result → LatinIME.commitVoiceText() →
SpeechnotesVoiceResultProcessor → InputConnection.commitText().

## Corrections in Step 5

- Voice commands are applied longest-first to avoid phrase collisions.
- English-only command interpretation is retained so Hindi/other languages are not rewritten as English commands.
- Added common documented dictation variants: new line, next line, open/close quote,
  exclamation point, full stop.
- Word-boundary protection prevents words such as “periodic” from being interpreted as
  the punctuation command “period”.
- Whitespace and punctuation normalization is performed after command replacement.
- Sentence capitalization is retained for English and disabled for non-English locales.
- Trailing-space decision is centralized and avoids adding a space after punctuation or
  line breaks.

## Verification

The formatter remains downstream of recognition. It does not alter the recognizer,
continuous session lifecycle, partial-result window, pause/restart behavior, or manual stop.

This is an observable-behavior reimplementation, not copied proprietary Speechnotes source.
Exact private implementation details cannot be claimed byte-for-byte identical.
