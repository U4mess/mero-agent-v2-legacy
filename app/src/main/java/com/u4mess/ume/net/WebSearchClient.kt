package com.u4mess.ume.net

import android.net.Uri
import androidx.core.text.HtmlCompat
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class WebResult(
    val title: String,
    val snippet: String,
    val url: String,
)

object WebSearchClient {

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, braveKey: String? = null): List<WebResult> =
        withContext(Dispatchers.IO) {
            val normalized = query.trim()
            if (normalized.isEmpty()) return@withContext emptyList()

            val key = braveKey?.trim().orEmpty()
            if (key.isNotEmpty()) {
                val brave = runCatching { braveSearch(normalized, key) }
                    .getOrDefault(emptyList())
                if (brave.isNotEmpty()) return@withContext brave
            }

            runCatching { duckDuckGoSearch(normalized) }.getOrDefault(emptyList())
        }

    fun formatForPrompt(results: List<WebResult>): String {
        val now = ZonedDateTime.now()
        val header = "Current date/time: " +
            now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.US))
        val builder = StringBuilder(header)
            .append("\nLive web search results:\n")

        for ((index, result) in results.take(4).withIndex()) {
            val entry = buildString {
                append(index + 1)
                append(". ")
                append(result.title.take(180))
                append('\n')
                append(result.snippet.take(360))
                append('\n')
                append(result.url)
                append('\n')
            }
            if (builder.length + entry.length > 1_600) break
            builder.append(entry)
        }
        return builder.toString().take(1_600)
    }

    private fun braveSearch(query: String, key: String): List<WebResult> {
        val url = "https://api.search.brave.com/res/v1/web/search?q=" +
            URLEncoder.encode(query, Charsets.UTF_8.name()) + "&count=4"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", key)
            .header("User-Agent", MOBILE_UA)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            val results = JSONObject(body)
                .optJSONObject("web")
                ?.optJSONArray("results")
                ?: return emptyList()

            val parsed = mutableListOf<WebResult>()
            for (index in 0 until minOf(4, results.length())) {
                val item = results.optJSONObject(index) ?: continue
                val title = clean(item.optString("title"))
                val link = item.optString("url").trim()
                if (title.isBlank() || link.isBlank()) continue
                parsed += WebResult(
                    title = title,
                    snippet = clean(item.optString("description")),
                    url = link,
                )
            }
            return parsed
        }
    }

    private fun duckDuckGoSearch(query: String): List<WebResult> {
        val url = "https://html.duckduckgo.com/html/?q=" +
            URLEncoder.encode(query, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()

            val titlePattern = Regex(
                """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
            val snippetPattern = Regex(
                """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
            val snippets = snippetPattern.findAll(html)
                .map { clean(it.groupValues[1]) }
                .toList()

            val results = mutableListOf<WebResult>()
            titlePattern.findAll(html).forEach { match ->
                if (results.size >= 4) return@forEach
                val link = normalizeDuckUrl(match.groupValues[1])
                val title = clean(match.groupValues[2])
                if (link.isBlank() || title.isBlank()) return@forEach
                results += WebResult(
                    title = title,
                    snippet = snippets.getOrNull(results.size).orEmpty(),
                    url = link,
                )
            }
            return results
        }
    }

    private fun normalizeDuckUrl(raw: String): String {
        var value = raw.trim()
        if (value.startsWith("//")) value = "https:$value"
        val parsed = runCatching { Uri.parse(value) }.getOrNull()
        val redirect = parsed?.getQueryParameter("uddg")
        if (!redirect.isNullOrBlank()) return redirect
        if (value.startsWith("/")) return "https://duckduckgo.com$value"
        return value
    }

    private fun clean(html: String): String =
        HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()
}
