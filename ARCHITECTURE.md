# Architecture

## 1. Component map

```
                     ┌─────────────────────────── ChatScreen (Compose) ──────────────────────────┐
                     │  message thread · config chips · Models dialog · share sheet entry         │
                     └───────────────────────────────┬───────────────────────────────────────────┘
                                                     │ startForegroundService(intent + config)
                                                     ▼
                                   DubbingService (foreground, wakelock, notification)
                                                     │
                                                     ▼
   EngineManager ── ModelPool (quota, LRU, real sizes)  ── ModelRepository (resume, multi-file)
        │
        ▼
   DubbingPipeline.run(JobConfig)
        │
        ├─ 1  AudioExtractor.extractMono16k()      MediaExtractor + MediaCodec → 16 kHz float
        │
        ├─ 2  AsrProcessor (whisper.cpp via JNI)   chunked 10-min blocks → timed segments
        │
        ├─ 3  TranslationEngine (NLLB ONNX)        encoder pass → free → decoder pass
        │      └─ StyleGlossary                    user's tone/slang replacements
        │
        ├─ 4  SystemTtsProcessor + VoiceProfiles   per-segment WAV → 24 kHz PCM
        │      └─ TimeStretcher                    fit clip into its ASR window
        │
        ├─ 5  AudioExtractor.extractStereoBed()    24 kHz L/R of the original track
        │      └─ AudioMixer                       centre suppression + ducking + mix
        │
        └─ 6  VideoMuxer                           AAC encode + MediaMuxer, video copied
                                                     │
                                                     ▼
                                    /sdcard/Android/data/ai.hindidub/files/Movies/dubbed/*.mp4
```

## 2. Directory structure

```
hindi-dubber/
├── README.md, ARCHITECTURE.md, MEMORY_STRATEGY.md, PERFORMANCE_NOTES.md, INTEGRATION_GUIDE.md
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── scripts/fetch_models.sh
├── tools/reference_mt.py
└── app/
    ├── build.gradle.kts, proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── cpp/
        │   ├── CMakeLists.txt          FetchContent: whisper.cpp v1.7.4, sentencepiece v0.2.0
        │   ├── whisper_jni.cpp         init / transcribe / free, returns "t0\tt1\ttext" lines
        │   └── sp_jni.cpp              SentencePiece load / encode / decode / free
        ├── java/ai/hindidub/
        │   ├── MainActivity.kt
        │   ├── engine/
        │   │   ├── WhisperBridge.kt            JNI surface
        │   │   ├── SentencePieceTokenizer.kt   JNI surface
        │   │   ├── AudioExtractor.kt           mono 16k + stereo 24k bed
        │   │   ├── AudioUtils.kt               resample, WAV read/write, PCM helpers
        │   │   ├── AsrProcessor.kt             whisper.cpp, chunked transcription
        │   │   ├── TranslationEngine.kt        NLLB INT8 ONNX, sequential sessions
        │   │   ├── StyleGlossary.kt            user-editable tone replacements
        │   │   ├── VoiceProfiles.kt            pitch/rate presets
        │   │   ├── TtsProcessor.kt             system TTS → PCM
        │   │   ├── TimeStretcher.kt            fit clip to segment window
        │   │   ├── AudioMixer.kt               ducking + centre suppression
        │   │   ├── VideoMuxer.kt               AAC encode + MediaMuxer
        │   │   ├── DubbingPipeline.kt          orchestration + progress
        │   │   └── EngineManager.kt            settings, model wiring
        │   ├── model/
        │   │   ├── ModelCatalog.kt             real URLs + real byte sizes
        │   │   ├── ModelPool.kt                quota, LRU eviction, disk summary
        │   │   └── ModelRepository.kt          resumable multi-file download, import
        │   ├── service/DubbingService.kt
        │   ├── ui/  ChatScreen.kt, DubbingViewModel.kt, Theme.kt
        │   └── util/DeviceProfiler.kt          SoC, cores, RAM, thread suggestion
        └── res/  drawable/, values/, xml/
```

## 3. Why these components

* **whisper.cpp, not a Python-on-Android stack.** A single C++ dependency, no Python
  runtime, works with q5_1 ggml files, and its 30-second internal windowing keeps RAM flat.
* **Android MediaExtractor/MediaCodec/MediaMuxer, not FFmpeg.** ffmpeg-kit was retired in
  2025; a self-built FFmpeg adds ~20–30 MB per ABI and re-implements what the phone's
  hardware codecs already do. We copy the video stream, never re-encode it.
* **NLLB INT8 ONNX, not a quantised chat LLM.** A translation model has no chat alignment
  layer, so it does not refuse or soften dialogue, and it is far cheaper than a 3–7 B LLM.
* **System TTS, not Piper/XTTS, as default.** Piper needs espeak-ng, which is GPL-3.0
  (copyleft over your whole app); XTTS-v2 needs >1 GB RAM and is slow on this SoC. The
  system voice is free, offline, and already installed.
* **Foley by retention, not regeneration.** See section 5.

## 4. Phased roadmap

| Phase | Content | State |
|---|---|---|
| 1 | End-to-end pipeline: ASR → MT → TTS → mux, foreground service, model pool | **implemented here** |
| 2 | Foley-preserving ducking mix, centre suppression, voice profiles, chat UI, share entry | **implemented here** |
| 3 | KV-cache decoder (the `decoder_model_merged.onnx` export) to cut MT time ~2–4× | next; needs 4×N KV tensors managed in `TranslationEngine` |
| 4 | Word-level timestamps (`whisper_full` token timings) + smarter segment splitting for tighter sync | next |
| 5 | Voice cloning: wrap a VITS/XTTS ONNX export behind `TtsEngine`; speaker embedding from the source track | needs >1 GB RAM and a quality trade-off |
| 6 | IndicTrans2 INT8 path (MIT licence) with a proper Unigram tokenizer + IndicProcessor pre/post-processing | see "Tokenizer traps" |
| 7 | Real foley separation (Demucs) — realistically only for short clips, or off-device | heavy; measure before committing |
| 8 | Lip-sync (Wav2Lip-class) | not viable on this SoC in real time |

## 5. Foley: what is actually achievable

The brief asks to "detect and replicate" breaths, gasps, doors and ambience. Two different
things are being conflated:

1. **Retaining** the original sound under the dub — cheap, exact, and what the industry
   does. Implemented: `AudioMixer` keeps the original 24 kHz stereo bed, attenuates it by a
   configurable amount, optionally subtracts the mid channel (L−R) so centre-panned dialogue
   drops while stereo music/ambience survives, then sums with the dubbed voice.
2. **Re-synthesising** non-verbal sound with a separation model (Demucs/Spleeter). Demucs
   is ~300 MB and runs several times slower than real time on desktop CPUs; on a Dimensity
   6000 it would dominate the entire job. Not implemented; the interface is there if you
   want to add it for short clips.

## 6. Tokenizer traps (verified the hard way)

Two widely-used community ONNX bundles ship tokenizers that do not match their models.
Recorded so you do not lose a day to it:

* `Xenova/opus-mt-en-hi`: `source.spm`/`target.spm` contain 32,000 pieces, but the model's
  vocabulary is 61,950. Feeding SentencePiece ids to that decoder produces degenerate
  output (repeated syllables). The correct tokenizer is the repo's `tokenizer.json` (a
  BPE/Unigram JSON), which needs a tokenizer implementation, not SentencePiece.
* `hari31416/indictrans2-en-indic-dist-200M-ONNX-int8`: `model.SRC` has 32,000 pieces while
  `src_dict_size` is 32,322 — the extra 322 are **added tokens** (the `<2lang>` tags) that a
  raw SentencePiece encoder cannot emit. You also need `IndicProcessor` from
  `IndicTransToolkit` for pre/post-processing. That is why the default path is NLLB, whose
  language tags are plain integer ids you can prepend (256047 / 256068).

## 7. Threading and cancellation

All pipeline work runs on `Dispatchers.Default` inside the service's scope; progress is
published through a `StateFlow` the UI collects. Cancellation is cooperative: every segment
loop calls `coroutineContext.ensureActive()`, so killing the job stops within one segment.
The service holds a partial wakelock for up to 6 hours and releases it in `finally`.
