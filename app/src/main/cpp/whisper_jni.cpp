#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Initialise a whisper context from a ggml file.
 * Returns a native handle, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_ai_hindidub_engine_WhisperBridge_nativeInit(JNIEnv *env, jobject /*thiz*/,
                                                jstring modelPath, jboolean useGpu) {
    if (modelPath == nullptr) return 0;
    const char *path = env->GetStringUTFChars(modelPath, nullptr);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = (useGpu == JNI_TRUE);
    cparams.flash_attn = false;

    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params() failed");
        return 0;
    }
    LOGI("whisper context ready (gpu=%d)", (int) cparams.use_gpu);
    return reinterpret_cast<jlong>(ctx);
}

/**
 * Transcribe 16 kHz mono float PCM.
 * Returns newline-separated "startMs\tendMs\ttext" lines ("" if nothing decoded).
 */
JNIEXPORT jstring JNICALL
Java_ai_hindidub_engine_WhisperBridge_nativeTranscribe(JNIEnv *env, jobject /*thiz*/,
                                                      jlong handle, jfloatArray pcm,
                                                      jstring language, jint nThreads,
                                                      jboolean translateToEnglish) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");

    const jsize nSamples = env->GetArrayLength(pcm);
    if (nSamples <= 0) return env->NewStringUTF("");
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads        = nThreads > 0 ? nThreads : 4;
    params.translate        = (translateToEnglish == JNI_TRUE);
    params.print_progress   = false;
    params.print_special    = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.no_context       = true;
    params.single_segment   = false;
    params.max_len          = 0;      // let whisper pick natural pause boundaries
    params.offset_ms        = 0;
    params.duration_ms      = 0;

    std::string lang;
    if (language != nullptr) {
        const char *l = env->GetStringUTFChars(language, nullptr);
        lang = l ? l : "";
        env->ReleaseStringUTFChars(language, l);
    }
    if (!lang.empty() && lang != "auto") {
        params.language = lang.c_str();
        params.detect_language = false;
    } else {
        params.language = nullptr;      // auto-detect
        params.detect_language = true;
    }

    const int rc = whisper_full(ctx, params, samples, nSamples);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full() failed with code %d", rc);
        return env->NewStringUTF("");
    }

    std::string out;
    const int nSeg = whisper_full_n_segments(ctx);
    for (int i = 0; i < nSeg; ++i) {
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;  // 10ms units -> ms
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;
        out += std::to_string(t0);
        out += '\t';
        out += std::to_string(t1);
        out += '\t';
        out += text;
        out += '\n';
    }
    LOGI("transcribed %d segments", nSeg);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_ai_hindidub_engine_WhisperBridge_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/,
                                                jlong handle) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);
    if (ctx != nullptr) {
        whisper_free(ctx);
        LOGI("whisper context freed");
    }
}

}  // extern "C"
