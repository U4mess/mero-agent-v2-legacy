package com.u4mess.ume

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.u4mess.ume.brain.ChatController
import com.u4mess.ume.brain.ModelManager
import com.u4mess.ume.memory.MemoryEntity
import com.u4mess.ume.memory.MemoryRepository
import com.u4mess.ume.memory.MyObjectBox
import com.u4mess.ume.memory.NightlyConsolidator
import com.u4mess.ume.sensors.MediaDocumentIndexer
import com.u4mess.ume.service.IslandSettings
import com.u4mess.ume.settings.CloudSettings
import com.u4mess.ume.settings.LanguageSettings
import com.u4mess.ume.settings.ThemeSettings
import com.u4mess.ume.settings.WebSearchSettings
import com.u4mess.ume.voice.VoiceInputManager
import com.u4mess.ume.voice.VoiceOutputManager
import io.objectbox.BoxStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UmeApplication : Application() {

    lateinit var memoryRepository: MemoryRepository
        private set

    private lateinit var boxStore: BoxStore
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        boxStore = MyObjectBox.builder().androidContext(this).build()
        memoryRepository = MemoryRepository(boxStore.boxFor(MemoryEntity::class.java))

        ModelManager.init(this)
        CloudSettings.init(this)
        WebSearchSettings.init(this)
        ThemeSettings.init(this)
        LanguageSettings.init(this)
        IslandSettings.init(this)
        VoiceInputManager.init(this)
        VoiceOutputManager.init(this)
        ChatController.start(this, memoryRepository)
        MediaDocumentIndexer.init(this, memoryRepository)
        scheduleNightlyConsolidation()
        autoLoadModels()
    }

    private fun autoLoadModels() {
        if (!ModelManager.autoLoadEnabled()) return
        applicationScope.launch {
            if (ModelManager.chatSelection.value != null) {
                runCatching { ModelManager.loadChat() }
            }
            if (ModelManager.embeddingSelection.value != null) {
                runCatching { ModelManager.loadEmbedding() }
            }
        }
    }

    private fun scheduleNightlyConsolidation() {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<NightlyConsolidator>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            NightlyConsolidator.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
