# देश हिंदी keyboard (Desh-style Hindi subtype)

This fork adds a separate HeliBoard subtype named `देश हिंदी keyboard`.

Implemented:
- Dedicated Hindi subtype in Languages & Layouts; existing Hindi subtypes remain unchanged.
- Built-in `desh_hindi` five-row Devanagari main layout.
- The optional global number row is forcibly hidden for this subtype via `NoNumberRow`.
- The first row changes contextually after a Devanagari consonant:
  - default: अ आ इ ई उ ऊ ए ऐ ओ औ ं
  - after consonant: ः ा ि ी ु ू े ै ो ौ ँ
- Keyboard cache identity includes the contextual state so the visible keys can actually change.
- The contextual state is recomputed from text before the cursor, so typing, deletion, cursor movement, paste, and autocorrection can update it.
- Conjunct/halant and contextual popup keys are included for common Hindi combinations.

The existing HeliBoard Hindi, Hindi (Compact), and Hindi (Phonetic) subtypes are not replaced.

Build note:
The project could not be Gradle-compiled in the modification environment because the Gradle wrapper distribution was not locally cached and external network access was unavailable. XML/JSON files were syntax-validated and the changed Kotlin sources were parser-checked; build on the user's normal Android/Gradle environment is still required.
