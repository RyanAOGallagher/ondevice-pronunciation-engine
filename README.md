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
result.rating           // BAD / OK / GOOD / EXCELLENT — calibrated bands, see below
result.grade            // A / B / C / D / F
result.words            // one entry per word: score, phones, respell (syllables), stress (placement check)
result.userGraph        // the learner's loudness graph, 100 ints — see "Graphs"

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

A row may also carry the native speaker's graph (see "Graphs"). Then it is an object:

```json
"I see stars.": {
  "words":  [{"word": "I", "wordIndex": 0, "ipa": "ˈaɪ"}, …],
  "graph":  [1, 1, 1, 5, 27, 44, … 100 ints 0–100],
  "accent": [75, 42, 14],
  "spanMs": 2160,
  "wordMs": [["I", 80, 590], ["see", 600, 1130], ["stars.", 1190, 2000]]
}
```

`tools/add_tutor_graphs.py <repeat CSV>` fills these from the production export
(`graph_value`, `graph_accent`) and the reference `.dat` word timings. Plain rows keep working.

## Rating

`result.rating` puts the method-A score into the four bands the app uses, with cutoffs
fitted by isotonic regression on 200 rated learner takes: Bad < 62 ≤ OK < 64 ≤ Good < 75 ≤ Excellent.
`ratingOf(score)` is public if you want to band a number yourself. `grade` is the older
letter scale and is unchanged.

## Graphs

The app draws a "Standard / Yours" loudness graph. `Result` carries both sides in the same shape:

```kotlin
// learner — computed from this take     // tutor — copied from the table row, null if it has none
r.userGraph    IntArray(100)              r.tutorGraph    IntArray?
r.userWords    List<GraphWord>            r.tutorWords    List<GraphWord>?
r.userSpanMs   Int                        r.tutorSpanMs   Int?
                                          r.tutorAccent   IntArray?   // bar indices of the accented words
```

Each graph is 100 ints 0–100 spanning `0..spanMs`, tallest bar = 100 — the format of the
app's `graph_value`. `GraphWord` is `text, startMs, endMs` on that timeline, so a word's bar
is `startMs * 100 / spanMs` on either side.

The learner graph is an 80 ms RMS envelope over the whole take, run through a port of the
product's `arrayToGraphData_buffer`. It matches ACD Maker's `[Energy (EPD size)]` curve through
the same converter to ~0.92 correlation. The tutor graph is never computed here: the production
values are hand-tuned (fixed-height peaks stamped at the accent words), so they are data, not audio.

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
./gradlew :engine:test                     # no phone needed (GraphTest: envelope + converter vs fixtures)
./gradlew :engine:connectedAndroidTest     # phone plugged in
```

## Model

ZIPA (`anyspeech/zipa-small-crctc-500k`), exported to ONNX and quantized to int8.
Lives at `engine/src/main/assets/pronunciation_engine/zipa/`. It's in git.
