# Notes for building the demo app

State (2026-09-09): `engine/` is done and pushed. 15 JVM tests green, AAR builds.
Not yet run on a phone. No app module exists yet.

## Toolchain
- JDK 17, Android SDK with platform 35. Gradle wrapper 8.14.3 downloads itself; AGP 8.7.3, Kotlin 2.0.21.
- `local.properties` is gitignored — create it with `sdk.dir=/Users/<you>/Library/Android/sdk` (or set `ANDROID_HOME`).
- First check the toolchain with no phone: `./gradlew :engine:test` (should be 15/15).

## Simplest app wiring
Add an `:app` module in this repo (Compose, minSdk 24) and depend on the engine directly —
no AAR copying:
```kotlin
// settings.gradle.kts: include(":app")
// app/build.gradle.kts:
dependencies { implementation(project(":engine")) }
android { androidResources { noCompress += "onnx" } }   // faster first load
```

## The table
`table/sentence_ipa.json` in this repo (1.7 MB) is the table — 3,579 sentences, same JSON
shape the engine reads. Copy it to `app/src/main/assets/`
and `PronunciationEngine.load(ctx, assets.open("sentence_ipa.json").bufferedReader().readText())`.
Its `ipa` is unspaced (`pɹˈɛzənt`); that aligns fine, per-phone cells are just coarser.
Any sentence you test must be in it — `lookup(sentence)` returns null otherwise.

## Audio
Record with `AudioRecord` at 16000 Hz, `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT`, convert
shorts to floats (`/32768f`), run `PronunciationEngine.preprocess(samples)` (peak-normalise +
trim, same as mini-coach's `dsp.dart`), then `engine.evaluate(sentence, samples)` — no WAV file
needed. Skipping preprocess on quiet mic audio makes the model decode nothing. Needs `RECORD_AUDIO` permission. Anything ≥ 0.5 s.

## Calling it
- `load` once (extracts 67 MB to filesDir on first run, ~1 s). Keep the instance.
- `evaluate` is blocking (~150–250 ms on arm64) and synchronized — `withContext(Dispatchers.Default)`.
- `engine.respell(sentence)` / `respellWords(sentence)` need no audio.

## First thing to do with a phone plugged in
`./gradlew :engine:connectedAndroidTest` — end-to-end on the bundled `test.wav`.
Look at logcat tag `PronEngine` for timings and the decoded IPA. Expect freeIpa
`ʃihædjəɪdəksuləndgɹiɪsiwɑʃwɔtə˞ɔɔʊljɪɹ` (sherpa's reference for that clip; the desktop
fixture differs by one token — int8 kernels).

## Known caveats
- Learner-side stress (`Respell.heard / stressScore`) is unverified on real recordings —
  on `test.wav` it says syllable 2 for `greasy`/`water`, which is wrong; that clip is very quiet
  (44/290 pitch frames voiced). Judge it on a real take before showing it.
- `grade` letters use method A's thresholds for all three methods.
- Only 16 kHz; no resampling.
- Emulator: arm64 image on Apple Silicon works; onnxruntime-android also ships x86_64.
