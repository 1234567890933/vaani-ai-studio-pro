#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "sentencepiece_processor.h"

#define LOG_TAG "SPJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_ai_hindidub_engine_SentencePieceTokenizer_nativeLoad(JNIEnv *env, jobject /*thiz*/,
                                                          jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    auto *sp = new sentencepiece::SentencePieceProcessor();
    const auto status = sp->Load(path);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!status.ok()) {
        LOGE("sentencepiece load failed: %s", status.ToString().c_str());
        delete sp;
        return 0;
    }
    return reinterpret_cast<jlong>(sp);
}

/** Encode text to token ids. addBos prepends the model's BOS id. */
JNIEXPORT jintArray JNICALL
Java_ai_hindidub_engine_SentencePieceTokenizer_nativeEncode(JNIEnv *env, jobject /*thiz*/,
                                                            jlong handle, jstring text,
                                                            jboolean addBos) {
    auto *sp = reinterpret_cast<sentencepiece::SentencePieceProcessor *>(handle);
    if (sp == nullptr) return nullptr;

    const char *cstr = env->GetStringUTFChars(text, nullptr);
    std::string input(cstr ? cstr : "");
    env->ReleaseStringUTFChars(text, cstr);

    std::vector<int> ids;
    sp->Encode(input, &ids);
    if (addBos == JNI_TRUE) ids.insert(ids.begin(), sp->bos_id());

    jintArray result = env->NewIntArray((jsize) ids.size());
    if (result != nullptr && !ids.empty()) {
        env->SetIntArrayRegion(result, 0, (jsize) ids.size(), ids.data());
    }
    return result;
}

JNIEXPORT jstring JNICALL
Java_ai_hindidub_engine_SentencePieceTokenizer_nativeDecode(JNIEnv *env, jobject /*thiz*/,
                                                            jlong handle, jintArray ids) {
    auto *sp = reinterpret_cast<sentencepiece::SentencePieceProcessor *>(handle);
    if (sp == nullptr || ids == nullptr) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(ids);
    std::vector<int> v((size_t) n);
    if (n > 0) env->GetIntArrayRegion(ids, 0, n, v.data());

    std::string out;
    sp->Decode(v, &out);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_ai_hindidub_engine_SentencePieceTokenizer_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/,
                                                          jlong handle) {
    auto *sp = reinterpret_cast<sentencepiece::SentencePieceProcessor *>(handle);
    delete sp;
}

}  // extern "C"
