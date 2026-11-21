package com.u4mess.ume.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** User-tunable island geometry, persisted across restarts. */
object IslandSettings {

    const val DEFAULT_Y_OFFSET_DP = 7
    const val MAX_Y_OFFSET_DP = 40

    private const val PREFS_NAME = "ume_island"
    private const val KEY_Y_OFFSET = "y_offset_dp"

    private lateinit var prefs: SharedPreferences

    val yOffsetDp = MutableStateFlow(DEFAULT_Y_OFFSET_DP)

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        yOffsetDp.value = prefs.getInt(KEY_Y_OFFSET, DEFAULT_Y_OFFSET_DP)
            .coerceIn(0, MAX_Y_OFFSET_DP)
    }

    /** Updates the live position without writing to disk while the slider moves. */
    fun previewYOffset(value: Int) {
        yOffsetDp.value = value.coerceIn(0, MAX_Y_OFFSET_DP)
    }

    fun persistYOffset(value: Int) {
        previewYOffset(value)
        prefs.edit().putInt(KEY_Y_OFFSET, yOffsetDp.value).apply()
    }
}
