// JNI bridge over llama.cpp.
//
// Scope is deliberately narrow: load a model, open a context, tokenize, decode
// batches, sample one token at a time. Everything above that (prompt assembly,
// streaming, stop sequences, cancellation policy) lives in Kotlin, where it is
// far easier to change without a rebuild of the native layer.
//
// Pinned against llama.cpp 96ffdc41. If the submodule is bumped, re-check
// every signature used here: the C API is stable within a release but not
// across the year.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "myllm-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/// Owns a context plus the batch it decodes into, so the batch is allocated
/// once instead of on every call, and an abort flag the Kotlin side can raise
/// to interrupt a long prompt ingestion.
struct ContextHandle {
    llama_context * ctx    = nullptr;
    llama_batch     batch  {};
    int32_t         nBatch = 0;
    std::atomic<bool> abort { false };
};

bool abortCallback(void * data) {
    auto * handle = static_cast<ContextHandle *>(data);
    return handle != nullptr && handle->abort.load(std::memory_order_relaxed);
}

void logCallback(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) return;
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default:                   prio = ANDROID_LOG_DEBUG; break;
    }
    __android_log_write(prio, LOG_TAG, text);
}

std::string jstringToStd(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars != nullptr ? std::string(chars) : std::string();
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_backendInit(JNIEnv *, jobject) {
    llama_log_set(logCallback, nullptr);
    llama_backend_init();
}

JNIEXPORT void JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_backendFree(JNIEnv *, jobject) {
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

// --- model ---------------------------------------------------------------

/// @param loadMode maps to llama_load_mode: 1 mmap, 3 mmap+mlock, 0 neither.
JNIEXPORT jlong JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_loadModel(
        JNIEnv * env, jobject, jstring jPath, jint nGpuLayers, jint loadMode) {
    const std::string path = jstringToStd(env, jPath);

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = nGpuLayers;
    params.load_mode    = static_cast<llama_load_mode>(loadMode);
    params.use_extra_bufts = true; // weight repacking, a large win on ARM

    llama_model * model = llama_model_load_from_file(path.c_str(), params);
    if (model == nullptr) {
        LOGE("failed to load model at %s", path.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_freeModel(JNIEnv *, jobject, jlong modelPtr) {
    if (modelPtr == 0) return;
    llama_model_free(reinterpret_cast<llama_model *>(modelPtr));
}

JNIEXPORT jstring JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_modelDescription(JNIEnv * env, jobject, jlong modelPtr) {
    if (modelPtr == 0) return env->NewStringUTF("");
    char buf[512] = {0};
    llama_model_desc(reinterpret_cast<llama_model *>(modelPtr), buf, sizeof(buf));
    return env->NewStringUTF(buf);
}

JNIEXPORT jint JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_modelContextLength(JNIEnv *, jobject, jlong modelPtr) {
    if (modelPtr == 0) return 0;
    return llama_model_n_ctx_train(reinterpret_cast<llama_model *>(modelPtr));
}

JNIEXPORT jlong JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_modelParamCount(JNIEnv *, jobject, jlong modelPtr) {
    if (modelPtr == 0) return 0;
    return static_cast<jlong>(llama_model_n_params(reinterpret_cast<llama_model *>(modelPtr)));
}

/// Returns the Jinja chat template baked into the GGUF, or null when absent.
JNIEXPORT jstring JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_modelChatTemplate(JNIEnv * env, jobject, jlong modelPtr) {
    if (modelPtr == 0) return nullptr;
    const char * tmpl = llama_model_chat_template(reinterpret_cast<llama_model *>(modelPtr), nullptr);
    return tmpl == nullptr ? nullptr : env->NewStringUTF(tmpl);
}

// --- context -------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_newContext(
        JNIEnv *, jobject, jlong modelPtr,
        jint nCtx, jint nBatch, jint nThreads, jint nThreadsBatch, jint flashAttn) {
    if (modelPtr == 0) return 0;
    auto * model = reinterpret_cast<llama_model *>(modelPtr);

    auto * handle = new ContextHandle();

    llama_context_params params = llama_context_default_params();
    params.n_ctx           = static_cast<uint32_t>(nCtx);
    params.n_batch         = static_cast<uint32_t>(nBatch);
    params.n_ubatch        = static_cast<uint32_t>(nBatch);
    params.n_threads       = nThreads;
    params.n_threads_batch = nThreadsBatch;
    params.flash_attn_type = static_cast<llama_flash_attn_type>(flashAttn);
    params.no_perf         = false;
    params.abort_callback      = abortCallback;
    params.abort_callback_data = handle;

    handle->ctx = llama_init_from_model(model, params);
    if (handle->ctx == nullptr) {
        LOGE("llama_init_from_model returned null (n_ctx=%d, n_batch=%d)", nCtx, nBatch);
        delete handle;
        return 0;
    }

    handle->nBatch = nBatch;
    handle->batch  = llama_batch_init(nBatch, 0, 1);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_freeContext(JNIEnv *, jobject, jlong ctxPtr) {
    if (ctxPtr == 0) return;
    auto * handle = reinterpret_cast<ContextHandle *>(ctxPtr);
    llama_batch_free(handle->batch);
    if (handle->ctx != nullptr) llama_free(handle->ctx);
    delete handle;
}

JNIEXPORT jint JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_contextLength(JNIEnv *, jobject, jlong ctxPtr) {
    if (ctxPtr == 0) return 0;
    return static_cast<jint>(llama_n_ctx(reinterpret_cast<ContextHandle *>(ctxPtr)->ctx));
}

/// Drops the whole KV cache. Called when a conversation restarts or is rewound.
JNIEXPORT void JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_clearMemory(JNIEnv *, jobject, jlong ctxPtr) {
    if (ctxPtr == 0) return;
    auto * handle = reinterpret_cast<ContextHandle *>(ctxPtr);
    llama_memory_clear(llama_get_memory(handle->ctx), true);
}

/// Raised from another thread to interrupt an in-flight decode.
JNIEXPORT void JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_setAbort(JNIEnv *, jobject, jlong ctxPtr, jboolean abort) {
    if (ctxPtr == 0) return;
    reinterpret_cast<ContextHandle *>(ctxPtr)->abort.store(abort == JNI_TRUE, std::memory_order_relaxed);
}

// --- tokenizer -----------------------------------------------------------

JNIEXPORT jintArray JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_tokenize(
        JNIEnv * env, jobject, jlong modelPtr, jstring jText,
        jboolean addSpecial, jboolean parseSpecial) {
    if (modelPtr == 0) return env->NewIntArray(0);
    const llama_vocab * vocab = llama_model_get_vocab(reinterpret_cast<llama_model *>(modelPtr));
    const std::string text = jstringToStd(env, jText);

    // One token is never shorter than one byte, so this upper bound always holds.
    std::vector<llama_token> tokens(text.size() + 16);
    int32_t n = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                               tokens.data(), static_cast<int32_t>(tokens.size()),
                               addSpecial == JNI_TRUE, parseSpecial == JNI_TRUE);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                           tokens.data(), static_cast<int32_t>(tokens.size()),
                           addSpecial == JNI_TRUE, parseSpecial == JNI_TRUE);
        if (n < 0) return env->NewIntArray(0);
    }

    jintArray out = env->NewIntArray(n);
    env->SetIntArrayRegion(out, 0, n, reinterpret_cast<const jint *>(tokens.data()));
    return out;
}

/// Returns raw bytes rather than a String: a single token can be half of a
/// multi-byte character, and decoding it in isolation would corrupt the text.
/// The Kotlin side buffers bytes and decodes once a sequence is complete.
JNIEXPORT jbyteArray JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_tokenToBytes(
        JNIEnv * env, jobject, jlong modelPtr, jint token) {
    if (modelPtr == 0) return env->NewByteArray(0);
    const llama_vocab * vocab = llama_model_get_vocab(reinterpret_cast<llama_model *>(modelPtr));

    char buf[256];
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    if (n < 0) {
        std::vector<char> big(-n);
        n = llama_token_to_piece(vocab, token, big.data(), static_cast<int32_t>(big.size()), 0, true);
        if (n < 0) return env->NewByteArray(0);
        jbyteArray out = env->NewByteArray(n);
        env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte *>(big.data()));
        return out;
    }
    jbyteArray out = env->NewByteArray(n);
    env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte *>(buf));
    return out;
}

JNIEXPORT jboolean JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_isEndOfGeneration(
        JNIEnv *, jobject, jlong modelPtr, jint token) {
    if (modelPtr == 0) return JNI_TRUE;
    const llama_vocab * vocab = llama_model_get_vocab(reinterpret_cast<llama_model *>(modelPtr));
    return llama_vocab_is_eog(vocab, token) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_vocabSize(JNIEnv *, jobject, jlong modelPtr) {
    if (modelPtr == 0) return 0;
    return llama_vocab_n_tokens(llama_model_get_vocab(reinterpret_cast<llama_model *>(modelPtr)));
}

// --- decoding ------------------------------------------------------------

/// Decodes one batch of tokens starting at absolute position [nPast].
/// Logits are produced only for the last token, which is all sampling needs.
/// Returns 0 on success, or the llama_decode error code.
JNIEXPORT jint JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_decode(
        JNIEnv * env, jobject, jlong ctxPtr, jintArray jTokens, jint nPast, jboolean wantLogits) {
    if (ctxPtr == 0) return -1;
    auto * handle = reinterpret_cast<ContextHandle *>(ctxPtr);

    const jsize n = env->GetArrayLength(jTokens);
    if (n == 0) return 0;
    if (n > handle->nBatch) return -2;

    std::vector<jint> tokens(n);
    env->GetIntArrayRegion(jTokens, 0, n, tokens.data());

    llama_batch & batch = handle->batch;
    batch.n_tokens = n;
    for (jsize i = 0; i < n; ++i) {
        batch.token[i]    = static_cast<llama_token>(tokens[i]);
        batch.pos[i]      = nPast + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]   = 0;
    }
    if (wantLogits == JNI_TRUE) batch.logits[n - 1] = 1;

    return llama_decode(handle->ctx, batch);
}

// --- sampling ------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_newSampler(
        JNIEnv *, jobject, jlong modelPtr,
        jfloat temperature, jint topK, jfloat topP, jfloat minP,
        jfloat repeatPenalty, jint repeatLastN, jint seed) {
    if (modelPtr == 0) return 0;
    auto * model = reinterpret_cast<llama_model *>(modelPtr);
    const int32_t nVocab = llama_vocab_n_tokens(llama_model_get_vocab(model));

    llama_sampler_chain_params params = llama_sampler_chain_default_params();
    params.no_perf = true;
    llama_sampler * chain = llama_sampler_chain_init(params);

    // Order matters: penalties act on raw logits, the truncating samplers
    // narrow the candidate set, temperature rescales what is left, and the
    // final sampler is the one that actually picks.
    if (repeatLastN > 0 && repeatPenalty != 1.0f) {
        llama_sampler_chain_add(chain,
            llama_sampler_init_penalties(nVocab, repeatLastN, repeatPenalty, 0.0f, 0.0f));
    }

    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        return reinterpret_cast<jlong>(chain);
    }

    if (topK > 0)    llama_sampler_chain_add(chain, llama_sampler_init_top_k(topK));
    if (topP < 1.0f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
    if (minP > 0.0f) llama_sampler_chain_add(chain, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain,
        llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));

    return reinterpret_cast<jlong>(chain);
}

JNIEXPORT void JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_freeSampler(JNIEnv *, jobject, jlong samplerPtr) {
    if (samplerPtr == 0) return;
    llama_sampler_free(reinterpret_cast<llama_sampler *>(samplerPtr));
}

JNIEXPORT void JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_resetSampler(JNIEnv *, jobject, jlong samplerPtr) {
    if (samplerPtr == 0) return;
    llama_sampler_reset(reinterpret_cast<llama_sampler *>(samplerPtr));
}

/// Samples from the logits of the last decoded token and records the choice in
/// the sampler chain, so penalties see the running history.
JNIEXPORT jint JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_sample(
        JNIEnv *, jobject, jlong ctxPtr, jlong samplerPtr) {
    if (ctxPtr == 0 || samplerPtr == 0) return -1;
    auto * handle  = reinterpret_cast<ContextHandle *>(ctxPtr);
    auto * sampler = reinterpret_cast<llama_sampler *>(samplerPtr);

    const llama_token token = llama_sampler_sample(sampler, handle->ctx, -1);
    llama_sampler_accept(sampler, token);
    return token;
}

// --- chat template -------------------------------------------------------

/// Applies a built-in chat template. Returns null when the template name is not
/// one llama.cpp knows, which is the signal for Kotlin to fall back to its own.
JNIEXPORT jstring JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_applyChatTemplate(
        JNIEnv * env, jobject, jstring jTemplate,
        jobjectArray jRoles, jobjectArray jContents, jboolean addAssistant) {

    const jsize n = env->GetArrayLength(jRoles);
    if (n != env->GetArrayLength(jContents)) return nullptr;

    const std::string tmpl = jstringToStd(env, jTemplate);

    std::vector<std::string> roles(n), contents(n);
    std::vector<llama_chat_message> messages(n);
    for (jsize i = 0; i < n; ++i) {
        auto role    = reinterpret_cast<jstring>(env->GetObjectArrayElement(jRoles, i));
        auto content = reinterpret_cast<jstring>(env->GetObjectArrayElement(jContents, i));
        roles[i]    = jstringToStd(env, role);
        contents[i] = jstringToStd(env, content);
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
        messages[i].role    = roles[i].c_str();
        messages[i].content = contents[i].c_str();
    }

    size_t capacity = 1024;
    for (jsize i = 0; i < n; ++i) capacity += roles[i].size() + contents[i].size() + 64;

    std::vector<char> buf(capacity);
    int32_t written = llama_chat_apply_template(
        tmpl.empty() ? nullptr : tmpl.c_str(),
        messages.data(), messages.size(), addAssistant == JNI_TRUE,
        buf.data(), static_cast<int32_t>(buf.size()));

    if (written < 0) return nullptr;
    if (static_cast<size_t>(written) > buf.size()) {
        buf.resize(written);
        written = llama_chat_apply_template(
            tmpl.empty() ? nullptr : tmpl.c_str(),
            messages.data(), messages.size(), addAssistant == JNI_TRUE,
            buf.data(), static_cast<int32_t>(buf.size()));
        if (written < 0) return nullptr;
    }

    return env->NewStringUTF(std::string(buf.data(), written).c_str());
}

/// Drops everything the KV cache holds from absolute position [fromPos] onward,
/// keeping the prefix intact. This is what makes a second turn in a long
/// conversation start generating immediately instead of re-reading the whole
/// history: only the diverging suffix has to be decoded again.
JNIEXPORT jboolean JNICALL
Java_pro_simonroux_myllm_engine_local_LlamaNative_trimMemory(
        JNIEnv *, jobject, jlong ctxPtr, jint fromPos) {
    if (ctxPtr == 0) return JNI_FALSE;
    auto * handle = reinterpret_cast<ContextHandle *>(ctxPtr);
    const bool ok = llama_memory_seq_rm(llama_get_memory(handle->ctx), 0, fromPos, -1);
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
