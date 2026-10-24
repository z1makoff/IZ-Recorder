package com.izrecorder.transcribe

/** Тонкая обёртка над whisper.cpp (см. src/main/cpp/whisper_jni.cpp). */
object WhisperLib {
    fun interface ProgressListener {
        fun onProgress(percent: Int)
    }

    init {
        System.loadLibrary("izwhisper")
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(ctx: Long)

    /** Возвращает число сегментов или -1 при ошибке/отмене. */
    external fun transcribe(
        ctx: Long, samples: FloatArray, language: String, threads: Int, listener: ProgressListener?,
    ): Int

    external fun segmentText(ctx: Long, index: Int): ByteArray
    external fun segmentT0(ctx: Long, index: Int): Long
    external fun segmentT1(ctx: Long, index: Int): Long
    external fun detectedLanguage(ctx: Long): String
    external fun recommendedThreads(): Int
    external fun abort()
}
