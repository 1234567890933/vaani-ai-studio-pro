# Performance notes — Dimensity 6000/6100 class

No figure below is a phone benchmark I ran; they are design targets and ranges implied by
the model sizes. The app has a speed-test path (`DubbingViewModel.runSpeedTest` in v1, and
the ASR stage progress in v2) so you can replace them with real numbers.

## Where the time goes

For a 10-minute clip: ASR ~85–95 % of wall time, translation ~2–5 %, TTS ~3–8 %, mix and
mux ~1–2 % (hardware codecs, video copied not re-encoded). Optimise ASR or nothing.

## Model tiers

| Tier | ASR | MT | Expectation |
|---|---|---|---|
| S | base q5_1 | NLLB int8 | only tier that fits a 4 GB phone; weakest Hindi |
| M | small q5_1 | NLLB int8 | the default; best accuracy you can finish in reasonable time |
| L | large-v3-turbo q5_0 | NLLB int8 | better transcript, several times real time; 8 GB phone |

Do not attempt `medium`/`large` non-turbo Whisper on this chip class: >1 GB of weights and
long decode times.

## Quantisation

* Whisper: q5_1 is the sweet spot. q8_0 costs ~40 % more RAM for no audible gain; q4_0
  starts to hurt Hindi.
* NLLB: INT8 weights. The output projection still dequantises at runtime — that is a
  property of the export, not of your settings.
* If you export your own MT model, keep the shared embedding in INT8 too, or the file stays
  large for no benefit.

## Threads

`threads = 4` by default. On a 2×A76 + 6×A55 layout, threads 5–8 land on LITTLE cores and
add contention. `DeviceProfiler` counts cores above 1.5 GHz and suggests 3–4.

## GPU and NNAPI — the honest verdict

* **whisper.cpp Vulkan**: needs a driver with solid compute support; on Mali-G57-class parts
  it is frequently *slower* than 4 CPU threads, and it adds a 1–2 s init cost per run.
  Wired as `EngineManager.useGpu`, default **off**.
* **NNAPI**: Android 12+ maps only a subset of ops; the rest fall back to CPU with copy
  overhead. Wired as `useNnapi`, default **off**. The app logs which provider actually
  initialised, so you can test it.
* **MediaCodec** (decode, AAC encode) *is* genuinely hardware-accelerated and is used.

## Realistic expectations

* 30-second test clip on a Dimensity 6080: ASR in the tens of seconds, whole job under a
  couple of minutes.
* 10-minute clip: 10–30 minutes depending on tier.
* 2-hour film: plan an overnight job; battery drain is substantial.

## What to measure first

Run the same 60-second clip at tier S and tier M. If tier M is within ~2× of tier S, keep
it. If ASR alone takes more than ~4× the clip length, drop a tier — the translation and TTS
stages are not what is hurting you.
