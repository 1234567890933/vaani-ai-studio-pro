package ai.hindidub.engine

/** JNI wrapper around google/sentencepiece (see cpp/sp_jni.cpp). */
internal object SentencePieceTokenizer {

    init {
        System.loadLibrary("spjni")
    }

    /** Returns a native handle, or 0 if the .model file could not be loaded. */
    external fun nativeLoad(modelPath: String): Long

    external fun nativeEncode(handle: Long, text: String, addBos: Boolean): IntArray?

    external fun nativeDecode(handle: Long, ids: IntArray): String

    external fun nativeFree(handle: Long)
}
