# pronunciation-engine

Android library. You give it a sentence and a recording, it gives you a score.
Everything runs on the phone.

## Use it

```kotlin
val engine = PronunciationEngine.load(context, tableJson)   // once

val result = engine.evaluate("I read a book.", wavFile)     // call off the main thread

result.overall          // 0–100
result.grade            // A / B / C / D / F
result.words            // one entry per word: score, phones, respelling

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

## Build

```
./gradlew :engine:assembleRelease
→ engine/build/outputs/aar/engine-release.aar
```

Drop the `.aar` into your app's `libs/` and add:

```kotlin
implementation(files("libs/engine-release.aar"))
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
```

## Tests

```
./gradlew :engine:test                     # no phone needed
./gradlew :engine:connectedAndroidTest     # phone plugged in
```

## Model

ZIPA (`anyspeech/zipa-small-crctc-500k`), exported to ONNX and quantized to int8.
Lives at `engine/src/main/assets/pronunciation_engine/zipa/`. It's in git.
