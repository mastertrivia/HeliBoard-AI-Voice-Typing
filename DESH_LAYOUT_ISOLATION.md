# Layout isolation and number-row policy

The keyboard row structure is now scoped to the active layout instance instead of allowing the global Number row preference to alter unrelated layouts.

## Policy

- English / other alphabetic layouts: use the global **Number row** preference.
- Hindi (`hi`, Devanagari): never inherit the global Number row preference.
- Symbols / More symbols: use the dedicated **Number row in symbols** preference independently of the global Number row switch.
- Symbols keep their own symbol rows; the number row is prepended instead of replacing the first symbol row.
- Custom layouts may contain any number of rows. Row heights are recalculated from the actual row count.
- Bottom-row sizing uses the actual active MAIN layout row count rather than a global 4/5-row assumption.

This keeps English, Hindi, Symbols, and custom layouts independent while preserving dynamic row sizing.
