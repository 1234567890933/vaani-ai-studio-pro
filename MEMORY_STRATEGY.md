# Memory & storage strategy

## 1. Two different budgets

| Budget | Reality on a Dimensity 6000/6100 phone |
|---|---|
| **Storage** | 20–22 GB is easy to allow, and `ModelPool` does (quota default 8 GB, max 22 GB) |
| **RAM** | 4–8 GB total, of which ~1.5–2.5 GB is realistically available to one app |
| **Compute** | 2×Cortex-A76 + 6×A55, no usable NPU for these graphs |

A 20 GB model pool does not make a 4 GB phone translate better; it just holds models the
phone cannot run at speed. The pool therefore exists to let you **switch** model tiers, and
it evicts least-recently-used groups before the phone runs out of space.

## 2. Measured facts behind the design

* NLLB INT8 encoder (419 MB) + decoder (470 MB) **cannot both be loaded** in a 2 GB memory
  budget: the decoder dequantises its 256,206 × 1,024 shared embedding (≈1 GB of floats) for
  the output projection. My own test run failed with `std::bad_alloc` in
  `model.shared.weight_transposed_DequantizeLinear` until the two sessions were split.
* Loading the decoder with ORT's **full graph optimisation** also failed at that budget;
  `OptLevel.BASIC` loaded fine. `TranslationEngine.openSession()` uses BASIC for that reason.
* Whisper model sizes (real): base q5_1 57 MB, small q5_1 181 MB, large-v3-turbo q5_0 547 MB.

## 3. Sequential session loading (the main trick)

`TranslationEngine.translateAll()` does two passes:

```
pass 1  encoder session  →  encode every ASR segment  →  keep hidden states (float arrays)
        close encoder        (~n_segments × n_tokens × 1024 × 4 bytes; a 1,000-segment film
                              is roughly 100–150 MB — this is the number to watch)
pass 2  decoder session  →  greedy decode each segment
        close decoder
```

Peak RAM is one session instead of two, at the cost of holding the hidden states. If you
dub something extremely long, chunk the *batch* (e.g. 200 segments at a time) — a one-line
change in `DubbingPipeline` — and peak RAM stays flat.

## 4. ASR memory

whisper.cpp windows at 30 s internally, so RAM does not grow with film length — only the
16 kHz float input does (≈230 MB for a 2-hour film). `AsrProcessor.transcribeChunked()`
splits into 10-minute blocks to cap even that; timestamps are re-based per block.

## 5. TTS and muxing memory

* TTS writes one WAV per segment to the cache dir and reads it back as floats — no
  accumulation, and the cache is wiped in `finally`.
* The dubbed voice timeline is one float array at 24 kHz (≈5.5 MB per minute of video).
* AAC encoding buffers the encoded samples (≈1.2 MB per minute at 96 kbps) so the audio
  track can be added to the muxer before `start()`. Video is streamed sample by sample
  through a 1 MB direct buffer.

## 6. Disk lifecycle

```
download → <file>.part (resume with HTTP Range) → rename on completion
eviction → LRU by .used_<group> marker files, only when space is short
import   → SAF pick → copy into the pool (no storage permission needed)
```

Quota rules in `ModelPool`:
* `missingBytes(group)` is computed from real Content-Length values, so the pre-flight
  check is exact;
* `evictToFit(needed, keep)` never deletes the group you are about to use;
* deleting a group removes its files and any now-empty directory.

## 7. Thermals

A long job will heat the phone and throttle within 5–10 minutes. Practical mitigations
already in place: 4 threads rather than "all cores", no GPU, no NNAPI, and a foreground
service so the screen can be off (which also cuts display power). If you ship this, add a
"charge only" toggle and process long films in the evening — that is a product decision, not
a code one.


---

# v5 update — the audio path

The earlier sections describe model memory. This one covers audio, which was the actual
problem:

| Approach | 24-min episode | 2-hour film | Verdict |
|---|---|---|---|
| v4: whole-file FloatArrays (bed + mono + voice) | ~500 MB, worse with boxing | ~2.5 GB | unusable on 4 GB phones |
| v5: 4-minute windows + PCM16 on disk | ~350–450 MB peak | ~350–450 MB peak | independent of length |

Rules that now hold in code:

1. Never allocate an array whose size depends on the file length — only on the window.
2. Audio that must persist across stages goes to `PcmStore` (disk), not to a FloatArray.
3. Mixing and encoding are streaming loops with a fixed block size (24 000 frames).
4. Every temp file lives under `cacheDir/dub_<timestamp>/` and is deleted in `finally`,
   including on cancellation.
