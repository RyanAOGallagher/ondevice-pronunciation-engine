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
val r = engine.evaluate("I read a book.", audioFile)        // off the main thread; or evaluate(sentence, FloatArray 16 kHz)
```

`audioFile` must be 16 kHz (no resampling): 16-bit PCM WAV directly, MP3/M4A/OGG/FLAC via Android's decoder.
The SDK only scores — record, normalise and trim first (the demo's `preprocess()` is the minimum for phone-mic audio).

## Everything on `Result`

```kotlin
// score
r.overall          Int              0–100, the chosen method's score (A by default)
r.rating           Rating           BAD / OK / GOOD / EXCELLENT  (A score, cutoffs 62 / 64 / 75 fitted on 200 rated takes)
r.grade            String           A–F letter
r.scores           Scores           .a .pferSlot .pferSeq — all three methods, always
r.freeIpa          String           what the recogniser heard, unconstrained

// words
r.words[i].text / .score / .scores / .startS / .endS
r.words[i].phones[k]    .expected .actual .top .conf .score .status   // "ok" | "sub" | "missing"
r.words[i].respell      .syllables .stress .text                       // e.g. "_G.R.EE_ | Z.EE"
r.words[i].stress       .target .heard .correct .score .sylScores .skipped
r.stress                .correct/.scored  .sylCorrect/.sylScored      // take-wide stress tallies

// graphs — learner computed from this take, tutor copied from the table row (null if it has none)
r.userGraph        IntArray(100)    r.tutorGraph    IntArray?        100 ints 0–100, tallest = 100, spanning 0..spanMs
r.userWords        List<GraphWord>  r.tutorWords    List<GraphWord>? .text .startMs .endMs — bar = startMs * 100 / spanMs
r.userSpanMs       Int              r.tutorSpanMs   Int?
                                    r.tutorAccent   IntArray?        bar indices of the accented words

// misc
r.durS  r.wpm  r.pitch  r.timingsMs
```

Other engine calls, no audio needed:

```kotlin
engine.respell("I read a book.")        // "ai / R.EH.D / uh / B.U.K"
engine.respellWords("I read a book.")   // per word: [WordRespell("read", ["R.EH.D"], stress=null), …]
engine.lookup(sentence)                 // the table row's phones, or null
ratingOf(score)                         // band any A score yourself
engine.evaluate(sentence, wav, Method.PFER_SLOT)   // B; Method.PFER_SEQ = C; picks what `overall` is
```

## The table

The engine doesn't guess pronunciations. It looks the sentence up in a JSON table you give it.
A row is the word list, or an object that also carries the native speaker's graph:

```json
{
  "I read a book.": [
    {"word": "I", "wordIndex": 0, "ipa": "ˈaɪ"}, {"word": "read", "wordIndex": 1, "ipa": "ɹ ˈɛ d"}, …
  ],
  "I see stars.": {
    "words":  [{"word": "I", "wordIndex": 0, "ipa": "ˈaɪ"}, …],
    "graph":  [1, 1, 1, 5, 27, 44, … 100 ints],
    "accent": [75, 42, 14],
    "spanMs": 2160,
    "wordMs": [["I", 80, 590], ["see", 600, 1130], ["stars.", 1190, 2000]]
  }
}
```

Sentence not in the table → `SentenceNotFoundException`. `tools/add_tutor_graphs.py <repeat CSV>`
fills the graph fields from the production export and the reference `.dat` timings.

## Notes

- **Rating** bands come from isotonic regression on 200 rated learner takes; `grade` is the older letter scale.
- **Learner graph** = 80 ms RMS envelope over the whole take through a port of the app's `arrayToGraphData_buffer`;
  ~0.92 correlation with ACD Maker's `[Energy (EPD size)]` curve. **Tutor graph** is never computed — the
  production values are hand-tuned (fixed-height peaks at the accent words), so they are data.
- **Stress**: prominence = 0.45·pitch + 0.35·energy + 0.20·duration over each vowel; monosyllables skipped;
  not folded into `overall`.

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
