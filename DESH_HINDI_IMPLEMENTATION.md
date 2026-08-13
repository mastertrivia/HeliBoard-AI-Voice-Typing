# देश हिंदी keyboard — Desh-style Hindi integration

This fork adds a separate `देश हिंदी keyboard` subtype (`desh_hindi`). Existing HeliBoard Hindi subtypes are not replaced.

## Safety / fallback design

- The Desh predictor is **lazy-loaded** only when the dedicated subtype requests suggestions.
- The predictor backend is currently packaged for `arm64-v8a`, matching the supplied Desh APK. Other ABIs automatically use HeliBoard's normal suggestion engine.
- Native library/model loading is wrapped in exception handling. If loading or a predictor call fails, HeliBoard falls back to its normal suggestion engine instead of allowing the predictor failure to take down the IME.
- The contextual vowel/diacritic state query is also guarded so an editor that rejects `getTextBeforeCursor()` cannot crash the keyboard.
- The dedicated subtype uses `NoNumberRow`, so the global HeliBoard Number row preference does not consume space in this Hindi keyboard.

## Desh predictor assets

The supplied Desh APK's native predictor backend and model assets are bundled under:

`app/src/main/assets/desh_predictor/`

and the required arm64 native dependencies under:

`app/src/main/jniLibs/arm64-v8a/`

The bridge calls the native predictor's exported interface for native-layout prefix search and next-word prediction. This is kept isolated from HeliBoard's normal dictionary engine so the rest of the keyboard remains functional if the backend is unavailable.

## Build note

The source was structurally/lexically validated here, but a full Gradle build was not possible in this environment because the Gradle 8.14 distribution is not locally cached and external network access is unavailable. Build the project normally in your Android/Gradle environment. If compilation reports an error, send the exact error and line and it can be corrected without changing the fallback architecture.

## Important

The bundled Desh model/native components originate from the supplied proprietary Desh APK. Keep this fork for personal/testing use unless you have the necessary rights to redistribute those components.
