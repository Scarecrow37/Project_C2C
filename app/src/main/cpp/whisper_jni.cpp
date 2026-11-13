// whisper.cpp 를 Kotlin(com.c2c.app.stt.WhisperLib)에서 쓰기 위한 최소 JNI 브리지.
#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "C2CWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_c2c_app_stt_WhisperLib_initContext(JNIEnv *env, jobject, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    LOGI("model loaded: %s -> %p", path, ctx);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_c2c_app_stt_WhisperLib_freeContext(JNIEnv *, jobject, jlong ctx_ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (ctx != nullptr) whisper_free(ctx);
}

/** 16kHz mono float PCM 을 받아 인식된 텍스트(세그먼트마다 줄바꿈)를 돌려준다. 실패 시 null. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_c2c_app_stt_WhisperLib_transcribe(JNIEnv *env, jobject, jlong ctx_ptr, jfloatArray samples,
                                          jstring language, jstring initial_prompt, jint n_threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (ctx == nullptr) return nullptr;

    const char *lang = env->GetStringUTFChars(language, nullptr);
    const char *prompt = env->GetStringUTFChars(initial_prompt, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = lang;
    params.translate = false;
    params.n_threads = n_threads;
    params.no_context = true;
    params.initial_prompt = prompt;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;

    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    int rc = whisper_full(ctx, params, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseStringUTFChars(initial_prompt, prompt);

    if (rc != 0) {
        LOGI("whisper_full failed: %d", rc);
        return nullptr;
    }

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        text += whisper_full_get_segment_text(ctx, i);
        text += '\n';
    }
    return env->NewStringUTF(text.c_str());
}
