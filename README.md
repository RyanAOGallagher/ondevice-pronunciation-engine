# ondevice-pronunciation-engine

## Install

1. Download `ondevice-pronunciation-engine-<version>.aar` from
   [Releases](https://github.com/RyanAOGallagher/ondevice-pronunciation-engine/releases) and put it in your app's `libs/` folder.
2. In `app/build.gradle.kts`:

```kotlin
dependencies { implementation(files("libs/ondevice-pronunciation-engine-0.2.0.aar")) }
android {
    defaultConfig { ndk { abiFilters += "arm64-v8a" } }   // the AAR ships arm64 native code only
    androidResources { noCompress += "onnx" }              // faster first load
}
```

That's everything — the AAR contains the model and its own trimmed ONNX Runtime. Do **not**
add `com.microsoft.onnxruntime:onnxruntime-android` as well; the classes would clash.
minSdk 24. (Working in this repo instead? `implementation(project(":engine"))`.)

## Use it

```kotlin
val engine = PronunciationEngine.load(context, tableJson)   // once; load(ctx, json, threads = 8) to use more cores

val result = engine.evaluate("I read a book.", audioFile)   // call off the main thread

result.overall          // 0–100
result.grade            // A / B / C / D / F
result.words            // one entry per word: score, phones, respell (syllables), stress (placement check)

engine.respell("I read a book.")        // no audio: "ai / R.EH.D / uh / B.U.K"
engine.respellWords("I read a book.")   // same, per word: [WordRespell("read", ["R.EH.D"], stress=null), …]
```

`audioFile` must be 16 kHz (no resampling). 16-bit PCM WAV is parsed directly; MP3, M4A, OGG and FLAC go through Android's own decoder. MP3s keep their Xing/LAME info tag so the decoder strips the encoder delay — tested sample-exact on device.

The SDK only scores. Recording, normalising and trimming the audio is the app's job —
the demo app's `preprocess()` (peak-normalise + trim silence) shows the minimum that quiet
phone-mic audio needs before it will decode.

## The table

The engine doesn't guess pronunciations. It looks the sentence up in a JSON table you
give it — one line per sentence, one object per word:

```json
{
  "I read a book.": [
    {"word": "I",    "wordIndex": 0, "ipa": "ˈaɪ"},
    {"word": "read", "wordIndex": 1, "ipa": "ɹ ˈɛ d"},
    {"word": "a",    "wordIndex": 2, "ipa": "ə"},
    {"word": "book", "wordIndex": 3, "ipa": "b ˈʊ k"}
  ]
}
```

Sentence not in the table → `SentenceNotFoundException`.

## Three ways to score

```kotlin
engine.evaluate(sentence, wav)                      // A  (default)
engine.evaluate(sentence, wav, Method.PFER_SLOT)    // B
engine.evaluate(sentence, wav, Method.PFER_SEQ)     // C
```

All three numbers are always in `result.scores`; `method` only picks which one is `overall`.

## Stress

Word scores are pronunciation only. Stress placement is checked separately, per word:

```kotlin
val g = r.stress.words.first { it?.word == "greasy" }!!   // or r.words[6].stress!!

g.syllables    // [G.R.EE, Z.EE]
g.target       // 0            which syllable should be stressed (from the table)
g.heard        // 0            which one the learner stressed (from the audio)
g.correct      // true
g.score        // 71           how clearly, 0–100, 50 = tie
g.sylScores    // [70, 63]     each syllable judged on its own: stressed one above average, others below
g.skipped      // null         "monosyllable" etc. when there was nothing to check

r.stress.correct / r.stress.scored        // words with the right syllable / words checked
r.stress.sylCorrect / r.stress.sylScored  // syllables in the right role / syllables checked
```

Prominence = 0.45·pitch + 0.35·energy + 0.20·duration over each vowel's aligned span.
Monosyllables are skipped. Not folded into `overall` — combine them yourself if you want one number.

## Build the AAR yourself

```
./gradlew :engine:assembleRelease     # → engine/build/outputs/aar/engine-release.aar
```

## Tests

```
./gradlew :engine:test                     # no phone needed
./gradlew :engine:connectedAndroidTest     # phone plugged in
```

## Model

ZIPA (`anyspeech/zipa-small-crctc-500k`), exported to ONNX and quantized to int8.
Lives at `engine/src/main/assets/pronunciation_engine/zipa/`. It's in git.
