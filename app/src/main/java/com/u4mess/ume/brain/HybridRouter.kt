package com.u4mess.ume.brain

import com.u4mess.ume.settings.CloudSettings

enum class HybridRoute { LOCAL, CLOUD }

object HybridRouter {

    private val ACTION_START = Regex(
        """^(please\s+)?(open|launch|message|text|call|search|play|click|tap|scroll|install|uninstall|send|email|book|order|set|turn|toggle|enable|disable|download|share|reply|go\s+to|navigate\s+to|switch\s+to|take\s+me\s+to)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val POLITE_ACTION = Regex(
        """^(can|could|would|will)\s+you\s+(open|launch|message|text|call|search|play|click|tap|scroll|install|uninstall|send|email|book|order|set|turn|toggle|enable|disable|download|share|reply|go\s+to|navigate\s+to|switch\s+to)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val QUESTION_START = Regex(
        """^(what|who|why|how|when|where|which|whose|explain|summari[sz]e|tell\s+me|do\s+you\s+remember|recall|describe)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun route(prompt: String): HybridRoute {
        val text = prompt.trim()
        if (hasPrefix(text, "/do")) return HybridRoute.CLOUD
        if (hasPrefix(text, "/local")) return HybridRoute.LOCAL
        if (QUESTION_START.containsMatchIn(text)) return HybridRoute.LOCAL
        if (ACTION_START.containsMatchIn(text) || POLITE_ACTION.containsMatchIn(text)) {
            return HybridRoute.CLOUD
        }
        return HybridRoute.LOCAL
    }

    fun clean(prompt: String): String {
        val text = prompt.trim()
        return when {
            hasPrefix(text, "/do") -> text.substring("/do".length).trim()
            hasPrefix(text, "/local") -> text.substring("/local".length).trim()
            else -> text
        }
    }

    fun cloudAvailable(): Boolean = CloudSettings.enabled.value && CloudSettings.hasKey()

    private fun hasPrefix(text: String, prefix: String): Boolean =
        text.equals(prefix, ignoreCase = true) ||
            (text.startsWith(prefix, ignoreCase = true) &&
                text.length > prefix.length &&
                text[prefix.length].isWhitespace())
}
