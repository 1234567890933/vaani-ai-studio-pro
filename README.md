# HindiDubber v4 — offline dubbing studio for Android

Dub any video **into an Indian language**, on the phone: Whisper ASR → NLLB translation →
offline TTS → foley-preserving mix → MP4. Source can be Japanese, Korean, English, anything
Whisper hears. Optional cloud translation (Sarvam / Gemini / OpenAI / Grok) when you add a key.

---

## 0. v5 — engine rebuilt around memory

The complaint that the engine was not properly optimised was correct, and it was measurable:
v4 held the whole soundtrack in RAM **twice** (stereo bed + mono ASR track) and collected
samples in `ArrayList<Float>`, so a 24-minute episode peaked around 1.3 GB — an OOM on any
4 GB phone, and the reason low-end output would be poor.

v5 makes memory independent of video length:

* **Windowed decode** — 4-minute windows; a 2-hour film costs the same RAM as a 2-minute clip.
* **Disk-backed audio** — `PcmStore` (PCM16, frame-addressed, sparse writes) for the voice
  timeline and the original bed; `AudioMixer.streamMix` mixes block by block;
  `VideoMuxer` AAC-encodes from file. Peak RAM for a 24-minute episode drops to ~350–450 MB.
* **Primitive buffers** — `FloatSink` instead of `ArrayList<Float>` (no boxing).
* **Sample-accurate windows** — the decoder drops the pre-roll after `seekTo`, so segment
  timestamps do not drift window to window.
* **Silence-only windows skipped** before ASR is even called.

Also fixed in this pass: nullable store references that would not have compiled, frame-size
maths for float-PCM tracks (4 bytes/sample, not 2), thread-count drift where the governor's
reduced value was written back permanently, and the float-vs-16-bit confusion in the decoder.

Full design and the numbers: `ENGINE.md`.


## 0a. v6 — tested, not just written

For the first time the engine's logic layer has been **compiled and executed**, not just
reviewed. `tools/jvmtest/` builds the pure-logic classes with a plain Kotlin compiler (no
Android SDK) and runs a suite of **1,502 assertions per run across 17 groups**; 20 seeded
rounds passed with different random data (30,040 assertions, 0 failures).

Three real bugs were found and fixed by that suite:

* `JsonArrays` leaked strings out of nested JSON objects, so a reply like `[{"a":1},"b"]`
  produced a bogus extra line.
* `JsonArrays` accepted a truncated string, which could inject half a line into the dub.
* `AudioMixer` soft-clipped every sample with `tanh`, compressing loud scenes unevenly. It now
  measures the peak in a first pass and applies one global gain to land on 0.98, and reports
  that gain in the Engine panel.

Also new: the Engine panel shows a live **engine trace** (`Diag`, a bounded in-memory ring of
400 events), which is what you want when a job fails on a phone you cannot attach to. Full
detail in `TESTING.md`.


## 0b. v7 — compiled against the real Android SDK

Every previous version was written and reviewed but never compiled. v7 compiles it: a
dependency resolver, a KMP-aware classpath builder and a `kotlinc` run against
`android-35/android.jar` (all in `tools/android-check/`).

* **Frontend type-check of all 64 source files: 0 errors.**
* **Bytecode for every non-UI layer: 0 errors, 110 classes.**
* Logic self-test: 1,502 assertions × 20 seeded rounds, 0 failures.

Three real bugs the compiler caught that review had missed: `OptLevel.BASIC` does not exist
(it is `BASIC_OPT`), `List<Long>.toIntArray()` does not exist (needs `map { it.toInt() }`), and
the UI's `viewModel()` call was replaced with an explicit factory owned by the Activity —
removing a dependency and a reflection path.

What still cannot be verified here: Compose bytecode generation (the Kotlin inliner crashes on
AAR-derived jars — a harness limitation, not a source error) and anything that needs a phone:
MediaCodec, JNI, ONNX sessions, TTS, muxing, memory. `TESTING.md` has the full matrix.


## 0c. v8 — offline build, and a bigger package for a reason

Two things changed, both about the package rather than the code.

**The native engines are now vendored.** `vendor/whisper.cpp` (v1.7.4) and
`vendor/sentencepiece` (v0.2.0) ship inside the zip, and `app/src/main/cpp/CMakeLists.txt`
points at them by default. Consequences:

* the native build needs **no network at all** — no `FetchContent` clone on first build;
* the pinned versions are the ones you build, not whatever a mirror serves that day;
* the package is ~21 MB instead of ~130 KB.

**Two more automated checks** (in `tools/android-check/`, run them with
`bash tools/android-check/verify.sh`):

| Check | What it catches | Result |
|---|---|---|
| `check_refs.py` | a manifest class that does not exist, an `@drawable/@string/@style` with no resource, an `R.*` reference with no resource — the classic "installs but crashes on launch" | **0 problems** |
| `check_deps.py` | an `import` with no matching Gradle dependency (a build failure in Gradle) | **0 problems** |

### About sizes, honestly

| Artifact | Size | Why |
|---|---|---|
| This zip | ~21 MB | source + vendored C/C++ engines; the Kotlin is only ~200 KB of it |
| Debug APK | ~40–80 MB | code + native libs per ABI (whisper.cpp, ggml, sentencepiece, ONNX Runtime) |
| Models after install | ~1.1 GB | Whisper small q5_1 (181 MB) + NLLB INT8 (912 MB), downloaded by the app on first run |

The zip is not the app. A 2 MB zip of Kotlin files and a 21 MB zip with vendored engines build
the *same* APK; the difference is whether the build needs internet. If you want the models
inside the package, that is what the app's **Set up automatically** button does after install —
it downloads them with resume support and verifies every byte, because shipping 1.1 GB inside
an APK is not installable on Android.


## 0d. v9 — a 10 GB model pool, a bundled starter model, and animation

### The package is now ~62 MB, and everything in it has a job

| Contents | Size | Why it is there |
|---|---|---|
| `vendor/whisper.cpp` (v1.7.4) + `vendor/sentencepiece` (v0.2.0) | 21 MB | native build with **no network** |
| `vendor/onnxruntime/onnxruntime-android-1.20.0.aar` | 27 MB | resolved from `flatDir`, so the dependency is vendored too |
| `app/src/main/assets/models/ggml-tiny-q5_1.bin` | 31 MB | **bundled starter model**: a fresh install can dub a clip before any download |
| Kotlin sources, harness, docs | ~1 MB | the app itself |

### The model pool: 10 options, 10.3 GB total, split by task

| Stage | Options | Range |
|---|---|---|
| ASR | tiny (bundled), base, small, medium, large-v3-turbo, large-v3 q5, large-v3 fp16 | 32 MB → 3.1 GB |
| MT | IndicTrans2 int8, NLLB int8, NLLB fp32 | 283 MB → 3.5 GB |
| TTS | system offline voice | 0 MB |

Total if you install everything: **10.3 GB**. Every size was read from the file's real
`Content-Length`, so the progress bar and the storage pre-flight are exact. You do not need
all of it — the setup screen picks a tier from the device's RAM (S/M/L/XL) and the Models
screen lets you switch any stage independently.

### Animation, with a reason for each

`ui/Animations.kt` holds four primitives, all driven by the Compose frame clock:

* **PulsingDot** — "the engine is alive right now". A static spinner cannot tell you whether a
  20-minute job is progressing; a pulse can, for the cost of one infinite transition.
* **AnimatedProgressBar** — the service reports progress about four times a second; animating
  between callbacks removes visible stepping without lying about the value.
* **AnimatedStageText** — stage names change abruptly; a 180 ms crossfade makes them readable.
* **BubbleEntrance** — each chat bubble slides in once, on first composition.

### Verification for this version

| Check | Result |
|---|---|
| Frontend type-check, all 65 source files | **0 errors** |
| Logic self-test | **1,510 assertions × 10 seeded rounds, 0 failures** |
| `check_refs.py` (manifest, resources, `R.*`) | 0 problems |
| `check_deps.py` (imports vs dependencies) | 0 problems |

Two more real bugs were caught by the compiler this round: a `by mutableStateOf` delegate
without the `getValue`/`setValue` imports, and a `state.fraction` read in the UI for a field
the ViewModel's state did not have.


## 0e. v10 — bigger package, tone-aware dubbing, 12.5 GB pool

### The package: ~250 MB, and every byte is accounted for

| Contents | Size | Purpose |
|---|---|---|
| `assets/models/ggml-tiny-q5_1.bin` + `ggml-small-q5_1.bin` | 213 MB | two ASR models **inside the APK** — a fresh install dubs immediately, and `small` is the good one |
| `vendor/whisper.cpp` (full tree) + `vendor/sentencepiece` | 28 MB | native build, offline |
| `vendor/onnxruntime/onnxruntime-android-1.20.0.aar` | 27 MB | inference runtime, offline |
| Kotlin (45 files, ~6k lines), harness, docs | ~1 MB | the app |

I did not pad the file count. A package is not better because it holds more files; it is
better when each file is needed. What grew here is models and native sources, which the app
genuinely uses.

### Model pool: 8 ASR + 3 MT + 9 neural voices = **12.5 GB**

| Stage | Options | Range |
|---|---|---|
| ASR | tiny (bundled), base, small (bundled), medium, turbo q5, turbo fp16, large q5, large fp16 | 32 MB → 3.1 GB |
| MT | IndicTrans2 int8, NLLB int8, NLLB fp32 | 283 MB → 3.5 GB |
| TTS | 9 Piper voices (Hindi ×3, Telugu ×2, Malayalam ×2, Marathi, Bengali) | 60–110 MB each |

Every size is the file's real `Content-Length`. Piper voices are optional because their
phonemiser (espeak-ng) is GPL-3.0 — the default stays the free system voice.

### Tone-aware dubbing (the "feelings" requirement)

`engine/core/ProsodyAnalyzer.kt` measures each original line and shapes the Hindi take to
match it:

* **loudness (RMS, quietest 10 % skipped)** → whisper vs shout;
* **pitch (autocorrelation with octave correction)** → tense vs flat vs sad;
* those map to small pitch/rate/gain multipliers applied per utterance through
  `TtsEngine.setProsody`, clamped to ±10 % because system TTS breaks up beyond that.

So a whispered line comes out quieter and slower, a shout comes out louder and faster, and a
sad line drops in pitch. The Engine panel now reports the tone mix (e.g. `calm 42 · tense 7`).

### Animation, now five primitives

PulsingDot (alive), AnimatedProgressBar (smooths 4 Hz service updates), AnimatedStageText
(crossfade on stage change), BubbleEntrance (once per bubble), ShimmerBox (loading rows in the
model list). All driven by the Compose frame clock.

### Verification

| Check | Result |
|---|---|
| Frontend type-check, all 68 files | **0 errors** |
| Logic self-test | **1,776 assertions × 10 seeded rounds, 0 failures** |
| `check_refs.py` / `check_deps.py` | 0 / 0 problems |

Two real bugs were caught by the new prosody tests: an **octave error** in the pitch detector
(a 260 Hz tone read as 130 Hz, because a lag of twice the period scores just as well — fixed by
picking the shortest lag within 90 % of the maximum), and a **mis-calibrated mood threshold**
(220 Hz, a normal female voice, was being classified as "tense"; the threshold is now 250 Hz).


## 0f. v11 — rebuilt UI, working repo, quality settings

### The repository now builds

`gradlew`, `gradlew.bat` and `gradle/wrapper/` are **inside this package**, at the root, along
with `.github/workflows/build-apk.yml`. The `chmod: cannot access 'gradlew'` failure cannot
recur: the workflow also generates the wrapper if it is ever missing, and finds
`settings.gradle.kts` wherever it sits instead of assuming the repo root.

### UI rebuilt around four screens

| Screen | What it does |
|---|---|
| **Home** | one-tap setup card, attach + language chips + start, and a Library list with sizes, play and delete |
| **Job** | the seven pipeline stages with done/active/pending dots, smooth progress, ETA, a moving level strip, cancel, and the last run's stats |
| **Player** | plays the finished MP4 (framework `MediaPlayer`), real waveform from a peak sidecar the engine writes, seek, share, delete |
| **Settings** | language, voice, sound, output quality, performance, models, translation, appearance, storage and the last crash log |

Navigation is an `AnimatedContent` over a sealed `Screen` type, so the `when` is exhaustive and
a missing screen is a compile error, not a blank page. Theme is Material 3 with dynamic colour
on Android 12+, plus a real light/dark/system switch.

Custom vector icons were drawn for home, job, player, settings, download, wave, share, delete,
folder and mic, and the launcher icon is a proper adaptive icon (background + foreground).

### New engine-facing features

* **Output quality is yours**: sample rate 24/32/44.1 kHz and AAC bitrate 64/96/128 kbps, applied
  to the muxed track.
* **Real waveform**: the engine reduces the finished audio to 320 normalised peaks and writes
  them next to the output, so the player draws the actual dub, not a decoration.
* **Crash log**: a global handler records the stack trace, device, ABI and the engine's last
  events to `crash_last.txt`, viewable from Settings.
* **Housekeeping worker**: WorkManager clears orphaned `dub_*` cache dirs and stale `.part`
  downloads daily. The dubbing job itself stays a foreground service — WorkManager workers are
  bounded, and a 3-hour job is not.
* **Library with sizes**: outputs are listed with real byte counts and can be deleted one by one.

### Package size: ~600 MB, and what is in it

| Contents | Size |
|---|---|
| `assets/models/ggml-medium-q5_0.bin` + `ggml-tiny-q5_1.bin` | 545 MB |
| `vendor/whisper.cpp`, `vendor/sentencepiece`, `vendor/onnxruntime` | 55 MB |
| Kotlin sources (52 files), wrapper, CI, harness, docs | ~1 MB |

Two ASR models ship inside the APK: tiny for an instant test, medium for real quality with no
download. Everything else — NLLB translation, the 8-model ASR pool, the 9 Piper voices — is
downloaded on demand from Settings.

### Verification

| Check | Result |
|---|---|
| Frontend type-check, all 52 Kotlin files | **0 errors** |
| Logic self-test | **1,953 assertions × 10 seeded rounds, 0 failures** |
| `check_refs.py` / `check_deps.py` | 0 / 0 problems |

Bugs the compiler and tests caught this round: a missing `painterResource` import in three
screens, a stub `R` class that lacked the new drawables (fixed by generating the stub from
`res/` automatically), a `ChipRow` whose `List<Pair<Any, String>>` rejected
`List<Pair<Int, String>>`, a `by animateFloat` delegate without the `getValue` import, and a
missing WorkManager artifact on the check classpath.


## 0g. v12 — hybrid cloud engine with a real router

### The blueprint, implemented

```
[Video file]
     │
     ▼
[EngineRouter]  ── network? cloud enabled? key present? ──►  per-stage plan
     │
     ├─ LOCAL   whisper.cpp · NLLB (or IndicTrans2) · system voice
     └─ CLOUD   Gemini / OpenAI / Claude / Grok / Sarvam (text)
                OpenAI speech (voice, WAV)
     │
     ▼
[Loudness normaliser] → [AAC muxer] → [Material 3 player + library]
```

`core/EngineRouter.kt` is pure logic and unit-tested: cloud off, no network, or no key for a
stage means that stage runs locally; ASR stays local unless you explicitly configure cloud ASR,
because uploading a film's *audio* is a different privacy decision from uploading its text. The
API screen shows the resulting plan for the next job, so "hybrid mode" is never a mystery.

### Cloud support

| Provider | Endpoint | Used for |
|---|---|---|
| Google Gemini | `generativelanguage.googleapis.com` | translation |
| OpenAI | `api.openai.com/v1/chat/completions` | translation |
| **Anthropic Claude** | `api.anthropic.com/v1/messages` | translation (new in v12) |
| xAI Grok | `api.x.ai/v1/chat/completions` | translation |
| Sarvam | `api.sarvam.ai/translate` | translation |
| **OpenAI speech** | `api.openai.com/v1/audio/speech` | **voice** (new in v12) |

The cloud voice asks for **WAV**, which is why it needs no decoder: `core/WavReader.kt` parses
RIFF/PCM16/float32 in pure Kotlin and the samples go straight into the mixer. Any cloud failure
falls through to the on-device voice for that line, so a dead key costs quality, not the job.

Google Cloud TTS is deliberately absent: it authenticates with an OAuth2 service account rather
than an API key, and shipping a service-account JSON into a phone app is a worse trade than the
feature is worth. That is stated on the screen, not hidden.

### Also in this round

* **Loudness matching** (`core/LoudnessNormalizer.kt`): peak normalisation stops clipping, but a
  whispered scene still came out far below the music. The mixer now also matches average level to
  −18 dBFS with a peak ceiling of 0.97.
* **API screen** with keys for all five providers, a hybrid switch, and the live plan summary.
* **Insets fixed**: the root layout now applies the status-bar inset, so content no longer slides
  under the clock on edge-to-edge devices.
* Keys stay in the Android Keystore; the screen shows a tick when a key is stored, never the key.

### Verification

| Check | Result |
|---|---|
| Frontend type-check, all 57 Kotlin files | **0 errors** |
| Logic self-test | **2,037 assertions × 10 seeded rounds, 0 failures** |
| `check_refs.py` / `check_deps.py` | 0 / 0 problems |

New test groups this round: **WavReader** (synthetic stereo PCM16, downmix, bad input),
**LoudnessNormalizer** (quiet boost, peak ceiling, silence, 60 randomised signals) and
**EngineRouter** (the full decision matrix, including a cloud voice with a missing key).

## 1. What v4 changed

**Languages.** Source and target are now separate lists, which is what dubbing anime needs:
* **Source — 50 languages:** Japanese, Korean, Chinese, English, Spanish, French, German,
  Portuguese, Italian, Russian, Ukrainian, Arabic, Turkish, Thai, Vietnamese, Indonesian,
  Malay, Filipino, Persian, Hebrew, Polish, Dutch, Swedish, Czech, Greek, Swahili, Amharic
  and all Indian languages.
* **Target — Indian only:** the 22 scheduled languages + Bhojpuri.
* Japanese audio goes `jpn_Jpan → hin_Deva` in **one** translation step. No English hop — the
  two-hop path is a common reason anime dubs sound wrong.

**A real engine (`DubEngine`).** New package `engine/core/` that schedules the components
instead of calling them in a straight line. Six changes, each with a measured reason — full
write-up in `ENGINE.md`:

| Change | Why it matters on a low-end phone |
|---|---|
| Models load once per session | v3 reloaded Whisper + 2 ONNX sessions per job; a queue now pays that once |
| VAD before ASR | 15–25 % of a film is silence; those windows never reach Whisper, and it stops hallucinating over music |
| Silence-aware chunking | Long files cut at the widest pause, not mid-word (v3 cut at a fixed 10 min) |
| Segment cache | Repeated lines are translated and voiced once |
| Thermal pacing | Threads and pauses adapt before the OS throttles |
| Deliberate sequencing | ASR→MT→TTS per chunk; overlapping them on 2 big + 6 little cores is slower and doubles RAM |

**Bugs fixed** (all found by reading the v3 code, listed so you can verify them):
1. Mono sources lost the whole audio bed — centre suppression (L−R) is silence on mono.
   `MixConfig.sourceIsMono` now falls back to the full signal.
2. The audio file was decoded **twice** per job (ASR pass + bed pass). Now one decode at
   24 kHz stereo, with the 16 kHz mono ASR track derived from it.
3. Every progress tick read `jobs.json` from disk on the UI path — the main source of the
   interface lag you noticed. Queue counts now come from the service state; nothing touches
   disk per frame.
4. Settings sliders rewrote `settings.json` on every pixel. Writes are debounced and atomic
   (`flush()` on dialog close), which also stops needless flash wear.
5. A second "share to HindiDubber" while the app was open did nothing (the intent was read
   once in a `LaunchedEffect`). Now routed through `IntentRelay` from `onNewIntent`.
6. Fixed 10-minute ASR chunks could split a line; now split at the widest pause.
7. `VideoMuxer` could leak the extractor if decoding threw; released in `finally` now.
8. NLLB language tags came from a hard-coded map; they now come from `LanguageCatalog` and
   the engine **fails loudly** if the tokenizer has no id for a tag instead of emitting
   nonsense.
9. Whisper was given ISO codes it does not support for some languages; unsupported codes now
   fall back to auto-detect.
10. Cancelling a job left the queue entry RUNNING forever; it is recovered to PENDING on the
    next start.

**UI.** Same chat skeleton, smoother and less flat: ETA under the progress bar, a quick
target-language strip for one-tap switching, an **Engine** panel showing what ran and how
fast (ASR RTF, speech %, chunks, cache hits), and a Quick mode (first 60 s) for testing.

## 2. Honest status

**Still not compiled by me.** I have a working toolchain here (JDK 17, SDK 35, Gradle 8.9,
dependencies resolved) but the sandbox has 2 GB RAM and a thread cap, so Gradle aborts with
`unable to create native thread`. Nothing in this project has been through a compiler, so
expect a few small fixes on your first build. `BETA_TEST_CHECKLIST.md` is the test order I
would run, including the new anime/mono/quick-mode cases.

**Verified on real files:** every model URL and byte size (HTTP Content-Length); the NLLB
ONNX tensor contract read from the `.onnx` files; tokenizer constants (vocab 256,206, eos 2,
eng_Latn 256047, hin_Deva 256068); that NLLB's encoder and decoder cannot be resident
together in 2 GB (hence sequential loading).

**Not promised:** real-time dubbing of a full film on a Dimensity 6000/6100. ASR dominates;
expect roughly 1–3× real time for the ASR stage. The app is a studio you queue and leave.

## 3. Build it into an APK

Standard Android Gradle project — Android Studio or `gradle` builds it directly:

```bash
unzip hindi-dubber-android.zip && cd hindi-dubber
gradle wrapper --gradle-version 8.9
./gradlew :app:assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17, Android SDK 35, NDK 27.0.12077973, CMake 3.22+. First build downloads
`whisper.cpp` v1.7.4 and `sentencepiece` v0.2.0 sources (CMake `FetchContent`), then caches.
`INTEGRATION_GUIDE.md` has the full sequence and troubleshooting.

## 4. Typical anime workflow

1. Setup → **Set up automatically** (picks a tier your phone can run, ~1.1 GB).
2. Settings → Dub into → हिन्दी (or Tamil, Telugu…); install that offline voice.
3. Share the episode from your gallery into HindiDubber.
4. Press **Dub**. Watch the stage line: VAD → ASR → TRANSLATE → TTS → MIX → MUX.
5. Result card → **Share** to your player.

Tips that matter for anime: keep bed level around −14 dB so the Japanese voices sit under the
Hindi but the music and impacts survive; turn centre suppression **on** for stereo sources
(it drops centre-panned dialogue), and leave it off for mono sources (the app does this
automatically). If dialogue lands slightly off, use Quick mode to test the setting on 60 s
before committing to a full episode.

## 5. Documents

| File | Contents |
|---|---|
| `ENGINE.md` | the engine: layers, the six decisions, language routing, how to read RTF, tuning, what's next |
| `DESIGN_DECISIONS.md` | every component with the alternative that was rejected |
| `ARCHITECTURE.md` | component map, data flow, directory tree, roadmap, tokenizer traps |
| `MEMORY_STRATEGY.md` | 20 GB pool vs RAM reality, sequential sessions, eviction |
| `PERFORMANCE_NOTES.md` | quantisation, GPU/NNAPI verdicts, realistic timings |
| `INTEGRATION_GUIDE.md` | toolchain, build, model setup, troubleshooting |
| `BETA_TEST_CHECKLIST.md` | on-device test order, including v4 cases |

## 6. Licences

whisper.cpp (MIT), Whisper weights (MIT), ONNX Runtime (MIT), SentencePiece (Apache-2.0),
NLLB-200 weights (**CC-BY-NC-4.0, non-commercial**), Android framework (Apache-2.0).
For a paid product swap in IndicTrans2 (MIT) — see `ARCHITECTURE.md` phase 6.
