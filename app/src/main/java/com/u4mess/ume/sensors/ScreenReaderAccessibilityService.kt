package com.u4mess.ume.sensors

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

class ScreenReaderAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        if (instance === this) {
            instance = null
            _connected.value = false
        }
    }

    /** Serializes the visible, interactive part of the active window into a compact JSON array. */
    fun captureUiTreeJson(): String {
        val root = rootInActiveWindow ?: return "[]"
        val nodes = JSONArray()
        collectNodes(root, "@", nodes)
        return nodes.toString()
    }

    private fun collectNodes(node: AccessibilityNodeInfo, path: String, out: JSONArray) {
        if (out.length() >= MAX_NODES) return

        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty() ||
                node.isClickable || node.isEditable || node.isScrollable
            ) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                out.put(
                    JSONObject().apply {
                        put("id", node.viewIdResourceName ?: path)
                        put("text", text.take(MAX_TEXT_LENGTH))
                        put("desc", desc.take(MAX_TEXT_LENGTH))
                        put("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
                        put("clickable", node.isClickable)
                        put("editable", node.isEditable)
                        put("scrollable", node.isScrollable)
                    },
                )
            }
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectNodes(child, "$path/$index", out)
        }
    }

    fun clickNode(id: String): Boolean =
        findNode(id)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false

    fun typeText(id: String, text: String): Boolean {
        val node = findNode(id) ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun scroll(direction: ScrollDirection): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findScrollable(root) ?: root
        val action = when (direction) {
            ScrollDirection.UP, ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ScrollDirection.DOWN, ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        return target.performAction(action)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun currentPackageName(): String? = rootInActiveWindow?.packageName?.toString()

    private fun findNode(id: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (id.startsWith("@")) {
            var node: AccessibilityNodeInfo = root
            val indices = id.removePrefix("@").split('/').filter { it.isNotEmpty() }
            for (part in indices) {
                val index = part.toIntOrNull() ?: return null
                node = node.getChild(index) ?: return null
            }
            return node
        }
        return findByResourceId(root, id)
    }

    private fun findByResourceId(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName == id) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findByResourceId(child, id)?.let { return it }
        }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isScrollable) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findScrollable(child)?.let { return it }
        }
        return null
    }

    companion object {
        @Volatile
        var instance: ScreenReaderAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        fun captureUiTreeJson(): String = instance?.captureUiTreeJson() ?: "[]"

        fun currentPackageName(): String? = instance?.currentPackageName()

        private const val MAX_NODES = 400
        private const val MAX_TEXT_LENGTH = 200
    }
}
