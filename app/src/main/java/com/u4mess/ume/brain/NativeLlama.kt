package com.u4mess.ume.brain

internal object NativeLlama {
    init {
        System.loadLibrary("llama_jni")
    }

    external fun nativeCreate(): Long

    external fun nativeDestroy(handle: Long)

    external fun nativeLoadChat(handle: Long, path: String, contextSize: Int, threads: Int): Boolean

    external fun nativeUnloadChat(handle: Long)

    external fun nativeChatTemplate(handle: Long): String

    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, sink: TokenSink): Boolean

    external fun nativeStop(handle: Long)

    external fun nativeLoadEmbedding(handle: Long, path: String, threads: Int): Boolean

    external fun nativeEmbeddingError(handle: Long): String

    external fun nativeUnloadEmbedding(handle: Long)

    external fun nativeEmbed(handle: Long, text: String): FloatArray?
}

internal class TokenSink(private val onToken: (String) -> Boolean) {
    fun emit(token: String): Boolean = onToken(token)
}
