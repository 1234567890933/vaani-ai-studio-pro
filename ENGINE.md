# DubEngine — design, memory model and optimisations

The engine is the part of this app that is written here rather than borrowed. Whisper,
ONNX Runtime and Android's TTS are components; `engine/core/DubEngine.kt` decides what runs
when, what stays in memory, what is skipped, and how hard the CPU is pushed.

## 1. The constraint that shapes everything

A 4 GB phone running the 1 GB translation model has roughly 1.5 GB of headroom. Audio is the
easy thing to get wrong, because it grows with video length:

| Item | 24-minute episode | 2-hour film |
|---|---|---|
| Stereo bed, 24 kHz, FloatArray | 276 MB | 1.4 GB |
| Mono ASR track, 16 kHz, FloatArray | 92 MB | 460 MB |
| Voice timeline, 24 kHz, FloatArray | 138 MB | 690 MB |
| **v4 peak (all at once)** | **~500 MB** | **~2.5 GB → OOM** |

v4 held all three, and the decoder collected samples into `ArrayList<Float>` — every sample a
boxed object, so the real figure was worse. That is the "engine not properly optimised"
problem, stated in numbers.

## 2. v5 memory model — bounded, disk-backed, windowed

```
for each 4-minute window of the file:
    decode that window to 16 kHz mono            (≈15 MB, freed immediately after)
        → VAD → Whisper → translate → TTS
        → write each clip into the voice timeline (PCM16 file, sparse writes)

then:
    original bed → PCM16 file (streamed window by window)
    mix voice + bed block-by-block (24 000 frames per block)
    AAC-encode from file, mux

peak RAM = one window + models, independent of film length
```

| Component | What it does | Why it is not a FloatArray |
|---|---|---|
| `audio/FloatSink` | growable primitive buffer | appends without boxing; a 24-min stereo track was ~69 M boxed Floats in v4 |
| `audio/PcmStore` | disk PCM16 with frame addressing + sparse writes | the timeline writes a clip at its timestamp and leaves silence elsewhere; 69 MB on disk instead of 138 MB in RAM |
| `audio/MediaDecoder` | decodes one time window, sink-based | peak memory is the window, not the file |
| `AudioMixer.streamMix` | reads voice+bed blocks, writes mixed blocks | mixing a 2-hour film now uses a few hundred KB |
| `VideoMuxer.encodeToSpool` | AAC-encodes to a temp file with a small index | MediaMuxer needs the audio format before `start()`, and holding every encoded sample is unnecessary |

Measured expectations (not device benchmarks — the arithmetic above): a 24-minute episode
now peaks around **350–450 MB** with the small model tier, versus ~1.3 GB before. That is the
difference between "runs on a 4 GB phone" and "dies on a 4 GB phone".

## 3. The optimisations, in order of how much they matter

1. **Windowed decode + disk-backed audio** — makes memory independent of duration (§2).
2. **VAD before ASR** — a 30 ms RMS gate with hysteresis; only speech windows reach Whisper.
   Anime/film tracks are 15–25 % silence, so that fraction of ASR time disappears, and
   Whisper stops inventing subtitle text over music (a quality win, not just speed).
3. **One model load per session** — Whisper + two ONNX sessions stay resident for the whole
   queue; v3 reloaded them per job.
4. **Dedupe** — `SegmentCache` keys translations and voiced clips by text; catchphrases and
   recaps are processed once. Hit counts are shown in the Engine panel.
5. **Sparse timeline writes** — a clip is written at its timestamp with `RandomAccessFile`;
   no need to build, pad and copy a whole-film array.
6. **Thermal pacing** — `ResourceGovernor` is consulted between segments; the engine sheds a
   thread and pauses before the OS throttles.
7. **Silence-only windows are skipped entirely** — a window with <500 ms of speech costs a
   decode and nothing else.
8. **Sample-accurate window starts** — `seekTo()` lands on a sync frame before the request;
   the decoder drops the pre-roll so segment timestamps do not drift window to window.

**Deliberately rejected:** running ASR, MT and TTS in parallel threads. On 2×A76 + 6×A55 they
compete for the same cores and memory bandwidth, and peak RAM doubles. Chunk-sequential keeps
caches warm and memory flat. This is written down so nobody "optimises" it back.

## 4. Languages (Japanese anime → Hindi)

`core/LanguageCatalog.kt` keeps sources and targets apart:

* **Sources — 50 languages:** Japanese, Korean, Chinese, English, Spanish, French, German,
  Portuguese, Italian, Russian, Ukrainian, Arabic, Turkish, Thai, Vietnamese, Indonesian,
  Malay, Filipino, Persian, Hebrew, Polish, Dutch, Swedish, Czech, Greek, Swahili, Amharic and
  every Indian language.
* **Targets — Indian only:** the 22 scheduled languages + Bhojpuri.

Each entry carries the whisper code, the NLLB tag and the TTS locale. Japanese goes
`jpn_Jpan → hin_Deva` in **one** step; there is no English pivot, which is the usual reason
anime dubs sound wrong.

## 5. Reading the numbers

The Engine panel reports, per run: ASR RTF (ASR time ÷ speech time — below 1.0 is faster than
real time), speech %, windows, segments, MT and TTS time, cache hits, peak window size.

If RTF is above ~3, drop to the `asr-base` tier. If peak window MB is above ~25, the file has
an unusual sample rate and the window is being resampled up — use Quick mode to confirm
before blaming the phone.

## 6. Still on the table

* **KV-cache decoder** for NLLB (`decoder_model_merged.onnx`) — the no-past export re-runs the
  decoder over the whole prefix per token. Biggest remaining MT win (2–4×).
* **Word-level timestamps** to split long segments at clause boundaries instead of stretching.
* **Piper/XTTS voice** behind `TtsEngine` for a less synthetic timbre (GPL/RAM caveats in
  `DESIGN_DECISIONS.md`).
* **Bed capture during the ASR pass** — currently the bed is a second decode pass (~1–2 % of
  total time). Capturing it while decoding for ASR would remove that pass at the cost of more
  disk writes per window.


---

# Prosody: making the dub follow the performance

`engine/core/ProsodyAnalyzer.kt` runs per segment, on the *original* audio slice for that line,
and returns a mood plus three multipliers that are handed to the TTS engine before that one
utterance is synthesised.

| Measurement | How | What it separates |
|---|---|---|
| Loudness | RMS over 256-sample frames, quietest 10 % dropped | whisper from shout |
| Pitch | normalised autocorrelation, octave-corrected | tense/excited from flat/sad |
| Mood | thresholds on the two above | CALM / TENSE / SHOUT / WHISPER / SAD |

Multipliers are deliberately small (pitch 0.93–1.06, rate 0.93–1.05, gain −1.5 to +1.5 dB).
The goal is to follow the performance, not caricature it: system TTS becomes metallic past
about ±12 %, and the point of the feature is that a whispered line should *feel* whispered, not
that it should be unrecognisable.

Why not a neural emotion model: a SER network means hundreds of MB and per-language training
data, and on this chip class it would compete with ASR for the same cores. Three cheap
measurements carry most of the signal for dubbing, and they are unit-tested (including the
octave-error case that a naive autocorrelation gets wrong).
