# pronunciation-engine

On-device pronunciation scoring for Android (Kotlin AAR). Sentence + 16 kHz audio →
per-word / per-phone scores, three scoring methods, respelling with stress check,
pitch track. No network, one 67 MB ZIPA model, one runtime dependency
(`onnxruntime-android`).

```kotlin
val engine = PronunciationEngine.load(context, tableJson)        // once (extracts the model on first run)

val result = withContext(Dispatchers.Default) {                  // blocking, ~150-250 ms on arm64
    engine.evaluate("I read a book.", wavFile)                   // 16 kHz mono PCM16 WAV
}
result.overall        // 0-100 (method A by default)
result.grade          // A/B/C/D/F
result.words[1].score
result.words[1].phones            // PhoneCell(expected, actual, top, conf, score, status)
result.words[1].respell           // Respell(syllables=["R.EE", …], stress, heard, correct, stressScore, …)
result.freeIpa        // what ZIPA heard, unconstrained
result.pitch, result.wpm, result.durS, result.timingsMs

engine.lookup("I read a book.")   // List<WordIpa>? — target phones without audio; null if not in table
engine.close()
```

Also `evaluate(sentence, samples16k: FloatArray)` for apps that record into memory.

## Scoring methods

All three are always computed and returned in `Result.scores` / `WordScore.scores`.
`method` picks which one fills `overall`, `grade` and `WordScore.score`:

| `Method` | what it is | bench agreement w/ app (1,000 clips) |
|---|---|---|
| `A` (default) | pronounce.js — free-IPA edit alignment blended with the aligner's per-slot verification | 89.5 % |
| `PFER_SLOT` | 20-feature articulatory distance of the aligner's top phone vs target, 1:1 | 84.7 % |
| `PFER_SEQ` | feature-weighted edit distance vs the free recognition, best-window (repeated takes don't sink it) | 88.4 % |

```kotlin
engine.evaluate(sentence, wav, Method.PFER_SEQ)
```

`grade` uses A's thresholds (A ≥ 90, B ≥ 80, C ≥ 70, D ≥ 55) for every method.

## Table

The engine does no G2P. It looks the sentence up in a table the app supplies as a
JSON string — one entry per sentence, one object per word:

```json
{
  "He asked me to open the window.": [
    {"word": "He",    "wordIndex": 0, "ipa": "h ˈi"},
    {"word": "asked", "wordIndex": 1, "ipa": "ˈæ s k t"},
    …
  ]
}
```

- `ipa`: **space-separated phones, stress marks kept** (`p ɹ ˈɛ z ə n t`). Stress is
  used by the respelling and stripped before alignment. An unspaced string still
  aligns (ZIPA tokens are single characters) but per-phone cells get coarser.
- Unknown fields are ignored, so a column-K export from the sheet works unchanged.
- Lookup ignores `{}` markers, surrounding/duplicate whitespace, and curly vs
  straight apostrophes.
- Sentence not in the table: `lookup` → `null`, `evaluate` → `SentenceNotFoundException`.
- `load` throws `IllegalArgumentException` if the JSON isn't a valid table.

## Audio

16 kHz mono 16-bit PCM WAV, ≥ 0.5 s. Anything else throws `IllegalArgumentException`
(no resampling). Stereo is downmixed.

## The model

ZIPA — `anyspeech/zipa-small-crctc-500k` on Hugging Face (zipformer2 CTC, 127-token
single-character IPA vocabulary). The ONNX in `engine/src/main/assets/pronunciation_engine/zipa/`
is that checkpoint exported with icefall's zipformer2-CTC ONNX export (`model_author=k2-fsa`)
and dynamically quantized to int8 with `onnxruntime.quantization` (`producer=onnx.quantize`).
It was exported on 2026-07-01 and benchmarked in `ondevice/BENCHMARK_RESULTS.md`; the
exact export command isn't recorded, so treat the committed file as the artifact of record:

```
model.int8.onnx  67.4 MB  sha256 d0e28b68164e8b1fbd6105100c01798828aa0855000ce9bbbd1a2cec233adf13
tokens.txt         769 B  sha256 f8e042a0c9130532b22d03ec7cae2f75a23fbec70c450c31a8efb51787b2b8fe
inputs   x [N,T,80] float32 (kaldi fbank, computed by Fbank.kt) · x_lens [N] int64
output   log_probs [N,T/4,127] float32 (already log-softmax) · opset 13
```

It ships inside the AAR; consumers download nothing. Other copies: `~/Desktop/ondevice_kit/zipa/`
(with fp16 126 MB and reference decode), `ondevice/native/app/src/main/assets/zipa/`,
`ondevice/flutter/assets/models/zipa/`.

## Consuming the AAR

```
./gradlew :engine:assembleRelease     # → engine/build/outputs/aar/engine-release.aar
```

```kotlin
dependencies { implementation(files("libs/engine-release.aar")) }
// onnxruntime-android comes transitively via the AAR's pom when consumed from Maven;
// with a bare .aar file, add it yourself:
dependencies { implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3") }

android { androidResources { noCompress += "onnx" } }   // optional: faster first load
```

minSdk 24. The model is extracted from assets to `filesDir/pronunciation_engine/zipa/`
on first `load` (≈1 s), skipped afterwards.

## Tests

```
./gradlew :engine:test                    # JVM: fbank vs reference vectors, Viterbi, scoring,
                                          #      PFER vs node fixture, respell vs python fixture,
                                          #      whole post-ORT chain on dumped ZIPA log-probs
./gradlew :engine:connectedAndroidTest    # device: load + evaluate test.wav end to end
```

## What's inside

| file | port of |
|---|---|
| `Fbank.kt` | mini-coach `audio/fbank.dart` — kaldi 80-dim fbank |
| `Ctc.kt` | `align/viterbi.dart` + `models/zipa_aligner.dart` — CTC Viterbi, token→phone merge, greedy decode |
| `Scoring.kt` | `scoring/pronounce.dart` — method A |
| `Pfer.kt` | `bench_scripts/pfer/pfer.mjs` — methods B and C |
| `Respell.kt` | `selvas/respell.py` (syllabify) + dashboard `stress.ts` (stress check) |
| `Prosody.kt` | `audio/dsp.dart` — pitch track |
| `PronunciationEngine.kt` | `pipeline.dart` orchestration + the ORT call |
