package com.u4mess.ume.sensors

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.u4mess.ume.service.IslandTouchRelay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

class ScreenReaderAccessibilityService : AccessibilityService() {

    private var islandTouchView: View? = null
    private var islandTouchParams: WindowManager.LayoutParams? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        IslandTouchRelay.attach(this)
    }

    fun setIslandTouchRelay(
        visible: Boolean,
        offsetDp: Int,
        widthDp: Int,
        heightDp: Int,
        onClick: (() -> Unit)?,
    ) {
        val windowManager = getSystemService(WindowManager::class.java)
        if (!visible) {
            islandTouchView?.let { runCatching { windowManager.removeView(it) } }
            islandTouchView = null
            islandTouchParams = null
            return
        }

        val density = resources.displayMetrics.density
        if (islandTouchView == null) {
            val view = View(this).apply {
                isClickable = true
                setOnClickListener { onClick?.invoke() }
            }
            val params = WindowManager.LayoutParams(
                (widthDp * density).toInt(),
                (heightDp * density).toInt(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (offsetDp * density).toInt()
                title = "Ume Island Touch"
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val added = runCatching { windowManager.addView(view, params) }
            if (added.isFailure) {
                android.util.Log.e(
                    "UmeRelay",
                    "Unable to add island touch relay",
                    added.exceptionOrNull(),
                )
                return
            }
            islandTouchView = view
            islandTouchParams = params
        } else {
            val view = islandTouchView ?: return
            val params = islandTouchParams ?: return
            view.setOnClickListener { onClick?.invoke() }
            params.width = (widthDp * density).toInt()
            params.height = (heightDp * density).toInt()
            params.y = (offsetDp * density).toInt()
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun removeIslandTouchRelay() {
        islandTouchView?.let {
            runCatching { getSystemService(WindowManager::class.java).removeView(it) }
        }
        islandTouchView = null
        islandTouchParams = null
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
            removeIslandTouchRelay()
            IslandTouchRelay.detach(this)
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
