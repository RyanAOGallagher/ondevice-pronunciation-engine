# ondevice-pronunciation-engine

## Install

1. Download `pronunciation-engine-0.1.0.aar` from
   [Releases](https://github.com/RyanAOGallagher/ondevice-pronunciation-engine/releases) and put it in your app's `libs/` folder.
2. In `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(files("libs/pronunciation-engine-0.1.0.aar"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
}
android { androidResources { noCompress += "onnx" } }
```

minSdk 24. Sync, done. (Working in this repo instead? `implementation(project(":engine"))`.)

Why the second line: a bare `.aar` doesn't carry its dependencies, so ONNX Runtime has to be
added by the app. There's no "add from GitHub" in Android Studio — that would need JitPack or
GitHub Packages, and since the repo is private both need a token on the consumer side. Not set
up; ask if there's more than one app consuming this.

## Use it

```kotlin
val engine = PronunciationEngine.load(context, tableJson)   // once; load(ctx, json, threads = 8) to use more cores

val result = engine.evaluate("I read a book.", wavFile)     // call off the main thread

result.overall          // 0–100
result.grade            // A / B / C / D / F
result.words            // one entry per word: score, phones, respell (syllables), stress (placement check)

engine.respell("I read a book.")        // no audio: "ai / R.EH.D / uh / B.U.K"
engine.respellWords("I read a book.")   // same, per word: [WordRespell("read", ["R.EH.D"], stress=null), …]
```

`wavFile` must be a 16 kHz mono 16-bit WAV.

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
