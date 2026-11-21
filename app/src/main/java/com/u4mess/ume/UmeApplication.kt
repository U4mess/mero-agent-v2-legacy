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
import com.u4mess.ume.service.IslandSettings
import com.u4mess.ume.settings.CloudSettings
import io.objectbox.BoxStore
import java.util.concurrent.TimeUnit

class UmeApplication : Application() {

    lateinit var memoryRepository: MemoryRepository
        private set

    private lateinit var boxStore: BoxStore

    override fun onCreate() {
        super.onCreate()
        boxStore = MyObjectBox.builder().androidContext(this).build()
        memoryRepository = MemoryRepository(boxStore.boxFor(MemoryEntity::class.java))

        ModelManager.init(this)
        CloudSettings.init(this)
        IslandSettings.init(this)
        ChatController.start(this, memoryRepository)
        scheduleNightlyConsolidation()
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
