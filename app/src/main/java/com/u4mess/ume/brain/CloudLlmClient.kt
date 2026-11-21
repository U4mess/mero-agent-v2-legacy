package com.u4mess.ume.brain

import com.u4mess.ume.settings.CloudSettings
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI-compatible chat completions client used for cloud UI actions. */
object CloudLlmClient {

    suspend fun completeJson(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 512,
    ): Result<String> = withContext(Dispatchers.IO) {
        val provider = CloudSettings.provider.value
        val apiKey = CloudSettings.apiKey()?.trim()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Add a ${provider.label} API key in Settings."),
            )
        }

        val endpoint = CloudSettings.endpoint()
        if (endpoint.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Set a Base URL for the Custom provider in Settings."),
            )
        }

        val modelId = CloudSettings.modelId()
        if (modelId.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Set a Model ID for ${provider.label} in Settings."),
            )
        }

        val body = JSONObject().apply {
            put("model", modelId)
            put("temperature", 0.1)
            put("max_tokens", maxTokens)
            put("stream", false)
            put("response_format", JSONObject().put("type", "json_object"))
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userPrompt)),
            )
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("X-Title", "Ume")
            }

            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException(httpErrorMessage(provider.label, code)),
                )
            }

            val content = JSONObject(response)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(
                    IllegalStateException("${provider.label} returned an empty response."),
                )

            Result.success(content)
        } catch (throwable: Throwable) {
            Result.failure(
                IllegalStateException(
                    "${provider.label} request failed: ${throwable.message ?: "network error"}",
                ),
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun httpErrorMessage(provider: String, code: Int): String = when (code) {
        401 -> "$provider rejected the API key (HTTP 401)."
        402 -> "$provider account has insufficient balance (HTTP 402)."
        429 -> "$provider rate limit reached. Try again shortly."
        in 500..599 -> "$provider is unavailable right now (HTTP $code)."
        else -> "$provider request failed (HTTP $code)."
    }
}
