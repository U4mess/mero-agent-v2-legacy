package com.u4mess.ume.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** User-tunable island geometry, persisted across restarts. */
object IslandSettings {

    const val DEFAULT_Y_OFFSET_DP = 7
    const val MAX_Y_OFFSET_DP = 40
    const val DEFAULT_WIDTH_DP = 56
    const val MIN_WIDTH_DP = 44
    const val MAX_WIDTH_DP = 96
    const val DEFAULT_X_OFFSET_DP = 14
    const val MIN_X_OFFSET_DP = -30
    const val MAX_X_OFFSET_DP = 30

    private const val PREFS_NAME = "ume_island"
    private const val KEY_Y_OFFSET = "y_offset_dp"
    private const val KEY_WIDTH = "width_dp"
    private const val KEY_X_OFFSET = "x_offset_dp"

    private lateinit var prefs: SharedPreferences

    val yOffsetDp = MutableStateFlow(DEFAULT_Y_OFFSET_DP)
    val widthDp = MutableStateFlow(DEFAULT_WIDTH_DP)
    val xOffsetDp = MutableStateFlow(DEFAULT_X_OFFSET_DP)

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        yOffsetDp.value = prefs.getInt(KEY_Y_OFFSET, DEFAULT_Y_OFFSET_DP)
            .coerceIn(0, MAX_Y_OFFSET_DP)
        widthDp.value = prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH_DP)
            .coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP)
        xOffsetDp.value = prefs.getInt(KEY_X_OFFSET, DEFAULT_X_OFFSET_DP)
            .coerceIn(MIN_X_OFFSET_DP, MAX_X_OFFSET_DP)
    }

    /** Updates the live position without writing to disk while the slider moves. */
    fun previewYOffset(value: Int) {
        yOffsetDp.value = value.coerceIn(0, MAX_Y_OFFSET_DP)
    }

    fun persistYOffset(value: Int) {
        previewYOffset(value)
        prefs.edit().putInt(KEY_Y_OFFSET, yOffsetDp.value).apply()
    }

    fun previewWidth(value: Int) {
        widthDp.value = value.coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP)
    }

    fun persistWidth(value: Int) {
        previewWidth(value)
        prefs.edit().putInt(KEY_WIDTH, widthDp.value).apply()
    }

    fun previewXOffset(value: Int) {
        xOffsetDp.value = value.coerceIn(MIN_X_OFFSET_DP, MAX_X_OFFSET_DP)
    }

    fun persistXOffset(value: Int) {
        previewXOffset(value)
        prefs.edit().putInt(KEY_X_OFFSET, xOffsetDp.value).apply()
    }
}
