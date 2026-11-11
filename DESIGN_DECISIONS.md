# Design decisions — what exists and why

Every component in this project earns its place. Nothing was added "because it is common".

## Pipeline

| Component | Why it is here | Alternative rejected, and why |
|---|---|---|
| `AudioExtractor` (MediaExtractor + MediaCodec) | The phone already has hardware decoders for every container it can play. Zero APK cost, zero licensing | FFmpeg binary: ffmpeg-kit was retired in 2025; a self-built FFmpeg adds 20–30 MB per ABI and duplicates hardware codecs |
| `AsrProcessor` (whisper.cpp via JNI) | One C++ dependency, quantised ggml models, 30 s internal windowing keeps RAM flat | Python/torch on Android: no viable runtime; a cloud ASR: violates the offline requirement |
| `TranslationEngine` (NLLB INT8 ONNX) | A translation model has no chat alignment layer, so it does not refuse or soften dialogue, and it is ~10× cheaper than a 3–7 B chat model | Chat LLM for translation: bigger, slower, and its safety layer is exactly the "refusal loop" the brief objects to |
| `CloudTranslator` + 4 providers | Optional quality upgrade for idiom/slang, and the "several APIs sharing the work" behaviour | Server-side proxy: there is no server, by design |
| `SystemTtsProcessor` | Already installed, offline, 0 MB in the APK, no GPL | Piper: needs espeak-ng (GPL-3.0) — copyleft over the whole app; XTTS-v2: >1 GB RAM, too slow on this SoC |
| `TimeStretcher` | Each clip must fit its ASR window or the dub drifts | Re-timing the video: destroys the edit |
| `AudioMixer` | Keeps the original bed (breaths, doors, music, ambience) under the dub — real foley retention | Demucs/Spleeter separation: hundreds of MB, several times slower than real time; documented as a later phase |
| `VideoMuxer` (MediaMuxer) | Copies the video stream untouched, so quality is identical and muxing is seconds | Re-encode: quality loss and 10× the time |
| `DubbingPipeline` | Orchestration with per-stage retry and governor checkpoints | One giant function: untestable, and a single failure loses the whole job |

## State, storage, security

| Component | Why | Rejected |
|---|---|---|
| `ModelPool` (quota + LRU) | The brief allows 20–22 GB; a quota prevents filling the phone while still allowing tier switching | Hard-coding one model set: users with 4 GB phones cannot run it |
| `ModelRepository` | Resumable, multi-file, byte-verified downloads. A 470 MB decoder over Indian mobile data will drop | Downloading at first launch without resume: restarts from zero |
| `AppPrefs` (JSON) | ~12 settings; JSON is instant, migration-free, dependency-free | DataStore/Room: extra dependency and schema cost for 3 KB of data |
| `SecretStore` (Keystore AES-GCM) | API keys must not sit in plaintext in a backup or a rooted dump | `EncryptedSharedPreferences`: still alpha at the time of writing; ~40 lines of Keystore code does the same job without the dependency |
| `JobQueue` (JSON) | A 3-hour job must survive a kill or reboot; the queue is what makes unattended runs safe | In-memory queue: loses work on process death |
| `ResourceGovernor` | Sustained CPU load throttles this chip class; pacing prevents the OS from doing it abruptly, and protects the battery | Fixed thread count: either wastes the phone or overheats it |
| `LanguageCatalog` | One place where NLLB tags and TTS locales are paired, so a language cannot be offered unless both exist | Hard-coded Hindi strings scattered through the UI |

## UI

| Component | Why | Rejected |
|---|---|---|
| Chat thread with bubbles | The brief asks for ChatGPT-like: attach, watch progress in-line, get a result card with Share. Progress belongs where the request was made | A form + separate progress screen: more taps, more places to get lost |
| Composer with Attach / Dub | Two actions, always in the same place; the queue count is visible without a new screen | A command console (Termux-like): the thing the brief explicitly rejects |
| Setup wizard (auto **or** manual) | Setup is where users quit. Auto-setup makes one tap enough; manual stays available for people who want specific models | Auto-only: no control. Manual-only: too much friction |
| Settings dialog with real controls | Language, voice, mix, threads, quota, translation engine and keys are all things a professional will want to change | Hidden config files |
| Model manager with size/licence/status | Users must know what they are downloading and under which licence | Silent background downloads |

## Deliberately **not** included

* **Voice cloning.** Needs a VITS/XTTS-class model (>1 GB, slow here) and raises consent
  questions about real people's voices. The `TtsEngine` interface is the extension point.
* **Lip-sync.** Requires a talking-head model; not viable on this SoC.
* **Foley re-synthesis.** Explained above; retention is implemented instead.
* **A server component.** There is no backend; the app is self-contained by design.
* **Jailbreak prompts / filter bypass.** The on-device model has no refusal layer to bypass;
  tone is handled by a user-editable glossary. Nothing in the repo contains explicit
  vocabulary, and age-rating responsibility for dubbed content stays with the user.


---

# v4 additions

| Component | Why | Rejected |
|---|---|---|
| `engine/core/DubEngine` | Owns scheduling, model lifetime, caching and pacing. The heavy pieces stay proven; the orchestration is ours and tuned for this SoC | Calling the processors in a straight line (v3): reloads models per job, no VAD, no dedupe |
| `engine/core/Vad` | RMS gate with hysteresis; removes silence before ASR. Costs milliseconds, saves 15–25 % of ASR time and stops music hallucination | Silero/neural VAD: extra 2 MB model and a second inference pass for no gain on dialogue tracks |
| `engine/core/SegmentCache` | Bounded LRU of translations + voiced clips; catchphrases and recaps repeat constantly | No cache: repeat work on every duplicate line |
| `AudioExtractor.decodeAll` | One decode pass, three outputs. v3 decoded the file twice | Two passes: double decode cost for identical audio |
| `MixConfig.sourceIsMono` | Fixes a real bug: L−R is silence on mono, so "centre suppression" deleted the whole bed | Assuming stereo: silent output on mono files |
| `IntentRelay` | Share-to-app works on every intent, not just the first | Reading `activity.intent` once in a composable |
| Debounced `AppPrefs.flush()` | Slider drags were writing JSON per frame — jank plus flash wear | Writing on every setter |
| `quickSeconds` (Quick mode) | Testing a setting on 60 s instead of a full episode | Only full-length runs: minutes per experiment |
| `LanguageCatalog.sources` split from `targets` | Dubbing FROM anything, INTO Indian languages — the anime case | One flat list: allowed nonsense targets or blocked Japanese source |


---

# v5 additions

| Component | Why | Rejected |
|---|---|---|
| `audio/PcmStore` | Disk-backed PCM16 with sparse writes: the voice timeline and bed no longer live in RAM, so memory stops scaling with film length | Whole-file FloatArrays: 1.3 GB peak on a 24-minute episode |
| `audio/MediaDecoder` | Decodes one 4-minute window, sink-based, sample-accurate start | One decode of the entire file: peak memory = file size |
| `audio/FloatSink` | Primitive append buffer | `ArrayList<Float>`: one boxed object per sample |
| `AudioMixer.streamMix` | Fixed-block mixing (24 000 frames) from two stores | Mixing two full arrays: hundreds of MB of transient garbage |
| `VideoMuxer.encodeToSpool` | AAC to a temp file + small index, then mux | Holding all encoded samples, or a second full-file pass |
| Window loop in `DubEngine` | Bounded memory, progress per window, skip silent windows | "Load everything, then process" |
| Skipping windows with <500 ms speech | A music-only scene costs a decode and nothing else | Running Whisper over silence |
