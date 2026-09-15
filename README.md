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
The take is peak-normalised and trimmed to its voiced span (250 ms pad) before scoring, so raw mic audio
is fine; `r.trimStartMs` says how much was cut from the front if you need to map times back.

## What comes back

`evaluate` returns one `Result`. Four groups: score, words, stress, graphs. Examples below are for
`"I see stars."` (values illustrative).

### Score

| Field | Type | What it is |
|---|---|---|
| `overall` | Int | 0–100, the chosen method's score (A by default) |
| `rating` | Rating | `BAD` / `OK` / `GOOD` / `EXCELLENT` |
| `grade` | String | A–F letter |
| `scores` | Scores | `.a` `.pferSlot` `.pferSeq`, all three methods |
| `freeIpa` | String | what the recogniser heard |

```kotlin
r.overall   // 78
r.rating    // EXCELLENT
r.grade     // "B"
r.scores    // Scores(a=78, pferSlot=81, pferSeq=74)
r.freeIpa   // "aɪ si stɑɹz"
```

### Words

`r.words` has one `WordScore` per word:

| Field | What it is |
|---|---|
| `text` `score` `scores` | the word and its scores |
| `startS` `endS` | where it was heard, seconds |
| `phones[k]` | `.expected` `.actual` `.top` `.conf` `.score` `.status` (`ok` / `sub` / `missing`) |
| `respell` | `.syllables` `.stress` `.text` |
| `stress` | this word's stress check, see below |

```kotlin
r.words[1].text      // "see"
r.words[1].score     // 84
r.words[1].startS    // 0.61
r.words[1].endS      // 1.12
r.words[1].phones    // [PhoneCell(expected="s", actual="s", top="s", conf=0.93, score=100, status="ok"),
                     //  PhoneCell(expected="iː", actual="i", top="i", conf=0.88, score=100, status="ok")]
r.words[1].respell   // WordRespell("see", ["S.EE"], stress=null)  → .text "S.EE"
```

### Stress

Per word in `r.words[i].stress`, tallies in `r.stress`. Monosyllables are skipped.

| Field | What it is |
|---|---|
| `target` `heard` `correct` | which syllable should be stressed, which was, and whether they match |
| `score` | how clearly, 0–100, 50 = tie |
| `sylScores` | each syllable judged on its own |
| `skipped` | why not checked, e.g. `"monosyllable"` |
| `r.stress.correct` / `.scored` | words with the right syllable / words checked |
| `r.stress.sylCorrect` / `.sylScored` | syllables in the right role / judged |

```kotlin
val g = r.words.first { it.text == "greasy" }.stress!!   // from another sentence
g.syllables   // ["G.R.EE", "Z.EE"]
g.target      // 0
g.heard       // 0
g.correct     // true
g.score       // 71
r.stress.correct / r.stress.scored   // 5 / 6
```

### Graphs

The app's "Standard / Yours" panel. Both sides have the same shape: 100 ints 0–100, tallest bar = 100,
spanning `0..spanMs`, plus the words in ms on that timeline. Bar of a word = `startMs * 100 / spanMs`.

| Field | Type | What it is |
|---|---|---|
| `userGraph` | IntArray(100) | your graph, computed over your spoken words (80 ms pad each side) |
| `userWords` | List\<GraphWord\> | your words: `.text` `.startMs` `.endMs` |
| `userSpanMs` | Int | your timeline length |
| `userGraphStartMs` | Int | where your graph starts on the take (80 ms before the first word) |
| `tutorGraph` | IntArray? | native graph from the table, null if the row has none |
| `tutorWords` | List\<GraphWord\>? | native words |
| `tutorSpanMs` | Int? | native timeline length |
| `tutorAccent` | IntArray? | graph indices of the accented words |

```kotlin
r.userGraph     // [0, 0, 2, 12, 27, 42, 47, 40, 23, 9, 12, 26, 53, 85, 100, 87, 53, 24, …]  100 values
r.userWords     // [GraphWord("I", 80, 595), GraphWord("see", 595, 1105), GraphWord("stars.", 1165, 1975)]
r.userSpanMs    // 2055
r.userGraphStartMs // 15

r.tutorGraph    // [1, 1, 1, 1, 1, 1, 1, 5, 27, 44, 47, 47, 54, 79, 97, 83, 61, 40, …]  100 values
r.tutorWords    // [GraphWord("I", 80, 590), GraphWord("see", 600, 1130), GraphWord("stars.", 1190, 2000)]
r.tutorSpanMs   // 2160
r.tutorAccent   // [75, 42, 14]
```

Also on `Result`: `durS`, `trimStartMs`, `wpm`, `pitch` (10 ms F0/energy frames), `timingsMs` (per stage).

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
