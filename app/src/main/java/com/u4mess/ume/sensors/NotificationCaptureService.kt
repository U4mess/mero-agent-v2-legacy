package com.u4mess.ume.sensors

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.u4mess.ume.UmeApplication
import com.u4mess.ume.brain.LocalLlmEngine
import com.u4mess.ume.brain.ModelStatus
import com.u4mess.ume.memory.MemorySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotificationCaptureService : NotificationListenerService() {

    private val repository
        get() = (application as UmeApplication).memoryRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val recentKeys = object : LinkedHashMap<String, Long>(16, 0.75f, true) {}

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val statusBarNotification = sbn ?: return
        if (isIgnored(statusBarNotification)) return

        val extras = statusBarNotification.notification?.extras ?: return
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))
            ?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val key = "${statusBarNotification.packageName}|$title|$text"
        if (isDuplicate(key)) return

        val id = repository.insert(
            source = MemorySource.NOTIFICATION,
            packageName = statusBarNotification.packageName,
            title = title,
            content = text,
            timestamp = statusBarNotification.postTime,
        )

        if (LocalLlmEngine.embeddingStatus.value is ModelStatus.Ready) {
            scope.launch {
                val embedding = LocalLlmEngine.embed("$title\n$text")
                if (embedding != null) repository.updateEmbedding(id, embedding)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun isIgnored(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return true
        if (sbn.packageName == "android") return true

        val notification = sbn.notification ?: return true
        if (sbn.isOngoing) return true
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
        if (notification.category in IGNORED_CATEGORIES) return true
        return false
    }

    @Synchronized
    private fun isDuplicate(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = recentKeys[key]
        if (last != null && now - last < DUPLICATE_WINDOW_MS) return true

        recentKeys[key] = now
        if (recentKeys.size > RECENT_KEYS_LIMIT) {
            recentKeys.remove(recentKeys.keys.first())
        }
        return false
    }

    private companion object {
        const val DUPLICATE_WINDOW_MS = 30_000L
        const val RECENT_KEYS_LIMIT = 100

        val IGNORED_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SYSTEM,
        )
    }
}
