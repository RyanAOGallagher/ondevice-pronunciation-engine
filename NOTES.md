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
shorts to floats (`/32768f`), run the demo app's `preprocess(samples)` (peak-normalise + trim,
same as mini-coach's `dsp.dart`; lives in the app, not the SDK), then `engine.evaluate(sentence, samples)` — no WAV file
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

## Reduced ONNX Runtime (how the 9 MB `libonnxruntime.so` was made)

Stock `onnxruntime-android` is 25 MB per ABI because it carries every operator. The engine
bundles a rebuild of ORT **1.24.3** containing only the ops ZIPA needs, arm64-v8a only:

```
git clone --depth 1 --branch v1.24.3 --recursive --shallow-submodules https://github.com/microsoft/onnxruntime
# ops config = static graph ops ∪ ops present after ORT_ENABLE_EXTENDED optimisation
# (the optimiser inserts contrib fusions like com.microsoft.MatMulIntegerToFloat at
#  session-creation time — a config from the raw graph alone misses them and the
#  session fails with "Failed to find kernel"). See engine/libs/zipa_required_ops.config.
python tools/python/create_reduced_build_config.py --format ONNX model.int8.onnx static.config
python tools/python/create_reduced_build_config.py --format ONNX model.optimized_extended.onnx opt.config
#   (model.optimized_extended.onnx = desktop ORT session with
#    graph_optimization_level=ORT_ENABLE_EXTENDED and optimized_model_filepath set)
CMAKE_POLICY_VERSION_MINIMUM=3.5 ./build.sh --android \
  --android_sdk_path $SDK --android_ndk_path $SDK/ndk/27.1.12297006 \
  --android_abi arm64-v8a --android_api 24 --config MinSizeRel --build_shared_lib \
  --include_ops_by_config zipa_required_ops.config --disable_ml_ops --disable_rtti \
  --skip_tests --compile_no_warning_as_error --parallel --cmake_generator Ninja --target onnxruntime
```

Outputs `libonnxruntime.so` (+ `libonnxruntime4j_jni.so` from the earlier `--build_java` run;
ORT's own Gradle step for the Java AAR fails on JBR jlink, but the JNI lib is built before
that). They go in `engine/src/main/jniLibs/arm64-v8a/`; the Java classes are the unmodified
`classes.jar` from the Maven `onnxruntime-android-1.24.3.aar`, in `engine/libs/`. The engine
pins `OptLevel.EXTENDED_OPT` so the runtime never asks for a fusion that isn't compiled in.

Consumers must NOT add `com.microsoft.onnxruntime:onnxruntime-android` themselves — the
classes would clash. Other ABIs: rebuild with `--android_abi armeabi-v7a` etc. and add the
`.so` under `jniLibs/<abi>/`.
