# On-device beta test checklist

I could not run the app here, so this is the exact order I would test in — cheapest first,
so a failure early does not hide a failure later. Tick each row and note the device.

Device under test: ______________________ (SoC / RAM / Android version)

## 1. Build and install

| # | Step | Expected |
|---|---|---|
| 1.1 | `./gradlew :app:assembleDebug` | APK built, no compile errors |
| 1.2 | `adb install -r app-debug.apk` | installs; app appears as HindiDubber |
| 1.3 | Launch | setup wizard, no crash |

## 2. Setup

| # | Step | Expected |
|---|---|---|
| 2.1 | Allow notifications | permission granted, no crash on Android 13+ |
| 2.2 | Tap "Set up automatically" | downloads start; progress moves; resumes if Wi-Fi drops |
| 2.3 | Wait for completion | status becomes "ready"; ~1.1 GB used |
| 2.4 | Open Settings → choose Tamil | language list shows 23 entries; voice hint updates |
| 2.5 | Install the Tamil offline voice, reopen Settings | "voice found" state |

## 3. First dub (30-second clip)

| # | Step | Expected |
|---|---|---|
| 3.1 | Attach a short MP4 (H.264 + AAC) | filename appears in composer |
| 3.2 | Press Dub | user bubble + stage bubble appear, progress advances |
| 3.3 | Watch the notification | same stage text as in-app |
| 3.4 | Lock the screen for 2 minutes | job continues (foreground service) |
| 3.5 | Job finishes | result card with "Share"; file exists in Movies/dubbed |
| 3.6 | Play the output | Hindi voice over ducked original audio; sync within ~0.3 s |

If 3.6 fails, note which of these it is: no audio / wrong language / silent voice / drift.

## 4. Stress and robustness

| # | Step | Expected |
|---|---|---|
| 4.1 | Queue three clips, walk away | all three complete in order; queue count decrements |
| 4.2 | Force-stop the app mid-job, relaunch | interrupted job returns to PENDING and retries |
| 4.3 | Dub a 10-minute file | completes; phone warm but no crash; governor message may appear |
| 4.4 | Dub while charging from <15 % | warning appears before start |
| 4.5 | Rotate the screen mid-job | no crash, progress intact |
| 4.6 | Share a video from Gallery → HindiDubber | app opens with that video attached |

## 5. Settings that matter

| # | Step | Expected |
|---|---|---|
| 5.1 | Change bed level to −30 dB | original audio noticeably quieter in next output |
| 5.2 | Turn off centre suppression | original dialogue audible under the dub |
| 5.3 | Switch voice profile | pitch/rate change audible |
| 5.4 | Set threads 1 then 8 | job slower at 1; at 8 no crash, possibly no faster |
| 5.5 | Set quota to 2 GB with 1.1 GB installed | no spurious eviction of models in use |

## 6. Cloud translation (only if you have a key)

| # | Step | Expected |
|---|---|---|
| 6.1 | Add one key, mode cloud, routing single | translation comes from the provider (check latency) |
| 6.2 | Add two keys, routing parallel | both are used; job finishes faster than single |
| 6.3 | Put a deliberately wrong key | provider cools down, job still finishes on-device |
| 6.4 | Aeroplane mode, mode cloud | falls back to on-device with a log line, no crash |

## 7. What to send back

1. The device line from the setup screen (SoC, cores, RAM).
2. Which row failed and the logcat lines tagged `Pipeline`, `MTEngine`, `ASR`, `TTS`,
   `Muxer`, `Governor`.
3. For 3.6 failures: the clip length, whether the source was mono or stereo, and the
   language pair.

`adb logcat -s Pipeline:V MTEngine:V ASR:V TTS:V Muxer:V Governor:V` captures all of it.


---

# v4 additions — run these after sections 1–3 pass

## 8. Japanese anime source

| # | Step | Expected |
|---|---|---|
| 8.1 | Settings → Spoken language → Japanese; attach a raw anime episode | source shows 日本語 |
| 8.2 | Dub 60 s (Quick mode on) | transcript is Japanese, output is Hindi; no English in between |
| 8.3 | Check Engine panel | RTF printed, speech % plausible (30–75 %) |
| 8.4 | Same clip with a mono source | bed is audible (mono bug fixed); no silence |
| 8.5 | Same clip, centre suppression on with stereo source | Japanese dialogue attenuated, music intact |

## 9. Engine behaviour

| # | Step | Expected |
|---|---|---|
| 9.1 | Queue 2 clips back to back | second starts fast (models already resident) |
| 9.2 | Watch Engine panel after a run | RTF, speech %, chunks, cache hits populated |
| 9.3 | Dub a file with repeated lines | cache hits > 0 |
| 9.4 | Drag a slider, then kill the app immediately | setting still applied (debounced flush on dialog close) |
| 9.5 | Share a second video while the app is open | attaches without restarting the app |

## 10. Interface smoothness (the lag report)

| # | Step | Expected |
|---|---|---|
| 10.1 | Scroll the chat during a running job | no stutter (no disk reads per tick) |
| 10.2 | Drag the bed-level slider | smooth, no jank |
| 10.3 | Open/close Settings repeatedly during a job | no dropped frames, no crash |
| 10.4 | Rotate during a job | state preserved |


---

# v5 additions — memory and long-file behaviour

| # | Step | Expected |
|---|---|---|
| 11.1 | Dub a 20–24 minute episode on the target phone | completes without OOM; watch `adb shell dumpsys meminfo ai.hindidub` — peak should stay under ~600 MB |
| 11.2 | Dub a 2-minute clip | peak memory similar to 11.1 (memory is window-bound, not file-bound) |
| 11.3 | Check the output around each 4-minute boundary | no audible jump, no repeated or missing line (window seams) |
| 11.4 | Engine panel after a long run | "peak window" under ~25 MB, windows count = length ÷ 4 min |
| 11.5 | Dub with bed level at −60 dB | no bed decode pass (faster), voice only |
| 11.6 | Kill the job mid-way, check `/data/data/ai.hindidub/cache/` | no leftover `dub_*` directories |
| 11.7 | Dub a mono-source file | bed audible (mono fix), no silence |
