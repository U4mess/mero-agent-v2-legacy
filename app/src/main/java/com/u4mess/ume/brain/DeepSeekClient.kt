package com.u4mess.ume.brain

import com.u4mess.ume.settings.CloudSettings
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object DeepSeekClient {

    private const val ENDPOINT = "https://api.deepseek.com/v1/chat/completions"

    suspend fun completeJson(
        systemPrompt: String,
        userPrompt: String,
        model: String,
        maxTokens: Int = 512,
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = CloudSettings.apiKey()?.trim()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Add a DeepSeek API key in Settings."))
        }

        val body = JSONObject().apply {
            put("model", model)
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
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                return@withContext Result.failure(IllegalStateException(httpErrorMessage(code)))
            }

            val content = JSONObject(response)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(
                    IllegalStateException("DeepSeek returned an empty response."),
                )

            Result.success(content)
        } catch (throwable: Throwable) {
            Result.failure(
                IllegalStateException(
                    "DeepSeek request failed: ${throwable.message ?: "network error"}",
                ),
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun httpErrorMessage(code: Int): String = when (code) {
        401 -> "DeepSeek rejected the API key (HTTP 401)."
        402 -> "DeepSeek account has insufficient balance (HTTP 402)."
        429 -> "DeepSeek rate limit reached. Try again shortly."
        in 500..599 -> "DeepSeek is unavailable right now (HTTP $code)."
        else -> "DeepSeek request failed (HTTP $code)."
    }
}
