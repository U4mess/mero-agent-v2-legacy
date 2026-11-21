package com.u4mess.ume.memory

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id

object MemorySource {
    const val NOTIFICATION = "notification"
    const val SCREEN = "screen"
    const val MANUAL = "manual"
    const val SUMMARY = "summary"
    const val ACTION = "action"
    const val SCREENSHOT = "screenshot"
    const val PDF = "pdf"
}

@Entity
data class MemoryEntity(
    @Id var id: Long = 0,
    var source: String = "",
    var packageName: String = "",
    var title: String = "",
    var content: String = "",
    var timestamp: Long = 0,
    @HnswIndex(dimensions = 384) var embedding: FloatArray? = null,
)
