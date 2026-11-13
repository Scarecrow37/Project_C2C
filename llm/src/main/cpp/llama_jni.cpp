// llama.cpp 를 Kotlin(com.c2c.llm.LlamaNative)에서 쓰기 위한 최소 JNI 브리지.
// 한 번의 호출 = 대화 템플릿 적용 → 프롬프트 처리 → GBNF 문법으로 제한된 그리디 생성.
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "C2CLlama", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> cancelled{false};
};

std::once_flag g_backend_once;

std::string to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

void throw_state(JNIEnv *env, const std::string &msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, msg.c_str());
}

/** 모델에 내장된 대화 템플릿을 적용한다. 템플릿이 없거나 llama.cpp 가 모르는 형식이면 ChatML 로 대신한다. */
std::string apply_template(const llama_model *model, const std::string &system, const std::string &user) {
    const llama_chat_message msgs[2] = {{"system", system.c_str()}, {"user", user.c_str()}};
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl != nullptr) {
        const int32_t n = llama_chat_apply_template(tmpl, msgs, 2, true, nullptr, 0);
        if (n > 0) {
            std::vector<char> buf(static_cast<size_t>(n) + 1);
            const int32_t m = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), static_cast<int32_t>(buf.size()));
            if (m > 0 && m <= static_cast<int32_t>(buf.size())) return {buf.data(), static_cast<size_t>(m)};
        }
        LOGI("chat template not supported, falling back to ChatML");
    }
    return "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
}

/** 출력이 완전한 JSON 객체가 되었는지 추적한다 (문법이 끝났는데 모델이 EOS 를 못 내는 경우를 대비). */
struct JsonTracker {
    int depth = 0;
    bool started = false, in_string = false, escape = false;

    /** @return 최상위 객체가 닫혔으면 true */
    bool feed(const std::string &piece) {
        for (char c : piece) {
            if (in_string) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') in_string = false;
                continue;
            }
            if (c == '"') in_string = true;
            else if (c == '{') { depth++; started = true; }
            else if (c == '}' && --depth == 0 && started) return true;
        }
        return false;
    }
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_c2c_llm_LlamaNative_load(JNIEnv *env, jobject, jstring model_path, jint n_ctx, jint n_threads) {
    std::call_once(g_backend_once, [] { llama_backend_init(); });
    const std::string path = to_std(env, model_path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // CPU 전용 (Android)
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        throw_state(env, "모델 파일을 불러오지 못했습니다: " + path);
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = 512;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        throw_state(env, "모델 실행 환경을 만들지 못했습니다 (메모리 부족일 수 있음)");
        return 0;
    }

    auto *e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    LOGI("model loaded: %s (n_ctx=%u)", path.c_str(), llama_n_ctx(ctx));
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT void JNICALL
Java_com_c2c_llm_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr) return;
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

extern "C" JNIEXPORT void JNICALL
Java_com_c2c_llm_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (e != nullptr) e->cancelled = true;
}

/** 생성 결과를 UTF-8 바이트로 돌려준다 (토큰 경계에서 잘린 한글 바이트를 Kotlin 에서 한 번에 디코딩하기 위해). */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_c2c_llm_LlamaNative_generate(JNIEnv *env, jobject, jlong handle, jstring jsystem, jstring juser,
                                      jstring jgrammar, jint max_tokens) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr) {
        throw_state(env, "이미 닫힌 모델");
        return nullptr;
    }
    e->cancelled = false;

    const std::string prompt = apply_template(e->model, to_std(env, jsystem), to_std(env, juser));
    const std::string grammar = to_std(env, jgrammar);

    // 토큰화
    const int32_t n_prompt = -llama_tokenize(e->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt > 0 ? n_prompt : 0));
    if (n_prompt <= 0 ||
        llama_tokenize(e->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), n_prompt, true, true) < 0) {
        throw_state(env, "프롬프트를 토큰으로 바꾸지 못했습니다");
        return nullptr;
    }
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(e->ctx));
    if (n_prompt + max_tokens > n_ctx) {
        throw_state(env, "프롬프트가 너무 깁니다 (" + std::to_string(n_prompt) + " 토큰, 최대 " +
                             std::to_string(n_ctx - max_tokens) + ")");
        return nullptr;
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(e->vocab, grammar.c_str(), "root");
        if (g == nullptr) {
            llama_sampler_free(smpl);
            throw_state(env, "GBNF 문법을 해석하지 못했습니다");
            return nullptr;
        }
        llama_sampler_chain_add(smpl, g);
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());  // 추출 작업이라 무작위성 없이

    llama_memory_clear(llama_get_memory(e->ctx), true);

    // 프롬프트를 배치 단위로 처리
    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(e->ctx));
    for (int32_t i = 0; i < n_prompt; i += n_batch) {
        const int32_t n = std::min(n_batch, n_prompt - i);
        if (llama_decode(e->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            llama_sampler_free(smpl);
            throw_state(env, "프롬프트 처리 실패");
            return nullptr;
        }
        if (e->cancelled) break;
    }

    std::string out;
    JsonTracker tracker;
    char piece[256];
    for (int32_t i = 0; i < max_tokens && !e->cancelled; ++i) {
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        const int32_t n = llama_token_to_piece(e->vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) {
            std::string p(piece, static_cast<size_t>(n));
            out += p;
            if (tracker.feed(p)) break;
        }
        if (llama_decode(e->ctx, llama_batch_get_one(&tok, 1)) != 0) break;
    }
    llama_sampler_free(smpl);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}
