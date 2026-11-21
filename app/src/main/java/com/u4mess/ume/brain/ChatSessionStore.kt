package com.u4mess.ume.brain

import android.content.Context
import android.content.SharedPreferences
import com.u4mess.ume.brain.ChatController.Message
import com.u4mess.ume.memory.MemorySource
import com.u4mess.ume.net.WebResult
import org.json.JSONArray
import org.json.JSONObject

data class ChatSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
)

data class StoredChatState(
    val sessions: List<ChatSession>,
    val currentSessionId: String?,
    val messages: Map<String, List<Message>>,
)

object ChatSessionStore {

    private const val PREFS = "ume_chat_sessions"
    private const val KEY_STATE = "state"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun load(): StoredChatState {
        if (!::prefs.isInitialized) return StoredChatState(emptyList(), null, emptyMap())
        val raw = prefs.getString(KEY_STATE, null) ?: return StoredChatState(emptyList(), null, emptyMap())
        return runCatching {
            val root = JSONObject(raw)
            val sessionsJson = root.optJSONArray("sessions") ?: JSONArray()
            val sessions = mutableListOf<ChatSession>()
            val messages = mutableMapOf<String, List<Message>>()
            for (i in 0 until sessionsJson.length()) {
                val objectValue = sessionsJson.optJSONObject(i) ?: continue
                val id = objectValue.optString("id")
                if (id.isBlank()) continue
                sessions += ChatSession(
                    id = id,
                    title = objectValue.optString("title").ifBlank { "New chat" },
                    updatedAt = objectValue.optLong("updatedAt"),
                )
                val messageArray = objectValue.optJSONArray("messages") ?: JSONArray()
                messages[id] = buildList {
                    for (j in 0 until messageArray.length()) {
                        messageArray.optJSONObject(j)?.let { add(it.toMessage(id)) }
                    }
                }
            }
            StoredChatState(
                sessions = sessions,
                currentSessionId = root.optString("currentSessionId").ifBlank { null },
                messages = messages,
            )
        }.getOrDefault(StoredChatState(emptyList(), null, emptyMap()))
    }

    fun save(
        sessions: List<ChatSession>,
        currentSessionId: String,
        messages: Map<String, List<Message>>,
    ) {
        if (!::prefs.isInitialized) return
        runCatching {
            val array = JSONArray()
            sessions.forEach { session ->
                val objectValue = JSONObject()
                    .put("id", session.id)
                    .put("title", session.title)
                    .put("updatedAt", session.updatedAt)
                val messageArray = JSONArray()
                (messages[session.id] ?: emptyList()).forEach { message ->
                    messageArray.put(message.toJson())
                }
                objectValue.put("messages", messageArray)
                array.put(objectValue)
            }
            val root = JSONObject()
                .put("currentSessionId", currentSessionId)
                .put("sessions", array)
            prefs.edit().putString(KEY_STATE, root.toString()).apply()
        }
    }

    private fun Message.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("role", role.name)
        put("text", text)
        put("createdAt", createdAt)
        put("proactive", proactive)
        put("hideText", hideText)
        put("webSources", JSONArray().apply {
            webSources.forEach { source ->
                put(
                    JSONObject()
                        .put("title", source.title)
                        .put("snippet", source.snippet)
                        .put("url", source.url),
                )
            }
        })
        attachment?.let { attachment ->
            put(
                "attachment",
                JSONObject()
                    .put("name", attachment.name)
                    .put("mimeType", attachment.mimeType)
                    .put("kind", attachment.kind.name)
                    .put("text", attachment.text)
                    .put("imagePath", attachment.imagePath)
                    .put("audioPath", attachment.audioPath)
                    .put("audioDurationMs", attachment.audioDurationMs),
            )
        }
        agent?.let { agent ->
            put(
                "agent",
                JSONObject()
                    .put("success", agent.success)
                    .put("message", agent.message)
                    .put("steps", JSONArray(agent.steps))
                    .put(
                        "details",
                        JSONArray().apply {
                            agent.details.forEach { detail ->
                                put(
                                    JSONObject()
                                        .put("step", detail.step)
                                        .put("action", detail.action)
                                        .put("label", detail.label)
                                        .put("packageName", detail.packageName)
                                        .put("nodeId", detail.nodeId)
                                        .put("ok", detail.ok)
                                        .put("detail", detail.detail)
                                        .put("latencyMs", detail.latencyMs),
                                )
                            }
                        },
                    ),
            )
        }
    }

    private fun JSONObject.toMessage(sessionId: String): Message {
        val role = runCatching { ChatRole.valueOf(optString("role")) }.getOrDefault(ChatRole.ASSISTANT)
        val webSources = mutableListOf<WebResult>()
        optJSONArray("webSources")?.let { array ->
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let {
                    webSources += WebResult(
                        title = it.optString("title"),
                        snippet = it.optString("snippet"),
                        url = it.optString("url"),
                    )
                }
            }
        }
        val attachment = optJSONObject("attachment")?.let {
            ChatAttachment(
                name = it.optString("name"),
                mimeType = it.optString("mimeType"),
                kind = runCatching { ChatAttachmentKind.valueOf(it.optString("kind")) }
                    .getOrDefault(ChatAttachmentKind.TEXT),
                text = it.optString("text"),
                imagePath = it.optString("imagePath").ifBlank { null },
                audioPath = it.optString("audioPath").ifBlank { null },
                audioDurationMs = it.optLong("audioDurationMs"),
            )
        }
        val agent = optJSONObject("agent")?.let { source ->
            val steps = mutableListOf<String>()
            source.optJSONArray("steps")?.let { array ->
                for (i in 0 until array.length()) steps += array.optString(i)
            }
            val details = mutableListOf<AgentStep>()
            source.optJSONArray("details")?.let { array ->
                for (i in 0 until array.length()) {
                    val detail = array.optJSONObject(i) ?: continue
                    details += AgentStep(
                        step = detail.optInt("step"),
                        action = detail.optString("action"),
                        label = detail.optString("label"),
                        packageName = detail.optString("packageName").ifBlank { null },
                        nodeId = detail.optInt("nodeId").takeIf { detail.has("nodeId") },
                        ok = detail.optBoolean("ok"),
                        detail = detail.optString("detail"),
                        latencyMs = detail.optLong("latencyMs"),
                    )
                }
            }
            UiAgentResult(
                success = source.optBoolean("success"),
                message = source.optString("message"),
                steps = steps,
                details = details,
            )
        }
        return Message(
            id = optLong("id"),
            role = role,
            text = optString("text"),
            createdAt = optLong("createdAt"),
            proactive = optBoolean("proactive"),
            agent = agent,
            webSources = webSources,
            attachment = attachment,
            sessionId = sessionId,
            hideText = optBoolean("hideText"),
        )
    }
}
