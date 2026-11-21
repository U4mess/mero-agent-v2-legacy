package com.u4mess.ume.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.u4mess.ume.MainActivity
import com.u4mess.ume.R
import com.u4mess.ume.ui.UmeTheme

class DynamicIslandService : Service() {

    private val expanded = mutableStateOf(false)
    private val lifecycleOwner = ServiceLifecycleOwner()

    private lateinit var windowManager: WindowManager
    private lateinit var islandView: ComposeView
    private lateinit var layoutParams: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        IslandState.running.value = true

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WindowManager::class.java)
        islandView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            isFocusable = true
            isFocusableInTouchMode = true
            setContent {
                UmeTheme {
                    val agentStatus by IslandAgent.status.collectAsState()
                    DynamicIsland(
                        expanded = expanded.value,
                        agentStatus = agentStatus,
                        onExpand = {
                            if (IslandAgent.status.value == null) setExpanded(true)
                        },
                        onCollapse = { setExpanded(false) },
                        onSend = { text ->
                            PromptInbox.send(text)
                            setExpanded(false)
                        },
                        onStopAgent = { IslandAgent.stop() },
                    )
                }
            }
            setOnTouchListener { _, event ->
                if (expanded.value && event.action == MotionEvent.ACTION_OUTSIDE) {
                    setExpanded(false)
                }
                false
            }
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            COLLAPSED_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topOffset()
            title = "Ume Island"
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        lifecycleOwner.start()
        lifecycleOwner.resume()
        runCatching { windowManager.addView(islandView, layoutParams) }.onFailure { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::islandView.isInitialized && islandView.isAttachedToWindow) {
            getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(islandView.windowToken, 0)
            windowManager.removeView(islandView)
        }
        lifecycleOwner.destroy()
        IslandState.running.value = false
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun setExpanded(value: Boolean) {
        if (expanded.value == value) return
        expanded.value = value

        layoutParams.flags = if (value) EXPANDED_FLAGS else COLLAPSED_FLAGS
        layoutParams.softInputMode = if (value) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        windowManager.updateViewLayout(islandView, layoutParams)

        if (value) {
            islandView.post { islandView.requestFocus() }
        } else {
            islandView.post {
                getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(islandView.windowToken, 0)
                islandView.clearFocus()
            }
        }
    }

    private fun topOffset(): Int {
        val statusBar = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics
                .windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                .top
        } else {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        }
        return statusBar + (8 * resources.displayMetrics.density).toInt()
    }

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ume)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    private companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "ume_island"

        val COLLAPSED_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val EXPANDED_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }
}

private class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.STARTED
    }

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
