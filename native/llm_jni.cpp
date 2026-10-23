// Minimal JNI bridge over llama.cpp (pinned b11838). Loads one GGUF model, streams tokens
// back to a Java sink, and can be cancelled. Built by scripts/build-native.sh for arm64-v8a.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "MercurealLLM"

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static std::atomic<bool> g_cancel{false};

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mercureal_voice_Llm_nativeLoad(JNIEnv *env, jclass, jstring path, jint threads, jint ctxSize) {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(jstr(env, path).c_str(), mp);
    if (!g_model) { __android_log_print(ANDROID_LOG_ERROR, TAG, "model load failed"); return JNI_FALSE; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = ctxSize;
    cp.n_batch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        llama_model_free(g_model); g_model = nullptr;
        __android_log_print(ANDROID_LOG_ERROR, TAG, "context init failed");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Builds the chat prompt from (role, content) pairs using the model's own template.
static std::string applyChat(JNIEnv *env, jobjectArray roles, jobjectArray contents) {
    jsize n = env->GetArrayLength(roles);
    std::vector<std::string> r(n), c(n);
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; i++) {
        r[i] = jstr(env, (jstring) env->GetObjectArrayElement(roles, i));
        c[i] = jstr(env, (jstring) env->GetObjectArrayElement(contents, i));
        msgs[i].role = r[i].c_str();
        msgs[i].content = c[i].c_str();
    }
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    std::vector<char> buf(8192);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), buf.size());
    if (len > (int32_t) buf.size()) {
        buf.resize(len + 1);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), buf.size());
    }
    return len < 0 ? std::string() : std::string(buf.data(), len);
}

// Streams generated text to sink.onToken(String). Returns tokens generated, or -1 on error.
extern "C" JNIEXPORT jint JNICALL
Java_com_mercureal_voice_Llm_nativeGenerate(JNIEnv *env, jclass, jobjectArray roles, jobjectArray contents,
                                            jint maxTokens, jobject sink) {
    if (!g_ctx || !g_model) return -1;
    g_cancel = false;
    llama_memory_clear(llama_get_memory(g_ctx), true);

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    std::string prompt = applyChat(env, roles, contents);
    if (prompt.empty()) return -1;

    int nCtx = (int) llama_n_ctx(g_ctx);
    std::vector<llama_token> toks(prompt.size() + 16);
    int n = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), (int32_t) toks.size(), true, true);
    if (n < 0) {
        toks.resize(-n);
        n = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), (int32_t) toks.size(), true, true);
    }
    if (n <= 0) return -1;
    // Keep the newest prompt tokens if the history is too long for the context window.
    int keep = nCtx - maxTokens - 8;
    if (keep < 16) keep = 16;
    int start = n > keep ? n - keep : 0;
    std::vector<llama_token> ptoks(toks.begin() + start, toks.begin() + n);

    if (llama_decode(g_ctx, llama_batch_get_one(ptoks.data(), (int32_t) ptoks.size()))) return -1;

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.6f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass sinkCls = env->GetObjectClass(sink);
    jmethodID onToken = env->GetMethodID(sinkCls, "onToken", "(Ljava/lang/String;)V");
    int generated = 0;
    char piece[256];
    while (generated < maxTokens && !g_cancel) {
        llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        int pl = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (pl > 0) {
            jstring js = env->NewStringUTF(std::string(piece, pl).c_str());
            env->CallVoidMethod(sink, onToken, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
        }
        generated++;
        if (llama_decode(g_ctx, llama_batch_get_one(&tok, 1))) break;
    }
    llama_sampler_free(smpl);
    return generated;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mercureal_voice_Llm_nativeCancel(JNIEnv *, jclass) { g_cancel = true; }

extern "C" JNIEXPORT void JNICALL
Java_com_mercureal_voice_Llm_nativeFree(JNIEnv *, jclass) {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}
