# Integration & build guide

## 0. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Android Studio | Ladybug (2024.2)+ | or SDK command-line tools |
| JDK | 17 | required by AGP 8.x |
| Android SDK platform | 35 | `compileSdk = 35` |
| NDK | 27.0.12077973 | pinned in `app/build.gradle.kts` |
| CMake | 3.22.1+ | from SDK Manager |
| Device | arm64-v8a, Android 8.0+ | ABI filter is arm64 only by default |

## 1. Unpack and create the wrapper

```bash
unzip hindi-dubber-android.zip
cd hindi-dubber
gradle wrapper --gradle-version 8.9      # the wrapper JAR is binary, so it is not shipped
```

## 2. First sync (native dependencies)

`whisper.cpp` (v1.7.4) and `sentencepiece` (v0.2.0) are **vendored** in `vendor/` and
`app/src/main/cpp/CMakeLists.txt` points at them, so the native build needs no network. If the
folders are ever missing, CMake falls back to `FetchContent` and clones them instead.

For a pinned/offline build:

```bash
git clone --depth 1 --branch v1.7.4 https://github.com/ggerganov/whisper.cpp.git vendor/whisper.cpp
git clone --depth 1 --branch v0.2.0   https://github.com/google/sentencepiece.git vendor/sentencepiece
```

and add to the `cmake { arguments += ... }` block in `app/build.gradle.kts`:

```
"-DWHISPER_DIR=${rootDir}/vendor/whisper.cpp",
"-DSENTENCEPIECE_DIR=${rootDir}/vendor/sentencepiece"
```

Known friction: `-DGGML_NATIVE=OFF` is required when cross-compiling (already set);
`libc++_shared.so` duplication is handled by `pickFirsts`; sentencepiece builds its bundled
protobuf-lite, keep `SPM_ENABLE_SHARED=OFF`.

## 2b. Bundled assets and vendored AAR

* `app/src/main/assets/models/` holds **two** bundled ASR models: `ggml-tiny-q5_1.bin` (31 MB)
  and `ggml-small-q5_1.bin` (182 MB). It is copied
  into app storage on first run, so a fresh install can dub a clip with no download. If you
  want a smaller APK, delete this file — the app then simply asks you to download a model.
* `vendor/onnxruntime/onnxruntime-android-1.20.0.aar` is resolved through `flatDir` in
  `settings.gradle.kts`, so ONNX Runtime needs no network either. To resolve it from Maven
  instead, change the dependency in `app/build.gradle.kts` back to the coordinate without
  `@aar`.

## 3. Build

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release signing: create `keystore.properties` (never commit it) and uncomment the
`signingConfigs` block in `app/build.gradle.kts`.

## 4. Models — all download from inside the app

Open **Models** in the app and press Download on:

1. one ASR group — `Whisper small q5_1` (181 MB) unless the phone has 4 GB RAM, then
   `Whisper base q5_1` (57 MB);
2. the MT group — `NLLB-200 distilled 600M (INT8)` (912 MB across 4 files).

Downloads are resumable and verified by byte count; they land in
`/data/data/ai.hindidub/files/models/`. If you prefer to pre-load them (useful when the
phone's Wi-Fi is slow), download the same URLs on a PC with
`scripts/fetch_models.sh` for Whisper, or straight from
`https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main/...` for NLLB, then use
**Import files** in the Models dialog. The importer matches file names to the catalog, so
keep them as `ggml-*.bin`, `encoder.onnx`, `decoder.onnx`, `sentencepiece.bpe.model`,
`tokenizer.json`.

## 5. Offline Hindi voice

Settings → System → Languages & input → Text-to-speech output → Google TTS → Install voice
data → हिन्दी. Confirm with "Listen to an example". Without it the TTS stage stops with a
clear error message.

## 6. First run checklist

1. Grant the notification permission (needed by the foreground service on Android 13+).
2. Download the two model groups; the header line shows quota and free space.
3. Pick a 30-second clip, press **Dub**. Watch the chat message progress through
   AUDIO → ASR → TRANSLATE → TTS → MIX → MUX.
4. Only then try a full episode. Disable battery optimisation for the app first.

## 7. Optional: sanity-check the MT model on a PC

`tools/reference_mt.py` reproduces the app's translation path in Python (same tensor names,
same tokenisation, same language ids). Run it before blaming the phone:

```bash
pip install onnxruntime sentencepiece numpy
python tools/reference_mt.py --model-dir ~/models/nllb --text "Where are you going?"
```

It needs ~2 GB of free RAM and prints the encoder/decoder tensor contract it found.

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `UnsatisfiedLinkError: dlopen failed ... whisperjni` | Native build skipped — run `./gradlew :app:externalNativeBuildDebug` and read the CMake log (usually a missing NDK version) |
| `whisper_init_from_file_with_params returned null` | Corrupt or wrong ggml file; re-download |
| ASR returns nothing | Audio codec the phone cannot decode (some MKV/AC3). Test with an MP4/H.264+AAC file |
| `Missing .../encoder.onnx` | MT group not fully downloaded — the pool requires all four files |
| MT output is nonsense or repeated syllables | Tokenizer/model mismatch — see ARCHITECTURE.md §6; check logcat tag `MTEngine` for the ids it loaded |
| OOM (`bad_alloc`) during TRANSLATE | Phone is short on RAM: close other apps, or use a shorter clip; the engine already loads sessions sequentially |
| `Hindi offline voice not installed` | Step 5 above |
| Audio drifts out of sync | Segments longer than the ASR window get time-stretched; check `TimeStretcher.MAX_STRETCH` and prefer natural pause segmentation |
| Job dies with the screen off | Battery optimisation, or the service was not started from the app button |
