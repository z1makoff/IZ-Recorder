#include <jni.h>
#include <atomic>
#include <cstring>
#include <string>
#include <thread>

#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "IZWhisper", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

namespace {

std::atomic<bool> g_abort{false};

struct ProgressCtx {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
};

void on_progress(whisper_context *, whisper_state *, int progress, void *user_data) {
    auto *p = static_cast<ProgressCtx *>(user_data);
    if (p == nullptr || p->listener == nullptr) return;
    p->env->CallVoidMethod(p->listener, p->method, (jint) progress);
    if (p->env->ExceptionCheck()) p->env->ExceptionClear();
}

bool on_abort(void *) {
    return g_abort.load();
}

whisper_context *ctx_of(jlong ptr) {
    return reinterpret_cast<whisper_context *>(ptr);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_izrecorder_transcribe_WhisperLib_initContext(JNIEnv *env, jobject, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_izrecorder_transcribe_WhisperLib_freeContext(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free(ctx_of(ptr));
}

JNIEXPORT void JNICALL
Java_com_izrecorder_transcribe_WhisperLib_abort(JNIEnv *, jobject) {
    g_abort.store(true);
}

JNIEXPORT jint JNICALL
Java_com_izrecorder_transcribe_WhisperLib_transcribe(
        JNIEnv *env, jobject, jlong ptr, jfloatArray samples, jstring language,
        jint threads, jobject listener) {
    whisper_context *ctx = ctx_of(ptr);
    if (ctx == nullptr) return -1;
    g_abort.store(false);

    const char *lang = env->GetStringUTFChars(language, nullptr);
    std::string lang_str(lang);
    env->ReleaseStringUTFChars(language, lang);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_context = true;
    params.suppress_blank = true;
    params.language = lang_str.empty() ? "auto" : lang_str.c_str();
    params.detect_language = false;

    ProgressCtx progress{env, listener, nullptr};
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        progress.method = env->GetMethodID(cls, "onProgress", "(I)V");
        env->DeleteLocalRef(cls);
        if (progress.method != nullptr) {
            params.progress_callback = on_progress;
            params.progress_callback_user_data = &progress;
        } else {
            env->ExceptionClear();
        }
    }
    params.abort_callback = on_abort;
    params.abort_callback_user_data = nullptr;

    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    int rc = whisper_full(ctx, params, data, (int) n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    if (rc != 0 || g_abort.load()) {
        LOGI("whisper_full failed: rc=%d abort=%d", rc, (int) g_abort.load());
        return -1;
    }
    return whisper_full_n_segments(ctx);
}

JNIEXPORT jbyteArray JNICALL
Java_com_izrecorder_transcribe_WhisperLib_segmentText(JNIEnv *env, jobject, jlong ptr, jint i) {
    // Возвращаем байты: текст whisper может содержать неполные UTF-8 последовательности,
    // а NewStringUTF на них падает. Декодирование делается на стороне Kotlin.
    const char *text = whisper_full_get_segment_text(ctx_of(ptr), i);
    jsize len = text ? (jsize) strlen(text) : 0;
    jbyteArray out = env->NewByteArray(len);
    if (len > 0) env->SetByteArrayRegion(out, 0, len, reinterpret_cast<const jbyte *>(text));
    return out;
}

JNIEXPORT jlong JNICALL
Java_com_izrecorder_transcribe_WhisperLib_segmentT0(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_t0(ctx_of(ptr), i) * 10; // в миллисекундах
}

JNIEXPORT jlong JNICALL
Java_com_izrecorder_transcribe_WhisperLib_segmentT1(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_t1(ctx_of(ptr), i) * 10;
}

JNIEXPORT jstring JNICALL
Java_com_izrecorder_transcribe_WhisperLib_detectedLanguage(JNIEnv *env, jobject, jlong ptr) {
    int id = whisper_full_lang_id(ctx_of(ptr));
    const char *s = id >= 0 ? whisper_lang_str(id) : "";
    return env->NewStringUTF(s ? s : "");
}

JNIEXPORT jint JNICALL
Java_com_izrecorder_transcribe_WhisperLib_recommendedThreads(JNIEnv *, jobject) {
    unsigned hw = std::thread::hardware_concurrency();
    if (hw == 0) return 4;
    return (jint) (hw > 6 ? 6 : (hw < 2 ? 2 : hw - 2));
}

} // extern "C"
