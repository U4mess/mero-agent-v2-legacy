#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>

#include "llama.h"

#define LOG_TAG "UmeLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int EMBEDDING_DIMENSIONS = 384;
constexpr int EMBEDDING_CONTEXT = 512;

struct Engine {
    llama_model *chat_model = nullptr;
    llama_context *chat_ctx = nullptr;
    const llama_vocab *chat_vocab = nullptr;
    int chat_ctx_size = 0;
    std::string chat_template;

    llama_model *emb_model = nullptr;
    llama_context *emb_ctx = nullptr;
    const llama_vocab *emb_vocab = nullptr;

    std::mutex chat_mutex;
    std::mutex emb_mutex;
    std::atomic<bool> stop{false};
};

Engine *engine_from(jlong handle) {
    return reinterpret_cast<Engine *>(handle);
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special,
                                  bool parse_special, size_t max_tokens) {
    if (text.empty() || vocab == nullptr) return {};

    int32_t needed = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0,
                                     add_special, parse_special);
    if (needed <= 0) return {};

    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    int32_t written = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(),
                                     needed, add_special, parse_special);
    if (written <= 0) return {};
    tokens.resize(static_cast<size_t>(written));

    if (tokens.size() > max_tokens) {
        tokens.erase(tokens.begin(), tokens.end() - static_cast<std::ptrdiff_t>(max_tokens));
    }
    return tokens;
}

std::u16string utf8_to_utf16(const char *data, size_t length) {
    std::u16string out;
    out.reserve(length);
    size_t i = 0;
    while (i < length) {
        const unsigned char first = static_cast<unsigned char>(data[i]);
        uint32_t code_point;
        int extra;
        if (first < 0x80) {
            code_point = first;
            extra = 0;
        } else if ((first & 0xE0) == 0xC0) {
            code_point = first & 0x1F;
            extra = 1;
        } else if ((first & 0xF0) == 0xE0) {
            code_point = first & 0x0F;
            extra = 2;
        } else if ((first & 0xF8) == 0xF0) {
            code_point = first & 0x07;
            extra = 3;
        } else {
            ++i;
            continue;
        }

        if (i + static_cast<size_t>(extra) >= length) break;

        bool valid = true;
        for (int j = 1; j <= extra; ++j) {
            const unsigned char next = static_cast<unsigned char>(data[i + j]);
            if ((next & 0xC0) != 0x80) {
                valid = false;
                break;
            }
            code_point = (code_point << 6) | (next & 0x3F);
        }
        if (!valid) {
            ++i;
            continue;
        }
        i += static_cast<size_t>(extra) + 1;

        if (code_point <= 0xFFFF) {
            out.push_back(static_cast<char16_t>(code_point));
        } else {
            code_point -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (code_point >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (code_point & 0x3FF)));
        }
    }
    return out;
}

jstring new_utf16_string(JNIEnv *env, const char *data, size_t length) {
    const std::u16string utf16 = utf8_to_utf16(data, length);
    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()), static_cast<jsize>(utf16.size()));
}

void unload_chat(Engine *engine) {
    if (engine->chat_ctx != nullptr) {
        llama_free(engine->chat_ctx);
        engine->chat_ctx = nullptr;
    }
    if (engine->chat_model != nullptr) {
        llama_model_free(engine->chat_model);
        engine->chat_model = nullptr;
    }
    engine->chat_vocab = nullptr;
    engine->chat_ctx_size = 0;
    engine->chat_template.clear();
}

void unload_embedding(Engine *engine) {
    if (engine->emb_ctx != nullptr) {
        llama_free(engine->emb_ctx);
        engine->emb_ctx = nullptr;
    }
    if (engine->emb_model != nullptr) {
        llama_model_free(engine->emb_model);
        engine->emb_model = nullptr;
    }
    engine->emb_vocab = nullptr;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_u4mess_ume_brain_NativeLlama_nativeCreate(JNIEnv *, jobject) {
    llama_backend_init();
    return reinterpret_cast<jlong>(new Engine());
}

extern "C" JNIEXPORT void JNICALL
Java_com_u4mess_ume_brain_NativeLlama_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    Engine *engine = engine_from(handle);
    if (engine == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(engine->chat_mutex);
        unload_chat(engine);
    }
    {
        std::lock_guard<std::mutex> lock(engine->emb_mutex);
        unload_embedding(engine);
    }
    delete engine;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_u4mess_ume_brain_NativeLlama_nativeLoadChat(JNIEnv *env, jobject, jlong handle, jstring path,
                                                     jint context_size, jint threads) {
    Engine *engine = engine_from(handle);
    if (engine == nullptr) return JNI_FALSE;

    const char *raw_path = env->GetStringUTFChars(path, nullptr);
    if (raw_path == nullptr) return JNI_FALSE;
    const std::string model_path(raw_path);
    env->ReleaseStringUTFChars(path, raw_path);

    std::lock_guard<std::mutex> lock(engine->chat_mutex);
    unload_chat(engine);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.check_tensors = false;

    llama_model *model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        LOGE("Failed to load chat model: %s", model_path.c_str());
        return JNI_FALSE;
    }

    const int context = std::max(512, static_cast<int>(context_size));
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context);
    context_params.n_batch = static_cast<uint32_t>(context);
    context_params.n_ubatch = static_cast<uint32_t>(std::min(context, 512));
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    context_params.embeddings = false;

    llama_context *ctx = llama_init_from_model(model, context_params);
    if (ctx == nullptr) {
        LOGE("Failed to create chat context");
        llama_model_free(model);
        return JNI_FALSE;
    }

    const char *chat_template = llama_model_chat_template(model, nullptr);

    engine->chat_model = model;
    engine->chat_ctx = ctx;
    engine->chat_vocab = llama_model_get_vocab(model);
    engine->chat_ctx_size = context;
    engine->chat_template = chat_template != nullptr ? chat_template : "";

    LOGI("Chat model ready: %s (ctx=%d, threads=%d)", model_path.c_str(), context, threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_u4mess_ume_brain_NativeLlama_nativeUnloadChat(JNIEnv *, jobject, jlong handle) {
    Engine *engine = engine_from(handle);
    if (engine == nullptr) return;
    std::lock_guard<std::mutex> lock(engine->chat_mutex);
    unload_chat(engine);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_u4mess_ume_brain_NativeLlama_nativeChatTemplate(JNIEnv *env, jobject, jlong handle) {
    Engine *engine = engine_from(handle);
    if (engine == nullptr) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lock(engine->chat_mutex);
    return new_utf16_string(env, engine->chat_template.data(), engine->chat_template.size());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_u4mess_ume_brain_NativeLlama_nativeGenerate(JNIEnv *env, jobject, jlong handle, jstring prompt,
                                                     jint max_tokens, jobject sink) {
    Engine *engine = engine_from(handle);
    if (engine == nullptr) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(engine->chat_mutex);
    if (engine->chat_ctx == nullptr || engine->chat_vocab == nullptr) return JNI_FALSE;

    const char *raw_prompt = env->GetStringUTFChars(prompt, nullptr);
    if (raw_prompt == nullptr) return JNI_FALSE;
    const std::string prompt_text(raw_prompt);
    env->ReleaseStringUTFChars(prompt, raw_prompt);

    const int prompt_budget = std::max(32, engine->chat_ctx_size - max_tokens - 8);
    std::vector<llama_token> tokens = tokenize(engine->chat_vocab, prompt_text, true, true,
                                               static_cast<size_t>(prompt_budget));
    if (tokens.empty()) {
        LOGE("Prompt produced no tokens");
        return JNI_FALSE;
    }

    llama_memory_clear(llama_get_memory(engine->chat_ctx), true);
    if (llama_decode(engine->chat_ctx, llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()))) != 0) {
        LOGE("Prompt decode failed");
        return JNI_FALSE;
    }

    jclass sink_class = env->GetObjectClass(sink);
    jmethodID emit = env->GetMethodID(sink_class, "emit", "(Ljava/lang/String;)Z");
    if (emit == nullptr) {
        LOGE("TokenSink.emit not found");
        return JNI_FALSE;
    }

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    engine->stop.store(false, std::memory_order_release);
    bool ok = true;

    for (int produced = 0; produced < max_tokens; ++produced) {
        if (engine->stop.load(std::memory_order_acquire)) break;

        llama_token token = llama_sampler_sample(sampler, engine->chat_ctx, -1);
        llama_sampler_accept(sampler, token);

        if (llama_vocab_is_eog(engine->chat_vocab, token)) break;

        char buffer[256];
        int32_t piece_length = llama_token_to_piece(engine->chat_vocab, token, buffer,
                                                    static_cast<int32_t>(sizeof(buffer)), 0, false);
        if (piece_length < 0) {
            std::vector<char> larger(static_cast<size_t>(-piece_length));
            piece_length = llama_token_to_piece(engine->chat_vocab, token, larger.data(),
                                                static_cast<int32_t>(larger.size()), 0, false);
            if (piece_length > 0) {
                jstring piece = new_utf16_string(env, larger.data(), static_cast<size_t>(piece_length));
                const jboolean keep_going = env->CallBooleanMethod(sink, emit, piece);
                env->DeleteLocalRef(piece);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    ok = false;
                    break;
                }
                if (!keep_going) break;
            }
        } else if (piece_length > 0) {
            jstring piece = new_utf16_string(env, buffer, static_cast<size_t>(piece_length));
            const jboolean keep_going = env->CallBooleanMethod(sink, emit, piece);
            env->DeleteLocalRef(piece);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                ok = false;
                break;
            }
            if (!keep_going) break;
        }

        if (llama_decode(engine->chat_ctx, llama_batch_get_one(&token, 1)) != 0) {
            LOGE("Token decode failed at %d", produced);
            ok = false;
            break;
        }
    }

    llama_sampler_free(sampler);
    engine->stop.store(false, std::memory_order_release);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_u4mess_ume_brain_NativeLlama_nativeStop(JNIEnv *, jobject, jlong handle) {
    Engine *engine = engine_from(handle);
    if (engine == nullptr) return;
    engine->stop.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_u4mess_ume_brain_NativeLlama_nativeLoadEmbedding(JNIEnv *env, jobject, jlong handle, jstring path,
                                                          jint threads) {
    Engine *engine = engine_from(handle);
    if (engine == nullptr) return JNI_FALSE;

    const char *raw_path = env->GetStringUTFChars(path, nullptr);
    if (raw_path == nullptr) return JNI_FALSE;
    const std::string model_path(raw_path);
    env->ReleaseStringUTFChars(path, raw_path);

    std::lock_guard<std::mutex> lock(engine->emb_mutex);
    unload_embedding(engine);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.check_tensors = false;

    llama_model *model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        LOGE("Failed to load embedding model: %s", model_path.c_str());
        return JNI_FALSE;
    }

    const int dimensions = llama_model_n_embd(model);
    if (dimensions != EMBEDDING_DIMENSIONS) {
        LOGE("Embedding model has %d dimensions, expected %d", dimensions, EMBEDDING_DIMENSIONS);
        llama_model_free(model);
        return JNI_FALSE;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = EMBEDDING_CONTEXT;
    context_params.n_batch = EMBEDDING_CONTEXT;
    context_params.n_ubatch = EMBEDDING_CONTEXT;
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    context_params.embeddings = true;
    context_params.pooling_type = LLAMA_POOLING_TYPE_MEAN;

    llama_context *ctx = llama_init_from_model(model, context_params);
    if (ctx == nullptr) {
        LOGE("Failed to create embedding context");
        llama_model_free(model);
        return JNI_FALSE;
    }

    engine->emb_model = model;
    engine->emb_ctx = ctx;
    engine->emb_vocab = llama_model_get_vocab(model);

    LOGI("Embedding model ready: %s (dim=%d)", model_path.c_str(), dimensions);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_u4mess_ume_brain_NativeLlama_nativeUnloadEmbedding(JNIEnv *, jobject, jlong handle) {
    Engine *engine = engine_from(handle);
    if (engine == nullptr) return;
    std::lock_guard<std::mutex> lock(engine->emb_mutex);
    unload_embedding(engine);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_u4mess_ume_brain_NativeLlama_nativeEmbed(JNIEnv *env, jobject, jlong handle, jstring text) {
    Engine *engine = engine_from(handle);
    if (engine == nullptr) return nullptr;

    std::lock_guard<std::mutex> lock(engine->emb_mutex);
    if (engine->emb_ctx == nullptr || engine->emb_vocab == nullptr) return nullptr;

    const char *raw_text = env->GetStringUTFChars(text, nullptr);
    if (raw_text == nullptr) return nullptr;
    const std::string input(raw_text);
    env->ReleaseStringUTFChars(text, raw_text);

    std::vector<llama_token> tokens = tokenize(engine->emb_vocab, input, true, false, EMBEDDING_CONTEXT - 1);
    if (tokens.empty()) return nullptr;

    llama_memory_clear(llama_get_memory(engine->emb_ctx), true);
    if (llama_decode(engine->emb_ctx, llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()))) != 0) {
        LOGE("Embedding decode failed");
        return nullptr;
    }

    float *values = llama_get_embeddings_seq(engine->emb_ctx, 0);
    if (values == nullptr) {
        values = llama_get_embeddings(engine->emb_ctx);
    }
    if (values == nullptr) {
        LOGE("No embeddings returned");
        return nullptr;
    }

    std::vector<float> embedding(values, values + EMBEDDING_DIMENSIONS);
    double norm = 0.0;
    for (float value : embedding) norm += static_cast<double>(value) * value;
    norm = std::sqrt(norm);
    if (norm > 0.0) {
        for (float &value : embedding) value = static_cast<float>(value / norm);
    }

    jfloatArray result = env->NewFloatArray(EMBEDDING_DIMENSIONS);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, EMBEDDING_DIMENSIONS, embedding.data());
    }
    return result;
}
