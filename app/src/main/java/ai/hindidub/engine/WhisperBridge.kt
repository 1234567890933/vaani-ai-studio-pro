package ai.hindidub.engine

/**
 * Thin JNI surface over whisper.cpp (see cpp/whisper_jni.cpp).
 *
 * The native library is built by CMake and packaged as libwhisperjni.so.
 */
internal object WhisperBridge {

    init {
        System.loadLibrary("whisperjni")
    }

    /** Returns a native whisper_context handle, or 0 on failure. */
    external fun nativeInit(modelPath: String, useGpu: Boolean): Long

    /**
     * Transcribe 16 kHz mono float PCM.
     * @param language ISO code such as "hi", "en", or "auto" for detection.
     * @param translateToEnglish true = Whisper's own translate task (rarely wanted here).
     * @return newline-separated "startMs\tendMs\ttext" records, empty string on failure.
     */
    external fun nativeTranscribe(
        handle: Long,
        pcm: FloatArray,
        language: String,
        threads: Int,
        translateToEnglish: Boolean
    ): String

    external fun nativeFree(handle: Long)
}
