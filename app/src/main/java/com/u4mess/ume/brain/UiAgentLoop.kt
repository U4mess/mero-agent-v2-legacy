package com.u4mess.ume.brain

import android.content.Context
import android.content.Intent
import com.u4mess.ume.sensors.ScreenReaderAccessibilityService
import com.u4mess.ume.sensors.ScrollDirection
import com.u4mess.ume.settings.CloudSettings
import java.util.Locale
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

data class UiAgentResult(
    val success: Boolean,
    val message: String,
    val steps: List<String>,
)

object UiAgentLoop {

    private const val MAX_STEPS = 10
    private const val SETTLE_MS = 1500L
    private const val MAX_UI_NODES = 160
    private const val MAX_UI_TEXT = 140
    private const val MAX_APPS = 80

    private data class InstalledApp(val packageName: String, val label: String)

    private data class Action(
        val name: String,
        val packageName: String? = null,
        val nodeId: Int? = null,
        val text: String? = null,
        val direction: String? = null,
        val reason: String = "",
        val message: String = "",
    )

    private data class StepOutcome(val ok: Boolean, val detail: String)

    @Volatile
    private var cachedApps: List<InstalledApp>? = null

    private val actions = setOf(
        "OPEN_APP", "CLICK", "TYPE", "SCROLL", "BACK", "HOME", "DONE", "FAIL",
    )

    private val systemPrompt = """
        You control an Android phone for the user. Reply with exactly one JSON object and nothing else.
        Available actions:
        {"action":"OPEN_APP","package":"<package>","reason":"..."}
        {"action":"CLICK","nodeId":<number>,"reason":"..."}
        {"action":"TYPE","nodeId":<number>,"text":"...","reason":"..."}
        {"action":"SCROLL","direction":"DOWN"|"UP","reason":"..."}
        {"action":"BACK","reason":"..."}
        {"action":"HOME","reason":"..."}
        {"action":"DONE","message":"..."}
        {"action":"FAIL","reason":"..."}

        Rules:
        - Use only nodeId values that exist in the CURRENT UI.
        - Prefer OPEN_APP with an exact package from the installed app list to start a task.
        - Click and type only when the target node is visible and suitable.
        - Inspect the updated UI after every action.
        - Reply with DONE as soon as the goal is achieved.
        - Reply with FAIL if the goal cannot be achieved.
        - Keep reason short.
    """.trimIndent()

    suspend fun run(
        context: Context,
        goal: String,
        onStatus: (String) -> Unit,
    ): UiAgentResult {
        if (ScreenReaderAccessibilityService.instance == null) {
            return UiAgentResult(
                success = false,
                message = "Enable Ume Accessibility in Settings before running phone actions.",
                steps = emptyList(),
            )
        }

        val apps = launchableApps(context)
        val history = mutableListOf<String>()
        var lastDetail = ""

        for (step in 1..MAX_STEPS) {
            val service = ScreenReaderAccessibilityService.instance
                ?: return UiAgentResult(false, "Accessibility service disconnected.", history)

            val rawTree = runCatching { service.captureUiTreeJson() }.getOrDefault("[]")
            val (tree, nodeIds) = compactTree(rawTree)
            if (nodeIds.isEmpty()) {
                return UiAgentResult(false, "Couldn't read the current screen.", history)
            }

            onStatus("$step/$MAX_STEPS · Thinking…")

            val content = DeepSeekClient.completeJson(
                systemPrompt = systemPrompt,
                userPrompt = buildPrompt(goal, apps, history, tree, step),
                model = CloudSettings.model.value,
                maxTokens = 320,
            ).getOrElse { throwable ->
                return UiAgentResult(
                    false,
                    throwable.message ?: "DeepSeek request failed.",
                    history,
                )
            }

            val action = parseAction(content)
                ?: return UiAgentResult(false, "DeepSeek returned an unreadable action.", history)

            when (action.name) {
                "DONE" -> return UiAgentResult(true, action.message.ifBlank { "Done." }, history)
                "FAIL" -> return UiAgentResult(
                    false,
                    action.reason.ifBlank { "The agent stopped." },
                    history,
                )
            }

            val label = actionLabel(action, apps)
            onStatus("$step/$MAX_STEPS · $label")
            val outcome = execute(context, service, action, nodeIds)
            history += "$step. $label — ${if (outcome.ok) "ok" else "failed"}: ${outcome.detail}"
            lastDetail = outcome.detail
            delay(SETTLE_MS)
        }

        return UiAgentResult(
            false,
            lastDetail.ifBlank { "Reached 10 steps without finishing." },
            history,
        )
    }

    private fun buildPrompt(
        goal: String,
        apps: List<InstalledApp>,
        history: List<String>,
        tree: String,
        step: Int,
    ): String = buildString {
        appendLine("Goal: $goal")
        appendLine("Step: $step of $MAX_STEPS")
        appendLine()
        appendLine("Installed launchable apps (package — label):")
        apps.take(MAX_APPS).forEach { app -> appendLine("${app.packageName} — ${app.label}") }
        appendLine()
        appendLine("Previous steps:")
        if (history.isEmpty()) appendLine("(none)") else history.forEach { appendLine(it) }
        appendLine()
        appendLine("Current UI nodes (JSON):")
        appendLine(tree)
    }

    private fun compactTree(raw: String): Pair<String, Map<Int, String>> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return "[]" to emptyMap()
        val nodes = JSONArray()
        val ids = LinkedHashMap<Int, String>()

        for (index in 0 until array.length()) {
            if (ids.size >= MAX_UI_NODES) break
            val node = array.optJSONObject(index) ?: continue
            val accessibilityId = node.optString("id").takeIf { it.isNotBlank() } ?: continue
            val nodeId = ids.size
            ids[nodeId] = accessibilityId
            nodes.put(
                JSONObject().apply {
                    put("nodeId", nodeId)
                    put("text", node.optString("text").take(MAX_UI_TEXT))
                    put("desc", node.optString("desc").take(MAX_UI_TEXT))
                    put("bounds", node.optString("bounds"))
                    put("clickable", node.optBoolean("clickable"))
                    put("editable", node.optBoolean("editable"))
                    put("scrollable", node.optBoolean("scrollable"))
                },
            )
        }

        return nodes.toString() to ids
    }

    private fun parseAction(content: String): Action? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null

        val json = runCatching {
            JSONObject(content.substring(start, end + 1))
        }.getOrNull() ?: return null

        val name = json.optString("action").trim().uppercase(Locale.ROOT)
        if (name !in actions) return null

        val nodeId = if (json.has("nodeId") && !json.isNull("nodeId")) {
            json.optInt("nodeId", -1).takeIf { it >= 0 }
        } else {
            null
        }

        return Action(
            name = name,
            packageName = json.optString("package").takeIf { it.isNotBlank() },
            nodeId = nodeId,
            text = if (json.has("text")) json.optString("text") else null,
            direction = json.optString("direction").uppercase(Locale.ROOT)
                .takeIf { it in setOf("UP", "DOWN", "LEFT", "RIGHT") },
            reason = json.optString("reason").trim(),
            message = json.optString("message").trim(),
        )
    }

    private fun execute(
        context: Context,
        service: ScreenReaderAccessibilityService,
        action: Action,
        nodeIds: Map<Int, String>,
    ): StepOutcome = when (action.name) {
        "OPEN_APP" -> {
            val packageName = action.packageName
            if (packageName.isNullOrBlank()) {
                StepOutcome(false, "OPEN_APP needs a package.")
            } else {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent == null) {
                    StepOutcome(false, "No launchable app found for $packageName.")
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }.fold(
                        onSuccess = { StepOutcome(true, "Opened $packageName.") },
                        onFailure = { StepOutcome(false, it.message ?: "Could not open $packageName.") },
                    )
                }
            }
        }

        "CLICK" -> {
            val accessibilityId = action.nodeId?.let { nodeIds[it] }
            if (accessibilityId == null) {
                StepOutcome(false, "Unknown nodeId ${action.nodeId}.")
            } else if (service.clickNode(accessibilityId)) {
                StepOutcome(true, "Clicked.")
            } else {
                StepOutcome(false, "Click failed.")
            }
        }

        "TYPE" -> {
            val accessibilityId = action.nodeId?.let { nodeIds[it] }
            val text = action.text
            if (accessibilityId == null) {
                StepOutcome(false, "Unknown nodeId ${action.nodeId}.")
            } else if (text == null) {
                StepOutcome(false, "TYPE needs text.")
            } else if (service.typeText(accessibilityId, text)) {
                StepOutcome(true, "Typed.")
            } else {
                StepOutcome(false, "Typing failed.")
            }
        }

        "SCROLL" -> {
            val direction = action.direction?.let { runCatching { ScrollDirection.valueOf(it) }.getOrNull() }
            if (direction == null) {
                StepOutcome(false, "SCROLL needs UP or DOWN.")
            } else if (service.scroll(direction)) {
                StepOutcome(true, "Scrolled $direction.")
            } else {
                StepOutcome(false, "Scroll failed.")
            }
        }

        "BACK" -> if (service.pressBack()) StepOutcome(true, "Went back.") else {
            StepOutcome(false, "Back failed.")
        }

        "HOME" -> if (service.pressHome()) StepOutcome(true, "Went home.") else {
            StepOutcome(false, "Home failed.")
        }

        else -> StepOutcome(false, "Unsupported action ${action.name}.")
    }

    private fun actionLabel(action: Action, apps: List<InstalledApp>): String {
        val text = when (action.name) {
            "OPEN_APP" -> {
                val label = apps.firstOrNull { it.packageName == action.packageName }?.label
                    ?: action.packageName?.substringAfterLast('.')
                    ?: "app"
                "Opening $label"
            }

            "CLICK" -> action.reason.ifBlank { "Tapping" }
            "TYPE" -> action.reason.ifBlank { "Typing" }
            "SCROLL" -> "Scrolling ${action.direction?.lowercase(Locale.ROOT).orEmpty()}".trim()
            "BACK" -> "Going back"
            "HOME" -> "Going home"
            else -> action.name
        }
        return text.take(60)
    }

    private fun launchableApps(context: Context): List<InstalledApp> {
        cachedApps?.let { return it }

        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            packageManager.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())

        val apps = resolved.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(packageManager)?.toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: packageName
            InstalledApp(packageName, label)
        }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.ROOT) }

        cachedApps = apps
        return apps
    }
}
