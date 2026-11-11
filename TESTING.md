# Testing — what was actually run, and what was not

This project has been tested in two different ways, and the difference matters.

## 1. Real, automated, repeatable: the engine's logic layer

`tools/jvmtest/` contains a self-test that compiles the pure-logic half of the engine with a
plain Kotlin compiler — **no Android SDK, no emulator** — and runs it on a JVM:

```bash
JAVA_HOME=/path/to/jdk KOTLIN_HOME=/path/to/kotlinc bash tools/jvmtest/run_selftest.sh
# or with different random data:
SEED=7 java -jar build/selftest/selftest.jar
```

Covered (17 groups, **1,502 assertions per run**):

| Group | What it proves |
|---|---|
| `FloatSink` | primitive append buffer grows correctly; downmix maths |
| `AudioUtils.resampleLinear` | length maths for every rate pair; DC preserved; 200 random cases |
| `TimeStretcher` | output is exactly the target length for 200 random clip/target pairs; no NaN; fades |
| `Vad.spans` | silence → no spans; two bursts → two spans; short pauses merge; 120 random signals stay non-overlapping |
| `Vad thresholds` | steady DC not fragmented; single-sample input safe; span count monotonic in amplitude |
| `PcmStore` | PCM16 round-trip precision; sparse writes leave silence; 60 random write patterns |
| `PcmStore edge cases` | writing past EOF extends the file with silence; ±1.0 clamping; zero-length write |
| `AudioMixer` | dB maths; centre suppression removes centre content; mono source keeps its bed; null bed = passthrough |
| `AudioMixer stereo detail` | hard-panned content survives at half level; peak normalisation lands on 0.98 |
| `SegmentCache` | LRU eviction; hit counters; long clips are not cached |
| `LanguageCatalog` | 50 sources, 23 Indic targets, unique codes/tags, whisper fallback, shared-voice detection |
| `ModelCatalog` | unique ids and paths, positive sizes, totals add up, valid tiers |
| `JsonArrays` | fences, prose, escapes, unicode, nested objects, trailing commas, truncated input |
| `JsonArrays robustness` | objects skipped, empty strings kept, unterminated input drops the fragment |
| `Diag` | ring buffer bounded at 400 events; injected clock; dump limit |
| `Diag concurrency` | 4 threads × 500 writes stay bounded |

**Result: 20 consecutive rounds with different random seeds — 20/20 passed, 30,040 assertions,
0 failures.** Three real bugs were found and fixed this way:

1. `JsonArrays` returned strings from inside nested objects (`{"a":1}` leaked `a`).
2. `JsonArrays` accepted a truncated string, which could inject half a line into a dub.
3. `AudioMixer` soft-clipped per sample with `tanh`, which compressed loud scenes unevenly;
   replaced with a measured two-pass peak normalisation to 0.98 (−0.2 dB) and a reported gain.

## 2. Not tested: anything that needs a phone

There is no Android SDK or emulator in my environment (2 GB RAM, hard thread cap — Gradle
aborts with `unable to create native thread`), so the following are **unverified**:

* compilation of the Android/Compose layer;
* MediaCodec decoding of real containers (MP4, MKV, AC3 …);
* the JNI bridges into whisper.cpp and sentencepiece;
* ONNX Runtime sessions with the real NLLB files;
* the system TTS path and voice availability per language;
* muxing and playback of the produced MP4;
* memory on a real 4 GB device.

`BETA_TEST_CHECKLIST.md` is the on-device plan, in the order I would run it, with the exact
logcat tags and the `dumpsys meminfo` check for peak RAM.

## 3. Why not "20–50 full app builds"

Compiling and installing the APK 20–50 times is exactly what the checklist is for, and it
needs a device. What I could do repeatedly — and did — is run the logic suite 20 times with
fresh random data, which is where the arithmetic bugs actually live. Claiming otherwise would
be inventing results.


---

# v8 — structural checks

Added two checks that need no toolchain (`tools/android-check/verify.sh`):

| Check | Scope | Result |
|---|---|---|
| `check_refs.py` | manifest classes, `@resource` references, `R.*` usage | 0 problems |
| `check_deps.py` | every third-party `import` covered by a declared Gradle dependency | 0 problems |

The Kotlin sources in this package are **byte-identical** to the v7 tree that was compiled
against `android-35/android.jar` (verified by diff), so the earlier result carries over: 0
frontend type errors across all 64 files, 110 classes of bytecode for the non-UI layers, and
1,502 assertions × 20 rounds on the logic suite. What changed in v8 is `CMakeLists.txt`
(vendored paths), `vendor/`, the harness and the docs.


---

# v9 — expanded pool, bundled model, animation layer

| Check | Scope | Result |
|---|---|---|
| Frontend type-check (all 65 files incl. the new animation layer and the 10-entry catalogue) | types, imports, arity | **0 errors** |
| `tools/jvmtest` logic suite | VAD, PCM store, mixer, cache, resampler, catalogues, JSON, diagnostics | **1,510 assertions × 10 seeded rounds, 0 failures** |
| `check_refs.py` | manifest classes, resources, `R.*` | 0 problems |
| `check_deps.py` | third-party imports vs declared dependencies | 0 problems |

New assertions added for this version: the pool is multi-GB, ASR/MT counts, the bundled model
is flagged as bundled, and the RAM→tier selection returns the expected model at 2/4/8 GB.

Bugs found and fixed by the compiler in this round:

1. `var visible by remember { mutableStateOf(false) }` without `getValue`/`setValue` imports —
   the delegate does not compile.
2. `state.fraction` in the UI for a field `DubbingViewModel.State` did not declare; the field
   was added and is now fed from the service.

Unchanged gap: nothing here runs on a device. MediaCodec, JNI, ONNX sessions, the TTS voice
and real memory behaviour still need `BETA_TEST_CHECKLIST.md` run on hardware.


---

# v10 — prosody tests, and two bugs they caught

New group: `ProsodyAnalyzer` (whisper/shout/calm/tense/sad classification, pitch estimation,
sanity bounds on the multipliers, 120 randomised clips).

| Check | Result |
|---|---|
| Logic self-test | 1,776 assertions × 10 seeded rounds — **0 failures** |
| Frontend type-check, all 68 files | 0 errors |
| `check_refs.py` / `check_deps.py` | 0 / 0 problems |

Bugs found and fixed this round:

1. **Octave error in pitch detection.** Autocorrelation scores a lag of twice the true period
   just as highly, so a 260 Hz tone was reported as 130 Hz and every "tense" line would have
   been read as calm. Fixed by taking the shortest lag within 90 % of the maximum score.
2. **Mood threshold mis-calibration.** 220 Hz — an ordinary female speaking pitch — was above
   the original 215 Hz "tense" threshold, so normal dialogue was being pitched up. Threshold
   moved to 250 Hz.
3. Generated `ModelCatalog` emitted `59,707,625L`, which is not a valid Kotlin literal; caught
   by the compile step and regenerated without separators.
4. The self-test's pure-source list did not include the new `ProsodyAnalyzer.kt`, so the suite
   silently failed to compile — fixed, and it is now part of the list.


---

# v11 — UI rebuild, wrapper, new logic

| Check | Result |
|---|---|
| Frontend type-check, 52 files (four new screens, WorkManager, crash logger) | 0 errors |
| Logic self-test | 1,953 assertions × 10 seeded rounds, 0 failures |
| `check_refs.py` (manifest, resources, `R.*`) | 0 problems |
| `check_deps.py` (imports vs dependencies) | 0 problems |

New test groups: **WaveformPeaks** (bucket count, normalisation, empty input, 80 randomised
signals) and **StorageManager** (human-readable sizes, MP4-only listing, delete/deleteAll).

Bugs found this round, all by the compiler or the suite:

1. `painterResource` used in three screens without its import.
2. The hand-written stub `R` class did not contain the new drawables — replaced by
   `tools/android-check/make_r_stub.py`, which generates the stub from `res/` (20 resources).
3. `ChipRow(options: List<Pair<Any, String>>)` rejected `List<Pair<Int, String>>`; the parameter
   is now `List<Pair<*, String>>`.
4. `by transition.animateFloat(...)` needed `import androidx.compose.runtime.getValue`.
5. `androidx.work` was missing from the check classpath, so the scheduler and worker could not
   be type-checked; the artifacts are now fetched into the harness.
6. `mkcp.py` preferred the KMP *stub* jar over the real `-jvm` one at equal versions, which made
   every coroutines import unresolved — it now prefers the larger file at equal version.


---

# v12 — hybrid routing, cloud voice, loudness

| Check | Result |
|---|---|
| Frontend type-check, 57 files | 0 errors |
| Logic self-test | 2,037 assertions × 10 seeded rounds, 0 failures |
| `check_refs.py` / `check_deps.py` | 0 / 0 problems |

New groups: `WavReader`, `LoudnessNormalizer`, `EngineRouter`.

What the router tests actually pin down (each is a real behaviour, not a tautology):

* cloud off → every stage local;
* no network → every stage local, even with keys;
* cloud on + key → translation goes cloud, ASR stays local, voice stays local;
* cloud voice honoured only when that provider has a key;
* empty provider list → all local.

Bug found by the compiler this round: `Diag` used in the engine without its import — the build
would have failed on the new plan trace line.
