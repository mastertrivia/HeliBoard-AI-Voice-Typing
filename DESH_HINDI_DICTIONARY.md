# Built-in Desh Hindi vocabulary

The `देश हिंदी keyboard` ships with the Hindi vocabulary/index from the supplied Desh v17.4.9 APK:

- `app/src/main/assets/desh_predictor/native_words.db`
- `app/src/main/assets/desh_predictor/native_lm.db`

The supplied `native_words.db` reports **85,676 vocabulary entries** in its header.

## Why this is not named `main_hi-desh.dict`

HeliBoard's ordinary `main_*.dict` files are AOSP BinaryDictionary files. Desh's
`native_words.db` is a different proprietary native index consumed by
`libnativepredictor.so`; renaming it to `.dict` would make HeliBoard reject it.

Therefore this build treats the Desh vocabulary/model as a **built-in read-only
prediction dictionary source** for the `desh_hindi` subtype. It requires no
manual dictionary download or installation. The existing HeliBoard dictionaries
remain available for other Hindi subtypes.

`DeshHindiDictionaryInfo` validates that the bundled native vocabulary exists and
records its entry count; `DeshHindiPredictor` uses the bundled native vocabulary
and language model directly.
